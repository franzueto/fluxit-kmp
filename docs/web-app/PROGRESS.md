# FluxIt Web App — Plan and Progress Tracker

Single source of truth for the mobile-first web client. Update this file at the end of
every phase (status, checklist, notes) and record the reviewer verdict before starting
the next phase.

- **Branch:** `web/wasm-app`
- **Started:** 2026-10-07
- **Current phase:** Phase 2 — Auth on web (next; not started)

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

Image decode/resize on web uses Skia (Skiko) — same code as `iosMain`; Phase 4 moves it
into a `skikoMain` source set shared by iOS and web.

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

### Phase 2 — Auth on web · ⬜

- [ ] Add Firebase JS SDK (npm) and the auth part of the JS bridge
- [ ] `WebAuthRepository` (ported from `IosAuthRepository`) + error mapping from Firebase JS error codes to `AuthError`
- [ ] Wrapped in `SessionAuthRepository`; `SessionCleanup` for web
- [ ] Sign in, session restore on reload, password reset, sign out work against the Auth emulator and the dev project
- [ ] Tests for error mapping
- [ ] Session starts at `Unresolved` and resolves only via `restoreSession()` (Phase 0 review follow-up)

### Phase 3 — Lists and items on web · ⬜

- [ ] Firestore part of the JS bridge: listeners with metadata, batch, transaction, field update, clear-completed query
- [ ] `FirebaseValue` ⇄ JS encoding (incl. server timestamps, pending timestamps, increments)
- [ ] `WebFirebaseListRepository`, `WebFirebaseItemRepository` (ported from iOS), including counter transactions
- [ ] All list/item flows work against the Firestore emulator with the deployed rules
- [ ] Cross-client check: changes from web appear on Android and vice versa
- [ ] Tests for value encoding and error mapping

### Phase 4 — Photos on web · ⬜

- [ ] `skikoMain` source set shared by iOS and web for image decode/resize (remove Phase 0 stubs)
- [ ] `WebPhotoPicker` via `<input type="file" accept="image/*">`
- [ ] `WebPhotoStorage` (upload/download/delete via JS bridge) wrapped in `SessionPhotoStorage`
- [ ] Attach, replace, remove, display photos; iPhone HEIC behaviour verified
- [ ] iOS tests (`ImageTransformIosTest`, `ImageDecoderIosTest`) still pass

### Phase 5 — Mobile web polish · ⬜

- [ ] Browser back button pops the Navigation 3 back stack
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

## Review log

| Date | Phase | Verdict | Findings / follow-ups |
|---|---|---|---|
| 2026-10-07 | 0 | PASS WITH NOTES | Re-ran wasm tests (213), Android unit tests (257), iOS simulator tests (326), production bundle; no secrets. Fixed: misleading build comment, `FakeAuthRepository.authenticate` KDoc. Carried forward: Phase 2 — real `WebAuthRepository` must start at `Unresolved` (spike stub starts at SignedOut); Phase 6 — exclude `composeApp.js.map` from Hosting. Browser render check accepted as implementer claim; phone check still owner action. |
| 2026-10-07 | 1 | FAIL | Blocking: malformed `firebase-web-config.json` (e.g. the console's JS snippet) made Groovy's JSON error echo the offending line, including values. Fixed: parse errors replaced by a value-free message with no chained cause. |
| 2026-10-07 | 1 | PASS WITH NOTES | Re-review: leak fixed; wasm 222, Android 265 re-run; iOS 334 and `npm run check` 4/4 from review 1 (sources unchanged since). Note: `DebugSeeder` single still declared in `appModule` but never resolved on web — acceptable. Browser check and file-based generator checks accepted as implementer claims. |

## Run the web build

```sh
./gradlew :composeApp:wasmJsBrowserDevelopmentRun   # dev server with live reload
./gradlew :composeApp:wasmJsBrowserDistribution     # production bundle
python3 -m http.server 8090 --directory composeApp/build/dist/wasmJs/productionExecutable
./gradlew :composeApp:wasmJsBrowserTest             # shared tests in headless Chrome
```

To try it on a phone on the same Wi-Fi, serve with `--bind 0.0.0.0` and open
`http://<your-mac-LAN-IP>:8090`. Plain `http://` on a LAN address is not a secure
context; that is fine for the spike, but Firebase Auth testing from Phase 2 on should
use `localhost` or the deployed HTTPS site.

## Risks and open questions

- Wasm incremental compilation is disabled (Kotlin 2.3.20 crash, see Phase 1). Re-enable both `kotlin.incremental.js*` flags after a Kotlin upgrade and re-test an edit-recompile cycle.

- Compose for Web is Beta and canvas-rendered: password-manager autofill, text selection and accessibility are weaker than HTML.
- Kotlin/Wasm needs Wasm GC: iOS/Safari 18.2+, current Chrome/Firefox. JS fallback possible if needed.
- Navigation 3 is not wired to browser history by default (Phase 5).
- Copying iOS repository logic duplicates counter rules; Phase 7 removes the duplication.
- `composeApp` is an Android application module; AGP 9 will require splitting the app module out.
