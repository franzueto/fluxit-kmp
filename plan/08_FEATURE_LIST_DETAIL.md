# Phase 08 — Feature: List Detail

> **Goal:** Ship the list-items screen on Android (Compose) and iOS (SwiftUI), wired to `ListDetailStore` (Phase 05). The screen is the app's most-used surface — every interaction must feel instantaneous (optimistic state) and never lose typed input.

**Owner:** Mobile platform (Android + iOS pair)
**Depends on:** Phases 02, 03, 04, 05 (`ListDetailStore`), 06, 07 (app shell + nav graph).
**Blocks:** Phase 10 (this screen is the entry point to Edit Item).
**Exit criteria (Definition of Done):**
- Cold-navigate from dashboard to a 50-item list renders first frame in < 250ms after route push.
- Toggling an item's completion animates between TO BUY ↔ COMPLETED sections without dropping a frame; underlying repository call is fire-and-forget from the UI's POV (optimistic).
- Inline composer survives process death: typed-but-unsubmitted text is restored.
- All design-system primitives — Konsist literal-ban green.
- ~~Snapshot tests checked in for: empty list, partially complete, fully complete, completed-section hidden, composer with text, swipe-pending-delete, error state.~~ **Revised (Slice 1): deferred to v2** — snapshot infra is out of v1 scope by the standing decision (see memory `snapshot-testing-deferred-v2` / Phase 07 §10 note). v1 relies on `@Preview`/SwiftUI previews for visual review plus the `:shared:state` `ListDetailStoreTest` suite, the exhaustive `when`/`switch` effect→nav mapping, pure-logic unit tests, and Konsist.

---

## 0. Slice plan & cadence

Phase 08 ships one `feat` commit per slice (plan files synced in-commit, impl-log
entry `_Commit `<pending>`._`) + a `docs(plan):` SHA-backfill commit, per the Phase
05–07 cadence. Pre-commit gate: `:check` of each touched module + `:build-logic:test
--rerun-tasks`; iOS-facing slices also run `scripts/test-ios.sh`.

**Decisions taken at kickoff (2026-06-06):** (a) the Android list-detail UI lives in a
new `:features:feature-list-detail` Gradle module (per §9/§12), mirroring
`:features:feature-lists`; (b) snapshot infra stays deferred to v2 (above); (c) the
shipped `ListDetailStore` (Phase 05) exposes intents only for the in-scope behaviours —
the §4 menu's **Edit list / Star / Reminders / Delete-list** entries (and the §6
cross-screen delete-list undo) depend on stores/use cases that land in Phases 09/13, so
they render as **disabled "(coming soon)"** menu rows in v1; only **Clear completed** is
wired. The bulk-undo backfill (§13/§14 — `ClearCompletedItems → List<ItemId>` +
`RestoreItems`) stays deferred consistent with the store's documented data-layer block.

1. **`:features:feature-list-detail` scaffold + Android screen** — new
   `fluxit.kmp.feature` module; `ListDetailRoute`/`ListDetailScreen`/
   `ListDetailComponents`/`ListDetailPreviews`/`ListDetailViewModel`; `:android-app`
   NavHost renders it (replacing the `Placeholder`); completion header + progress,
   TO BUY/COMPLETED sections with row variants in `FluxItSwipeRow`, composer dock,
   optimistic toggle, swipe-delete + 5s undo, hide/show, exhaustive `EffectHandler`,
   list-actions sheet (Clear completed wired; rest disabled), `SavedStateHandle`
   process-death persistence (§5). DS backfill: `FluxItToBuyListItem` subtitle +
   optional trash on `FluxItCompletedListItem` (§2 trash-removal). `:shared:state`
   `ListDetailStore` factory gains the optional-`CoroutineScope` param (viewModelScope).
2. **iOS list-detail screen** — `ListDetailView`/`ListDetailRow`/`ComposerDock`/
   `ListActionsSheet`; `ContentView` `.listDetail` route renders it; observe/effects,
   row variants, `.swipeActions` delete + undo overlay, composer, actions sheet,
   `@SceneStorage` persistence (§5).
3. **Tests + close-out** — pure-logic unit tests (`completionFraction` / row
   formatters) in `:features:feature-list-detail` androidUnitTest; `MASTER_PLAN.md` →
   🟢; hand-off (§14). Snapshot + UI-instrumented tests tracked for v2 (§11).

---

## 1. Screen anatomy

```
FluxItScaffold
├── Top bar (FluxItTopBar variant B)
│   ├── Leading: text button "‹ Lists" (primary.blue, body.md)
│   ├── Center: list name (title.md, white, single-line, ellipsize)
│   └── Trailing: ⋯ icon button → list actions menu
├── Completion header (16dp horizontal, 8dp vertical)
│   ├── "LIST COMPLETION" caption.xs muted, uppercase
│   ├── "{completed}/{total}" body.md white, right-aligned
│   └── FluxItProgressBar (full-width, 6dp tall, primary.blue fill)
├── Sections list (LazyColumn / List, sticky section headers OFF)
│   ├── "TO BUY" section
│   │   ├── FluxItSectionHeader (caption.xs muted)
│   │   └── Active items (FluxItListItem detail-active variant), wrapped in FluxItSwipeRow
│   ├── "COMPLETED" section
│   │   ├── FluxItSectionHeader with trailing text-button "Hide" / "Show"
│   │   └── Completed items (FluxItListItem detail-completed variant), wrapped in FluxItSwipeRow
│   └── (Empty state if both sections empty)
└── Composer dock (sticky bottom, above keyboard)
    ├── FluxItInlineComposer ("+ Add new item…" pill)
    └── Circular submit button (arrow-up icon, primary.blue)
```

## 2. Row variants (extend Phase 02 / Phase 07)

- **Detail-active** — leading 24dp **circle radio** (outlined, no fill); title + optional subtitle (`subtitle` field; e.g. "Produce Section"); trailing chevron-right (decorative, indicates "tap for edit"). Background = `surface.card` (no transparency tier — these are foreground content).
- **Detail-completed** — leading 24dp **filled circle with check** (`primary.blue` background, white check); title strikethrough + `text.muted` color; **no chevron** (still tappable to edit). Trash icon NOT rendered (swipe-to-delete handles it, per Phase 07 resolution).
- Both wrapped in `FluxItSwipeRow` for delete (5s undo, same pattern as dashboard).

## 3. Behaviors

- [ ] **Tap radio (active item)** → `dispatch(ItemCompletionToggled(id))`. Optimistically moves the item from `active` to `completed` section with a slide+fade animation. If `setCompleted` fails, item slides back + `ShowError` toast.
- [ ] **Tap radio (completed item)** → same intent, reverse direction (un-complete).
- [ ] **Tap row body** → `dispatch(ItemTapped(id))` → `Effect.NavigateToEditItem(id)`. Works on both active and completed items.
- [ ] **Swipe-to-delete** → `dispatch(ItemDeleteClicked(id))` → optimistic remove + 5s undo snackbar (per Phase 05 §6). Snackbar lives above the composer dock, below any system keyboard.
- [ ] **Tap "Hide" / "Show"** → `dispatch(ToggleShowCompleted)`. State persists per-list across navigations within the same session (not across cold start in v1 — DB column for that is v2).
- [ ] **Composer focus** → keyboard rises, composer docks just above it. Submit button enabled only when `composerText.isNotBlank()`.
- [ ] **Composer submit** → `dispatch(ComposerSubmit)`. On success: composer clears, list scrolls to bottom of TO BUY section to reveal the new item (smooth scroll). On failure: text retained + inline error pill above composer.
- [ ] **Composer keyboard "return"** → submits (Android `ImeAction.Done`; iOS `.submitLabel(.send)` + `.onSubmit`).
- [ ] **Tap ⋯ (more)** → opens list actions menu (§4).
- [ ] **Tap "‹ Lists"** → `dispatch(BackClicked)` → `Effect.NavigateBack`.

## 4. List actions menu (⋯)

Bottom sheet on iOS (`.confirmationDialog`-style) and Compose `ModalBottomSheet` on Android. Single source of truth for the action list:

- [ ] **Edit list details** — opens the Create List screen in edit mode (Phase 09 supports both create + edit via the same store; constructed with optional `editingId`).
- [ ] **Star / Unstar list** — toggles `is_starred` (set/cleared via `SetListStarred` use case). Surfaces in v2 Starred tab; in v1 the icon next to the list title flips state but tab is "Coming soon."
- [ ] **Reminder settings** — opens reminder editor (Phase 13 ships the editor; this phase wires the menu entry behind `ConfigProvider[Reminders.editorEnabled]` flag — true in v1, but the screen is in Phase 13).
- [ ] **Clear completed** — `dispatch(ClearCompletedClicked)` → confirmation alert ("Remove {N} completed items?") → `ClearCompletedItems` use case. **Note:** no undo for bulk clear in v1 (open question §13).
- [ ] **Delete list** — confirmation alert with destructive role → `dispatch(DeleteListClicked)` → optimistic pop back to dashboard with 5s undo snackbar attached *to the dashboard*. Cross-screen undo handled by `RootStore` carrying the pending-delete state across the navigation (see §6).
- [ ] Cancel button (sheet dismiss) — no intent.

## 5. State persistence (process death)

Mobile OSes will kill background processes; users coming back expect their typed-but-unsubmitted text intact.

- [ ] **Composer text**: persisted via Android `SavedStateHandle` (in `DashboardViewModel`-equivalent for detail) and iOS `@SceneStorage(...)`. Key: `"composer:{listId}"`. Restored on store init.
- [ ] **Show/hide completed**: same mechanism. Key: `"showCompleted:{listId}"`.
- [ ] **Pending-delete state**: NOT persisted. If process is killed within the 5s window, the soft-delete remains (it already happened) and the undo opportunity is lost. Documented as acceptable v1 behavior.
- [ ] All persistence happens at the platform-host edge (ViewModel / Scene); the shared store accepts an optional `initialState` constructor parameter for restoration.

## 6. Cross-screen undo (delete list)

When the user taps "Delete list" from the menu:

- [ ] `ListDetailStore` emits `Effect.NavigateBackWithUndo(listSummary)`.
- [ ] `RootStore` receives via parent listener (or via the navigation host) and:
  - Triggers nav pop.
  - Forwards a `PendingListDelete(name, id, expiresAt)` to the dashboard's store snapshot (mechanism: `RootStore.state.pendingDashboardSnack` consumed by `ListsDashboardStore` on its next `Init`).
- [ ] Dashboard re-foreground → renders the snackbar with the same 5s timer countdown.
- [ ] Implementation note: keep this glue in the **app layer** (Compose nav callbacks / SwiftUI `EnvironmentObject`), not in the shared store layer, to avoid cross-store coupling. The shared `RootStore` only carries the snapshot.

## 7. Performance

- [ ] LazyColumn / `List` with stable keys (`Item.id`).
- [ ] Section headers and progress bar live **outside** the lazy container so completion percent updates don't recompose row items.
- [ ] Completion fraction computed from `ItemsSection` (already in state) — no extra subscriptions.
- [ ] Optimistic toggle animation budget: 200ms slide + 120ms fade; 60fps required on Pixel 6a with 100 items.
- [ ] Cold-render budget: 250ms from route arrival to first frame on Pixel 6a (acknowledging that the route arrival itself happens after the dashboard tap).
- [ ] Avoid recomposing the whole list when only one row's completion flips: use `derivedStateOf` for per-section slicing only when the underlying lists change identity, not on each emission.

## 8. Accessibility

- [ ] Each row exposes:
  - Composite label: "Organic Bananas, Produce Section, not completed."
  - Custom action: "Mark complete" / "Mark incomplete".
  - Custom action: "Delete".
  - Default action: "Edit item" (tap row).
- [ ] Progress bar exposes value (`13 of 20`) as accessibility value, not just visual.
- [ ] Composer field labelled "Add new item to {list name}".
- [ ] Submit button labelled "Add item"; disabled state announces "disabled."
- [ ] Section headers exposed as headings (Compose `semantics { heading() }`, SwiftUI `.accessibilityAddTraits(.isHeader)`).
- [ ] Hide/Show toggle announces collapsed/expanded state.

## 9. File layout

### Android

```
:features:feature-list-detail/src/androidMain/kotlin/com/fluxit/listdetail/
  ListDetailRoute.kt           ← Koin/ViewModel glue, observes store, restores SavedStateHandle
  ListDetailScreen.kt          ← stateless, takes (state, onIntent)
  ListDetailComponents.kt      ← row variants, composer dock, completion header, list-actions sheet
  ListDetailPreviews.kt
```

### iOS

```
ios-app/Features/ListDetail/
  ListDetailView.swift
  ListDetailRow.swift           ← active + completed variants
  ComposerDock.swift
  ListActionsSheet.swift
  ListDetailPreviews.swift
```

## 10. Animations

- [ ] **Item completion**: cross-section move uses Compose `animateItemPlacement()`; SwiftUI uses `.animation(.spring(), value: items)` with stable identity. Both must keep the radio's filled state visible the entire time (no flicker through "outlined").
- [ ] **Add item**: new active row enters with 150ms slide-up + opacity. Auto-scroll to ensure visibility.
- [ ] **Swipe-to-delete**: 200ms collapse animation when committed.
- [ ] **Hide completed toggle**: 250ms section collapse via `AnimatedVisibility` / `.transition(.opacity.combined(with: .move))`.
- [ ] **Reduce-motion**: respect platform setting; collapse to instant transitions when reduce-motion is on.

## 11. Testing

- [ ] **Snapshot tests** — empty, populated (3 active / 2 completed mock from mockup), all-complete (progress full, completed section dominant), all-active (no completed section header), completed-hidden, composer-with-text, swipe-pending-delete, error-state, very-long-title-ellipsis.
- [ ] **UI behavior**:
  - Toggle radio → row animates to other section, percentage updates.
  - Submit composer → row appears in TO BUY, scrolls into view, composer cleared.
  - Submit with offline-failure stub → composer text retained.
  - Hide → completed section vanishes; Show → returns.
  - Swipe row → undo snackbar; tap undo → row restored in correct section.
  - Process-death restoration: simulate via Android `ActivityScenario.recreate()` + iOS `XCTApplicationLaunchArguments(["--simulate-scene-restore"])`.
- [ ] **Effect mapping** — exhaustive `when`/`switch` test as in Phase 07.
- [ ] **A11y audit** — TalkBack + VoiceOver pass on all interactive elements.
- [ ] **Perf test** — 100-item list scroll at 60fps on Pixel 6a; toggle animation maintains 60fps.

## 12. Konsist rules (additions)

- [ ] `feature-list-detail` cannot depend on `feature-lists` or any other feature module.
- [ ] No raw `Color`/`dp`/`TextStyle` literals.
- [ ] `ListDetailStore` is the only store referenced by this module.

## 13. Resolved decisions for this phase (2026-05-11)

- ✅ **Clear-completed undo:** confirmation alert remains, then bulk clear with a **single 5s undo snackbar** ("N items removed — Undo"). `ClearCompletedItems` use case returns the deleted ids so undo restores them in one op (extends Phase 04 §7 — open task: update use case signature to return `List<ItemId>` instead of `Int`).
- ✅ **Star toggle:** lives in the ⋯ menu only. No star button on the title bar in v1. Menu entry shows current state ("Star list" / "Unstar list") with a leading filled/outlined star icon.
- ✅ **Section headers: inline.** They scroll with content, matching the mockup. No sticky behavior.
- ✅ **Subtitle source: seeded-only in v1.** No UI to set/edit `Item.subtitle`. Sample data + future reminder-import paths can populate it; user-created items have `subtitle = null` and the row hides the second line. Edit Item subtitle field deferred to v2.
- ✅ **Composer focus on submit: retained.** After successful add, the field clears but keeps focus and the keyboard stays up. Empty submit (or back gesture) dismisses. Matches mobile note-taking conventions for rapid entry.

### Implications

- **Phase 04 §7** — `ClearCompletedItems` returns `Outcome<List<ItemId>, DomainError>` (was `Int`). Add a `RestoreItems(ids)` use case to back the bulk-undo. Tracked as a **backfill checkbox** in §14 below.
- **Phase 03 §2 / `Items.sq`** — query `softDeleteCompletedByList` should be wrapped to also return the affected ids for the use case (already returnable via `RETURNING` in SQLDelight 2). Tracked as a backfill.
- **Phase 02 §5** — primitive list unchanged (star icon already in the ~25 vectorized set per Phase 02 resolved decisions).

## 14. Hand-off checklist (gate to Phase 09)

- [ ] All checkboxes above ✅.
- [ ] Both apps demoed: open list → toggle items (animation smooth) → add new item via composer → swipe to delete + undo → kill process and reopen (composer text restored).
- [ ] Snapshot tests checked in; CI golden compare green.
- [ ] A11y audit clean.
- [ ] Perf budget met on Pixel 6a + iPhone 12 mini.
- [ ] **Phase 04 backfill**: `ClearCompletedItems` signature → `Outcome<List<ItemId>, DomainError>`; new `RestoreItems(ids)` use case added; both tested.
- [ ] **Phase 03 backfill**: `softDeleteCompletedByList` query updated to `RETURNING id`.
- [ ] `MASTER_PLAN.md`: Phase 08 → 🟢, ▶ Next Step → Phase 09.

---

## 15. Implementation Log

- **2026-06-06** — Slice 1: `:features:feature-list-detail` module + Android screen
  (§0 / §1 / §2 / §3 / §5 / §9 / §12). Stood up the new feature module via the
  `fluxit.kmp.feature` convention plugin (namespace
  `dev.franzueto.fluxit.feature.listdetail`, Compose on, deps `:shared:state` +
  `:shared:domain` + `:core:core-designsystem` + the `compose-ui` bundle +
  `koin-compose`); registered in `settings.gradle.kts`; `:android-app` depends on it and
  the NavHost's `list/{listId}` route now renders `ListDetailRoute` (replacing the
  `Placeholder`). Split into `ListDetailRoute` (Koin/ViewModel glue + exhaustive
  `ListDetailEffect` `when` → nav callbacks / undo+error snackbar / actions-sheet
  visibility), the stateless `ListDetailScreen` (`FluxItScaffold` with `FluxItTopBarCentered`
  variant B, completion header + `FluxItProgressBar` above the `LazyColumn`, TO BUY /
  COMPLETED sections via `FluxItToBuyListItem` / `FluxItCompletedListItem` wrapped in
  `FluxItSwipeRow`, sticky `FluxItInlineComposer` dock with `imePadding()`), and
  `ListDetailComponents` (snackbars, `CompletionHeader`, rows, list-actions
  `ModalBottomSheet`, `completionFraction`). Optimistic toggle / swipe-delete + 5s undo /
  hide-show / composer all dispatch to the Phase-05 store; the undo countdown mirrors the
  dashboard's 50ms-tick pattern. **§5 persistence:** `ListDetailViewModel` owns a
  `SavedStateHandle` (keys `composer:{listId}` / `showCompleted:{listId}`), replaying the
  saved values as intents on (re)creation — the shipped store has no `initialState` ctor
  param (a §5 sketch that never landed), so intent-replay is the restoration path;
  pending-delete is intentionally not persisted. **DS backfill:** added an optional
  `subtitle` to `FluxItToBuyListItem` (§2 second line) and made the trash trailing
  optional on `FluxItCompletedListItem` (§2 trash-removal — swipe handles delete); the
  Theme Gallery still passes both so it compiles. **`:shared:state`:** the `ListDetailStore`
  Koin factory gained the optional-`CoroutineScope` param so the VM scopes it to
  `viewModelScope` (mirrors `ListsDashboardStore`). **Divergences:** (a) §4's Edit / Star /
  Reminders / Delete-list menu rows render disabled "(coming soon)" — their backing
  intents land in Phases 09/13 and the store exposes none; only Clear completed (with the
  §13 confirm alert) is wired; (b) composer submit is button-tap only — the DS
  `FluxItInlineComposer` exposes no `ImeAction.Done` / disabled-when-blank wiring yet
  (store ignores blank submits), tracked as DS polish; (c) snapshot tests deferred to v2
  (§11). Gate green: `:features:feature-list-detail:check`, `:core:core-designsystem:check`,
  `:shared:state:check`, `:android-app:assembleDebug`, `:build-logic:test --rerun-tasks`,
  `scripts/test-ios.sh` (`** TEST SUCCEEDED **`). _Commit `<pending>`._
