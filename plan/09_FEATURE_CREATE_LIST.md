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

## 0. Slice plan & cadence

Phase 09 ships one `feat` commit per slice (plan files synced in-commit, impl-log
entry `_Commit `<pending>`._`) + a `docs(plan):` SHA-backfill commit, per the Phase
05–08 cadence. Pre-commit gate: `:check` of each touched module + `:build-logic:test
--rerun-tasks`; iOS-facing slices also run `scripts/test-ios.sh`.

**Decisions taken at kickoff (2026-06-10):**
(a) **Edit mode lands this phase as a `CreateListStore` backfill** — the shipped
Phase-05 store is create-only, so Slice 1 adds the optional `editingId` (prefill via
`ListsRepository.observe(id).first()`, save via `RenameList` + `UpdateListAppearance`,
success → `Effect.Dismiss`), plus the §6 `dirty`/`ConfirmDiscard` flow and the §4
`validationVisible` gating, none of which shipped in Phase 05. Phase 08's ⋯ menu
"Edit list" row flips from disabled "(coming soon)" to live on both platforms.
(b) **Reminder editor stays in Phase 13**: a new `ConfigKey.RemindersEditorEnabled`
defaults **false** in v1 (ADR-004 staged-off pattern — diverges from §8's
"defaults to true"), so the Reminder Settings row renders disabled with subtitle
"Coming soon"; the §8 result-return plumbing is deferred with it.
(c) **Snapshot tests deferred to v2** (standing decision, see Phase 08 §0) — §15's
snapshot list is replaced by store tests + previews + Konsist.
(d) **`NAME_MAX_LEN` 100 → 60** in the store, matching this plan's locked cap (§2/§4)
and the §15 boundary cases.
(e) **8th icon chip = `STAR`, no MORE affordance** (§5 decision stands); the grid
renders `state.palette` from `PaletteCatalog` directly.

1. **`CreateListStore` backfill + tests** (`:shared:state`) — `editingId` Koin param
   (`parametersOf(scope, editingId)`), edit-mode prefill + original-snapshot dirty
   compare, edit-mode submit path (one `CreateClicked` intent serves both modes —
   no `SaveClicked` alias; §3 divergence), `CancelClicked` → `ConfirmDiscard` when dirty +
   `DiscardConfirmed` intent, `NameBlurred`/invalid-submit → `validationVisible`,
   name cap 60, `ConfigKey.RemindersEditorEnabled`; `CreateListStoreTest` grows to
   cover all of it (Kover ≥90% stays green).
2. **Android `:features:feature-create-list` module + nav** — Route/Screen/
   Components/Previews/ViewModel; `create-list?editingId={id}` route (slide-in
   modal) replaces the `Placeholder`; FAB + dashboard entries unchanged; Phase 08
   menu "Edit list" wired to navigate with `editingId`.
3. **iOS screen** — `CreateListView`/`IconGrid`/`ColorSwatchRow`/
   `ReminderSettingsRow`; `.createList` route renders it; `resolveCreateListStore()`
   facade + SKIE accessor backfills as needed; list-detail sheet gains "Edit list".
4. **Close-out** — `MASTER_PLAN.md` → Phase 09 🟢 / ▶ Next Step → Phase 10; §14
   divergences logged; hand-off (§17).

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

## 18. Implementation Log

> One entry per slice (see §0), appended in the slice's `feat` commit with a
> pending-commit marker and backfilled to the real SHA in the follow-up
> `docs(plan):` commit.

- **2026-06-10** — Slice 1: `CreateListStore` backfill + tests (§0 / §3 / §4 / §6 /
  §8 / §9). The Phase-05 store was create-only; it now also owns the edit flow: an
  optional `editingId` constructor param (Koin factory takes optional
  `CoroutineScope` + `ListId` params, either/both/neither) flips it into edit mode —
  prefill loads the first `ObserveListDetail` emission (a null detail → the list is
  gone → `Dismiss`), keeps the loaded `ListDetail` as the private `original`
  snapshot, and submit (still the single `CreateClicked` intent — **no `SaveClicked`
  alias**, §3 divergence) persists via `RenameList` (only when the trimmed name
  changed) + `UpdateListAppearance` (only when icon/color changed), a no-change save
  being an immediate success; success emits `Dismiss`, never `NavigateToListDetail`.
  The three edit use cases ride in an `EditListDeps` holder (detekt 8-param cap,
  cf. `ListDetailChrome`) and `RenameList`/`UpdateListAppearance` joined
  `domainModule`. §6 cancel/discard: `CancelClicked` emits `ConfirmDiscard` when
  dirty (create mode: any field off its default / pending reminder; edit mode:
  differs from `original`, not-yet-prefilled = pristine), else `Dismiss`; new
  `DiscardConfirmed` intent → `Dismiss`. §4 visibility: new
  `state.validationVisible`, set by the new `NameBlurred` intent or by a submit
  attempt with an invalid name (which no longer silently no-ops). `NAME_MAX_LEN`
  100 → 60 (§0 decision d). §8: new domain `ConfigKey.RemindersEditorEnabled`
  (default **false**, §0 decision b) surfaced as `state.reminderEditorEnabled` via
  an injected `ConfigProvider`. `CreateListStoreTest` grew from 9 to 19 cases
  (edit prefill/save/no-op-save/failure/missing-list, dirty-cancel both modes,
  validation visibility, flag default+override, 60/61 boundary). Gate green:
  `:shared:state:check`, `:shared:domain:check` (Kover gates included),
  `:build-logic:test --rerun-tasks`. _Commit `a2661d0`._

- **2026-06-10** — Slice 2: Android `:features:feature-create-list` module + nav
  (§0 / §1 / §2 / §3 / §4 / §5 / §6 / §7 / §8 / §10). New module via the
  `fluxit.kmp.feature` convention plugin (namespace
  `dev.franzueto.fluxit.feature.createlist`, Compose on, same dep set as
  `feature-list-detail`); registered in `settings.gradle.kts` + `:android-app`.
  Split: `CreateListRoute` (Koin/ViewModel glue — `viewModel { CreateListViewModel
  { scope -> koin.get { parametersOf(scope[, id]) } } }` — + exhaustive
  `CreateListEffect` `when` → dismiss / confirm-discard alert / error banner /
  pop+push on `NavigateToListDetail`; `BackHandler` dispatches `CancelClicked` so
  system back gets the §6 dirty check), stateless `CreateListScreen`
  (`FluxItTopBarCentered` with "Cancel" leading text button, scrollable form,
  sticky `FluxItPrimaryButton` dock — disabled unless `validation == Valid` and
  not `Submitting`, label flips Create List/Save/Creating…/Saving…), and
  `CreateListComponents` (4-column `FluxItIconChip` grid via `chunked`, single
  `FluxItColorSwatch` row, §8 reminder row, §4 inline error + §7 banner).
  `:android-app` `create-list?editingId={id}` route (optional nullable arg,
  vertical-slide enter/exit) replaces the `Placeholder`; FAB + dashboard navigate
  the bare base route; the Phase 08 list-detail sheet's **"Edit list details" row
  is now live** (pure nav hop — `ListDetailRoute` gained `onOpenEditList`, no
  store intent needed). **Divergences:** (a) the icon grid renders 7 chips —
  `FluxItIconRef.MORE` is filtered out (`pickableIcons`): it's the ⋯ chrome
  glyph, not a list identity, and §5 drops the "more" affordance (so §2's "8
  icons → 2 rows" is 4+3 until the set grows); (b) the reminder row renders
  disabled "Coming soon" (flag off, §0 decision b) and the
  `NavigateToReminderSettings` effect arm is a documented no-op — no Phase 13
  stub route exists to push; (c) submit shows a disabled "Creating…/Saving…"
  label instead of §7's in-button spinner — the DS `FluxItPrimaryButton` has no
  progress slot yet (DS polish item, joins the Phase 08 composer note); (d) the
  top bar renders "‹ Cancel" (the DS back-button chevron prefix) vs the mockup's
  bare "Cancel". `CreateListFormattersTest` covers the pure formatters
  (error/label/subtitle/pickable-icons). Gate green:
  `:features:feature-create-list:check`, `:features:feature-list-detail:check`,
  `:android-app:assembleDebug`, `:build-logic:test --rerun-tasks`.
  _Commit `f224af5`._
