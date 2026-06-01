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
6. **Swap interim → real** in `initKoin`: replace `InterimPlatformModule` bindings with the 5 platform modules, **delete `InterimPlatformModule.kt`**, update `appModules()`/`initKoinIos()` + android start site. ✅
7. **Composition roots** — `:android-app` (Koin start + Compose `NavHost` off `RootStore`); iOS `@main App` + `NavigationStack`. ✅
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

## 3. `platform-analytics` — ✅ Slice 3

- [x] Vendor-agnostic event flattening. _(Slice 3 — **modeled as `AnalyticsEvent.name: String` + `properties: Map<String, Any?>` directly on the sealed type** rather than a `toFlatPayload()` extension: each event owns its already-flat payload, so every sink consumes the same two members uniformly. Snake_case names, **no user-content** (only appearance tokens). Seed taxonomy is deliberately tiny — `AppStarted`, `ListCreated(color, icon)` (id omitted from the payload) — the full hierarchy is Phase 16 §4 work grown as features emit. Lives in `:shared:domain/port/AnalyticsSink.kt` with the other ports; the `AnalyticsSink` port was a Phase 04 §5 deferral, built here.)_
- [x] commonMain: `analyticsModule = module { single<AnalyticsSink> { LoggingAnalyticsSink(get()) } }`. v1 ships `LoggingAnalyticsSink` only (ADR-012a — events → `AppLogger` at debug; nothing leaves the device). No `AnalyticsSinkProvider` indirection. _(Slice 3 — `LoggingAnalyticsSink` routes through the `AppLogger` **port** (not Kermit directly) so the module stays off any logging backend and tests assert via `RecordingAppLogger`. Logs at debug under tag `Analytics`.)_
- [x] **androidMain / iosMain**: no Firebase Analytics SDK in v1; `FirebaseAnalyticsSink` not shipped. _(Slice 3 — confirmed deferred per the scope decision; `platform-analytics` has **no** per-platform code in v1 (`LoggingAnalyticsSink` is pure common). A Firebase sink is a v2 focused PR.)_
- [x] `RecordingAnalyticsSink` — only other v1 sink. _(Slice 3 — placed in `:shared:domain-testing` commonMain (not this module's commonTest) so store/integration tests in any module can reuse it. `LoggingAnalyticsSinkTest` proves routing via `RecordingAppLogger`.)_
- [ ] **Privacy**: `docs/ANALYTICS_EVENTS.md` (Phase 16 §4) listing every event + property + retention + PII classification. _(Deferred to Phase 16 with the full taxonomy — the v1 seed events carry no PII by construction.)_

## 4. `platform-config` — ✅ Slice 2

- [x] `ConfigKey<T>` typed keys — `CalendarEnabled`/`StarredEnabled` (Boolean), `ReminderMaxFutureDays` (Int), `PhotoReencodeQuality` (Float), `PhotoMaxDimension` (Int). _(Slice 2 — placed in **`:shared:domain/port/ConfigProvider.kt`**, not `platform-config` commonMain, so the keys + the `ConfigProvider` port sit with every other domain port (`AppLogger`, `Clock`, `ReminderScheduler`) and stay readable from use cases/stores. The `ConfigProvider` port itself didn't exist (Phase 04 §5 deferred it to its first consumer) — built here. `get` is generic so `ConfigProvider` is a plain `interface`, not a `fun interface` (Kotlin forbids a SAM with a type-parameterized abstract method).)_
- [x] commonMain: `DefaultConfigProvider : ConfigProvider` returning each key's `default`. _(Slice 2 — **diverged from `BuildConfigProvider(buildKonfig)`:** BuildKonfig is a Gradle plugin not in the catalog and v1 needs no per-flavor values (staged features off everywhere). The seam is the `configModule` binding — swapping in a `BuildKonfigConfigProvider` later leaves the `ConfigProvider`/`ConfigKey` surface + call sites untouched.)_
- [x] commonMain: `configModule` binds `Clock` → `Clock.System`, `IdGenerator` → `IdGenerator.System`, `ConfigProvider` → `DefaultConfigProvider`. _(Slice 2 — **diverged from `expect class UuidGenerator`:** the UUID-v4 actual already lives in `:core:core-utils` (`IdGenerator.System` over expect/actual `newId()`, ADR-006a); re-deriving it here would duplicate that. So `platform-config` has **no** androidMain/iosMain code — its only OS primitive (UUID) is provided transitively by core-utils, and `Clock` is kotlinx.datetime common. `configModule` replaces the interim `Clock.System` binding in `:shared:state`'s `InterimPlatformModule` (Slice 6) and adds the `IdGenerator`/`ConfigProvider` bindings.)_
- [ ] BuildKonfig per-flavor/per-platform values. _(Deferred — see above; ADR-004 defaults ship via `ConfigKey.default`: `CalendarEnabled = false`, `StarredEnabled = false`.)_
- [x] Konsist: `ConfigProvider` is the only flag-reading seam — no `BuildConfig.*` references outside this module. _(Slice 2 — holds trivially: no `BuildConfig` references exist yet; revisit when BuildKonfig lands.)_
- [x] **Test fake**: `FakeConfigProvider` (map override + default fallback) in `:shared:domain-testing` commonMain; `DefaultConfigProviderTest` asserts the ADR-004 defaults. _(Slice 2.)_

## 5. `platform-reminders` — ✅ Slice 4 (Android impl + iOS impl; manual device QA pending)

This is the heaviest module. Both platforms have rough edges around permissions, recurrence, deep-link payloads, and reboot persistence.

> **✅ Slice 4 status.** `ReminderScheduler` shipped on both platforms: `AndroidReminderScheduler`
> (WorkManager + NotificationCompat) and `IosReminderScheduler` (UNUserNotificationCenter), wired by
> the `expect`/`actual` `remindersModule()`. Recurrence→trigger mapping done for all four `RecurrenceRule`
> cases. The `:build-logic` Konsist `PlatformLayerArchTest` (plan §0) now guards `androidx.work.*` /
> `platform.UserNotifications` / CameraX / Photos / Firebase / kermit-crashlytics outside `:platform:*`.
> Android behaviour covered by Robolectric (WorkManager test harness: one-shot enqueue, weekly per-day,
> permission-denied, cancel); iOS impl is built by the iOS-Sim gate and verified by manual device QA
> (Phase 06 scope decision). The interim `NoOpReminderScheduler` is swapped out in Slice 6.
> **Open boxes below** (permission UX wiring, demo screen, on-device fire) are Phase 13 / manual-QA items.

### Common contract

- [x] Implement `ReminderScheduler` (Phase 04 §5). _(Slice 4 — Android + iOS actuals.)_
- [x] `Reminder.toScheduledNotification()` producing a vendor-agnostic `ScheduledNotification` (title, body, deep-link URI, fire instant, recurrence). _(Slice 4 — named `toScheduledNotification` not `toPlatformPayload`. **Content gap:** the `ReminderScheduler` port passes only a `Reminder`, which carries no list/item name — so v1 uses a generic title + owner-shaped body; name enrichment needs the port to carry text and is a documented follow-up.)_
- [x] Deep links: `fluxit://list/{listId}` / `fluxit://item/{itemId}`. _(Slice 4 — `ReminderOwner.deepLink()`; app-level nav glue lands with the composition-root UI, Slice 7.)_

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

## 6. `platform-photo` — ✅ Slice 5 (Android + iOS impls; manual device QA pending)

> **✅ Slice 5 status.** `PhotoStorage` + `PhotoEncoder` shipped on both platforms and
> wired by the expect/actual `photoModule()`; `PhotoCapture` shipped on both (manual-QA
> only per the Phase 06 scope decision — no instrumented test drives the system camera /
> picker). Konsist: nothing new — the default system-camera / `UIImagePickerController`
> path uses neither CameraX (`androidx.camera.*`) nor `Photos.framework`, both already
> banned outside `:platform:*` by `PlatformLayerArchTest`. Interim NoOp photo bindings
> swapped in Slice 6. Open boxes (`PhotoDemo` screen, permission UX) are Phase 13 /
> manual-QA items.

### Common contract

- [x] Implement `PhotoCapture` and `PhotoStorage` (Phase 04 §5). _(Slice 5 — Android + iOS actuals.)_
- [x] commonMain: `PhotoEncoder` interface — `suspend fun reencode(bytes, mime, maxDim, jpegQuality): ByteArray`. Keeps encoding logic out of `PhotoCapture` so we can re-encode at ingest time per Phase 03 open question. _(Slice 5 — plus a `mimeToExtension()` helper used by both storages; everything normalises to JPEG so unknown types fall back to `jpg`.)_

### androidMain

- [x] `AndroidPhotoStorage(context)`: root `filesDir/photos`; atomic write (`.tmp` + `renameTo`, copy-fallback); `delete` returns whether a file went; `resolveAbsolute` → **absolute file path** (v1). _(Slice 5 — **diverged from `content://` via FileProvider for storage:** a content URI is only needed to hand a photo to an *external* app (share/edit), which v1 doesn't do — Compose's loaders read the in-app file path directly. Storage FileProvider is a documented follow-up. Not-backed-up is enforced by the app `backup_rules` in Slice 7, not this module.)_
- [x] `AndroidPhotoCapture(context, registryProvider)`: `capture()` → `TakePicture` into a cache temp file (FileProvider URI) read back as bytes; `pickFromLibrary()` → `PickVisualMedia(ImageOnly)` content URI read via `ContentResolver`. Registry comes from the §7 host-holder. _(Slice 5 — manual-QA only.)_
- [x] `AndroidPhotoEncoder`: BitmapFactory (`inSampleSize` decode + exact scale) + `Bitmap.compress(JPEG, quality)`, on `Dispatchers.IO`. _(Slice 5.)_
- [x] **CAMERA permission** + `WRITE_EXTERNAL_STORAGE` not needed (internal storage; system camera owns its permission). _(Slice 5.)_
- [x] FileProvider declared in manifest — for the **camera temp file** (`${applicationId}.fileprovider`, `cache-path`), a narrower concern than the deferred storage FileProvider. _(Slice 5.)_

### iosMain

- [x] `IosPhotoStorage`: root `applicationSupport/photos`; `write`/`read`/`delete`/`resolveAbsolute` over `NSFileManager`; left in iCloud backup (§10). _(Slice 5 — `NSData`↔`ByteArray` bridge helpers added.)_
- [x] `IosPhotoCapture`: `capture()`/`pickFromLibrary()` present `UIImagePickerController` (camera / photoLibrary) via a continuation + `NSObject` delegate; host from `TopViewControllerProvider`. _(Slice 5 — **diverged from `PHPickerViewController` for the library pick:** v1 uses one `UIImagePickerController` delegate for both sources (less plumbing); PHPicker is a documented follow-up. `NSCameraUsageDescription`/`NSPhotoLibraryUsageDescription` go in the app `Info.plist` (Slice 7). Manual-QA only.)_
- [x] `IosPhotoEncoder`: `UIImage` resize (bitmap context) → `UIImageJPEGRepresentation`. _(Slice 5.)_

### Both

- [ ] `PhotoDemo` debug screen: take a photo, persist, navigate away, come back, render it. _(Phase 13 / manual-QA.)_
- [x] Tests: `FakePhotoStorage` / `FakePhotoCapture` already in `:shared:domain-testing` (Phase 04). Slice 5 adds `MimeExtensionsTest` (common) + Robolectric `AndroidPhotoStorageTest` (write→read→delete round-trip, `resolveAbsolute`, mime→ext); capture is manual-QA.

## 7. The "I need a UIKit/Activity reference" problem — ✅ Slice 5

`PhotoCapture` needs an Activity (Android) and a UIViewController (iOS) to present picker UIs. Domain ports can't carry these.

**Pattern adopted**: a per-platform host-holder provided at app start.

- [x] commonMain: no host concept; the *capture impl* is constructed with whatever host it needs. _(Slice 5.)_
- [x] androidMain: `AndroidPhotoCapture` consumes an `ActivityResultRegistryProvider` (a `MutableStateFlow<ActivityResultRegistry?>` holder); the Activity `attach`es on resume / `detach`es on pause (Slice 7). Capture waits for a non-null registry (5s timeout → `CaptureError.Unknown`). _(Slice 5.)_
- [x] iosMain: `IosPhotoCapture` consumes a `TopViewControllerProvider`; the default walks the key window's presented-controller chain — no app-side wiring needed. _(Slice 5.)_
- [x] Document the pattern in `:platform:platform-photo/README.md` so future host-needing modules (file picker, share sheet) follow it. _(Slice 5.)_

## 8. Koin wiring

## 8. Koin wiring — ✅ Slice 6

- [x] Each module exports one Koin `Module`. _(Slice 6 — `:shared:state`'s `appModules()` now aggregates `fluxitPlatformModules() + domainModule + dataModule + stateModule`; the interim `platformModule` is gone.)_
- [x] **Ordering matters**: logging first, config next, then capabilities, then features. _(Slice 6 — `fluxitPlatformModules()` = `loggingModule, configModule, analyticsModule, remindersModule(), photoModule()` in that order; domain/data/state follow.)_
- [x] Provide a single `fluxitPlatformModules(): List<Module>` aggregator to make Koin start sites identical on both platforms. _(Slice 6 — in `:shared:state` `di/InitKoin.kt`; `expect`/`actual` `remindersModule()`/`photoModule()` resolve per-platform. Lives in `:shared:state` (not a UI module) so `initKoin`/`initKoinIos` already pick it up — the iOS smoke now resolves the real iOS platform ports + reaches `RootStore` Ready. **`InterimPlatformModule.kt` deleted**; the `factory<CoroutineScope>` moved into `stateModule`. JVM `KoinGraphTest` runs over the real common platform modules + `:shared:domain-testing` fakes for the two OS-context-bound capability ports (those actuals are covered by their own modules' builds + on-device QA). `appModules(platformModules = …)` gained an injectable param for that substitution.)_

## 9. Permissions UX scaffolding

This phase ships the *plumbing*; Phase 13 ships the polished UX. Scaffolding here:

- [ ] commonMain enum `Permission { Notifications, Camera, PhotoLibrary }`.
- [ ] commonMain interface `PermissionRequester { suspend fun ensure(p: Permission): PermissionResult }` with `Granted | Denied(canRequestAgain: Boolean) | PermanentlyDenied`.
- [ ] androidMain: ActivityResult-based requester for runtime perms (POST_NOTIFICATIONS, CAMERA — system camera handles its own, but if we ever switch to CameraX we'll need this).
- [ ] iosMain: bridges to `UNUserNotificationCenter.requestAuthorization`, `AVCaptureDevice.requestAccess`, `PHPhotoLibrary.requestAuthorization`.
- [ ] Permission state observation: `Flow<PermissionStatus>` (cold) per permission, recomputed on app foreground. Stores subscribe to surface "permission required" banners.

## 10. Backup / data residency — ✅ Slice 8

- [x] **Android**: auto-backup enabled but the SQLDelight DB + photo store excluded (lists are device-local in v1; restoring on a new device with no sync would be confusing). `android:fullBackupContent="@xml/backup_rules"` (API ≤30) + `android:dataExtractionRules="@xml/data_extraction_rules"` (API 31+) excluding `fluxit.db` (+ `-wal`/`-shm`, WAL is on) and `files/photos/`. _(Slice 8 — **the exclude `path` for the DB is `fluxit.db`, not `databases/fluxit.db`** as the original sketch had it: in the `<full-backup-content>` / `<data-extraction-rules>` schema, `domain="database"` already roots at the `databases/` dir, so the path is the bare filename. v2 reversal documented in both rules files + `platform-config/README.md` + ADR-017.)_
- [x] **iOS**: leave default (included in iCloud backup). User expectation is that an iCloud restore brings their lists back; this is the only "sync" v1 effectively offers. _(Slice 8 — no app-side change; documented.)_
- [x] Document the asymmetry in `:platform:platform-config/README.md` so it's not a surprise. _(Slice 8 — created the README.)_

## 11. ADRs to write in this phase — ✅ Slice 8

> Numbering reconciled to the canonical `00_DECISIONS.md` ledger at close-out (the
> reserved slots differed from this section's original draft). Mapping: the drafted
> **ADR-009** → **ADR-008** (the reserved Phase-06 slot; ADR-009 stays reserved for
> the Phase-13 notification-permission-UX decision); **ADR-009a** → **ADR-016**;
> **ADR-009b** → **ADR-017**; **ADR-009c** → **ADR-012a** (already in `plan/16`,
> formal acceptance in Phase 16, not duplicated here).

- [x] **ADR-008** — Platform capabilities are Koin-injected interfaces (not bare `expect/actual`). Keeps the `expect/actual` surface to truly OS-level primitives (`newId()`, `DriverFactory`, the module factories). _(Slice 8 — flipped Accepted at close-out.)_
- [x] **ADR-016** — WorkManager (best-effort) over `AlarmManager` (exact) for v1 reminders. Exact alarms need explicit user permission on Android 12+/14+; v1's target is "reminded around the right time." _(Slice 8 — Accepted.)_
- [x] **ADR-017** — Android backups disabled for `fluxit.db` + photos in v1; reasoning + v2 revert plan. _(Slice 8 — Accepted.)_
- [x] **ADR-012a** — `LoggingAnalyticsSink` as v1 default (no Firebase Analytics); defers consent UI to v2. _(Owned by `plan/16`; formal acceptance in Phase 16. Not re-numbered here.)_

## 12. Testing

- [ ] **Per-impl unit tests** where possible: encoders, payload mappers, mime sniffer.
- [ ] **Permission flow tests**: state machine for each `Permission` (granted → denied → permanently-denied) on both platforms with fakes.
- [ ] **Reminder scheduling**: scheduler returns `PlatformHandle`; cancel removes it. Use Robolectric for Android WorkManager tests; iOS uses `XCTestExpectation` against `UNUserNotificationCenter` mock.
- [ ] **Photo round-trip**: write → resolveAbsolute → read back → bytes match.
- [ ] **Demo screens** (debug-only) double as manual QA.

## 13. Open questions for this phase

- ✅ **Firebase Analytics in v1?** **Resolved (Phase 16 / ADR-012a):** no. `LoggingAnalyticsSink` only. `FirebaseAnalyticsSink` not shipped; not even in tree.
- [ ] **Crashlytics in v1?** Recommend yes — a shipped app without crash reporting is flying blind. Adds Firebase iOS/Android SDK + `GoogleService-Info.plist` / `google-services.json` config to repo.
- ✅ **Backup strategy** — **Resolved (Slice 8 / ADR-017):** Android auto-backup enabled but `fluxit.db` + `files/photos/` excluded; iOS left on default iCloud backup. Asymmetry documented in `platform-config/README.md`.
- [ ] **System camera vs. CameraX on Android.** Default proposal: system camera intent (smaller binary, no permission needed beyond the system's own UX). Switch to CameraX in v2 if we want in-app capture preview.
- [ ] **Recurrence scope** still floating from Phase 03/04. Locks the reminder scheduler implementation surface.

## 14. Hand-off checklist (gate to Phase 07)

- [x] All shipping checkboxes above ✅ (the remaining open boxes are explicitly Phase 13 / Phase 16 / manual-QA — `PhotoDemo`/`RemindersDemo` debug screens, permission UX scaffolding §9, the `docs/ANALYTICS_EVENTS.md` taxonomy, Crashlytics §13).
- [ ] Reminder + photo demo screens exercised manually on a real Android device + a real iPhone. _(Owner: user — per the Phase 06 scope decision, device/sim QA is run by the user. The Lists Dashboard composition root reaches Ready on both the Android build + the iOS Sim smoke; capture/reminders device QA is the user's pass.)_
- [x] Firebase / Crashlytics configs (if adopted) added to repo with secrets in CI, not in source. _(N/A — Crashlytics/Firebase deferred, §13; nothing added.)_
- [x] `MASTER_PLAN.md`: Phase 06 → 🟢, ▶ Next Step → Phase 07. _(Slice 8.)_
- [x] `00_DECISIONS.md`: capability ADRs accepted — **ADR-008** (Koin-injected ports), **ADR-016** (WorkManager), **ADR-017** (backups); LoggingAnalyticsSink is **ADR-012a** (Phase 16). _(Slice 8 — numbering reconciled to the ledger, see §11.)_

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
  `loggingModule` in Slice 6, not here (no dead wiring ahead of the swap). _Commit `d5b1cc2`._

- **2026-05-31** — Slice 2: `platform-config` — `ConfigProvider` + real `Clock`/
  `IdGenerator` bindings (§4). Built the `ConfigProvider` port + typed `ConfigKey<T>`
  hierarchy (ADR-004 defaults) in `:shared:domain/port` — the port was a Phase 04 §5
  deferral, landed here with its first home. Shipped `DefaultConfigProvider` (static
  defaults) + `configModule` binding `ConfigProvider`/`Clock.System`/`IdGenerator.System`
  in `:platform:platform-config`; added the reusable `FakeConfigProvider` to
  `:shared:domain-testing`. Tests: `DefaultConfigProviderTest`. Gate green:
  `:platform:platform-config:check` + `:shared:domain:check` (≥95% Kover, JVM + iOS
  Sim) + `:build-logic:test`. **Divergences:** (1) `ConfigKey`/`ConfigProvider` live in
  `:shared:domain` (port pattern) not `platform-config` commonMain. (2) `ConfigProvider`
  is a plain `interface` — a generic abstract method can't sit on a `fun interface`.
  (3) **No BuildKonfig** (not in catalog; no per-flavor values needed in v1) — static
  defaults via `ConfigKey.default`, BuildKonfig is a later binding swap. (4) **No
  androidMain/iosMain** — the only OS primitive (UUID) already lives in `:core:core-utils`
  (ADR-006a) so `configModule` binds `IdGenerator.System` rather than a redundant
  `expect class UuidGenerator`; `Clock` is kotlinx.datetime common. `configModule`
  replaces the interim `Clock.System` binding in Slice 6. _Commit `9752383`._

- **2026-05-31** — Slice 3: `platform-analytics` — `AnalyticsSink` port + v1
  `LoggingAnalyticsSink` (§3, ADR-012a). Built the `AnalyticsSink` port + a tiny
  `AnalyticsEvent` seed taxonomy (`AppStarted`, `ListCreated(color, icon)`; name +
  already-flat content-free `properties`) in `:shared:domain/port` — the port was a
  Phase 04 §5 deferral; the full taxonomy + `docs/ANALYTICS_EVENTS.md` stays Phase 16
  §4 (confirmed scope decision). Shipped `LoggingAnalyticsSink` (routes events to the
  `AppLogger` port at debug, tag `Analytics`) + `analyticsModule` in
  `:platform:platform-analytics`; added the reusable `RecordingAnalyticsSink` to
  `:shared:domain-testing`. Tests: `LoggingAnalyticsSinkTest` (via `RecordingAppLogger`).
  Gate green: `:platform:platform-analytics:check` + `:shared:domain:check` (≥95% Kover,
  JVM + iOS Sim) + `:build-logic:test`. **Divergences:** (1) event payload modeled as
  `name`/`properties` members rather than a `toFlatPayload()` extension. (2) No Firebase
  / per-platform code (deferred per scope decision) — pure-common module. (3)
  `RecordingAnalyticsSink` in `:shared:domain-testing` for cross-module reuse. _Commit `857d9a2`._

- **2026-05-31** — Slice 4: `platform-reminders` — `ReminderScheduler` on both
  platforms (§5; ADR-009a — WorkManager best-effort). commonMain: `ScheduledNotification`
  + `Reminder.toScheduledNotification()` + `ReminderOwner.deepLink()` + the expect
  `remindersModule()`. androidMain: `AndroidReminderScheduler` (None→OneTime, Daily→24h
  periodic, Weekly→one 7-day periodic per day, Monthly→one-shot re-armed by the worker),
  `ReminderWorker` (NotificationCompat + deep-link tap intent + Monthly re-arm),
  `AndroidNotifications` (channel `fluxit_reminders` + POST_NOTIFICATIONS check), the
  POST_NOTIFICATIONS manifest entry, and the android `remindersModule()`. iosMain:
  `IosReminderScheduler` (UNCalendarNotificationTrigger per recurrence; weekday mapping)
  + iOS `remindersModule()`. Added catalog deps (androidx.work 2.9.1, androidx.core,
  robolectric 4.14, androidx.test.core, junit) and the `:build-logic` Konsist
  `PlatformLayerArchTest` (plan §0). Tests: `ScheduledNotificationTest` (mapper, common)
  + Robolectric `AndroidReminderSchedulerTest` (4: one-shot/weekly/permission-denied/cancel,
  via WorkManager test harness). Gate green: `:platform:platform-reminders:check` (JVM +
  Robolectric + iOS-Sim compile) + `:build-logic:test` (Konsist incl. new arch test).
  **Divergences/notes:** (1) mapper named `toScheduledNotification`. (2) Content gap — port
  carries no name; generic title/owner-shaped body in v1. (3) iOS behaviour by manual device
  QA (scope decision); iOS-Sim only compiles it. (4) Crashlytics/exact-alarm not used (§13 /
  ADR-009a). (5) interim `NoOpReminderScheduler` swapped in Slice 6. _Commit `ea09e49`._

- **2026-05-31** — Slice 5: `platform-photo` — `PhotoStorage`/`PhotoCapture`/`PhotoEncoder`
  on both platforms + the §7 host-holder (§6/§7). commonMain: `PhotoEncoder` interface +
  `mimeToExtension()` helper + the expect `photoModule()`. androidMain: `AndroidPhotoStorage`
  (filesDir/photos, atomic tmp+rename, absolute-path `resolveAbsolute`), `AndroidPhotoEncoder`
  (BitmapFactory inSampleSize + scale + JPEG compress on Dispatchers.IO), `AndroidPhotoCapture`
  (ActivityResult `TakePicture`/`PickVisualMedia` over the `ActivityResultRegistryProvider`
  host-holder; camera temp file via FileProvider + manifest provider + `fluxit_photo_paths.xml`)
  and the android `photoModule()`. iosMain: `IosPhotoStorage` (applicationSupport/photos,
  NSFileManager, iCloud-backed §10) + `NSData`↔`ByteArray` bridges, `IosPhotoEncoder`
  (`UIImage` resize → `UIImageJPEGRepresentation`), `IosPhotoCapture` (`UIImagePickerController`
  + `NSObject` delegate via continuation, `TopViewControllerProvider` default), iOS `photoModule()`.
  Catalog: added `androidx-activity` (plain `activity`, for `ActivityResultRegistry`/contracts).
  Tests: `MimeExtensionsTest` (common) + Robolectric `AndroidPhotoStorageTest` (5: round-trip,
  resolveAbsolute, delete semantics, missing-path read, mime→ext); capture is manual-QA only.
  Gate green: `:platform:platform-photo:check` (JVM + Robolectric + iOS-Sim compile) +
  `:build-logic:test`. **Divergences:** (1) storage `resolveAbsolute` returns an absolute file
  path, not a `content://` FileProvider URI — that's only needed for *external* sharing (v1
  has none); documented follow-up. The capture FileProvider is a separate, narrower concern
  (camera temp file). (2) iOS library pick uses `UIImagePickerController` not `PHPickerViewController`
  (one delegate, less plumbing; PHPicker is a follow-up). (3) Capture (both platforms) is
  manual-QA only per the Phase 06 scope decision — built (Android) / built-not-run (iOS-Sim),
  no instrumented test. (4) No CameraX / `Photos.framework` → nothing to add to the Konsist
  ban. (5) interim NoOp photo bindings swapped in Slice 6. _Commit `0b89714`._

- **2026-05-31** — Slice 6: swap interim → real platform ports in the composition
  root (§8). Added the `fluxitPlatformModules()` aggregator to `:shared:state`
  `di/InitKoin.kt` (`loggingModule, configModule, analyticsModule, remindersModule(),
  photoModule()` in §8 order) and rewired `appModules()` to
  `fluxitPlatformModules() + domainModule + dataModule + stateModule`. **Deleted
  `InterimPlatformModule.kt`** (NoOp scheduler/capture/storage + interim
  Clock/AppLogger); the `factory<CoroutineScope>` moved into `stateModule`. Added
  the five `:platform:*` modules as `:shared:state` commonMain deps (di/ is exempt
  from `StateLayerArchTest`'s `:platform:*` import ban). `appModules()` gained an
  injectable `platformModules` param so the JVM `KoinGraphTest` runs over the real
  common platform modules + `:shared:domain-testing` fakes for the OS-context-bound
  reminders/photo ports (whose android actuals need `androidContext()` + WorkManager).
  `initKoinIos()` needs no change — it calls `initKoin` which now defaults to the
  real platform graph; the iOS smoke (`scripts/test-ios.sh`) resolves the **real**
  iOS `remindersModule()`/`photoModule()` actuals and `RootStore` still reaches Ready.
  Gate green: `:shared:state:check` (JVM + Kover ≥90% + iOS-Sim) + `:build-logic:test`
  + `scripts/test-ios.sh`. **Notes:** AccountStore `version`/`flags` stay interim
  literals (routing them through `ConfigProvider` is a later slice, not the port swap).
  _Commit `dbea031`._

- **2026-05-31** — Slice 7: composition-root UI on both platforms (§0 item 7, §7).
  **Common (`:shared:state` di/):** `initKoin` gained an `appDeclaration: KoinAppDeclaration`
  param (runs inside `startKoin { }` before modules) so a platform can install Koin
  extensions other modules depend on; added `resolveListsDashboardStore()` (Swift-callable
  factory resolver, mirroring `resolveRootStore()`). New `androidMain` `initKoinAndroid(context)`
  (mirrors `initKoinIos`) supplies the native `SqlDriver` via `DriverFactory(context).create()`
  **and** installs `androidContext(context)` via `appDeclaration` — now **required** because the
  real `remindersModule()`/`photoModule()` android actuals resolve `androidContext()` (interim
  Slice-6 modules didn't). Added `koin-android` to `:shared:state` `androidMain`.
  **`:android-app`:** `FluxItApp.onCreate()` → `initKoinAndroid(this)`; `MainActivity` `inject()`s
  the `ActivityResultRegistryProvider` host-holder (§7) and `attach`/`detach`es
  `activityResultRegistry` on resume/pause via a `DefaultLifecycleObserver` (without this, photo
  capture times out), then hosts `FluxItRoot` — a Compose composition root that resolves
  `RootStore` via `koinInject`, dispatches `AppStarted`, and gates a `NavHost` (start destination
  Lists) on `InitState` (splash / retry / Ready). `ListsDashboardScreen` is wired to
  `ListsDashboardStore` (state renders; search dispatches into the real use-case feed). Added
  `:platform:platform-photo` to `:android-app` deps for the holder type. **iOS `ios-app`:**
  `FluxItApp.init` now starts Koin once via `doInitKoinIos()` (the composition root owns it for
  the process); `ContentView` resolves `RootStore`, dispatches `AppStarted`, observes `init`, and
  shows a `NavigationStack` → `ListsDashboardView` (wired to `ListsDashboardStore` via
  `resolveListsDashboardStore()`) on Ready. `NSCameraUsageDescription` +
  `NSPhotoLibraryUsageDescription` added to `Info.plist`. **Test fix:** the §12 runtime smoke
  (`testRootStoreReachesReadyAtRuntime`) no longer calls `doInitKoinIos()`/`stopKoinApp()` itself
  — the app host now starts Koin at launch, so a second start threw
  `KoinApplicationAlreadyStartedException`; it resolves the already-started `RootStore` instead.
  **Divergences:** (a) the Android Lists rows / iOS list use plain Compose-Material3 / SwiftUI
  rather than the design-system list components — the polished DS rows + delete/undo + navigation
  effects land with the Lists feature phase / Slice 8 e2e (Slice 7's job is the reachable
  composition root); (b) the iOS `ForEach` ids rows by index, not `ListSummary.id` — the `ListId`
  value class projects as a boxed `Any` across SKIE so a `\.id.value` keypath won't type-check.
  Gate green: `:android-app:check` + `:shared:state:check` + `:build-logic:test --rerun-tasks` +
  `scripts/test-ios.sh`. _Commit `0c3fb49`._

- **2026-06-01** — Slice 8: Phase 06 close-out (§10/§11/§14). **Android backups (§10,
  ADR-017):** `:android-app` keeps `allowBackup="true"` but ships `res/xml/backup_rules.xml`
  (API ≤30) + `res/xml/data_extraction_rules.xml` (API 31+) excluding `fluxit.db` (+ `-wal`/`-shm`,
  WAL is on) and `files/photos/` from cloud-backup + device-transfer — enforces the not-backed-up
  assumption `platform-photo` storage makes; v2 reversal documented in both files. (The DB exclude
  `path` is the bare `fluxit.db`: `domain="database"` already roots at `databases/`.) Created
  `platform-config/README.md` documenting the Android-off / iOS-on asymmetry. **ADRs (§11):**
  numbering reconciled to the canonical `00_DECISIONS.md` ledger (the §11 draft's ADR-009/009a/009b
  collided with reserved slots). Flipped **ADR-008** → Accepted (platform capabilities are
  Koin-injected interfaces — the reserved Phase-06 slot, leaving ADR-009 for Phase 13's
  notification-permission UX); wrote **ADR-016** (WorkManager over AlarmManager) + **ADR-017**
  (Android backups disabled), both Accepted; the LoggingAnalyticsSink decision stays **ADR-012a**
  (owned by `plan/16`, formal acceptance in Phase 16 — not duplicated). **Phase close-out (§14):**
  `MASTER_PLAN.md` → Phase 06 🟢 100%, ▶ Next Step → Phase 07, ADR count 19 Accepted. On-device/sim
  capture+reminder QA is the user's manual pass (Phase 06 scope decision); the Lists Dashboard rows
  are intentionally minimal (polished DS rows + delete/undo + navigation are Phase 07). Gate green:
  `:android-app:check` + `:build-logic:test --rerun-tasks` (no Kotlin changed in this slice — XML +
  docs only; ran the Android + arch gates to confirm the manifest/resources build). _Commit `<pending>`._
