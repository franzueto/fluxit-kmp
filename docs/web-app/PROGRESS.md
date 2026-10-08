# FluxIt Web App — Plan and Progress Tracker

Single source of truth for the mobile-first web client. Update this file at the end of
every phase (status, checklist, notes) and record the reviewer verdict before starting
the next phase.

- **Branch:** `web/wasm-app`
- **Started:** 2026-10-07
- **Current phase:** Phase 5 follow-up (keep keyboard open when adding items, in review); Phase 6 next

## Goal

A mobile-first web version of FluxIt, built from this KMP project, hosted on Firebase
Hosting, used by one person with one existing account. It shows and manages lists and
items with photos.

**In scope:** sign in to an existing account, restore session, password reset, sign
out; dashboard of lists; create/edit/soft-delete/undo lists; list detail with items;
add/edit/complete/delete/undo items; clear completed; attach/replace/remove item photos.

**Out of scope on web:** account creation (sign-up), the debug sample-data seeder,
offline persistence across reloads, production environment.

## Decisions

| # | Decision | Date |
|---|---|---|
| D1 | Compose Multiplatform for Web on Kotlin/Wasm (`wasmJs` target in `composeApp`) — reuse shared UI, ViewModels and domain. | 2026-10-07 |
| D2 | Firebase on web through a small hand-written JS bridge over the official Firebase JS modular SDK (GitLive SDK has no Wasm support). Port the iOS Kotlin repositories to web first ("copy first"); unify iOS + web into a shared bridged layer later (optional Phase 7). | 2026-10-07 |
| D3 | Disable client sign-up project-wide in Firebase Auth (Authentication → Settings → User actions → Enable create). Accounts can still be created in Console (Authentication → Users → Add user) or with the Admin SDK. Done by the project owner in Phase 6. | 2026-10-07 |
| D4 | Web Firestore uses the in-memory cache (no IndexedDB persistence), so sign-out cleanup is terminate-and-recreate, not a disk wipe. | 2026-10-07 |
| D5 | Sign-up and seeding are hidden on web via a platform-provided feature-flags object, not by forking screens. Hiding is UX only; D3 is the enforcement. | 2026-10-07 |
| D6 | The Storage bucket allows cross-origin `GET` from any origin (`storage.cors.json`, applied once per bucket by the owner). Browsers need it to download photo bytes, on localhost, the LAN and Firebase Hosting alike; native apps do not. GET only; CORS only decides whether browser code may read a response and is not access control. Downloads through the SDK still need the user's token and pass the owner-only Rules (no FluxIt client creates token download URLs), so it widens no access. | 2026-10-07 |

## Architecture (target)

```
composeApp/src/
  commonMain/     shared UI, ViewModels, domain, FirebaseContracts (unchanged except feature flags)
  androidMain/    Android Firebase adapters (unchanged)
  iosMain/        iOS adapters over Swift bridges (unchanged)
  wasmJsMain/
    kotlin/       main.kt (ComposeViewport + Koin), PlatformModule.wasmJs.kt,
                  Web*Repository (ported from Ios*Repository), WebPhotoPicker,
                  external declarations for the JS bridge
    resources/    index.html, styles, JS bridge module over the Firebase JS SDK
```

Image decode/resize on web uses Skia (Skiko) — the same code as iOS, in a `skikoMain` source
set shared by iOS and web (Phase 4).

Web Firebase config (apiKey, projectId, …) comes from a gitignored local file, like the
mobile configs.

## Workflow

1. Implement the current phase's checklist on `web/wasm-app`.
2. Run the phase's verification commands; record results below.
3. Run the **`web-phase-reviewer`** agent (`.claude/agents/web-phase-reviewer.md`) on the
   phase. It validates acceptance criteria, mobile non-regression and security, and
   returns PASS / PASS WITH NOTES / FAIL.
4. Fix any FAIL findings and re-review. Record the verdict in the Review log.
   (Custom agents load at session start; in the session that created the agent, the
   reviewer ran as a general-purpose agent instructed to follow that file.)
5. Commit the phase; check in with the project owner before starting the next phase.

## Phases

Status legend: ⬜ not started · 🟨 in progress · ✅ done (reviewed) · ⛔ blocked

### Phase 0 — Spike: wasmJs target builds and renders · ✅

Goal: prove every shared dependency compiles for Wasm and the real shared UI renders in a
mobile browser. Go/no-go for text input and scrolling on a phone.

- [x] Add `wasmJs` target (browser, executable) to `composeApp/build.gradle.kts`
- [x] All `commonMain` dependencies resolve for `wasmJs` (Compose 1.10.3, Navigation 3, lifecycle, Koin, coroutines, datetime, icons — no changes needed)
- [x] `actual` stubs for `platformModule`, `decodeImageBytes`, `readImageDimensions`, `resizeImage` (image stubs are temporary; replaced in Phase 4)
- [x] Web entry point: `main.kt` (Koin + `ComposeViewport`) and `index.html` with mobile viewport
- [x] Spike auth binding: a stub `AuthRepository` that resolves to SignedOut so the real login screen renders (replaced in Phase 2)
- [x] `commonTest` compiles for `wasmJs` — replaced three `runBlocking { auth.signUp(...) }` setups (JVM/Native-only API) with a synchronous `FakeAuthRepository.authenticate(email)`; 213/213 shared tests pass in headless Chrome
- [x] Production bundle builds (`wasmJsBrowserDistribution`): 12.7 MB raw, **4.4 MB gzip** (Skia wasm 3.3 MB, app wasm 1.0 MB, JS 0.1 MB)
- [x] Renders in a browser at 375×812; login form accepts typing, show/hide password, mode switching, sign-in error round trip; no console errors
- [x] Android `testDebugUnitTest` (257/257) + `assembleDebug`, iOS `compileKotlinIosSimulatorArm64` + `compileTestKotlinIosSimulatorArm64` succeed
- [x] Owner checks on a real phone: typing, keyboard, scrolling — sign-in pages look good on the owner's phone (2026-10-07)

Acceptance: web bundle builds; login screen renders at 375 px wide; mobile targets unaffected.

### Phase 1 — Feature flags and web Firebase app config · ✅

- [x] `AppFeatures` (allowSignUp, allowSampleData) in `commonMain/.../config/AppFeatures.kt`, bound by each `platformModule()`; Android/iOS = `AppFeatures.Mobile` (both on), web = `AppFeatures.Web` (both off)
- [x] `AuthViewModel` takes `AppFeatures` (default `Mobile`); `AuthUiState.canSignUp`; switching into or submitting `SignUp` is refused when disabled; `AuthScreen` hides the create-account link. Password recovery stays available.
- [x] `DashboardViewModel` takes a nullable `DebugSeeder` and exposes `canSeedSampleData`; `DashboardScreen` uses it instead of the removed `DEBUG_SEED_ENABLED` const; `appModule` resolves the seeder only when `allowSampleData` (never on web)
- [x] Owner registers a Web app in the dev Firebase project (owner action, done 2026-10-07)
- [x] Gitignored `composeApp/firebase-web-config.json` (template `firebase-web-config.example.json`) → `generateFirebaseWebConfig` task generates `FirebaseWebConfig.options` into wasmJsMain; validates the six required keys, rejects placeholders and unsafe characters, reports key names only; missing file → `options = null` (compiles/tests), production webpack refuses to build. Documented in README.
- [x] Owner copies the real web config into `composeApp/firebase-web-config.json` (done 2026-10-07; validated by the generator, gitignored; `measurementId` intentionally omitted — the app has no Analytics)
- [x] Unit tests: `AppFeaturesTest` (commonTest, 8 tests: VM behaviour + `appModule` wiring for both feature sets) and `WebPlatformModuleTest` (wasmJsTest)

Verification (2026-10-07): wasm tests 222/222, Android unit tests 265/265, iOS simulator tests 334/334, `assembleDebug`, Android instrumented-test compile, iOS compile, `firebase` `npm run check` 4/4. Dev bundle at 375×812 shows sign-in without the create-account link. Generator checked with: no file, untouched template (rejected), valid fake demo config, unsafe characters (rejected), and malformed files — the console's JS snippet, unquoted keys, a top-level array — all rejected with zero occurrences of the value in the output, even with `--stacktrace`.

Also fixed in this phase:
- Review 1 (FAIL): malformed JSON made the parser's message echo the offending line, including the value. The parse error is now replaced by a value-free message with no chained cause.
- `SessionGateAccountSwitchIosTest` builds its own Koin graph from `appModule`; it now binds `AppFeatures.Mobile` like the iOS platform module.
- Kotlin 2.3.20 Wasm incremental compile crashed on any edit after the first build (`ArrayIndexOutOfBoundsException` in `WasmIrFileMetadata`; latent in Phase 0). The JS/Wasm compile is incremental if either `kotlin.incremental.js.klib` or `kotlin.incremental.js` is on, so both are off in `gradle.properties` (Wasm-only effect; full Wasm compile is ~1–2 s).

### Phase 2 — Auth on web · ✅

- [x] Firebase JS SDK `firebase@12.19.0` (npm, `wasmJsMain`; 13.0.0 was released the same day, so skipped) and the auth part of the JS bridge: `wasmJsMain/resources/firebase-bridge.mjs`, copied next to the compiled Kotlin by the resources sync so webpack bundles it with the SDK. Side-effect free on import; `initializeAuth` with IndexedDB/localStorage persistence and no popup/redirect resolver. Kotlin side: `WebFirebaseExternals.kt` (`@JsModule`), `WebFirebase.ensureStarted()` (lazy start from the generated web options + shared emulator constants)
- [x] `WebAuthRepository` (ported from `IosAuthRepository`, same state machine) over a `WebAuthBridge` interface (`JsWebAuthBridge` in production) + `WebFirebaseAuthErrorMapper`: JS `auth/...` codes → `AuthError`, same cases as the iOS table; diagnostics logged to the console before collapsing to `Unknown`. Web-only step: `restoreSession()` waits for `authStateReady()` before reading `currentUser`
- [x] Wrapped in `SessionAuthRepository`; `WebSessionCleanup`: durable marker in `localStorage` (write failure → `CleanupFailed`), `clear()` calls the bridge's `clearSessionData()`, which has nothing to clear yet (no data clients until Phase 3 adds Firestore terminate-and-recreate, D4; Phase 4 adds Storage task cancellation)
- [x] Sign in, session restore on reload, password reset, sign out work against the Auth emulator (2026-10-07, see Verification)
- [x] Same flows against the dev project with the owner's account (owner action, done 2026-10-07: sign in, reload, password reset, sign out)
- [x] Tests for error mapping (`WebFirebaseAuthErrorMapperTest`), plus `WebAuthRepositoryTest` (iOS matrix ported + persistence wait + wrapped-session gating), `WebSessionCleanupTest`, `WebPlatformModuleTest` (graph resolves without starting Firebase)
- [x] Session starts at `Unresolved` and resolves only via `restoreSession()` (Phase 0 review follow-up): `WebAuthRepository` starts `Unresolved`; the `SessionAuthRepository` wrapper ignores raw listener states until `restoreSession()`/sign-in — covered by `theWrappedSessionStaysUnresolvedUntilRestoreEvenWhenTheListenerReports`
- Temporary: `PendingWebListRepository`/`PendingWebItemRepository` (empty reads, writes fail with a retryable error) so the signed-in dashboard renders and sign-out is reachable. Removed in Phase 3.

Verification (2026-10-07): wasm tests 253/253 (was 222), Android unit tests 265/265, `assembleDebug`, iOS `compileKotlinIosSimulatorArm64` (no `commonMain` change in this phase), production bundle builds — 13.9 MB raw, **4.72 MB gzip** (+0.3 MB for Firebase Auth); no emulator host in the production JS. Auth emulator (started with the web config's project ID, test user created through the emulator REST API) with a `-Pfluxit.firebase.emulator.enabled=true` dev bundle at 375×812: cold load → sign-in screen; wrong password → "That email and password do not match." (console: `auth/wrong-password` → `InvalidCredentials`); correct password → dashboard; reload → still signed in; sign out → sign-in screen, cleanup marker removed, reload stays signed out; password reset → confirmation shown and the emulator recorded one `PASSWORD_RESET` code.

Also in this phase (review note): the production webpack task refuses `fluxit.firebase.emulator.enabled=true`.

Known gaps: `firebase-bridge.mjs` is also copied raw into the distribution (unused there; harmless, exclude in Phase 6). Compose canvas text input in automated testing occasionally dropped or delayed typed characters; not seen by the owner on a real phone in Phase 0.

### Phase 3 — Lists and items on web · ✅

- [x] Firestore part of the JS bridge (`firebase-bridge.mjs`): collection/document listeners with snapshot metadata (separate `includeMetadataChanges` listener, as iOS), whole-document create, field-scoped `updateDoc`, add-item batch with `totalItems` increment, counter transaction (`runTransaction`, Kotlin `decide` called inside it; iOS's permission-denied re-read-and-retry rule), clear-completed chunk query + batch. Path segments are validated (no empty or `/`-containing IDs). In-memory cache only (D4)
- [x] `FirebaseValue` ⇄ JS encoding: `WebFirestoreCodec` (Kotlin) + `wireToFirestoreData`/`firestoreDataToWire` (JS) over `{ key, type, text, bool, number }` entries; writes cover null/text/bool/number/timestamp/`serverTimestamp()`/`increment()`; reads use `serverTimestamps: "estimate"` and decode like the iOS bridge (numbers truncated, unsupported types left out)
- [x] `WebFirebaseListRepository`, `WebFirebaseItemRepository` ported from iOS (mechanical port: same counter policy, chunking, ordering, uid-per-call), over `WebFirestoreListBridge`/`WebFirestoreItemBridge` (same methods as the iOS protocols) with `JsWebFirestore*Bridge` implementations; wrapped in `SessionListRepository`/`SessionItemRepository`. `clearSessionData()` now terminates the Firestore instance (kept for retry on failure) and the next use creates a fresh one (Phase 2 carry-over)
- [x] All list/item flows work against the Firestore emulator with the repo's rules (2026-10-07, see Verification)
- [x] Cross-client check: changes from web appear on Android and vice versa (Android debug build with the emulator flag on the owner's Pixel_10a AVD, owner-approved; afterwards reinstalled a normal dev build with app data cleared)
- [x] Tests for value encoding and error mapping: `WebFirestoreCodecTest` (Kotlin → real Firestore values checked in JS through a test-only module, real Firestore data → Kotlin, round trip), `WebFirestoreErrorMappingTest`; plus the iOS suites ported: `WebFirebaseListRepositoryTest`, `WebFirebaseItemRepositoryTest`, `WebFirebaseItemRepositoryChunkSizeTest`; `WebPlatformModuleTest` checks the session wrapping
- Temporary: `PendingWebPhotoPicker`/`PendingWebPhotoStorage` (picking does nothing; storage fails with a retryable error) so item detail opens. Replaced in Phase 4. The Phase 2 list/item placeholders are removed.

Verification (2026-10-07): wasm tests 320/320 (was 253), Android unit tests 265/265, `assembleDebug`, iOS `compileKotlinIosSimulatorArm64` + `compileTestKotlinIosSimulatorArm64` (no `commonMain` change), production bundle 14.6 MB raw / **4.91 MB gzip** (+0.19 MB for Firestore), no emulator host in the production JS. Auth + Firestore emulators (web config's project ID, repo rules) with a dev bundle at 375×812; after each step the stored documents were read back from the emulator REST API: create list (name, colour) → list detail; add 3 items (`totalItems` 3); complete one (`completedItems` 1, transaction); edit an item's description; swipe-delete an item (tombstone, `totalItems` −1) and swipe-delete + Undo another (restored, counters net zero); clear completed (tombstoned, both counters −1); edit list icon/colour; swipe-delete the list + Undo; hard-delete an item from item detail (document gone, counters −1). Web → Android: the list with its web edits and item count showed on Android. Android → web: an item added and one completed on Android appeared live on the open web list (1/2). Web sign-out (Firestore terminated) → sign-in → data reloads on a fresh instance. No console errors; all Firestore traffic went to the local emulator.

Known gaps: until Phase 4, removing a photo or hard-deleting an item that has a photo (e.g. one added on Android) from web clears `photoRef`/deletes the item but cannot delete the Storage object (the placeholder fails and the screen ignores that failure), leaving an orphaned photo; avoid those two actions on web against the dev project until Phase 4. `clearSessionData()`'s terminate-and-retry path is covered only by the manual sign-out → sign-in run, not by an automated test. The first Undo attempt in testing came after the snackbar's short timeout (behaviour shared with mobile, not a web bug). The raw `firebase-bridge.mjs` in the distribution now also ships the Firestore code (still unused there; exclude in Phase 6).

### Phase 4 — Photos on web · ✅

- [x] `skikoMain` source set shared by iOS and web for image decode/resize (Phase 0 stubs removed): a `skiko` group (iOS + wasmJs) added to the default hierarchy template in `composeApp/build.gradle.kts`; `ImageTransform.ios.kt`/`ImageDecoder.ios.kt` moved unchanged to `skikoMain` as `*.skiko.kt`, and their tests to `skikoTest` as `ImageTransformSkikoTest`/`ImageDecoderSkikoTest`, so the same real-Skia tests now run on iOS and in headless Chrome
- [x] `WebPhotoPicker` via a temporary, hidden `<input type="file" accept="image/*">` attached to the page while open; `change` → bytes, `cancel` → `null` (no change, no error). Reads at most `MAX_SOURCE_BYTES + 1` bytes, so an oversized file is still rejected as `TooLarge` without loading all of it. Type checks stay with the shared magic-byte sniffing
- [x] `WebPhotoStorage` (ported from `IosPhotoStorage`: same ref building, validation-before-upload, MIME from sniffed bytes, missing-object = `null`/no-op, everything else → `PhotoStorageException`) over `WebFirebaseStorageBridge` (`JsWebFirebaseStorageBridge` in production) + `WebFirebaseStorageErrorMapping` (same table as iOS/Android on the JS `storage/...` codes); wrapped in `SessionPhotoStorage`. Storage part of `firebase-bridge.mjs`: resumable upload with content type, `getBytes` with the Android/iOS "fail when larger than the limit" behaviour (the JS SDK would silently truncate), delete, Storage emulator wiring. Bytes cross Kotlin ⇄ JS through Wasm linear memory in one copy (`WebBytes.kt`)
- [x] Sign-out cleanup cancels Storage work (Phase 2 carry-over): `clearSessionData()` first cancels running uploads and waits for them, and answers running downloads (which the JS SDK cannot cancel) with `storage/canceled`, dropping their late result; then terminates Firestore as before. Deletes are not tracked, as on iOS
- [x] Attach, replace, remove, display photos against the Auth + Firestore + Storage emulators (2026-10-07, see Verification); this closes the Phase 3 orphaned-photo gap (remove and hard-delete now delete the Storage object)
- [x] Real-iPhone check in Safari against the dev project (owner action, done 2026-10-07): chooser opens, dismissing re-enables the button, HEIC library photo and camera shot upload and show
- [x] Owner found that a saved photo did not show when the item was reopened on web (Android fine). Cause: the bucket had no CORS configuration, so the browser blocked the `alt=media` response (the emulator allows any origin, so emulator runs passed); the SDK retried it as a network error for up to 2 minutes. Fix (D6): `storage.cors.json` in the repo, applied by the owner to the dev bucket from Cloud Shell; verified in the browser (download 206 in ~0.5 s) and on the owner's phones. `JsWebFirebaseStorageBridge` now logs Storage failures (except not-found and cleanup's `storage/canceled`) to the console as `FluxItStorage`, since item detail drops load errors
- [x] iOS tests (now `ImageTransformSkikoTest`, `ImageDecoderSkikoTest` in `skikoTest`) still pass: `iosSimulatorArm64Test` 334/334, same count as before the move
- [x] Tests: `WebPhotoStorageTest` (iOS validation cases + addressing, MIME, missing object, error wrapping), `WebFirebaseStorageErrorMappingTest` (iOS table ported), `WebStorageSessionTasksTest` (bridge task cancellation with fake operations, directly and through `clearSessionData()`), `WebBytesTest` (round trips incl. every byte value and 5 MB), `WebPlatformModuleTest` (picker + session-wrapped storage), plus the shared Skia tests on web
- Removed: the Phase 3 `PendingWebPhotoPicker`/`PendingWebPhotoStorage` placeholders

Verification (2026-10-07): wasm tests 351/351 (was 320), Android unit tests 265/265, `assembleDebug`, Android instrumented-test compile, iOS `iosSimulatorArm64Test` 334/334, `compileSkikoMainKotlinMetadata`, production bundle 14.6 MB raw / **4.91 MB gzip -9** (4.93 MB at the default gzip level; about the same as Phase 3), no emulator host or port in the production JS or wasm. Auth + Firestore + Storage emulators (web config's project ID, repo rules) with a dev bundle at 375×812; the native file dialog cannot be automated, so a debug-only page hook fed real files into the production `change`/`cancel` path. After each step the Storage objects and item documents were read back from the emulator REST APIs: cancel → no change, no error, input removed; desktop HEIC, GIF and a 27 MB file → rejected locally (failure banner, nothing uploaded); 4032×3024 JPEG → resized to 2048×1536, uploaded as `image/jpeg` (332 KB), `photoRef` set; replace with a 640×480 PNG → uploaded unchanged as `image/png`, ref updated, old object deleted; reload → photo downloaded and shown; sign out → sign in → photo loads on the fresh session; remove photo → ref cleared, object deleted; hard-delete an item with a photo → document and object gone. All Storage traffic went to the local emulator. Secret scan of changed/new files against the local web config: 0 hits.

Known gaps: the picker's native path (chooser opening from a Compose tap, the `cancel` event on a real dismiss) was not exercised in automation, which fed files through a hook; if a browser never fires `cancel`, the photo button stays busy until the user leaves the screen (covered by the owner's iPhone check). The upload-cancellation-on-sign-out path is covered by the fake-operation bridge test, not by a live sign-out during an upload. Wasm memory never shrinks, so after a large pick the page keeps roughly that much memory (up to 25 MB); the largest tested byte round trip is 5 MB. A downloaded photo's request still completes in the background after cleanup (result dropped). Desktop HEIC files (from Finder/Files, not the iPhone photo library) are rejected as unrecognized, same policy as mobile. Two shared issues found while testing, both pre-existing and outside this phase: `strings.xml` has two strings escaped as `\'`, which Compose resources render with a visible backslash on every platform (separate fix suggested); in-app back buttons in `AppNavHost.kt` pop without a root guard, so rapid taps during a screen transition can empty the back stack and crash (`NavDisplay backstack cannot be empty`) — Phase 5 owns back-stack handling. Not checked: web ⇄ Android photo cross-client (needs an emulator-flag Android build on the owner's AVD; owner approval was per-run in Phase 3).

### Phase 5 — Mobile web polish · ✅

- [x] Browser back (toolbar button, Android back gesture, iOS Safari edge swipe) goes back in the app: `BrowserBackNavigation` adds a web-only `NavigationEventInput` to the window's navigation-event dispatcher (the same mechanism Compose web uses for Escape), so whatever handles back on mobile handles it on web — NavDisplay pops, an open dialog closes. While the app can go back, one guard entry sits on top of the browser history; leaving it dispatches back and re-arms; when the app can no longer go back the guard is removed, so browser back at the dashboard leaves the page. Browser forward into a stale guard is undone, and a guard left current by a reload is removed on start. No shared navigation code involved beyond the guard below
- [x] In-app back never empties the stack (found in Phase 4): in `AppNavHost` (commonMain, so Android and iOS get the fix too), system back uses `popUnlessRoot()` and each screen's own back/dismiss uses `popIfTop(route)`, so taps on a screen that is already animating out are ignored. the create-list screen's `onCreated` replaces itself with the new list only while it is on top (review note: a save finishing after the user went back used to remove the dashboard)
- [x] Viewport and safe areas: the app renders into a fixed `#app` container inset by `env(safe-area-inset-*)` (viewport-fit=cover), with page and splash backgrounds matching the app theme in light and dark. On-screen keyboard: `web-shell.js` sizes `#app` to the visual viewport (iOS Safari overlays the keyboard instead of resizing; Chrome on Android resizes via `interactive-widget=resizes-content`) and fires `resize`, the only signal Compose web re-measures on; the bottom inset is dropped while the keyboard is open. Swipe-to-delete on touch: `touch-action: none` on `#app` hands all touch gestures inside the app to Compose (browsers would otherwise take horizontal/vertical pans and cancel the pointer)
- [x] Loading splash (icon, name, spinner; plain HTML/CSS, shown while the Wasm downloads, removed after the first composition). `web-shell.js` checks Wasm GC support before loading `composeApp.js` and otherwise shows "This browser can't run FluxIt…" (Safari 18.2+, current Chrome/Edge/Firefox); a failed script load or an error before the first frame shows "FluxIt couldn't start…" with Try again
- [x] Web app manifest (`manifest.webmanifest`: standalone, portrait, theme colours) + icons (192/512 "any", 512 maskable, 180 apple-touch-icon), drawn as a white check on the app's primary blue (no icon art existed: the iOS asset catalog is empty and Android uses a system drawable); `mobile-web-app-capable`/Apple meta tags
- [x] Real-phone check (owner action), iPhone Safari and, if available, Android Chrome — iPhone: all passed (2026-10-08); Android Chrome: two issues, fixed below: (1) back gesture / browser back goes back one screen, and at the dashboard leaves; (2) in a list, tapping "+ Add new item" keeps the field visible above the keyboard, and the layout returns to full height when the keyboard closes; (3) swipe an item row with a finger to delete; a long list still scrolls; (4) Add to Home Screen shows "FluxIt" with the new icon, opens full screen, and nothing sits under the status bar or home indicator (iOS standalone has no browser back; the in-app back buttons cover it); (5) the splash shows briefly while loading

- [x] Owner's Android finding 1 (2026-10-08): item detail → back gesture → list → back gesture left the site instead of reaching the dashboard. Cause: Chrome's history manipulation intervention — the first version re-pushed its guard entry after each back, without a user tap, so Chrome marked the page's entry as skippable and the next back jumped past it (it did not show on the iPhone, though WebKit has a similar rule; the browser pane's programmatic back does not apply it). Fix: `BrowserBackNavigationEventInput` now mirrors the back depth in the history (NavDisplay reports one back-history entry per screen; an open overlay — dialog, menu — adds one, recognised as an active back history that is not NavDisplay's `SceneInfo`), adds entries only when the app goes deeper (right after a tap), and never adds entries after a back: browser back moves down existing entries and each step is dispatched to the app. In-app back follows with `history.go(-n)`. Syncs run in a microtask so one navigation's separate updates (back history, enabled state) cannot cause a transient push. Bonus: two quick browser backs now pop two screens instead of leaving the page. Tests: `BrowserBackNavigationEventInputTest` rewritten (11 tests) with a fake history that models the intervention (the entry current at a tap-less push becomes skippable), including the reported case and, from the follow-up review, a dialog on item detail closed with back
- [x] Owner's Android finding 2 (2026-10-08): with the keyboard up, the first tap on the add-item send button only closed the keyboard. Likely cause: the tap starts closing the keyboard, Chrome resizes the page mid-tap, the button moves from under the finger and Compose cancels the click. Fix: `web-shell.js` holds viewport changes while a finger is down and applies them when the last finger lifts. Desktop emulation has no on-screen keyboard, so this is unverified until the owner's recheck
- [x] Owner recheck on Android Chrome and iPhone Safari (both changes also run on iOS), done 2026-10-08: send button adds the item with one tap while the keyboard is up; back gestures item → list → dashboard → leave; on item detail, open Delete Item, close it with the back gesture, then back twice reaches the dashboard

- [x] Owner request (2026-10-08): keep the keyboard open while adding several items. The add-item bar disabled its text field while an add was saving, which took the field's focus and closed the keyboard after every item (shared code, so the Android and iOS apps did the same). Now the field stays enabled and only sending waits (`ListDetailScreen` `Composer`: `enabled` for typing, `canSend` for the button; the view model already ignores a send while one is saving, keeping the typed text). The send button never takes focus (`focusProperties { canFocus = false }`), so tapping it with a mouse or hardware keyboard does not move focus either; Send on the keyboard still submits. The keyboard closes as usual from the device, or when another field takes focus or the screen changes. Test: `theNextItemCanBeTypedWhileAnAddIsSavingAndIsSentAfterwards` (commonTest, runs on all three platforms)
- [ ] Owner check: on a phone (web, and optionally the Android/iOS apps), add several items in a row with Send and with the send button; the keyboard stays open

Keep-keyboard verification (2026-10-08): Android unit tests 269/269, iOS simulator tests 338/338, wasm tests 366/366 (new test on all three), `assembleDebug`, production bundle rebuilt (served by the owner's `:8090`). Browser pane against the emulators (`127.0.0.1:8092`): items added with Enter and then typed without refocusing; first try with the send button lost focus to the button (mouse clicks count as keyboard input on web), fixed with `canFocus = false`, after which items added with the send button and the next one typed without refocusing. Known gaps: hardware-keyboard and D-pad users can no longer Tab to the send button (Enter/IME Send in the field still adds; screen readers are unaffected); if an add fails and the user sends the next item before tapping Retry, the failed title is dropped without notice (pre-existing, now more likely with rapid entry).

Follow-up verification (2026-10-08): wasm tests 365/365; Android unit tests and iOS compile unchanged (no commonMain change); production bundle rebuilt (served by the owner's `:8090`). Browser pane against the emulators on the new `web-dev-dist-emulator` preview (`127.0.0.1:8092`): list → depth 1, item → depth 2; browser back twice → list → dashboard, no entries added; in-app back from item and list follows with `go()`; account dialog on the dashboard and the delete dialog on item detail each add one entry, and back closes them without adding any (history length unchanged), then back reaches the dashboard. The pane's programmatic back does not apply Chrome's skip rule, so the Android behaviour itself rests on the modelled tests and the owner's recheck. The keyboard fix cannot be exercised without an on-screen keyboard. `web-shell.js` also stops waiting for a lost `touchend` after 3 s or when the page is hidden

Verification (2026-10-07): wasm tests 360/360 (was 351; new `BrowserBackNavigationEventInputTest` — real dispatcher, scripted history: rearm, last-screen cleanup, in-app back, stale forward, reload — and `BackStackGuardTest`), Android unit tests 268/268, iOS `iosSimulatorArm64Test` 337/337 (`BackStackGuardTest` runs on all three), `assembleDebug`, Android instrumented-test compile, production bundle 14.6 MB raw / **4.93 MB gzip -9** (+~15 KB for icons and shell), no emulator host. In the browser pane at 375×812 against the Auth/Firestore/Storage emulators (on `127.0.0.1:8091`, a separate origin from the owner's signed-in `localhost:8091` session): splash → app, `#app` 375×812 with `touch-action: none`; dashboard → New List pushes the guard, browser back returns to the dashboard with no guard left; list → item, browser back twice → list (guard re-armed) → dashboard (guard removed), history length unchanged; browser back on the dashboard leaves the page; account dialog open → browser back closes it; four rapid in-app back taps from item detail → dashboard, no crash; swipe-to-delete on a row → deleted + Undo snackbar. Test copies of the page (build output only): splash alone; Wasm GC check forced false → unsupported message; app script missing → couldn't-start message with Try again. Manifest parses; all icons load at their sizes.

Known gaps: the iOS keyboard handling, safe areas in home-screen mode and real touch swipes cannot be reproduced in desktop emulation (owner check above). Limits of the history design: after a reload on a deep entry, returning to the page's own entry can step into the previous document's entry, so the page loads once more (no loop); an overlay whose back handler does not close it would leave its entry consumed until the next change (none in the app today); if a `history.go()` never reported back, history syncing would stop for the session (no known path). From the follow-up re-review: two browser backs inside one frame while a dialog is open (e.g. rapid Alt+Left) can push without a tap; nested overlays would share one entry (the app has none); an overlay opened by a long press, if mobile web shows one, would push before the tap's activation (worth a look during the recheck). Back gestures on the create/edit-list screen discard unsaved edits without the discard dialog (already so on Android; web adds more ways to get there). An error before the first frame shows the couldn't-start message even when it is not fatal; it goes away when the app renders. The browser pane currently renders Compose about 5% smaller than the viewport (taps land where Compose lays out, not where it draws); the Phase 4 bundle shows the same, and the owner's phone did not in Phase 0, so it is a pane artifact. Not done: a specific message when the browser blocks site data (`localStorage`), still in Risks. The production rebuild for this phase replaced the files the owner's `:8090` server serves, so phones on that address now get the Phase 5 build.

### Phase 6 — Ship to Firebase Hosting · ⬜

- [ ] `hosting` block in `firebase.json` (public dir = production distribution, cache headers, exclude `*.js.map`)
- [ ] Deploy with `firebase deploy --only hosting --project <id>` (owner-confirmed)
- [ ] Owner disables client sign-up (D3) and sets a budget alert
- [ ] Confirm photos load from the Hosting origin (bucket CORS from D6 already covers it; any other bucket needs `storage.cors.json` applied)
- [ ] Optional: restrict the browser API key to the Hosting domains
- [ ] End-to-end smoke test on the owner's phone

### Phase 7 (optional) — Unify iOS and web bridged repositories · ⬜

- [ ] Extract neutral bridge error/bytes types and a shared bridged repository source set

## Status log

| Date | Phase | Note |
|---|---|---|
| 2026-10-07 | — | Plan approved (D1–D5). Branch `web/wasm-app` created. |
| 2026-10-07 | 0 | wasmJs target added; all shared deps compile for Wasm unchanged; real login screen renders at phone width; shared tests run on Wasm (213 pass); mobile builds/tests unaffected. |
| 2026-10-07 | 0 | Owner verified the sign-in pages on a real phone. |
| 2026-10-07 | 1 | Feature flags hide sign-up and seeding on web; web Firebase config loader + template + README; Wasm incremental-compile crash worked around. |
| 2026-10-07 | 1 | Review 1 FAIL (config value leak on malformed JSON) → fixed → review 2 PASS WITH NOTES. |
| 2026-10-07 | 1 | Owner added the real web config; generator validates it. Session paused; Phase 2 starts in a new session. |
| 2026-10-07 | 2 | Firebase JS SDK + auth bridge; `WebAuthRepository` (iOS port) wrapped in `SessionAuthRepository`; `WebSessionCleanup`; sign-in/restore/reset/sign-out verified against the Auth emulator. Dev-project check left to the owner. |
| 2026-10-07 | 2 | Review PASS WITH NOTES; wording and emulator-flag guard fixed. |
| 2026-10-07 | 2 | Owner verified sign-in, reload, password reset and sign-out against the dev project. |
| 2026-10-07 | 3 | Firestore bridge + codec; list/item repositories ported from iOS and session-wrapped; Firestore terminate on sign-out; all flows and web⇄Android cross-client verified against emulators. |
| 2026-10-07 | 3 | Review PASS WITH NOTES; cleanup edge case, Timestamp comparison and a KDoc fixed; photo-orphan caveat recorded. |
| 2026-10-07 | 4 | `skikoMain`/`skikoTest` shared by iOS and web; `WebPhotoPicker`, `WebPhotoStorage` (iOS port) + Storage bridge, session-wrapped; Storage task cancellation on sign-out; attach/replace/remove/display verified against emulators. iPhone HEIC check left to the owner. |
| 2026-10-07 | 4 | Review PASS WITH NOTES; cleanup-snapshot comment, size wording and known gaps updated; owner iPhone check widened to cover the native chooser and cancel. |
| 2026-10-07 | 4 | Owner's iPhone check passed, but saved photos did not reload on web: missing bucket CORS. Owner applied `storage.cors.json` (D6); photos load. Storage failures now logged to the console. |
| 2026-10-07 | 5 | Browser back wired to the app's back via a navigation-event input; back-stack guard in `AppNavHost`; safe-area/keyboard-aware `#app`; splash with unsupported-browser and failed-start messages; manifest + icons. Real-phone check left to the owner. |
| 2026-10-07 | 5 | Review PASS WITH NOTES; create-list `onCreated` guarded, `env()` fallbacks added, history-guard limits and other notes recorded under known gaps. |
| 2026-10-08 | 5 | Owner: iPhone checks all pass. Android Chrome: back left the site after item → list (Chrome's history intervention) and the send button needed two taps with the keyboard up. Back integration redesigned (depth-mirrored history, no pushes after back; overlays get their own entry); viewport changes held while a finger is down. Recheck on both phones pending. |
| 2026-10-08 | 5 | Follow-up review PASS WITH NOTES after one round (dialog on an inner screen fixed; touch-tracking reset added); edge cases recorded under known gaps. |
| 2026-10-08 | 5 | Owner recheck passed on both phones. Owner request: the add-item field keeps focus (and the keyboard) across adds — shared change, so the Android and iOS apps get it too. |
| 2026-10-08 | 5 | Keep-keyboard change reviewed PASS WITH NOTES; tracker ordering, verification and known gaps fixed; view-model KDoc corrected. |

## Review log

| Date | Phase | Verdict | Findings / follow-ups |
|---|---|---|---|
| 2026-10-07 | 0 | PASS WITH NOTES | Re-ran wasm tests (213), Android unit tests (257), iOS simulator tests (326), production bundle; no secrets. Fixed: misleading build comment, `FakeAuthRepository.authenticate` KDoc. Carried forward: Phase 2 — real `WebAuthRepository` must start at `Unresolved` (spike stub starts at SignedOut); Phase 6 — exclude `composeApp.js.map` from Hosting. Browser render check accepted as implementer claim; phone check still owner action. |
| 2026-10-07 | 1 | FAIL | Blocking: malformed `firebase-web-config.json` (e.g. the console's JS snippet) made Groovy's JSON error echo the offending line, including values. Fixed: parse errors replaced by a value-free message with no chained cause. |
| 2026-10-07 | 1 | PASS WITH NOTES | Re-review: leak fixed; wasm 222, Android 265 re-run; iOS 334 and `npm run check` 4/4 from review 1 (sources unchanged since). Note: `DebugSeeder` single still declared in `appModule` but never resolved on web — acceptable. Browser check and file-based generator checks accepted as implementer claims. |
| 2026-10-07 | 2 | PASS WITH NOTES | Re-ran wasm 253, production bundle (no emulator host in JS or wasm), Android 265 + `assembleDebug`, iOS main+test compile; secret scan of tracked/untracked files against the local web config: 0 hits; yarn.lock only adds the `firebase@12.19.0` tree. Fixed: mapper KDoc overstated iOS parity (phone-auth `sessionExpired` has no web code; `auth/invalid-login-credentials` is legacy); production webpack now refuses the emulator flag; blocked-`localStorage` fail-closed noted under Risks. Carried to Phase 3: `clearSessionData` must terminate-and-recreate Firestore (D4) and real repos must be wrapped in `SessionListRepository`/`SessionItemRepository`; consider a `demo-*` project ID for web emulator runs. Phase 6: exclude raw `firebase-bridge.mjs` from Hosting. Emulator browser check accepted as implementer claim; dev-project check is an owner action. |
| 2026-10-07 | 3 | PASS WITH NOTES | Re-ran wasm 320 (`--rerun`), production bundle (4.90 MB gzip; no emulator host in JS or wasm; test-support module not shipped), Android 265 + `assembleDebug`, iOS main+test compile; secret scan of tracked/untracked/staged files: 0 hits; only wasmJs + tracker changed. Repositories diff against iOS only in KDoc, visibility, production constructor and clock; JS bridge matches the Swift bridges (batch increment, transaction + retry rule, clear-completed query/batch, decoding, path validation). Fixed: `clearSessionData` retry now also terminates an instance created after a failed attempt; retry check compares `deletedAt` with `Timestamp.isEqual` like Swift; KDoc test pointer. Recorded: photo orphaning on web until Phase 4; no automated test for terminate/retry; `demo-*` project ID for emulator runs (Risks). Emulator and cross-client checks accepted as implementer claims. |
| 2026-10-07 | 4 | PASS WITH NOTES | Re-ran wasm 351 (`--rerun-tasks`), Android 265 + `assembleDebug` + instrumented-test compile, iOS compile + `compileSkikoMainKotlinMetadata`, `iosSimulatorArm64Test` 334 (Skiko tests ran on iOS), production bundle (no emulator host/port in JS or wasm; test support not shipped); secret scan of 28 changed/new files: 0 hits; no rules changes; no JS interop in `commonMain`/`skikoMain`. `WebPhotoStorage` matches `IosPhotoStorage` line for line; error table matches iOS/Android; `storageTask` exactly-once and cleanup ordering confirmed; `maxSize + 1` checked against the vendored SDK's truncation; `WebBytes` reads the memory buffer after allocation and copies out with `slice()`. Notes: picker native path untested in automation (added to the owner's iPhone check); `cancelStorageTasks` snapshot relies on `SessionWork` closing first (comment added); Wasm memory high-water mark after large picks (Known gaps); gzip size wording; test-only exports and raw `.mjs` in dist (Phase 6). Emulator checks accepted as implementer claims. |
| 2026-10-07 | 4 (follow-up) | PASS WITH NOTES | Bucket CORS + Storage logging delta. Re-ran wasm 351 (`--rerun-tasks`) and wasm compile; no rules/`firebase.json` changes; no config values, bucket names or LAN addresses in tracked files; no client uses token download URLs. Fixed: `storage.cors.json` tracked; D6/README wording (CORS is not access control; SDK downloads need the token); cleanup's `storage/canceled` no longer logged. Noted: logged SDK messages can include the object path (user's own console only); Phase 6 could narrow CORS origins. Root cause and dev-bucket fix accepted as owner/implementer observations. |
| 2026-10-07 | 5 | PASS WITH NOTES | Re-ran wasm 360 (`--rerun-tasks`), production bundle (new shell files and icons shipped; no emulator host — the only `localhost` string is inside the Auth SDK's IdP code), Android 268 + `assembleDebug`, iOS compile + `iosSimulatorArm64Test` 337; secret/LAN scan of changed files: 0 hits; PNGs carry no text/EXIF. Read `navigationevent` 1.0.2: `addInput` reports the initial enabled state; overlay handlers count, so dialogs arm the guard. Root guard keeps Android/iOS behaviour except the rapid-tap case. State machine sound for forward, sign-out, dialogs and own-back vs push ordering; no resize loop; sensible fallbacks. Fixed: create-list `onCreated` only replaces itself while on top; `env()` fallbacks. Recorded: two quick browser backs, reload double-load, unsaved create-list edits on back, non-fatal pre-frame error message; `localStorage` message deferred (Risks). Browser checks accepted as implementer claims; phone check is an owner action. |
| 2026-10-08 | 5 (follow-up) | PASS WITH NOTES | Android fixes. Review 1: back redesign sound for the reported bug, but closing a dialog on an inner screen with back still pushed without a tap — fixed (overlays get their own entry, told apart from NavDisplay's `SceneInfo`); `touches` could stick after a lost `touchend` — reset after 3 s / on hide; tracker wording and recheck scope fixed. Re-review: wasm 365 (`--rerun`, `BrowserBackNavigationEventInputTest` 11/11), production bundle, Android/iOS compile (no commonMain change), no secrets/rules changes; Compose 1.10.3 `Dialog`/focusable `Popup` register `NavigationEventInfo.None` handlers, so the screen/overlay split holds; no tap-less push in the app's current flows. Recorded: two backs within one frame with a dialog open, nested overlays, long-press overlays (unverified). Keyboard fix and Android behaviour rest on the owner's recheck. |

## Run the web build

```sh
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # dev server with live reload
./gradlew :composeApp:wasmJsBrowserDistribution     # production bundle
python3 -m http.server 8090 --directory composeApp/build/dist/wasmJs/productionExecutable
./gradlew :composeApp:wasmJsBrowserTest             # shared tests in headless Chrome
```

Against the local emulators (Auth from Phase 2, Firestore from Phase 3, Storage from Phase 4): start them under the web config's project ID,
then build with the emulator flag and serve the development distribution on `localhost`:

```sh
FLUXIT_PROJECT_ID=$(node -e 'process.stdout.write(JSON.parse(require("node:fs").readFileSync("composeApp/firebase-web-config.json")).projectId)')
firebase/node_modules/.bin/firebase --config firebase.json --project "$FLUXIT_PROJECT_ID" emulators:start --only auth,firestore,storage
./gradlew :composeApp:wasmJsBrowserDevelopmentExecutableDistribution -Pfluxit.firebase.emulator.enabled=true
python3 -m http.server 8091 --bind 127.0.0.1 --directory composeApp/build/dist/wasmJs/developmentExecutable
```

In the Claude desktop browser pane, use the `web-dev-dist-emulator` preview (`http://127.0.0.1:8092`) for emulator builds: a separate origin from `localhost:8091`, so a stored dev-project session is never loaded by an emulator build.

The production bundle (`wasmJsBrowserDistribution`) refuses to build with the flag on. Create test users in the emulator UI
(http://127.0.0.1:4000/auth); the app itself cannot sign up on web.

To try it on a phone on the same Wi-Fi, serve with `--bind 0.0.0.0` and open
`http://<your-mac-LAN-IP>:8090`. Plain `http://` on a LAN address is not a secure
context; that is fine for the spike, but Firebase Auth testing from Phase 2 on should
use `localhost` or the deployed HTTPS site.

## Risks and open questions

- Web emulator runs use the dev project's ID (the web config has no override), so a client not routed to the emulator would reach the real project. The production bundle refuses the emulator flag; consider a `demo-*` project override for emulator builds.
- If a browser blocks site data (`localStorage`), the cleanup marker cannot be written, so web sign-in fails closed with the generic cleanup error. Same policy as mobile; a specific message was not added in Phase 5 (possible follow-up).
- Wasm incremental compilation is disabled (Kotlin 2.3.20 crash, see Phase 1). Re-enable both `kotlin.incremental.js*` flags after a Kotlin upgrade and re-test an edit-recompile cycle.

- Compose for Web is Beta and canvas-rendered: password-manager autofill, text selection and accessibility are weaker than HTML.
- Kotlin/Wasm needs Wasm GC: iOS/Safari 18.2+, current Chrome/Firefox. JS fallback possible if needed.
- Copying iOS repository logic duplicates counter rules; Phase 7 removes the duplication.
- `composeApp` is an Android application module; AGP 9 will require splitting the app module out.
