# Phase 16 — Observability

> **Goal:** Make the running app legible. Wire up structured logging, crash reporting, an analytics event catalog (even if the v1 sink is local-only), and lightweight performance traces so we can answer "what happened?" and "is it healthy?" without redeploying.

**Owner:** Mobile platform
**Depends on:** Phase 06 (`AppLogger`, `AnalyticsSink`, `platform-logging`, `platform-analytics`), Phase 04 (event types), Phase 05 (stores emit traces).
**Blocks:** Phase 17 (release readiness needs crash reporting verified end-to-end).
**Exit criteria (Definition of Done):**
- Crashlytics receives a synthetic crash from Android + iOS, with deobfuscation working.
- `docs/ANALYTICS_EVENTS.md` lists every event with properties, retention, and PII classification.
- Log levels are consistent across modules (§3). Konsist rule passes.
- "Diagnostics" debug screen surfaces last 100 log lines, last 20 analytics events, current feature-flag values, and a "Send report" button (debug only).
- Cold-start trace + first-meaningful-frame trace recorded for each release build (used by Phase 17 perf gates).

---

## 1. Crashlytics — end-to-end wiring

### Android

- [ ] Add `google-services` plugin + `com.google.firebase.crashlytics` plugin to `:android-app`.
- [ ] `google-services.json` lives in repo for the **dev** Firebase project; **prod** json injected from secret (`FIREBASE_GOOGLE_SERVICES_JSON` per Phase 15 §8) at CI build time.
- [ ] `FluxItApp.onCreate()`:
  ```kotlin
  FirebaseApp.initializeApp(this)
  FirebaseCrashlytics.getInstance().apply {
      setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)  // off in debug builds
      setCustomKey("app_version", BuildConfig.VERSION_NAME)
      setCustomKey("build_type", BuildConfig.BUILD_TYPE)
  }
  ```
- [ ] `kermit-crashlytics` writer (Phase 06 §2) bridges:
  - `Severity.Warn` → `FirebaseCrashlytics.log()` (breadcrumb)
  - `Severity.Error` + `Throwable` → `recordException(throwable)` (non-fatal)
  - `Severity.Assert` → `recordException` + `setCustomKey("assert", message)`
- [ ] Mapping upload via `gradle-firebase-crashlytics-plugin` — Phase 15 §5 already wires this for release tags.
- [ ] **NDK crashes** not enabled (we have no native code in v1). If we add Coil's native AVIF decoder etc. in v2, revisit.

### iOS

- [ ] Add Firebase SDK via SwiftPM (`https://github.com/firebase/firebase-ios-sdk`). Modules: `FirebaseCrashlytics`, `FirebaseAnalytics` (Phase 16 §4 may keep this stubbed).
- [ ] `GoogleService-Info.plist` injected from secret at CI build time.
- [ ] `FluxItApp.init` → `FirebaseApp.configure()`.
- [ ] dSYM upload: Fastlane lane `upload_symbols` runs as part of `release` (Phase 15 §5).
- [ ] Crashlytics collection disabled in debug builds via Info.plist `FirebaseCrashlyticsCollectionEnabled = false` + override in code for staging.

### Verification

- [ ] **Synthetic crash test** in debug builds only: `SettingsScreen` (debug section) → "Force crash" button → `throw RuntimeException("test crash")`. After relaunch, Crashlytics dashboard shows the crash within ~5 min.
- [ ] Documented in `docs/RELEASE_PROCESS.md` as a release-day check.

## 2. PII / privacy policy for crash reports

- [ ] **Never call `setUserId`** with anything user-typed (list names, item titles). Use a stable but anonymous install id only.
- [ ] **Never log item titles, list names, or descriptions** at any severity. Konsist rule: any `AppLogger.x()` call whose first arg is a known PII variable name (`title`, `name`, `description`) is forbidden — engineers can log `itemId` instead.
- [ ] **Photo bytes / paths** never logged.
- [ ] Stack traces are fine (they're code, not user content).
- [ ] Document this contract in `docs/PRIVACY_LOGS.md` and link from `CONTRIBUTING.md`.

## 3. Logging conventions

### Levels

- [ ] **`Verbose`** — never used in shipped code (debug-only spelunking). Konsist rule blocks it in non-debug source sets.
- [ ] **`Debug`** — store intent → state diffs (debug builds only; stripped in release via Kermit's `minSeverity`).
- [ ] **`Info`** — lifecycle events: app start, list created, reminder scheduled, photo ingested. ~10–30 lines per session.
- [ ] **`Warn`** — recoverable errors: permission denied (after retry), photo re-encode failed (falling back to original), reminder skipped due to past instant.
- [ ] **`Error`** — unexpected failures with stack traces: DB exceptions, reminder scheduling errors that aren't permission. Recorded as Crashlytics non-fatals.
- [ ] **`Assert`** — invariant violations (`should never happen` paths). Force a Crashlytics non-fatal even in release.

### Tags

- [ ] One Kermit tag per module: `Lists`, `Items`, `Reminders`, `Photos`, `Storage`, `Scheduler`, `Capture`, `Store:<Name>`. Set via `Logger(tag = "Lists")` at module init.
- [ ] No string-interpolated tags. Konsist rule: `Logger(tag = "…")` literal-string only.

### Structured key-values

- [ ] Kermit doesn't natively support structured logs, but Crashlytics' `setCustomKey` does. Pattern: at the start of a store's `reduce(intent)`, set `Crashlytics.setCustomKey("last_intent", intent::class.simpleName)`. On crash, dashboard shows the last user action.
- [ ] Similar for: `last_route` (set in nav effect handler), `last_list_id` (only in debug — PII concern in release).

## 4. Analytics event catalog (`docs/ANALYTICS_EVENTS.md`)

Single source of truth. Updated in lockstep with `AnalyticsEvent` sealed hierarchy (Phase 04 §5 / Phase 06 §3).

Format per event:

```
### list_created
Trigger: CreateList use case succeeds.
Fired by: CreateList (domain).
Properties:
  - color: ColorToken (e.g. PRIMARY_BLUE) — non-PII, enum
  - icon: FluxItIconRef (e.g. CART) — non-PII, enum
  - has_reminder: Boolean
PII: none.
Retention: 90 days (Firebase default).
```

Initial event list (v1):

- [ ] `app_started` — properties: `cold_start_ms`, `is_first_launch`.
- [ ] `list_created` — color, icon, has_reminder.
- [ ] `list_renamed` — has_reminder.
- [ ] `list_deleted` — was_starred, item_count, completed_count.
- [ ] `list_restored` — (after undo) item_count.
- [ ] `item_added` — list_color, list_icon, position (active count after add).
- [ ] `item_completed` — list_color, completion_fraction_after.
- [ ] `item_uncompleted` — list_color.
- [ ] `item_deleted` — was_completed.
- [ ] `clear_completed` — count.
- [ ] `reminder_scheduled` — recurrence (`NONE` | `DAILY` | `WEEKLY` | `MONTHLY`), days_ahead (rounded to bucket).
- [ ] `reminder_fired` — recurrence, was_foreground.
- [ ] `reminder_cancelled` — recurrence.
- [ ] `notification_tapped` — owner_type (LIST), recurrence.
- [ ] `permission_requested` — type (NOTIFICATIONS | CAMERA | PHOTO_LIBRARY).
- [ ] `permission_granted` — type.
- [ ] `permission_denied` — type, was_permanent.
- [ ] `photo_attached` — source (CAMERA | LIBRARY).
- [ ] `photo_removed`.
- [ ] `search_performed` — result_count, query_length (NOT the query itself — PII).
- [ ] `tab_selected` — tab.
- [ ] `app_backgrounded` — session_duration_ms.
- [ ] `analytics_consent_changed` — enabled (Boolean). Fired when the user toggles the Settings → Privacy → "Anonymous crash reports" switch. Lets us see consent rate over time. PII: none.

- [ ] Sink in v1 is `LoggingAnalyticsSink` (per ADR-009c — Firebase Analytics deferred). Events go to `AppLogger` at `Debug`, viewable via the Diagnostics screen (§7).
- [ ] **Never log user-typed content** (list names, item titles, descriptions, search queries). Properties are enums, counts, durations, booleans only. Tracked in `docs/PRIVACY_EVENTS.md`.

## 5. Performance traces

We don't ship Firebase Performance in v1 (avoids vendor + privacy buy-in for one feature). Instead, lightweight in-process traces.

- [ ] **`TraceSpan` API** in `:core:core-utils`:
  ```kotlin
  inline fun <T> trace(name: String, block: () -> T): T { … }
  suspend fun <T> traceSuspend(name: String, block: suspend () -> T): T { … }
  ```
  Records start/end timestamps to an in-memory ring buffer (last 256 spans, ~10KB).
- [ ] Instrumented sites (cheap, ≤ 20 sites total):
  - `app_cold_start` (process start → dashboard first frame).
  - `dashboard_first_render` (route arrival → first frame).
  - `list_detail_first_render`.
  - `db_query:selectAllActive`, `db_query:observeByList`, etc. (every long-lived query).
  - `photo_ingest` (capture → ingest → row written).
  - `rehydrate_reminders`.
- [ ] On `app_backgrounded`, emit an aggregated trace summary to `AppLogger.Info` (median + p95 per span). Visible in Diagnostics screen.
- [ ] **No automatic Firebase upload.** If a v2 decision to ship Firebase Perf comes, the existing span data plugs in via a new sink without touching call sites.

## 6. Custom keys / breadcrumbs for crashes

Set automatically (not per-feature opt-in):

- [ ] `last_intent` — set in `BaseStore.reduce` (Phase 05).
- [ ] `last_route` — set in `EffectHandler` (Phase 07).
- [ ] `current_screen` — set in screen `LaunchedEffect` / `.onAppear`.
- [ ] `permission_status_notifications`, `_camera`, `_photo_library` — updated on permission state change.
- [ ] `seeded_data` — true if debug seed was used (so we can ignore those reports if needed).
- [ ] `db_version` — from SQLDelight.
- [ ] Build vars (already wired): `app_version`, `build_type`, `flavor`.

## 7. Diagnostics screen (debug builds only)

Reached from Settings → "Diagnostics" (only present in debug + internal builds).

- [ ] Tabs:
  - **Logs**: last 100 Kermit lines (in-memory `LogWriter` accumulating into a ring buffer), filterable by severity + tag. Tap a line to copy.
  - **Events**: last 20 analytics events with timestamp + properties (uses `RecordingAnalyticsSink` from Phase 14 fakes, gated by `BuildConfig.DEBUG`).
  - **Flags**: every `ConfigKey` from Phase 06 §4 with current value. Read-only in v1; flippable in v2 dev menu.
  - **Traces**: ring buffer from §5, p50/p95 per span name.
  - **Storage**: row counts per table, total photo bytes on disk.
  - **Send report**: bundles the above + last screenshot into a zip; opens a share sheet so we can grab it from a tester's device. Never sent automatically.
- [ ] **Production builds**: the entire screen is excluded via source-set selection (`debug` only), Konsist verifies.

## 8. Crash-free rate target

- [ ] **Crash-free sessions ≥ 99.5%** as the published target (matches Firebase's "healthy" band). Tracked in Phase 17's release gate.
- [ ] **ANR-free sessions ≥ 99.8%** on Android. ANRs commonly come from main-thread DB / file IO — our architecture already pushes those to `Dispatchers.IO` via SQLDelight's driver, so ANRs from our code should be rare.
- [ ] Alert thresholds (Crashlytics velocity alerts): any crash affecting > 1% of sessions within 24h → page release manager.

## 9. Log/event volume budget

- [ ] **Per cold session**: ≤ 50 Info+ lines. Excess implies unnecessary logging or a regression (loop).
- [ ] **Per analytics event**: ≤ 20 per session typical, ≤ 200 abusive (Firebase free tier limit considerations even though we're not using Firebase Analytics in v1; future-proof).
- [ ] **Per crash report**: custom keys < 64 (Crashlytics limit). We use ~15.

## 10. Network considerations (forward-looking)

- [ ] **No network calls from observability in v1**. Logs are in-memory ring buffer; analytics sink is `LoggingAnalyticsSink`. Crashlytics is the only network sender, and only for crash/non-fatal upload.
- [ ] **No third-party SDK auto-collect**. Disable Firebase ad-id collection, GMS Tasks collection, etc. Document in `docs/PRIVACY_LOGS.md`:
  ```xml
  <meta-data android:name="firebase_analytics_collection_deactivated" android:value="true" />
  <meta-data android:name="google_analytics_adid_collection_enabled" android:value="false" />
  ```
- [ ] On iOS: `FirebaseAnalyticsCollectionDeactivated = true` in Info.plist.

## 11. Konsist rules (additions)

- [ ] `Verbose` log level not used in non-debug source.
- [ ] `Logger(tag = …)` argument is a string literal (no interpolation).
- [ ] No `println` / `print` / `NSLog` / `os_log` direct calls — must go through `AppLogger`.
- [ ] Forbidden log/event arguments (PII-named variables): variables matching `title|name|description|query|email|phone` at the call site of `AppLogger`/`AnalyticsSink` methods. Best-effort heuristic; intentional violations require a `// @SuppressLint("LogPII")` comment.

## 12. Testing

- [ ] **`RecordingAppLogger`** + **`RecordingAnalyticsSink`** (Phase 14 fakes) used in store tests to assert events fire on expected paths.
- [ ] **PII guard tests**: a Konsist test scans all `AppLogger` call sites in `:shared:state` and `:features:*` and fails if a PII-named arg appears without the suppress comment.
- [ ] **Synthetic crash**: a debug-only XCTest / instrumented test triggers the "Force crash" button, confirms the process exits with the expected exception. Doesn't actually verify Crashlytics receipt (that's manual + cloud-dependent).
- [ ] **Trace coverage**: assert every instrumented site (§5) has a non-zero span recorded after a smoke flow.

## 13. ADRs to write in this phase

- [ ] **ADR-012** — In-process trace ring buffer over Firebase Performance for v1. Why: avoid vendor + privacy buy-in; data captured in spans is fully under our control.
- [ ] **ADR-012a** — `LoggingAnalyticsSink` as v1 default (formalizes ADR-009c which was "pending"). Defers Firebase Analytics + consent UI to v2.
- [ ] **ADR-012b** — PII-blocking Konsist rule against named variables in log/event call sites. Heuristic, not perfect; intentional violations explicit via comment.

## 14. Resolved decisions for this phase (2026-05-11)

- ✅ **Firebase Analytics: NOT in v1.** `LoggingAnalyticsSink` only. Events flow to `AppLogger` and the Diagnostics screen; nothing leaves the device. Formally locks ADR-012a; supersedes the still-pending ADR-009c from Phase 06.
- ✅ **Crashlytics consent toggle in Settings.** Settings → Privacy → "Anonymous crash reports — Help us improve FluxIt by sending anonymous crash data." Defaults **ON**. Wired to `FirebaseCrashlytics.setCrashlyticsCollectionEnabled(value)`. State persisted in app preferences (`platform-config`). On change, takes effect on next launch (Firebase requirement); UI displays an inline note about this.
- ✅ **Crash-spike alerts: Slack `#fluxit-builds` with `[CRASH SPIKE]` prefix.** Crashlytics velocity alert (>1% sessions affected in 24h) → Crashlytics → Google Cloud Monitoring → Slack webhook. Prefix lets us filter alerts from build noise. Single channel keeps signal-to-noise high while team is small (< 4 engineers); revisit dedicated channel at growth.
- ✅ **"Send report" transport: share sheet only.** Bundles last 100 logs + last 20 events + a screenshot into a zip; opens platform share sheet (`Intent.ACTION_SEND` Android, `UIActivityViewController` iOS). Tester chooses Slack/email/etc. No backend dependency. HTTP transport reconsidered in v2 alongside backend rollout.

### Implications

- **Settings screen (Phase 07 §12 resolution)** — add a "Privacy" subsection: anonymous-crash-reports toggle + brief copy + link to privacy-policy URL placeholder. Backfill into Phase 07's stub Settings screen spec.
- **Crashlytics enabled-state observability** — emit `analytics_consent_changed` event (boolean property) so we can see consent rate over time.
- **No SDK weight from Firebase Analytics in v1.** `platform-analytics` ships only `LoggingAnalyticsSink` + `RecordingAnalyticsSink` (debug); the `FirebaseAnalyticsSink` class from Phase 06 §3 is **deleted** until v2 (don't ship dead code).

## 15. Hand-off checklist (gate to Phase 17)

- [ ] All checkboxes above ✅.
- [ ] Synthetic crash verified on both platforms; Crashlytics dashboard shows it with deobfuscation.
- [ ] `docs/ANALYTICS_EVENTS.md` published; matches `AnalyticsEvent` sealed hierarchy.
- [ ] `docs/PRIVACY_LOGS.md` published.
- [ ] Diagnostics screen reachable in internal builds.
- [ ] Konsist rules from §11 green.
- [ ] Crash-free-rate target documented in `docs/RELEASE_PROCESS.md`.
- [ ] `MASTER_PLAN.md`: Phase 16 → 🟢, ▶ Next Step → Phase 17.
- [ ] `00_DECISIONS.md`: ADR-012 (a/b) accepted; ADR-009c formally superseded by ADR-012a.
