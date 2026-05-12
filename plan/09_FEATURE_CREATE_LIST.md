# Phase 09 — Feature: Create List (and Edit)

> **Goal:** Ship the modal "New List" screen on Android (Compose) and iOS (SwiftUI), wired to `CreateListStore` (Phase 05). Same screen serves **edit** mode (re-uses the store with an optional `editingId`), invoked from Phase 08's ⋯ menu → "Edit list details".

**Owner:** Mobile platform (Android + iOS pair)
**Depends on:** Phases 02, 03, 04, 05 (`CreateListStore`), 06 (config provider), 07 (FAB + nav graph entry).
**Blocks:** Phase 13 (reminder editor entry point lives here, but the editor screen is in Phase 13).
**Exit criteria (Definition of Done):**
- Modal presents from FAB on dashboard (Phase 07) and from ⋯ "Edit list details" (Phase 08).
- Validation surfaces inline before submit; "Create List" / "Save" button enabled state reflects `validation = Valid`.
- Successful submit dismisses the modal and (create flow) auto-navigates into the new list's detail; (edit flow) just dismisses.
- Reminder Settings row routes to a stub Phase 13 screen behind a `ConfigProvider[Reminders.editorEnabled]` flag.
- All design-system primitives — Konsist literal-ban green.
- Snapshot tests checked in for: empty/initial, name-typed, all-fields-set, validation-error, submitting, edit-mode-prefilled.

---

## 1. Modal presentation

This is a **full-screen modal**, not a bottom sheet — the field/grid/swatch density needs the room and the mockup shows a full-screen layout with Cancel/title/Create button arrangement.

- [ ] **Android**: presented as a Navigation Compose route `create-list?editingId={id?}` with `enterTransition = slideInVertically(initialOffsetY = { it })`. Status bar adapts to modal background (same `#101822`).
- [ ] **iOS**: presented as `.fullScreenCover` from the dashboard `FAB` and from list detail's ⋯ menu. Uses `NavigationStack` inside the cover.
- [ ] **Dismiss gestures**: Android back button dispatches `CancelClicked` (with dirty-check); iOS swipe-down on the cover dispatches `CancelClicked`. Cancel with unsaved changes shows confirm-discard alert (see §6).

## 2. Screen anatomy

```
FluxItScaffold (modal variant — no tab bar, no FAB)
├── Top bar (modal variant)
│   ├── Leading: text button "Cancel" (primary.blue)
│   ├── Center: "New List" / "Edit List" (title.md, white)
│   └── Trailing: (empty in v1; reserved for future "Templates" in v2)
├── Form (vertical stack, 16dp horizontal padding, 24dp inter-section gap, scrollable)
│   ├── Section: LIST NAME
│   │   ├── caption.xs uppercase muted
│   │   ├── FluxItTextField (single-line, placeholder "e.g., Summer Trip", maxLength = 60)
│   │   └── inline error pill (when validation = Empty | TooLong)
│   ├── Section: CHOOSE ICON
│   │   ├── caption.xs uppercase muted
│   │   ├── 4-column grid of FluxItIconChip (one per FluxItIconRef from Phase 04 §2)
│   │   └── (8 icons → 2 rows; the 8th icon is "MORE" — see §5)
│   ├── Section: LIST COLOR
│   │   ├── caption.xs uppercase muted
│   │   └── horizontal row of FluxItColorSwatch (one per ColorToken; 6 swatches)
│   ├── Section: REMINDER SETTINGS
│   │   └── tappable row: bell icon + "Reminder Settings" + chevron-right
│   │       (subtitle below shows current reminder summary or "None")
└── Bottom dock (sticky, 16dp horizontal, 16dp bottom safe-area)
    └── FluxItPrimaryButton "Create List" / "Save" (full-width, disabled until valid)
```

## 3. Form bindings → store intents

| Field | Intent | State path |
|---|---|---|
| Name TextField onChange | `NameChanged(text)` | `state.name`, `state.validation` |
| Icon chip tap | `IconSelected(ref)` | `state.selectedIcon` |
| Color swatch tap | `ColorSelected(token)` | `state.selectedColor` |
| Reminder row tap | `ReminderSettingsClicked` | (effect: `NavigateToReminderSettings`) |
| Cancel | `CancelClicked` | (effect: `Dismiss` or `ConfirmDiscard`) |
| Submit | `CreateClicked` (alias `SaveClicked` in edit mode) | `state.submission` |

- [ ] Name field is auto-focused on screen entry (create mode); not auto-focused in edit mode (avoids surprise keyboard).
- [ ] Icon and color have **default selections** in create mode: `FluxItIconRef.CART`, `ColorToken.PRIMARY_BLUE` (matches mockup).
- [ ] In edit mode, selections are pre-filled from the list being edited.

## 4. Validation

- [ ] Name validation rules (from Phase 04 `TrimmedNonBlank` + cap): `Empty` (after trim), `TooLong(max = 60)`. Live as the user types but only **show** error after first blur OR first submit attempt (avoid noisy errors on first keystroke).
- [ ] Submit button disabled state reflects `validation = Valid`. Disabled visual = 40% opacity per Phase 02.
- [ ] On `Submit` with invalid state, dispatch a no-op intent that triggers `validationVisible = true` so errors render even if the user never blurred.
- [ ] Color and icon: always have a value (defaults). No validation needed.

## 5. The "MORE" icon (8th chip)

The mockup's 8th chip is a "···" (ellipsis) icon, suggesting "show more icons."

- [ ] **v1 behavior**: tapping "MORE" opens a small bottom sheet listing the icon set in `FluxItIconRef`. Since v1's `FluxItIconRef` enum has exactly 8 entries (the 8 chip slots), the "more" sheet would currently show the same 8 icons — pointless.
- [ ] **Decision**: in v1, **render the 8th slot as `FluxItIconRef.STAR` directly** (not "more"), and drop the "more" affordance entirely. Add a `// v2: bring back MORE chip when icon set grows` comment. Mockup divergence flagged in §13.
- [ ] **Alternative (if design pushes back)**: keep the MORE chip but disable it with a "Coming soon" tooltip. Recommend not doing this — disabled UI is uglier than removing the affordance.

## 6. Cancel / discard

- [ ] **`dirty` derivation**: `state.dirty = (name.isNotEmpty() || selectedIcon != defaultIcon || selectedColor != defaultColor || reminder != null)` in create mode; in edit mode, compares against the original snapshot.
- [ ] **Cancel** with `dirty = false` → emit `Effect.Dismiss` immediately.
- [ ] **Cancel** with `dirty = true` → emit `Effect.ConfirmDiscard`; UI surfaces alert ("Discard changes? — Discard / Keep editing"). On Discard → `Effect.Dismiss`. On Keep editing → no-op.
- [ ] **iOS swipe-down on full-screen cover** with `dirty = true` → `.interactiveDismissDisabled(true)` blocks the gesture and shows the alert.

## 7. Submit flow

- [ ] On `CreateClicked` (create mode):
  - Set `state.submission = Submitting`; disable button + show inline progress indicator inside the button label area (button text replaced with `CircularProgressIndicator` 16dp).
  - Call `CreateList` use case (Phase 04). On success → `state.submission = Success(newListId)` → emit `Effect.NavigateToListDetail(newListId)` (which both pops the modal and pushes the detail route — handled by the platform nav glue).
  - On failure → `state.submission = Error(message)` → keep modal open, show error banner above the submit button, re-enable button.
- [ ] On `SaveClicked` (edit mode):
  - Same lifecycle; on success → `Effect.Dismiss` (no auto-navigation, since the user came from the list detail and we land back there).
  - If only metadata changed (no rename), still emit success — the list detail will re-observe via `ObserveListDetail` and re-render.
- [ ] Re-entrancy guard: `dispatch(CreateClicked)` while `submission = Submitting` is a no-op.

## 8. Reminder Settings entry

- [ ] Row is tappable; visual reflects `state.reminder`:
  - `null` → subtitle "None" (`text.muted`).
  - `ReminderSpec(firesAt, recurrence)` → subtitle "{relative date} · {recurrence summary}" e.g. "Tomorrow at 9:00 AM · Weekly".
- [ ] Tap → `dispatch(ReminderSettingsClicked)` → emit `Effect.NavigateToReminderSettings(currentSpec)`.
- [ ] Phase 13's editor returns via:
  - **Android**: `SavedStateHandle["reminderResult"]` set by the editor screen and observed in this screen's `LaunchedEffect`.
  - **iOS**: closure passed at presentation time; SwiftUI environment binding.
- [ ] Editor result dispatched as `ReminderConfigured(spec)` (or `ReminderConfigured(null)` for "Remove reminder").
- [ ] **Permission interplay**: actual scheduling happens on `CreateClicked` after the row is persisted (see Phase 04 `ScheduleReminder`). If the OS denies notification permission at that moment, the list still saves; the user sees a non-blocking error banner explaining "List saved, but reminder couldn't be scheduled — enable notifications in Settings."
- [ ] **`ConfigProvider[Reminders.editorEnabled]`**: defaults to true in v1. If false (kill switch), the row renders disabled with subtitle "Coming soon" — gives us a way to ship Create List even if the reminder editor slips.

## 9. Edit mode specifics

- [ ] Route param `editingId` populated when arriving from Phase 08's ⋯ menu.
- [ ] Store factory: `koin.get<CreateListStore> { parametersOf(editingId) }`. With null → create mode; with id → edit mode (loads via `ListsRepository.observe(id).first()` once at init).
- [ ] Title bar shows "Edit List"; submit button shows "Save".
- [ ] Cancel with no changes immediately dismisses (no confirm).
- [ ] **Renaming** is allowed; **list deletion** is NOT exposed here (lives in Phase 08 ⋯ menu only — keeps destructive actions to one entry point).

## 10. File layout

### Android

```
:features:feature-create-list/src/androidMain/kotlin/com/fluxit/createlist/
  CreateListRoute.kt            ← Koin/ViewModel glue, observes store, handles editingId
  CreateListScreen.kt           ← stateless, takes (state, onIntent)
  CreateListComponents.kt       ← icon grid, color swatches, reminder row, error banner
  CreateListPreviews.kt
```

### iOS

```
ios-app/Features/CreateList/
  CreateListView.swift
  IconGrid.swift
  ColorSwatchRow.swift
  ReminderSettingsRow.swift
  CreateListPreviews.swift
```

## 11. Animations

- [ ] Modal enter/exit: 250ms slide; respects reduce-motion (instant).
- [ ] Icon chip selection: 100ms border + tint cross-fade.
- [ ] Color swatch selection: 120ms ring scale-in.
- [ ] Submit progress: button label cross-fades to spinner over 80ms.

## 12. Accessibility

- [ ] Name field: labelled "List name", error announced via live region on first show.
- [ ] Icon grid: each chip labelled with the icon's name ("Cart", "Home", …); selected state communicated via `Selected` trait, not just border color.
- [ ] Color swatches: each labelled with color name ("Blue", "Rose", …); selected state via trait.
- [ ] Reminder row: labelled "Reminder settings, currently None" / "currently {summary}".
- [ ] Submit button: announces disabled state.
- [ ] **Color isn't the only signal** — selected icon shows a border, selected color shows a ring outline (not just chroma).

## 13. Konsist rules (additions)

- [ ] `feature-create-list` cannot depend on other feature modules.
- [ ] `CreateListStore` is the only store referenced.
- [ ] No raw literals.

## 14. Mockup divergences (for design review)

- [ ] **8th icon chip = STAR, not MORE.** Justification in §5; ask design to confirm or supply additional icons that would make a "more" sheet meaningful in v1.
- [ ] **Swipe-to-dismiss with unsaved changes blocked** on iOS. The mockup doesn't address dismiss gestures — flag the chosen behavior.

## 15. Testing

- [ ] **Snapshot tests**: empty/initial, name-typed-no-error, name-empty-error, name-too-long-error, all-fields-set-with-reminder, submitting (spinner), submission-error, edit-mode-prefilled.
- [ ] **UI behavior**:
  - Type name → submit enables.
  - Tap icon → state updates; tap color → state updates.
  - Tap Cancel with dirty form → confirm dialog → stay or dismiss.
  - Submit success → modal dismisses + (create) navigates to detail.
  - Submit failure (forced via fake repo) → error banner shown, modal stays.
  - Reminder row: tap → navigates to editor; result returned → row updates subtitle.
  - Edit mode: arriving with `editingId` pre-fills all fields; saving with no changes is a no-op success.
- [ ] **Effect mapping**: exhaustive `when`/`switch` test.
- [ ] **A11y audit** TalkBack + VoiceOver.
- [ ] **Validation tests**: 0/1/60/61-character name boundary cases.

## 16. Resolved decisions for this phase (2026-05-11)

- ✅ **Recurrence scope (v1):** `None | Daily | Weekly(daysOfWeek) | Monthly(dayOfMonth)`. Closes the question that floated since Phase 03; unblocks Phase 13's editor and the `RecurrenceCalculator` test surface.
- ✅ **Edit mode is fully symmetric with create.** Name, icon, color, and reminder are all editable. Same screen, same store.
- ✅ **Default name on create: empty + auto-focused.** Submit button disabled until user types a non-blank name.
- ✅ **Keyboard Done: dismisses keyboard only.** No auto-submit. Multi-step form stays intentional.
- ✅ **Color picker layout: single horizontal row in v1.** Six swatches fit comfortably; if v2 grows the palette, switch to wrap.

### Reminder subtitle format (§8) — locked

- `null` → "None"
- `Recurrence.None` → "{relative date} at {h:mm a}" — e.g. "Tomorrow at 9:00 AM"
- `Recurrence.Daily` → "Daily at {h:mm a}"
- `Recurrence.Weekly(days)` → "Weekly · {short day list}, {h:mm a}" — e.g. "Weekly · Mon, Wed, Fri, 9:00 AM"
- `Recurrence.Monthly(day)` → "Monthly on the {ordinal} at {h:mm a}" — e.g. "Monthly on the 15th at 9:00 AM"

### Backfills generated for earlier phases

- **Phase 03 §3 / `RecurrenceRuleAdapter`** — confirm the sealed type matches the locked scope (no `Custom(rrule)` variant in v1).
- **Phase 04 `RecurrenceCalculator`** — implement and test all four variants (Property tests for Weekly's day-set semantics, Monthly's last-day-of-short-month edge case e.g. 31st in February).
- **Phase 06 `platform-reminders`** — Android: Daily/Weekly use `PeriodicWorkRequest`; Monthly uses one-shot-chain (re-schedule on fire). iOS: all four map to `UNCalendarNotificationTrigger(repeats: true)` with appropriate `DateComponents`.
- **Phase 13** — reminder editor UI now has a definitive widget set: date+time picker + recurrence type picker + (if Weekly) day-of-week multi-select + (if Monthly) day-of-month picker.

## 17. Hand-off checklist (gate to Phase 10)

- [ ] All checkboxes above ✅.
- [ ] Both apps demoed: FAB → fill form → create → land in detail; ⋯ Edit → change icon → save → return.
- [ ] Snapshot tests checked in; CI golden compare green.
- [ ] A11y audit clean.
- [ ] Mockup divergences (§14) signed off by design.
- [ ] `MASTER_PLAN.md`: Phase 09 → 🟢, ▶ Next Step → Phase 10.
