# FluxIt Web App — Plan and Progress Tracker

Single source of truth for the mobile-first web client. Update this file at the end of
every phase (status, checklist, notes) and record the reviewer verdict before starting
the next phase.

- **Branch:** `web/wasm-app`
- **Started:** 2026-10-07
- **Current phase:** Phase 5 — Mobile web polish (next; not started)

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
- [ ] Real-iPhone check in Safari against the dev project (owner action): tapping Update opens the photo chooser; dismissing it re-enables the button (the `cancel` event); a HEIC library photo and a camera shot both upload (Safari hands over JPEG) and show
- [x] iOS tests (now `ImageTransformSkikoTest`, `ImageDecoderSkikoTest` in `skikoTest`) still pass: `iosSimulatorArm64Test` 334/334, same count as before the move
- [x] Tests: `WebPhotoStorageTest` (iOS validation cases + addressing, MIME, missing object, error wrapping), `WebFirebaseStorageErrorMappingTest` (iOS table ported), `WebStorageSessionTasksTest` (bridge task cancellation with fake operations, directly and through `clearSessionData()`), `WebBytesTest` (round trips incl. every byte value and 5 MB), `WebPlatformModuleTest` (picker + session-wrapped storage), plus the shared Skia tests on web
- Removed: the Phase 3 `PendingWebPhotoPicker`/`PendingWebPhotoStorage` placeholders

Verification (2026-10-07): wasm tests 351/351 (was 320), Android unit tests 265/265, `assembleDebug`, Android instrumented-test compile, iOS `iosSimulatorArm64Test` 334/334, `compileSkikoMainKotlinMetadata`, production bundle 14.6 MB raw / **4.91 MB gzip -9** (4.93 MB at the default gzip level; about the same as Phase 3), no emulator host or port in the production JS or wasm. Auth + Firestore + Storage emulators (web config's project ID, repo rules) with a dev bundle at 375×812; the native file dialog cannot be automated, so a debug-only page hook fed real files into the production `change`/`cancel` path. After each step the Storage objects and item documents were read back from the emulator REST APIs: cancel → no change, no error, input removed; desktop HEIC, GIF and a 27 MB file → rejected locally (failure banner, nothing uploaded); 4032×3024 JPEG → resized to 2048×1536, uploaded as `image/jpeg` (332 KB), `photoRef` set; replace with a 640×480 PNG → uploaded unchanged as `image/png`, ref updated, old object deleted; reload → photo downloaded and shown; sign out → sign in → photo loads on the fresh session; remove photo → ref cleared, object deleted; hard-delete an item with a photo → document and object gone. All Storage traffic went to the local emulator. Secret scan of changed/new files against the local web config: 0 hits.

Known gaps: the picker's native path (chooser opening from a Compose tap, the `cancel` event on a real dismiss) was not exercised in automation, which fed files through a hook; if a browser never fires `cancel`, the photo button stays busy until the user leaves the screen (covered by the owner's iPhone check). The upload-cancellation-on-sign-out path is covered by the fake-operation bridge test, not by a live sign-out during an upload. Wasm memory never shrinks, so after a large pick the page keeps roughly that much memory (up to 25 MB); the largest tested byte round trip is 5 MB. A downloaded photo's request still completes in the background after cleanup (result dropped). Desktop HEIC files (from Finder/Files, not the iPhone photo library) are rejected as unrecognized, same policy as mobile. Two shared issues found while testing, both pre-existing and outside this phase: `strings.xml` has two strings escaped as `\'`, which Compose resources render with a visible backslash on every platform (separate fix suggested); in-app back buttons in `AppNavHost.kt` pop without a root guard, so rapid taps during a screen transition can empty the back stack and crash (`NavDisplay backstack cannot be empty`) — Phase 5 owns back-stack handling. Not checked: web ⇄ Android photo cross-client (needs an emulator-flag Android build on the owner's AVD; owner approval was per-run in Phase 3).

### Phase 5 — Mobile web polish · ⬜

- [ ] Browser back button pops the Navigation 3 back stack; in-app back never pops the last entry (found in Phase 4: rapid back taps can empty the stack)
- [ ] Viewport, safe areas, on-screen keyboard behaviour; swipe-to-delete on touch
- [ ] Loading splash while Wasm downloads; unsupported-browser message
- [ ] Web app manifest + icons (add to home screen)

### Phase 6 — Ship to Firebase Hosting · ⬜

- [ ] `hosting` block in `firebase.json` (public dir = production distribution, cache headers, exclude `*.js.map`)
- [ ] Deploy with `firebase deploy --only hosting --project <id>` (owner-confirmed)
- [ ] Owner disables client sign-up (D3) and sets a budget alert
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

## Review log

| Date | Phase | Verdict | Findings / follow-ups |
|---|---|---|---|
| 2026-10-07 | 0 | PASS WITH NOTES | Re-ran wasm tests (213), Android unit tests (257), iOS simulator tests (326), production bundle; no secrets. Fixed: misleading build comment, `FakeAuthRepository.authenticate` KDoc. Carried forward: Phase 2 — real `WebAuthRepository` must start at `Unresolved` (spike stub starts at SignedOut); Phase 6 — exclude `composeApp.js.map` from Hosting. Browser render check accepted as implementer claim; phone check still owner action. |
| 2026-10-07 | 1 | FAIL | Blocking: malformed `firebase-web-config.json` (e.g. the console's JS snippet) made Groovy's JSON error echo the offending line, including values. Fixed: parse errors replaced by a value-free message with no chained cause. |
| 2026-10-07 | 1 | PASS WITH NOTES | Re-review: leak fixed; wasm 222, Android 265 re-run; iOS 334 and `npm run check` 4/4 from review 1 (sources unchanged since). Note: `DebugSeeder` single still declared in `appModule` but never resolved on web — acceptable. Browser check and file-based generator checks accepted as implementer claims. |
| 2026-10-07 | 2 | PASS WITH NOTES | Re-ran wasm 253, production bundle (no emulator host in JS or wasm), Android 265 + `assembleDebug`, iOS main+test compile; secret scan of tracked/untracked files against the local web config: 0 hits; yarn.lock only adds the `firebase@12.19.0` tree. Fixed: mapper KDoc overstated iOS parity (phone-auth `sessionExpired` has no web code; `auth/invalid-login-credentials` is legacy); production webpack now refuses the emulator flag; blocked-`localStorage` fail-closed noted under Risks. Carried to Phase 3: `clearSessionData` must terminate-and-recreate Firestore (D4) and real repos must be wrapped in `SessionListRepository`/`SessionItemRepository`; consider a `demo-*` project ID for web emulator runs. Phase 6: exclude raw `firebase-bridge.mjs` from Hosting. Emulator browser check accepted as implementer claim; dev-project check is an owner action. |
| 2026-10-07 | 3 | PASS WITH NOTES | Re-ran wasm 320 (`--rerun`), production bundle (4.90 MB gzip; no emulator host in JS or wasm; test-support module not shipped), Android 265 + `assembleDebug`, iOS main+test compile; secret scan of tracked/untracked/staged files: 0 hits; only wasmJs + tracker changed. Repositories diff against iOS only in KDoc, visibility, production constructor and clock; JS bridge matches the Swift bridges (batch increment, transaction + retry rule, clear-completed query/batch, decoding, path validation). Fixed: `clearSessionData` retry now also terminates an instance created after a failed attempt; retry check compares `deletedAt` with `Timestamp.isEqual` like Swift; KDoc test pointer. Recorded: photo orphaning on web until Phase 4; no automated test for terminate/retry; `demo-*` project ID for emulator runs (Risks). Emulator and cross-client checks accepted as implementer claims. |
| 2026-10-07 | 4 | PASS WITH NOTES | Re-ran wasm 351 (`--rerun-tasks`), Android 265 + `assembleDebug` + instrumented-test compile, iOS compile + `compileSkikoMainKotlinMetadata`, `iosSimulatorArm64Test` 334 (Skiko tests ran on iOS), production bundle (no emulator host/port in JS or wasm; test support not shipped); secret scan of 28 changed/new files: 0 hits; no rules changes; no JS interop in `commonMain`/`skikoMain`. `WebPhotoStorage` matches `IosPhotoStorage` line for line; error table matches iOS/Android; `storageTask` exactly-once and cleanup ordering confirmed; `maxSize + 1` checked against the vendored SDK's truncation; `WebBytes` reads the memory buffer after allocation and copies out with `slice()`. Notes: picker native path untested in automation (added to the owner's iPhone check); `cancelStorageTasks` snapshot relies on `SessionWork` closing first (comment added); Wasm memory high-water mark after large picks (Known gaps); gzip size wording; test-only exports and raw `.mjs` in dist (Phase 6). Emulator checks accepted as implementer claims. |

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

The production bundle (`wasmJsBrowserDistribution`) refuses to build with the flag on. Create test users in the emulator UI
(http://127.0.0.1:4000/auth); the app itself cannot sign up on web.

To try it on a phone on the same Wi-Fi, serve with `--bind 0.0.0.0` and open
`http://<your-mac-LAN-IP>:8090`. Plain `http://` on a LAN address is not a secure
context; that is fine for the spike, but Firebase Auth testing from Phase 2 on should
use `localhost` or the deployed HTTPS site.

## Risks and open questions

- Web emulator runs use the dev project's ID (the web config has no override), so a client not routed to the emulator would reach the real project. The production bundle refuses the emulator flag; consider a `demo-*` project override for emulator builds.
- If a browser blocks site data (`localStorage`), the cleanup marker cannot be written, so web sign-in fails closed with the generic cleanup error. Same policy as mobile; consider a specific message in Phase 5.
- Wasm incremental compilation is disabled (Kotlin 2.3.20 crash, see Phase 1). Re-enable both `kotlin.incremental.js*` flags after a Kotlin upgrade and re-test an edit-recompile cycle.

- Compose for Web is Beta and canvas-rendered: password-manager autofill, text selection and accessibility are weaker than HTML.
- Kotlin/Wasm needs Wasm GC: iOS/Safari 18.2+, current Chrome/Firefox. JS fallback possible if needed.
- Navigation 3 is not wired to browser history by default (Phase 5).
- Copying iOS repository logic duplicates counter rules; Phase 7 removes the duplication.
- `composeApp` is an Android application module; AGP 9 will require splitting the app module out.
