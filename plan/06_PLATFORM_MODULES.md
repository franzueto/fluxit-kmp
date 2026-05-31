# Phase 06 — Platform Modules

> **Goal:** Implement the per-platform side of every domain port from Phase 04. Each `platform-*` module exposes a Koin module that binds the domain interface to an Android impl (`androidMain`) and an iOS impl (`iosMain`). Domain stays pure; the rest of the app sees only interfaces.

**Owner:** Mobile platform
**Depends on:** Phase 04 (ports defined), Phase 01 (`platform-*` module stubs exist).
**Blocks:** Phase 09, 10, 13 (any feature that needs reminders, photos, or analytics).
**Exit criteria (Definition of Done):**
- Each platform module ships an Android impl + iOS impl + Koin binding + a fake for tests.
- Permissions: every capability with a permission requirement has a documented "ask → granted → use" flow, including denial recovery.
- Smoke test: a `RemindersDemo` debug screen schedules a real local notification on Android *and* iOS; a `PhotoDemo` screen captures, persists, and re-renders an image on both.
- Konsist: nothing outside `:platform:*` imports `androidx.work.*`, `UserNotifications`, `androidx.camera.*`, `Photos.framework`, Firebase SDKs, `co.touchlab.kermit.crashlytics.*`, etc.

---

## 0. Slice plan (commit cadence)

Phase 06 ships one `feat` commit per slice (plan files synced in-commit, impl-log
entry `_Commit `<pending>`._`) + a `docs(plan):` SHA-backfill commit, per the Phase 05
cadence. Each `platform-*` slice ships: commonMain Koin module + helpers,
`androidMain` + `iosMain` actuals, and a fake/test. Slice order (approved plan Part 2):

1. **`platform-logging`** — Kermit-backed `AppLogger` actual + `loggingModule`. ✅
2. **`platform-config`** — `ConfigProvider` + typed `ConfigKey`s + real `Clock`/`IdGenerator` bindings.
3. **`platform-analytics`** — `AnalyticsSink` port (built in `:shared:domain` first) + `LoggingAnalyticsSink` (ADR-012a).
4. **`platform-reminders`** — `ReminderScheduler` (WorkManager+NotificationCompat / `UNUserNotificationCenter`).
5. **`platform-photo`** — `PhotoCapture` + `PhotoStorage` + encoder + Activity/UIViewController host plumbing.
6. **Swap interim → real** in `initKoin`: replace `InterimPlatformModule` bindings with the 5 platform modules, **delete `InterimPlatformModule.kt`**, update `appModules()`/`initKoinIos()` + android start site.
7. **Composition roots** — `:android-app` (Koin start + Compose `NavHost` off `RootStore`); iOS `@main App` + `NavigationStack`.
8. **Lists Dashboard end-to-end** both platforms; on-device/sim round-trip; flip ADRs 009/009a/009b(/009c) Accepted; Phase 06 → 🟢.

A Konsist `PlatformLayerArchTest` (mirroring `StateLayer`/`DataLayerArchTest`) lands
with the first slice that introduces a bannable platform import (reminders/photo),
guarding that nothing outside `:platform:*` imports `androidx.work.*`/`UserNotifications`/
`androidx.camera.*`/`Photos`/Firebase/`kermit.crashlytics`.

---

## 1. Module map

| Module | Ports implemented (Phase 04) | Android backbone | iOS backbone |
|---|---|---|---|
| `platform-logging` | `AppLogger` | Kermit + Logcat + Crashlytics sink | Kermit + os_log + Crashlytics sink |
| `platform-analytics` | `AnalyticsSink` | Firebase Analytics (or no-op debug sink) | Firebase Analytics |
| `platform-config` | `ConfigProvider`, `Clock`, `IdGenerator` | BuildKonfig + remote config flag (v2) | BuildKonfig |
| `platform-reminders` | `ReminderScheduler` | WorkManager + `NotificationCompat` | `UNUserNotificationCenter` |
| `platform-photo` | `PhotoCapture`, `PhotoStorage` | CameraX + ActivityResult API + app-internal storage | `PHPickerViewController` + `AVCaptureSession` + `FileManager` |

Each module's `commonMain` declares:
- Koin module factory (`val xModule: Module`)
- Any common helpers (e.g. mime sniffing for photos)

`androidMain` and `iosMain` each ship the actual implementations.

## 2. `platform-logging` — ✅ Slice 1

- [x] commonMain: `KermitAppLogger(private val logger: Logger) : AppLogger` — adapter from domain `AppLogger` to Kermit's `Logger`; maps the four port levels onto Kermit severities + forwards `tag` and the warn/error `Throwable`. _(Slice 1.)_
- [x] commonMain: `loggingModule = module { single<Logger> { Logger(StaticConfig(minSeverity = Severity.Info, logWriterList = platformLogWriters()), tag = "FluxIt") } ; single<AppLogger> { KermitAppLogger(get()) } }`. _(Slice 1 — writer list comes from the expect/actual `platformLogWriters()` seam rather than a Koin-resolved `List<LogWriter>`, so no separate binding is needed and the writer list is a compile-time platform concern.)_
- [x] **androidMain / iosMain**: `actual fun platformLogWriters(): List<LogWriter>`. _(Slice 1 — both return `listOf(platformLogWriter())` — Kermit's default resolves to `LogcatWriter` on Android / `NSLogWriter` (os_log) on iOS. **Diverged from the sketch's explicit `LogcatWriter`/`OSLogWriter(subsystem=…)` construction + `CrashlyticsLogWriter`:** Crashlytics is an unresolved open question (§13) and pulls Firebase + a `google-services.json`/`GoogleService-Info.plist` into the build, so v1 ships Logcat/os_log only. `platformLogWriters()` is the seam the Crashlytics writer slots into per platform when §13 resolves — `loggingModule`/`KermitAppLogger` stay unchanged.)_
- [ ] Crashlytics initialization done in `android-app` / `ios-app` startup (Phase 06 doesn't include vendor SDK init — only the writer wiring). _(Deferred with §13 Crashlytics decision.)_
- [x] **Test fake**: `RecordingAppLogger` collecting `(level, tag, message, throwable)` entries. _(Slice 1 — placed in `:shared:domain-testing` `commonMain` (next to `FakeClock` etc.), not `platform-logging` commonTest, so it is reusable from any module's tests per the "reused across other modules" note. `KermitAppLoggerTest` (commonTest) proves the severity/tag/throwable mapping via a recording Kermit `LogWriter`.)_

## 3. `platform-analytics`

- [ ] commonMain: `AnalyticsEvent.toFlatPayload(): Pair<String, Map<String, Any>>` — single source for vendor-agnostic event flattening (e.g. `ListCreated(id, color, icon)` → `"list_created", {color: "PRIMARY_BLUE", icon: "CART"}`). Snake-case names. **Never include user-content** (no list names, no item titles, no photo bytes).
- [ ] commonMain: `analyticsModule = module { single<AnalyticsSink> { LoggingAnalyticsSink(get()) } }`. **v1 ships `LoggingAnalyticsSink` only** (locked by Phase 16 ADR-012a). Events flow to `AppLogger` at `Debug`; nothing leaves the device. The `expect class AnalyticsSinkProvider` indirection is **not built in v1** — re-introduce when v2 adds a vendor sink.
- [ ] **androidMain / iosMain**: no Firebase Analytics SDK in v1. `FirebaseAnalyticsSink` class is **not shipped**; do not add the dependency. Adding it in v2 is a focused PR (one new class per platform + Koin binding swap).
- [ ] `RecordingAnalyticsSink` (in `commonTest`) is the only other sink in v1, used by store/integration tests.
- [ ] **Privacy**: maintain `docs/ANALYTICS_EVENTS.md` (Phase 16 §4) listing every event + property + retention + PII classification. Update in lockstep with `AnalyticsEvent` sealed hierarchy.

## 4. `platform-config`

- [ ] commonMain: `ConfigKey<T>` typed keys (`Calendar.enabled: Boolean`, `Starred.enabled: Boolean`, `Reminders.maxFutureDays: Int`, `Photo.reencodeQuality: Float`, `Photo.maxDimension: Int`, …).
- [ ] commonMain: `BuildConfigProvider(buildKonfig: GeneratedBuildKonfig) : ConfigProvider` — reads compile-time flags via [BuildKonfig](https://github.com/yshrsmz/BuildKonfig) Gradle plugin.
- [ ] commonMain: bind domain ports `Clock` and `IdGenerator` here too — they are platform-shaped capabilities even if our `actual`s are trivial.
  - `Clock` → `kotlinx.datetime.Clock.System` (commonMain).
  - `IdGenerator` → `expect class UuidGenerator : IdGenerator`. androidMain uses `java.util.UUID.randomUUID()`. iosMain uses `NSUUID().UUIDString`.
- [ ] BuildKonfig configured in `:platform:platform-config/build.gradle.kts` to emit per-flavor (debug/release) and per-platform values. Default flag values from ADR-004:
  - `Calendar.enabled = false`
  - `Starred.enabled = false`
- [ ] Konsist: `ConfigProvider` is the only thing the rest of the codebase reads flags through — no direct `BuildConfig.*` references outside this module.

## 5. `platform-reminders`

This is the heaviest module. Both platforms have rough edges around permissions, recurrence, deep-link payloads, and reboot persistence.

### Common contract

- [ ] Implement `ReminderScheduler` (Phase 04 §5).
- [ ] `Reminder.toPlatformPayload()` extension producing a vendor-agnostic `ScheduledNotification` (title, body, deep-link URI, fire instant, optional recurrence rule). Title/body composed from list/item names — pulled by the use case before calling the scheduler (scheduler stays content-agnostic).
- [ ] Deep links: `fluxit://list/{listId}` for list reminders, `fluxit://item/{itemId}` for item reminders. Handled by app-level navigation glue.

### androidMain (WorkManager + NotificationCompat)

- [ ] `AndroidReminderScheduler(workManager, notificationManager, context)`:
  - **`Recurrence.None` (one-shot)** → `OneTimeWorkRequest` with `setInitialDelay(firesAt - now)`.
  - **`Recurrence.Daily`** → `PeriodicWorkRequest(repeatInterval = 24h, flexInterval = 1h)`. Initial delay aligns to first `firesAt` instant.
  - **`Recurrence.Weekly(days)`** → one `PeriodicWorkRequest(repeatInterval = 7.days)` **per selected day-of-week** (so `Weekly(Mon, Wed, Fri)` = 3 work requests). Each request's `PlatformHandle` is recorded as a comma-joined string so cancel removes all of them. Avoids the WorkManager limitation that periodic requests don't natively support a "fire only on these weekdays" trigger.
  - **`Recurrence.Monthly(dayOfMonth)`** → one-shot chain. `ReminderWorker` schedules the *next* one-shot via `RecurrenceCalculator.nextFireAfter(...)` after firing. Survives reboot via WorkManager persistence; if the worker fails to enqueue the follow-up, `RehydrateReminders` (use case from Phase 04) repairs on next app start.
  - Worker: `ReminderWorker(context, params)` → builds `NotificationCompat.Builder` with channel `fluxit_reminders` (importance `IMPORTANCE_DEFAULT`), tap intent → `MainActivity` with `data = deepLink`.
  - Persists across reboot via WorkManager's built-in persistence.
- [ ] **Notification channel** registered at app start (Android 8+ requirement). Channel ID: `fluxit_reminders`, name: "Reminders", importance: `DEFAULT`, sound: default.
- [ ] **POST_NOTIFICATIONS permission** (Android 13+):
  - Domain port doesn't request — that's a UI concern. Provide an `AndroidNotificationPermission` helper consumed by the Android app's permission flow (Phase 13 wires the UX).
  - `schedule()` checks current grant; if denied, returns `Outcome.Failure(SchedulerError.PermissionDenied)` without queuing the work.
- [ ] **Exact alarms**: not used in v1 (WorkManager is best-effort, which is fine for list reminders). Documented in module README.
- [ ] **Boot completed**: WorkManager handles persistence; **no** `BOOT_COMPLETED` receiver needed.
- [ ] Fakes: `FakeReminderScheduler` (in `platform-reminders/src/commonTest`) tracks scheduled handles in-memory.

### iosMain (UNUserNotificationCenter)

- [ ] `IosReminderScheduler(center: UNUserNotificationCenter)`:
  - **`Recurrence.None`** → `UNCalendarNotificationTrigger(dateMatching: components(.year, .month, .day, .hour, .minute), repeats: false)` from `firesAt`.
  - **`Recurrence.Daily`** → `UNCalendarNotificationTrigger(dateMatching: components(.hour, .minute), repeats: true)`.
  - **`Recurrence.Weekly(days)`** → one `UNNotificationRequest` **per selected day-of-week**, each with `UNCalendarNotificationTrigger(dateMatching: components(.weekday, .hour, .minute), repeats: true)`. Cancel removes all the request ids associated with the `PlatformHandle`.
  - **`Recurrence.Monthly(dayOfMonth)`** → `UNCalendarNotificationTrigger(dateMatching: components(.day, .hour, .minute), repeats: true)`. iOS auto-clamps invalid days (Feb 31 → Feb 28/29) consistent with `RecurrenceCalculator`'s contract, so behavior matches Android.
  - Content: `UNMutableNotificationContent` with `title`, `body`, `userInfo[deepLink] = "fluxit://…"`, `sound = .default`.
  - Request id: `PlatformHandle.raw` (UUID, optionally suffixed `:0`, `:1`, … for Weekly's per-day requests).
- [ ] **Permission**: `requestAuthorization(options: [.alert, .sound, .badge])`. `schedule()` calls `getNotificationSettings()` first; if denied, `SchedulerError.PermissionDenied`.
- [ ] **Background delivery**: iOS handles automatically once scheduled; no app-side rehydration needed for already-pending notifications.
- [ ] **Cold-start rehydration** (`RehydrateReminders` use case): cancel everything via `removeAllPendingNotificationRequests()` and re-add. Cheap and avoids drift.
- [ ] **Critical alerts / time-sensitive**: not requested in v1 (no special entitlement needed).
- [ ] **App delegate**: `UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:)` extracts `deepLink` and forwards to `RootStore.dispatch(.openDeepLink(...))`.

### Both

- [ ] Test app `RemindersDemo` debug screen: schedule "in 10 seconds" → assert local notification fires on both platforms.
- [ ] Open question: **silent failures on missed reminders** (device off / DND) — log, no recovery. Document.

## 6. `platform-photo`

### Common contract

- [ ] Implement `PhotoCapture` and `PhotoStorage` (Phase 04 §5).
- [ ] commonMain: `PhotoEncoder` interface — `suspend fun reencode(bytes: ByteArray, mime: String, maxDim: Int, jpegQuality: Float): ByteArray`. Keeps encoding logic out of `PhotoCapture` so we can re-encode at ingest time per Phase 03 open question.

### androidMain

- [ ] `AndroidPhotoStorage(context)`:
  - Root: `context.filesDir / "photos"` (auto-cleared on uninstall, not backed up by default — opt out of `android:fullBackupContent` for this dir).
  - `write(bytes, mime)` → writes `<uuid>.<ext>` atomically (`File.tmp` + `renameTo`).
  - `delete(relativePath)` → returns true if removed.
  - `resolveAbsolute(relativePath)` → returns a `content://` URI via FileProvider (so Compose's `AsyncImage` and external apps can read).
- [ ] `AndroidPhotoCapture(activityResultRegistry)`:
  - `capture()` → `ActivityResultContracts.TakePicture()` writing to a temp file; reads bytes back.
  - `pickFromLibrary()` → `ActivityResultContracts.PickVisualMedia(ImageOnly)`.
  - Lives in androidMain but the registry is provided by the Activity — see §7 for the wiring trick.
- [ ] `AndroidPhotoEncoder`: BitmapFactory + `Bitmap.compress(JPEG, quality)`, downsampled with `inSampleSize` to honor `maxDim`. Runs on `Dispatchers.IO`.
- [ ] **CAMERA permission** + `WRITE_EXTERNAL_STORAGE` not needed (capturing into internal storage; system camera UI handles its own permission).
- [ ] FileProvider declared in manifest with `<paths>` for `files-path/photos`.

### iosMain

- [ ] `IosPhotoStorage`:
  - Root: `FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!.appendingPathComponent("photos")`.
  - `write` / `delete` / `resolveAbsolute` analogues. iOS is auto-backed-up — we *do* want photos in iCloud backup so users don't lose them; document that decision.
- [ ] `IosPhotoCapture`:
  - `capture()` → presents `UIImagePickerController(sourceType: .camera)` wrapped in a Kotlin coroutine via continuation.
  - `pickFromLibrary()` → `PHPickerViewController` (iOS 14+).
  - **Permissions**: `NSCameraUsageDescription` and `NSPhotoLibraryUsageDescription` strings in `Info.plist`. PHPicker doesn't require library permission (returns ephemeral results); camera does.
- [ ] `IosPhotoEncoder`: `UIImage` resize → `jpegData(compressionQuality:)`.

### Both

- [ ] `PhotoDemo` debug screen: take a photo, persist, navigate away, come back, render it.
- [ ] Tests: `FakePhotoStorage` (in-memory map) and `FakePhotoCapture` (returns canned bytes) — used by `:shared:state` ItemDetailStore tests.

## 7. The "I need a UIKit/Activity reference" problem

`PhotoCapture` needs an Activity (Android) and a UIViewController (iOS) to present picker UIs. Domain ports can't carry these.

**Pattern adopted**: a per-platform `HostHolder` provided at app start.

- [ ] commonMain: no host concept; the *capture impl* is constructed with whatever host it needs.
- [ ] androidMain: `AndroidPhotoCapture` consumes an `ActivityResultRegistryProvider` interface; the Activity sets the current registry on resume and clears it on pause. Capture impl waits for a registry to be present (with a short timeout → `CaptureError.Unknown` if none).
- [ ] iosMain: `IosPhotoCapture` consumes a `TopViewControllerProvider` (`UIApplication.shared.windows.first?.rootViewController?.topMostPresentedController()`) — no app-side wiring needed.
- [ ] Document the pattern in `:platform:platform-photo/README.md` so future modules with similar needs (file picker, share sheet) follow it.

## 8. Koin wiring

- [ ] Each module exports one Koin `Module`. App-level Koin init in `android-app` / `ios-app` aggregates: `loggingModule + analyticsModule + configModule + remindersModule + photoModule + dataModule + stateModule + featureModules…`.
- [ ] **Ordering matters**: logging first (so other modules can log init), config next (clock + ids needed for IDs in stores), then capabilities, then features.
- [ ] Provide a single `fluxitPlatformModules(): List<Module>` aggregator in commonMain to make Koin start sites identical on both platforms.

## 9. Permissions UX scaffolding

This phase ships the *plumbing*; Phase 13 ships the polished UX. Scaffolding here:

- [ ] commonMain enum `Permission { Notifications, Camera, PhotoLibrary }`.
- [ ] commonMain interface `PermissionRequester { suspend fun ensure(p: Permission): PermissionResult }` with `Granted | Denied(canRequestAgain: Boolean) | PermanentlyDenied`.
- [ ] androidMain: ActivityResult-based requester for runtime perms (POST_NOTIFICATIONS, CAMERA — system camera handles its own, but if we ever switch to CameraX we'll need this).
- [ ] iosMain: bridges to `UNUserNotificationCenter.requestAuthorization`, `AVCaptureDevice.requestAccess`, `PHPhotoLibrary.requestAuthorization`.
- [ ] Permission state observation: `Flow<PermissionStatus>` (cold) per permission, recomputed on app foreground. Stores subscribe to surface "permission required" banners.

## 10. Backup / data residency

- [ ] **Android**: opt out of auto-backup for the SQLDelight DB (lists are device-local in v1; restoring on a new device with no sync would be confusing). `android:fullBackupContent="@xml/backup_rules"` excluding `databases/fluxit.db` and `files/photos/`. Document v2 reversal once sync ships.
- [ ] **iOS**: leave default (included in iCloud backup). User expectation is that an iCloud restore brings their lists back; this is the only "sync" v1 effectively offers.
- [ ] Document the asymmetry in `:platform:platform-config/README.md` so it's not a surprise.

## 11. ADRs to write in this phase

- [ ] **ADR-009** — Platform ports use Koin-injected interfaces (not bare `expect/actual` for capabilities). Why: easier to fake in tests, swap impls per build flavor, and keeps the `expect/actual` surface to truly OS-level primitives (`UuidGenerator`, file IO).
- [ ] **ADR-009a** — WorkManager (best-effort) over `AlarmManager` (exact) for v1 reminders. Why: exact alarms now need explicit user permission on Android 14+; v1's reliability target is "reminded around the right time," not "reminded to the second."
- [ ] **ADR-009b** — Android backups disabled for fluxit.db + photos in v1. Reasoning + revert plan once sync ships.
- [ ] **ADR-009c** — `LoggingAnalyticsSink` ships as v1 default (no Firebase Analytics). Defers privacy/consent UI to v2. (Pending §3 confirmation.)

## 12. Testing

- [ ] **Per-impl unit tests** where possible: encoders, payload mappers, mime sniffer.
- [ ] **Permission flow tests**: state machine for each `Permission` (granted → denied → permanently-denied) on both platforms with fakes.
- [ ] **Reminder scheduling**: scheduler returns `PlatformHandle`; cancel removes it. Use Robolectric for Android WorkManager tests; iOS uses `XCTestExpectation` against `UNUserNotificationCenter` mock.
- [ ] **Photo round-trip**: write → resolveAbsolute → read back → bytes match.
- [ ] **Demo screens** (debug-only) double as manual QA.

## 13. Open questions for this phase

- ✅ **Firebase Analytics in v1?** **Resolved (Phase 16 / ADR-012a):** no. `LoggingAnalyticsSink` only. `FirebaseAnalyticsSink` not shipped; not even in tree.
- [ ] **Crashlytics in v1?** Recommend yes — a shipped app without crash reporting is flying blind. Adds Firebase iOS/Android SDK + `GoogleService-Info.plist` / `google-services.json` config to repo.
- [ ] **Backup strategy** — confirm the Android off / iOS on asymmetry (§10).
- [ ] **System camera vs. CameraX on Android.** Default proposal: system camera intent (smaller binary, no permission needed beyond the system's own UX). Switch to CameraX in v2 if we want in-app capture preview.
- [ ] **Recurrence scope** still floating from Phase 03/04. Locks the reminder scheduler implementation surface.

## 14. Hand-off checklist (gate to Phase 07)

- [ ] All checkboxes above ✅.
- [ ] Reminder + photo demo screens exercised manually on a real Android device + a real iPhone.
- [ ] Firebase / Crashlytics configs (if adopted) added to repo with secrets in CI, not in source.
- [ ] `MASTER_PLAN.md`: Phase 06 → 🟢, ▶ Next Step → Phase 07.
- [ ] `00_DECISIONS.md`: ADR-009 (a/b/c) accepted.

---

## 15. Implementation Log

- **2026-05-31** — Slice 1: `platform-logging` — Kermit-backed `AppLogger` actual
  (§2). Shipped `KermitAppLogger` (commonMain) adapting the `:shared:domain`
  `AppLogger` port onto a Kermit `Logger` (four levels → Kermit severities, `tag`
  forwarded, warn/error `Throwable` carried); `loggingModule` binding a `single<Logger>`
  (`StaticConfig(minSeverity = Info, tag = "FluxIt")`) + `single<AppLogger>`; and the
  expect/actual `platformLogWriters()` seam (androidMain Logcat / iosMain os_log via
  Kermit's `platformLogWriter()`). Added the reusable `RecordingAppLogger` fake to
  `:shared:domain-testing` commonMain. Tests: `KermitAppLoggerTest` (commonTest) proves
  the severity/tag/throwable mapping via a recording Kermit `LogWriter`. Gate green:
  `:platform:platform-logging:check` (JVM + iOS Sim) + `:build-logic:test --rerun-tasks`.
  **Divergences:** (1) writer list is the expect/actual `platformLogWriters()` (compile-
  time platform concern) rather than a Koin-resolved `List<LogWriter>` — simpler, one
  fewer binding. (2) Crashlytics writer **not** shipped: it's an unresolved §13 open
  question that drags Firebase + vendor config into the build; v1 is Logcat/os_log only,
  with `platformLogWriters()` as the documented seam the Crashlytics writer slots into
  per platform later. (3) `RecordingAppLogger` lives in `:shared:domain-testing` (not
  this module's commonTest) so it's reusable across modules per §2. The interim
  `AppLogger.NoOp` binding in `:shared:state`'s `InterimPlatformModule` is replaced by
  `loggingModule` in Slice 6, not here (no dead wiring ahead of the swap). _Commit `<pending>`._
