# Phase 13 — Notifications & Reminders

> **Goal:** Ship the user-facing reminder editor (entry from Create List screen and List Detail ⋯ menu) and the end-to-end runtime glue: permission prompts, notification delivery, foreground policy, deep-link tap handling, and cold-start / boot rehydration. Phase 06 built the scheduling primitives; Phase 13 puts them in users' hands.

**Owner:** Mobile platform
**Depends on:** Phases 02, 04 (`ReminderSpec`, `RecurrenceCalculator`, `ScheduleReminder`, `RehydrateReminders`), 05 (extends stores; introduces `ReminderEditorStore`), 06 (`ReminderScheduler`, `PermissionRequester`), 07 (deep-link nav graph already in place), 09 (Reminder Settings entry point).
**Blocks:** Phase 17 (release hardening — needs final notification copy + permission UX for store screenshots).
**Exit criteria (Definition of Done):**
- Editor screen lets a user set/clear a reminder for any list or item with full v1 recurrence (None / Daily / Weekly / Monthly).
- A scheduled local notification fires on a real Android device AND a real iPhone at the expected wall-clock time, with correct copy and a deep link that lands on the right surface.
- Permission denial paths surface a recoverable banner, never a silent failure.
- Cold-start: rehydration completes within 200ms of `RootStore.AppStarted` on a 100-reminder dataset; no duplicate notifications scheduled.
- Boot-completed (Android) restores all active reminders without app launch (WorkManager handles this — verified).
- Foreground notification: when the app is open, the notification is presented as an in-app banner with `body.md` copy, not the OS notification (Android: in-app snackbar; iOS: `UNNotificationPresentationOptions.banner | .sound`).
- All design-system primitives — Konsist literal-ban green.
- Snapshot tests: editor in each recurrence mode (None/Daily/Weekly/Monthly), permission-denied, "in the past" inline error.

---

## 1. Reminder editor screen

### Anatomy

```
FluxItScaffold (modal-detail variant — no tab bar, no FAB)
├── Top bar (variant B)
│   ├── Leading: text button "Cancel" (primary.blue)
│   ├── Center: "Reminder" (title.md, white)
│   └── Trailing: text button "Done" (primary.blue, semibold; disabled while invalid)
├── Form (vertical stack, 16dp horizontal, scrollable)
│   ├── Toggle row "Reminder enabled" (FluxItToggle — see §2)
│   ├── (When enabled:) Date & time pickers
│   │   ├── DATE (FluxItPickerRow → expands to inline calendar / wheel)
│   │   └── TIME (FluxItPickerRow → expands to inline time wheel)
│   ├── (When enabled:) RECURRENCE section
│   │   ├── caption.xs uppercase muted "REPEATS"
│   │   ├── Segmented picker: [Never] [Daily] [Weekly] [Monthly]
│   │   ├── (If Weekly) Day-of-week multi-select chips (S M T W T F S)
│   │   └── (If Monthly) Day-of-month picker (1–31; locked-to-last-day note for short months)
│   ├── (When enabled:) Subtitle preview "Will fire {RecurrenceCalculator-derived next-fire summary}" (caption.xs muted)
│   ├── Inline validation banner (when firesAt ≤ now and Recurrence.None)
│   └── (When existing reminder:) FluxItDestructiveButton "Remove reminder"
```

### Routing

- [ ] Route `reminder-editor?ownerType={LIST|ITEM}&ownerId={id}&existingId={id?}` (Android Navigation Compose); SwiftUI `.sheet` with `ReminderEditorView(owner:, existing:)`.
- [ ] Reached from:
  - Create List (Phase 09) "Reminder Settings" row — owner = synthetic `(LIST, draftId = pending)`. The editor returns a `ReminderSpec` to the create flow; persistence happens when the list is created.
  - List Detail ⋯ menu → "Reminder settings" — owner = `(LIST, listId)`. Editor persists immediately on Done.
  - Item Detail (future v2 — not in v1; reminder per item is a v2 surface).
- [ ] Returns via the Phase 09 result-channel pattern (`SavedStateHandle`/SwiftUI binding closure).

### Validation

- [ ] `firesAt <= Clock.now()` AND `Recurrence == None` → inline error "Pick a future time." Done button disabled.
- [ ] `firesAt <= Clock.now()` AND `Recurrence != None` → allowed; first fire is computed as `RecurrenceCalculator.nextFireAfter(rule, now, tz)`. Subtitle preview reflects.
- [ ] `Recurrence.Weekly(emptySet)` → error "Pick at least one day." Done disabled.
- [ ] `Recurrence.Monthly(0..31)` validated against valid range; default to `firesAt.dayOfMonth`.

### Subtitle preview generation

- [ ] Reuses the format locked in Phase 09 §16 (reminder subtitle format). Recomputed live as the user adjusts pickers.
- [ ] Uses `RecurrenceCalculator.nextFireAfter` to phrase the leading "Next: {relative date} at {h:mm a}" line beneath the recurrence chips, so the user can verify clamping (e.g. "Monthly on the 31st" → "Next: Feb 28, 2026 at 9:00 AM").

## 2. New design-system primitive

- [ ] **`FluxItToggle`** — iOS-style switch component. Backfill into `02_DESIGN_SYSTEM.md` §5. Compose: built on `Switch` with custom `colors`. SwiftUI: `Toggle().toggleStyle(.switch)` with tinted track (`primary.blue`).
- [ ] **`FluxItPickerRow`** — tappable row with caption.xs label + body.md value + chevron-right. On tap, expands an inline picker beneath (Compose `AnimatedVisibility`; SwiftUI `.disclosureGroup`). Backfill into Phase 02 §5.
- [ ] **`FluxItSegmentedPicker`** — pill-rounded segmented control. Compose: custom; SwiftUI: `Picker().pickerStyle(.segmented)` re-themed. Backfill into Phase 02 §5.
- [ ] **`FluxItDayOfWeekChips`** — 7 toggleable circular chips for weekday selection. Backfill into Phase 02 §5.

## 3. `ReminderEditorStore`

State / Intent / Effect contract — adds a new store to Phase 05's catalog (backfill into `05_STATE_MANAGEMENT.md` §4).

- **State**
  - `enabled: Boolean`
  - `firesAt: Instant` (default: now + 1h, rounded up to next 5-min)
  - `recurrence: RecurrenceRule = None`
  - `weeklyDays: Set<DayOfWeek>` (only meaningful when `recurrence is Weekly`)
  - `monthlyDay: Int` (only meaningful when `recurrence is Monthly`)
  - `existing: ReminderSpec?` (null in create flow)
  - `validation: ReminderValidation = Valid | PastInstant | NoWeeklyDays | InvalidMonthlyDay`
  - `submitting: Boolean`
- **Intents**
  - `Init(owner, existing?)`, `EnabledToggled`, `DateChanged(LocalDate)`, `TimeChanged(LocalTime)`, `RecurrenceTypeSelected(RecurrenceRule.Tag)`, `WeeklyDayToggled(DayOfWeek)`, `MonthlyDayChanged(Int)`, `DoneClicked`, `CancelClicked`, `RemoveClicked`
- **Effects**
  - `ReturnResult(spec: ReminderSpec?)` — `null` means "remove"; non-null means "set/replace".
  - `Dismiss`
  - `RequestNotificationPermission`
  - `ShowError(message)`
- **Wiring**
  - `DoneClicked` validates → if existing flow (List Detail entry), calls `ScheduleReminder` use case; on `SchedulerError.PermissionDenied`, emits `RequestNotificationPermission`. On grant retry, redo. If create flow, just emits `ReturnResult` (no scheduling yet).
  - `RemoveClicked` → `CancelReminder` (existing flow) or `ReturnResult(null)` (create flow).
  - `EnabledToggled` to false: same as `Remove` for existing; clears form fields for new.

## 4. Permission UX (notifications)

This is where the half-built scaffolding from Phase 06 §9 gets a proper user-facing flow.

### When to ask

- [ ] **Android**: never prompt at app launch. First prompt is on first `DoneClicked` for a reminder. Subsequent failures show the in-screen banner.
- [ ] **iOS**: same — defer until first reminder save attempt. Apple guidance favors contextual prompts.
- [ ] Rationale: a list-making app is fully usable without notifications; pre-permission prompts get rejected.

### Pre-prompt rationale

- [ ] Before calling `PermissionRequester.ensure(Notifications)` for the first time, show an in-editor sheet:
  - Title: "Stay on top of your lists"
  - Body: "Allow notifications so we can remind you when it's time."
  - CTA: "Continue" → triggers OS permission dialog. "Not now" → cancels save, returns to editor with reminder still enabled in the form (user can dismiss via Cancel).
- [ ] Only shown once per install (tracked via `ConfigProvider`-backed boolean in app preferences). Subsequent denials skip straight to OS dialog.

### Recovery banner (in-editor)

- [ ] Soft denial: banner inside the editor "Notifications are off — your reminder won't fire. {Try Again}" → re-requests.
- [ ] Hard denial: banner "Notifications are off in Settings. {Open Settings}" → deep-links to app settings.
- [ ] Banner hides automatically when permission becomes granted (observe via `Flow<PermissionStatus>` from Phase 06 §9).
- [ ] User can still tap Done with permission denied: the row persists (`is_active = 0`, `platform_handle = null` per Phase 04 `ScheduleReminder`); the editor exits cleanly with a non-blocking toast on the previous screen "Reminder saved — enable notifications to receive it."

## 5. Notification content composition

- [ ] `Reminder.toNotification(repository, clock)` extension — composes title + body from owner type:
  - `LIST` reminder: title = list name (e.g. "Supermarket"), body = "{N} items to do" (or "Check your list" if N == 0).
  - `ITEM` reminder (v2): title = item title, body = list name.
- [ ] Lives in `:shared:state/reminders/` (not in domain — it does string composition that depends on locale).
- [ ] Titles are truncated visually by the OS; we don't pre-truncate.
- [ ] Deep links per Phase 06 §5: `fluxit://list/{id}` for list reminders.
- [ ] **Notification action buttons** in v1: none. (Considered: "Mark all complete" — adds Android channel actions + iOS category registration, which is a meaningful surface area for v1.)
- [ ] Sound: default OS sound. No custom sound asset in v1.
- [ ] Badge: not used in v1 (iOS badge counts require a usage decision we're not making yet — what does the badge count?).

## 6. Deep-link handling end-to-end

- [ ] **Notification tap (Android)** → WorkManager-launched `ReminderWorker` posts notification with `PendingIntent` opening `MainActivity` with `data = Uri.parse("fluxit://list/{id}")`. `MainActivity` reads intent in `onCreate` AND `onNewIntent` and dispatches `RootStore.OpenDeepLink(uri)`.
- [ ] **Notification tap (iOS)** → `UNUserNotificationCenterDelegate.userNotificationCenter(_:didReceive:withCompletionHandler:)` reads `userInfo["deepLink"]` and forwards to `RootStore`.
- [ ] **`RootStore.OpenDeepLink(uri)`** → parses `fluxit://list/{id}` → emits `Effect.NavigateToListDetail(id)` after popping any modal sheets (so the user lands on the list, not on the reminder editor over a list).
- [ ] **Edge case**: app is in the foreground showing the same list. Deep link is a no-op (already there); show a toast "Reminder for {list name}" so it's not silent.
- [ ] **Edge case**: deep link to a deleted list (soft-deleted between schedule and fire). `RootStore` checks via `ListsRepository.observe(id).first()` — if null, route to dashboard with a toast "That list was removed."

## 7. Foreground policy

- [ ] **Android**: when the app process is in the foreground, suppress the system notification for `fluxit_reminders` channel and instead show an in-app snackbar ("⏰ Supermarket — 5 items to do" + Open). Implemented by checking `ProcessLifecycleOwner` state inside `ReminderWorker` before posting; if foreground, broadcast an in-process event consumed by `RootStore`.
- [ ] **iOS**: implement `userNotificationCenter(_:willPresent:withCompletionHandler:)` → return `.banner | .sound` (iOS 14+ — system banner appears even with app open). Skip the in-app duplicate.
- [ ] Tap on the in-app snackbar (Android) follows the same deep-link path as a system notification tap.

## 8. Cold-start rehydration

- [ ] On `RootStore.AppStarted` (Phase 05 §4), launch `RehydrateReminders` use case (Phase 04 §7) on a background coroutine.
  - Android: cancel-then-recreate strategy is unnecessary (WorkManager persists). Implementation: read `RemindersRepository.selectActive()`, diff against `WorkManager.getWorkInfosByTag()`, only enqueue missing.
  - iOS: nuke-and-pave (`removeAllPendingNotificationRequests()` then re-add all active). Cheap (≤ N requests, N typically small) and avoids drift.
- [ ] Performance budget: 200ms wall-clock for 100 active reminders on a Pixel 6a. Measured by Phase 16 trace.

## 9. Boot-completed (Android)

- [ ] Verified at exit: `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED` followed by waiting and observing notification fires for a near-future reminder. WorkManager handles persistence; no `BOOT_COMPLETED` receiver in our manifest.
- [ ] Documented in `:platform:platform-reminders/README.md`.

## 10. Battery / "doze" mode considerations

- [ ] WorkManager is best-effort under doze; reminders may be delayed by tens of minutes when the device is idle. Documented in editor footer in v2 (skipped for v1 — too much copy on a small screen). For v1, accept the trade-off (per ADR-009a).
- [ ] No `setExactAndAllowWhileIdle` / `requestIgnoreBatteryOptimizations` in v1.

## 11. Notifications channel + categories registration

- [ ] **Android (one-time at app start)**: register `NotificationChannel("fluxit_reminders", "Reminders", IMPORTANCE_DEFAULT)` with description "Reminders for your lists." Lives in `FluxItApp.onCreate()` (idempotent).
- [ ] **iOS**: no category registration in v1 (no notification action buttons).

## 12. Localization & relative-time formatting

- [ ] Subtitle previews ("Tomorrow at 9:00 AM", "Daily at 9:00 AM", "Monthly on the 31st") use a small `RelativeTimeFormatter` in `:shared:state/util/`. v1 ships English only; copy lives in a single Kotlin file with a TODO marker for future extraction to platform string resources.
- [ ] Day-of-week and month names use `kotlinx.datetime.Month` / `DayOfWeek` enum + a static English mapping. v2 swaps to platform-localized formatting via expect/actual.

## 13. File layout

### Android

```
:features:feature-reminders/src/androidMain/kotlin/com/fluxit/reminders/
  ReminderEditorRoute.kt        ← Koin/ViewModel glue
  ReminderEditorScreen.kt       ← stateless
  ReminderEditorComponents.kt   ← date/time pickers, segmented, day chips, banners
  ReminderEditorPreviews.kt
```

### iOS

```
ios-app/Features/Reminders/
  ReminderEditorView.swift
  RecurrencePicker.swift
  WeeklyDayChips.swift
  PermissionBanner.swift        ← (or shared with Item Detail's banner from Phase 10)
  ReminderEditorPreviews.swift
```

### Cross

- `:shared:state/reminders/` — `ReminderEditorStore`, `Reminder.toNotification()`, `RelativeTimeFormatter`.

## 14. Testing

- [ ] **Snapshot tests**: editor disabled, enabled-with-Never, enabled-with-Daily, enabled-with-Weekly (some days selected), enabled-with-Monthly, past-instant validation error, permission-denied banner, removing-existing-reminder.
- [ ] **`ReminderEditorStore` tests**: every intent → state delta; validation transitions; permission-denied path emits the right effects; removing existing emits `ReturnResult(null)`.
- [ ] **`RecurrenceCalculator` tests** are owned by Phase 04 — verify they pass before this phase merges.
- [ ] **Integration test (per platform)**: schedule a reminder for "now + 5s" via the editor → assert notification posted via WorkManager test rule (Android) / `XCTNSPredicateExpectation` against `UNUserNotificationCenter` (iOS).
- [ ] **Deep-link test**: launch with `Intent(action = VIEW, data = Uri.parse("fluxit://list/{seed-id}"))` → assert dashboard NOT shown, list detail IS shown. iOS via `XCUIApplication.launch(arguments: ["-FluxItDeepLink", "fluxit://list/{id}"])` instrumented in app delegate for tests.
- [ ] **Foreground policy test**: open app → trigger fake fire → assert in-app snackbar (Android) / system banner (iOS), no duplicate.
- [ ] **Permission flow test**: deny once → editor banner appears → grant via mock `PermissionRequester` → banner dismisses → reminder schedules.
- [ ] **A11y audit**: editor pickers, segmented control, day chips all reachable via TalkBack/VoiceOver with correct labels.
- [ ] **Manual QA on real devices**: schedule for +1 minute → lock device → wait → notification fires → tap → app opens to right list. Repeat with each recurrence type.

## 15. Konsist rules (additions)

- [ ] `feature-reminders` cannot depend on other feature modules.
- [ ] Notification posting code only lives in `:platform:platform-reminders` (the worker / center delegate). Editor module never imports `NotificationCompat` / `UNUserNotificationCenter`.

## 16. Backfills generated for earlier phases

- **Phase 02 §5** (Design System primitives): add `FluxItToggle`, `FluxItPickerRow`, `FluxItSegmentedPicker`, `FluxItDayOfWeekChips`. (See §2 above.)
- **Phase 05 §4**: add `ReminderEditorStore` to the per-feature stores list (full contract in §3 above).
- **Phase 04**: confirm `ScheduleReminder` returns `Outcome` cleanly disambiguates "row saved + scheduling deferred due to permission" from a hard failure — already noted as `SchedulerFailure(PermissionDenied)`.

## 17. Open questions for this phase

- [ ] **Notification action buttons** (e.g. "Mark all complete" on a list reminder). Default proposal: **none in v1** — simpler permission/category surface; defer to v2.
- [ ] **Badge counts on iOS app icon.** Default proposal: **no badge in v1** — what would it count? (Active reminders? Overdue items?) Decide in v2 with a clearer use case.
- [ ] **Snooze action** in the notification. Default proposal: **no in v1** — pairs with action buttons; defer.
- [ ] **Editor entry from Item Detail**. Item-level reminders are scoped to v2. Confirm List Detail ⋯ menu is the only entry point besides Create List in v1.
- [ ] **Custom sound / vibration pattern**. Default proposal: **default OS sound only**. Custom assets pulled into v2.

## 18. Hand-off checklist (gate to Phase 14)

- [ ] All checkboxes above ✅.
- [ ] Real-device demo on Android + iOS: schedule across each recurrence type, lock device, observe fires, tap deep link, land correctly.
- [ ] Permission flows verified: granted, soft-denied + recovered, hard-denied + Open Settings.
- [ ] Foreground policy verified.
- [ ] Backfills (§16) applied to Phases 02 and 05 source files.
- [ ] Snapshot tests checked in; CI green.
- [ ] A11y audit clean.
- [ ] `MASTER_PLAN.md`: Phase 13 → 🟢, ▶ Next Step → Phase 14, M3 (Platform Plumbing) **complete**.
