# Phase 07 — Feature: Lists Dashboard

> **Goal:** Ship the dashboard screen on Android (Compose) and iOS (SwiftUI), wired to `ListsDashboardStore` (Phase 05) using only design-system primitives (Phase 02). This is the first user-facing surface; it also establishes the **app shell** (tab host, navigation graph) that Phases 08–10 plug into.

**Owner:** Mobile platform (Android + iOS pair)
**Depends on:** Phases 02 (design system), 03 (data), 04 (domain), 05 (`ListsDashboardStore` + `RootStore`), 06 (logging/config bound).
**Blocks:** Phases 08, 09 (navigation entry points originate here), 10 (transitively via 08).
**Exit criteria (Definition of Done):**
- Both apps cold-start to the dashboard, render the empty state, then render seeded lists from a debug "seed sample data" action.
- Search, swipe/trash delete with 5-second undo, and FAB → create flow all work end-to-end.
- Tab bar renders all four tabs; Lists + Account are functional, Calendar + Starred route to the "Coming soon" placeholder per ADR-004.
- All UI built from `core-designsystem` primitives — Konsist literal-ban rule passes.
- Snapshot tests (Compose + SwiftUI) checked in for: empty state, populated state, search-active, undo-snackbar visible.

---

## 0. Slice plan & cadence

Phase 07 ships one `feat` commit per slice (plan files synced in-commit, impl-log
entry `_Commit `<pending>`._`) + a `docs(plan):` SHA-backfill commit, per the Phase
05/06 cadence. Pre-commit gate: `:check` of each touched module + `:build-logic:test
--rerun-tasks`; iOS-facing slices also run `scripts/test-ios.sh`.

**Decisions taken at kickoff (2026-06-01):** (a) the Android dashboard UI lives in a
new `:features:feature-lists` Gradle module (per §4/§11), not inline in `:android-app`;
(b) snapshot infra (Paparazzi + swift-snapshot-testing) is built in the final slice,
meeting the exit criterion.

1. **`:features:feature-lists` scaffold** — new `fluxit.kmp.feature` module; move the
   Phase-06 minimal dashboard in as `DashboardRoute`/`DashboardScreen`; `:android-app`
   depends on it; §11 Konsist rules (cross-feature ban already present; add the
   `:shared:data` ban).
2. **DS backfill** — `FluxItSwipeRow` + public `FluxItIconRef→ImageVector` /
   `ColorToken→Color` mappers in `core-designsystem`; `02_DESIGN_SYSTEM.md` §5 note.
3. ✅ **`RootStore` deep-link extension** — `OpenDeepLink(url)` intent + `NavigateToList`/
   `NavigateToItem` effects + a pure `fluxit://` parser, unit-tested (≥90% gate).
4. ✅ **Android app shell & nav graph** — full route set + tab host (`FluxItBottomTabBar`
   + center FAB off `RootStore.currentTab`), deep-link intent filter, edge-to-edge.
5. ✅ **Android dashboard screen** — real DS composition, swipe-to-delete + 5s undo
   snackbar, FAB→create, empty/empty-search/error/loading, exhaustive `EffectHandler`.
6. ✅ **Coming-soon + Account + Settings stub + debug seed** — `SeedSampleData` use case
   (`:shared:state/debug/`), Account seed button (debug), coming-soon placeholder.
7. **iOS app shell + dashboard** — `NavigationStack` per tab, tab bar + FAB overlay,
   DS-composed `DashboardView` with `.swipeActions` + undo overlay, deep links, seed.
8. **Snapshot infra + tests + close-out** — Paparazzi + swift-snapshot-testing goldens;
   `MASTER_PLAN.md` → 🟢; hand-off (§13).

---

## 1. App shell (one-time setup)

### Android (`android-app`)

- [x] `MainActivity` hosts `FluxItRoot()` Composable; Koin-injected `RootStore` (Slice 4).
- [x] Navigation: **Navigation Compose** (`androidx.navigation:navigation-compose`). Single `NavHost` with routes: _(Slice 4)_
  - `dashboard` (start destination, hosts the tab bar)
  - `list/{listId}`
  - `list/{listId}/item/{itemId}`
  - `create-list` (modal — bottom sheet + dialog hybrid; see Phase 09)
  - `settings` (account → settings entry)
  - **Divergence:** Calendar/Starred render "Coming soon" as **inline tab-host content**
    (swapped on `RootStore.currentTab`) rather than via a pushed `coming-soon/{tab}`
    route — the tab bar lives inside the `dashboard` host, so a pushed route would hide
    it. Config-gated routing (§2/§80) lands in Slice 6. Also added a standalone
    `item/{itemId}` route: a bare item deep link has no parent `listId` to build the
    nested route, so it lands on an item placeholder until a Phase 08 lookup use case.
- [x] Deep link handling: Activity `VIEW` intent filter for `fluxit://list/{listId}` and `fluxit://item/{itemId}`; `MainActivity` (`singleTop`) forwards `intent.data` to `RootStore.OpenDeepLink` from `onCreate` + `onNewIntent`; effects → `navController` pushes. _(Slice 4)_
- [x] Status bar: transparent, `SystemBarStyle.dark` (light icons on `#101822`). _(Slice 4)_
- [x] Edge-to-edge enabled (`enableEdgeToEdge`). _(Slice 4)_

### iOS (`ios-app`)

- [ ] `FluxItApp.swift` declares the `App` with `WindowGroup { RootView() }`.
- [ ] `RootView` owns `RootStoreOwner` (the `StoreOwner` pattern from Phase 05 §8) and observes `state.currentTab`.
- [ ] `NavigationStack` per tab, each rooted at the tab's screen.
- [ ] Deep link handling: `.onOpenURL { url in rootStore.dispatch(.openDeepLink(url)) }` → `RootStore` emits `Effect.NavigateToList(id)` etc.
- [ ] `.preferredColorScheme(.dark)` at the root (per ADR-005b).
- [ ] Safe area + bottom-inset handling for the tab bar's blur backdrop.

## 2. Tab host

- [ ] `FluxItBottomTabBar` (Phase 02 §5) consumes a `selected: Tab` and `onSelect: (Tab) -> Unit`. Tab enum: `Lists, Calendar, Starred, Account`.
- [ ] Active state: filled icon variant + `primary.blue` tint; inactive: outlined + `text.muted`.
- [ ] Tab order matches the mockup. FAB is **center-docked** above the tab bar (separate composable / SwiftUI overlay), aligned to the same horizontal center as the search field.
- [ ] Tapping Calendar or Starred dispatches `RootStore.Intent.TabSelected(tab)`; `RootStore` checks `ConfigProvider[Calendar.enabled]` (false in v1 → emits `NavigateToTab(.comingSoon(tab))`).
- [ ] Coming-soon screen uses `FluxItEmptyState` with copy: "Coming in a future update." and an icon hinting the upcoming feature.

## 3. Dashboard screen

### Composition (both platforms — same structure, native widgets)

```
FluxItScaffold
├── Header (sticky, blurred backdrop)
│   ├── Avatar (leading, 40dp circle, default placeholder for v1)
│   └── Settings icon (trailing)
├── Title block ("My Lists", display.lg, 16dp horizontal, 8dp top)
├── Search field (FluxItSearchField, full-width, 16dp horizontal)
├── Lists section
│   └── LazyColumn / List (LoadState-driven)
│       ├── Loading: 3 placeholder skeleton rows (FluxItCard with shimmer)
│       ├── Empty: FluxItEmptyState ("No lists yet — tap + to create one")
│       ├── Loaded: rows of FluxItListItem (dashboard variant)
│       └── Error: inline FluxItEmptyState with retry button
└── (FAB + tab bar overlaid by FluxItScaffold's bottom slot)
```

### Row composition (`FluxItListItem` dashboard variant)

- 56dp colored leading icon container (`color` swatch @ 20% bg, full-color icon).
- Title (`title.md`).
- Subtitle (`label.sm` muted): conditional formatting from `ListSummary`:
  - If `totalItems == 0` → "No items yet"
  - Else: "{totalItems} items · {metadataLine}"
  - `metadataLine` priority: explicit subtitle (e.g. "Travel packing") > completion ("50% completed" if 0 < completed < total) > "Last updated {relative}" (e.g. "2h ago") via `kotlinx-datetime` + a small relative-time formatter.
- Trailing: trash icon button + chevron-right (decorative, indicates tappability).
- Tap row → `dispatch(OpenList(id))`.
- Tap trash → `dispatch(DeleteListClicked(id))`.

### Behaviors

- [ ] **Search**: typing debounces 200ms (state-side per Phase 05 §7); clear-button shows when non-empty.
- [ ] **Pull-to-refresh** (Android `PullToRefreshContainer`, iOS `.refreshable`): dispatches `Refresh`. Local-only v1 means refresh just re-emits from DB; still useful as a UX affordance.
- [ ] **Undo snackbar**: bottom-anchored, 5s countdown, dismissible by tap. Render above the tab bar but below the FAB. On Android use a custom `Snackbar`-like composable (Material's `SnackbarHost` doesn't sit well above a custom tab bar); on iOS use a `.overlay(alignment: .bottom)` view with a timer-driven progress bar.
- [ ] **Long-press a row** (v1 nice-to-have, optional): no-op for now. Reserved for v2 multi-select.
- [ ] **Empty-search state**: when `searchQuery.isNotBlank()` and `lists.isEmpty()`, show "No lists matching '{query}'" instead of the create-first empty state.

## 4. Compose (Android) — file layout

```
:features:feature-lists/src/androidMain/kotlin/com/fluxit/lists/
  DashboardRoute.kt              ← Koin/ViewModel glue, observes store
  DashboardScreen.kt             ← stateless Composable, takes (state, onIntent)
  DashboardComponents.kt         ← row composable, undo snackbar, empty/error states
  DashboardPreviews.kt           ← @Preview functions for Theme Gallery + snapshot tests
```

- [x] `DashboardRoute(onOpenList, onCreateList, onComingSoon, onOpenSettings)` _(Slice 5)_:
  - `val store = viewModel { DashboardViewModel { scope -> koin.get { parametersOf(scope) } } }.store`.
  - Collects `state` via `collectAsState()` (lifecycle-aware variant deferred — no extra dep needed for v1).
  - Collects `effects` via `LaunchedEffect` → exhaustive `when` mapping to nav callbacks + undo/error snackbar state.
- [x] `DashboardViewModel(storeFactory)` — exists only to scope the store to `viewModelScope` (Phase 05 §8); takes a `(CoroutineScope) -> ListsDashboardStore` factory and mints the store with `viewModelScope`. The `:shared:state` Koin factory gained an optional `CoroutineScope` parameter (no-arg resolves keep the fresh-scope fallback for iOS/tests). _(Slice 5)_
- [x] All `dp`/`Color`/`TextStyle` references go through DS primitives + tokens / `FluxItIcons.*` — Konsist verifies. _(Slice 5)_
- **Slice 5 notes:** sticky header is `FluxItTopBarLarge("My Lists", trailing=Settings)` rendered as the screen's first column child (the host's single `FluxItScaffold` owns the bottom bar; no nested scaffold). The mockup avatar (§12) is deferred — not yet a bundled asset. The FAB lives in the host and navigates straight to `create-list` (the store's `NavigateToCreateList` path stays wired + handled but has no in-screen producer yet). Loading renders 3 muted `FluxItDashboardListItem` placeholders (no DS skeleton/shimmer primitive yet; literal-ban forbids raw sizing in feature code). Pure subtitle/relative-time logic (`subtitleFor`) is unit-tested in Slice 8 alongside snapshots.

## 5. SwiftUI (iOS) — file layout

```
ios-app/Features/Dashboard/
  DashboardView.swift            ← @MainActor View, observes store via StoreOwner
  DashboardRow.swift             ← FluxItListItem dashboard variant
  DashboardComponents.swift      ← undo overlay, empty/error
  DashboardPreviews.swift        ← #Preview blocks for snapshot tests
```

- [ ] `DashboardView` uses `@StateObject var owner: StoreOwner<ListsDashboardStore>` constructed via Koin facade (`KoinIOS.dashboardStore()`).
- [ ] `.task` blocks observe `state` and `effects` AsyncSequences (per Phase 05 §3).
- [ ] All `Color`/`Font`/`spacing` references go through `FluxItTokens.*` — generated Swift mirror from Phase 02.

## 6. Navigation effects → routes

Mapping table (same on both platforms):

| Effect | Action |
|---|---|
| `NavigateToListDetail(id)` | Push `list/{id}` |
| `NavigateToCreateList` | Present `create-list` modal |
| `NavigateToTab(.comingSoon(tab))` | Push `coming-soon/{tab}` |
| `NavigateToTab(.lists)` | Pop to dashboard root |
| `ShowUndoSnackbar(name, secs)` | Trigger snackbar overlay state |
| `ShowError(message)` | Trigger toast / inline error |

- [ ] One `EffectHandler` per platform translates the sealed `Effect` enum into nav/snackbar calls. Exhaustive `when`/`switch` so adding a new effect breaks the build.

## 7. Debug seed action

- [x] In debug builds, the Account tab includes a "Seed sample data" button that invokes a `SeedSampleData` use case (lives in `:shared:state/debug/` → `commonMain/.../state/debug/`, so the iOS Slice-7 seed resolves the same type). _(Slice 6)_
  - Creates the 5 lists from the mockup ("Supermarket", "Home To-Do", "Trip to Japan", "Gift Ideas", "Work Q4 Goals") with their respective icons + colors.
  - Adds 3–10 items to each (a few pre-completed via `ToggleItemCompleted`).
- [x] Stripped from release builds via Gradle source-set selection (`android-app/src/debug` real `DebugActionsSection`, `src/release` no-op twin) — mirrors the designsystem Theme Gallery in `androidDebug`. _(Slice 6)_
- [x] Used by manual QA + screenshot tests so we don't depend on a network or fixture file. _(Slice 6; screenshot use lands Slice 8.)_

## 8. Accessibility

- [ ] Every row exposes a single accessibility action: "Open {list name}, {item count}, {metadata}". Trash button is a separate a11y element with action "Delete list {name}".
- [ ] Search field labelled "Search lists".
- [ ] FAB labelled "Create new list".
- [ ] Tab bar items announce selected state.
- [ ] Undo snackbar uses live-region announcement on appearance and on countdown completion.
- [ ] Test with TalkBack + VoiceOver before merging.

## 9. Performance

- [ ] LazyColumn / `List` use stable keys (`ListSummary.id`).
- [ ] Row composable is `@Stable`; relative-time string memoized to avoid recomputing on every scroll frame.
- [ ] Header blur cost benchmarked on a Pixel 6a — must hold 60fps with 50 lists. Falls back to opaque per Phase 02 §7 if it doesn't.
- [ ] Cold start to first frame < 800ms on a Pixel 6a with seeded data; documented as a perf budget.

## 10. Testing

- [ ] **Snapshot tests** (Paparazzi for Compose; SwiftUI snapshot via `swift-snapshot-testing`):
  - Empty state.
  - Populated (5 sample lists).
  - Search active with matches.
  - Search active with no matches.
  - Undo snackbar visible.
  - Loading skeletons.
- [ ] **UI behavior tests**:
  - Android: Compose UI test — type query, assert filtered list; tap trash, assert undo snackbar; tap undo, assert restoration.
  - iOS: XCUITest equivalent flows.
- [ ] **Tab routing test**: tap Calendar → assert "Coming soon" screen rendered.
- [ ] **Effect mapping test**: every effect variant produces the right nav side-effect (assert via a fake `EffectHandler`).
- [ ] **A11y audit**: run `Accessibility Scanner` on Android, `Accessibility Inspector` on iOS; all findings triaged (no Critical at merge).

## 11. Konsist rules (additions)

- [ ] No `Color(0x…)`, `dp(…)`, raw text styles outside `core-designsystem` (already from Phase 02 — verified per-feature here).
- [ ] `feature-lists` may not depend on any other `feature-*` module.
- [ ] `feature-lists` may not import `:shared:data` directly — only domain interfaces via use cases via the store.

## 12. Resolved decisions for this phase (2026-05-11)

- ✅ **Avatar:** ship the stylized illustrated avatar from the mockup as a bundled static asset. Tap is a no-op in v1 (reserved for v2 profile entry).
- ✅ **Settings gear:** routes to a real stub `SettingsScreen` with sections:
  - **About**: app version, build number.
  - **Privacy** (added per Phase 16 resolution): "Anonymous crash reports" toggle (defaults ON; wired to `FirebaseCrashlytics.setCrashlyticsCollectionEnabled` and persisted via `platform-config`; inline note "Takes effect on next launch"); link rows for "Privacy Policy" and "Terms of Service" (URL placeholders for v1, opens system browser).
  - **Diagnostics** (debug + internal builds only — Phase 16 §7): entry point to the Diagnostics screen.
  - **Debug actions** (debug builds only): "Seed sample data", "Clear all data" (wipes `fluxit.db` + `files/photos/` then restarts the activity / iOS scene), "Force crash" (Phase 16 §1 verification).
- ✅ **Pull-to-refresh:** keep. Implement as a 300ms artificial spinner that re-emits the current `lists` state. UX affordance only; no real refresh in local-only v1.
- ✅ **Row subtitle priority:** explicit subtitle → completion % (when partially done) → relative "Last updated …" (default).
- ✅ **Trash UX: SWIPE-TO-DELETE with 5s undo snackbar.** **Mockup divergence:** the design shows a visible trash icon in each row; we hide it in favor of swipe (Android: `SwipeToDismissBox` revealing a rose-tinted delete affordance; iOS: native `.swipeActions` with destructive role). Reasoning: cleaner row, native gesture on both platforms, less accidental-tap risk next to the chevron. **Action item:** loop in design before merge — they may want to keep the visible icon AND swipe, or accept the swipe-only approach. Tracked in §13 hand-off.

### Implications for §3 / §4 / §5

- Remove the trailing trash icon from the `FluxItListItem` dashboard variant (still present on the **completed-item** variant used in Phase 08, where swipe is awkward inside a sectioned list).
- Add `FluxItSwipeRow` to `core-designsystem` if not already there — Phase 02 §5 currently doesn't list it. **Backfill into Phase 02 checklist** on next pass.
- `DeleteListClicked` intent semantics unchanged; only the gesture that produces it changes.

## 13. Hand-off checklist (gate to Phase 08)

- [ ] All checkboxes above ✅.
- [ ] Both apps demoed: cold-start → seed → search → delete → undo → tap row (lands on placeholder for now until Phase 08 ships) → tap FAB (lands on placeholder until Phase 09 ships).
- [ ] Snapshot tests checked in; CI golden compare green.
- [ ] No Konsist failures; literal-ban + cross-feature-dep rules verified.
- [ ] **Design sign-off** on swipe-to-delete (vs. mockup's visible trash icon). Outcome recorded in commit message.
- [x] **`02_DESIGN_SYSTEM.md` backfill**: add `FluxItSwipeRow` to the primitives list (§5). _(Slice 2.)_
- [ ] `MASTER_PLAN.md`: Phase 07 → 🟢, ▶ Next Step → Phase 08, M4 (Core User Surfaces) progress bar advanced.

---

## 14. Implementation Log

- **2026-06-01** — Slice 1: `:features:feature-lists` module scaffold (§0 / §4 / §11).
  Stood up the new feature module via the pre-existing `fluxit.kmp.feature` convention
  plugin (namespace `dev.franzueto.fluxit.feature.lists`, Compose enabled, deps on
  `:shared:state` + `:shared:domain` + `:core:core-designsystem` + the `compose-ui`
  bundle + `koin-compose`). Registered it in `settings.gradle.kts`. Moved the Phase-06
  minimal `ListsDashboardScreen` out of `:android-app/ui` and split it into
  `DashboardRoute` (Koin/store glue — `koinInject<ListsDashboardStore>` + `collectAsState`,
  forwards `store::dispatch`) and the stateless `DashboardScreen(state, onIntent)`;
  `:android-app` now depends on `:features:feature-lists` and `FluxItRoot`'s NavHost
  renders `DashboardRoute()`. Konsist: the cross-feature-dep ban already covers
  `/features/feature-*`; added the §11 `:shared:data`-import ban to `ArchitectureTest`.
  The minimal body is a deliberate placeholder — DS composition, swipe/undo, and the
  effect→nav mapping land in Slices 2/4/5. **Divergence:** kept the Phase-06
  `koinInject` store-resolution pattern rather than introducing the §4
  `DashboardViewModel`/`koinViewModel` scoping yet — that lands with the real screen in
  Slice 5 to keep the scaffold a pure move. Gate green: `:features:feature-lists:check`,
  `:android-app:assembleDebug`, `:build-logic:test --rerun-tasks`. _Commit `70a3f37`._

- **2026-06-01** — Slice 2: design-system backfill — `FluxItSwipeRow` + public
  list-identity mappers (§3 / §12 / §13, Phase 02 §5). Added `FluxItSwipeRow` to
  `core-designsystem` (Material3 `SwipeToDismissBox`, end-to-start only, rose-tinted
  `accentRose @ 20%` background, `confirmValueChange` → `onDelete`; the store owns the
  optimistic removal + 5s undo so the primitive never animates back). Promoted the
  `FluxItIconRef→ImageVector` and `ColorToken→Color` mappings out of the debug Theme
  Gallery into public DS API (`components/ListIdentity.kt`), both `when`s exhaustive over
  the domain enums so a new ref/token breaks the build here. This needed two supporting
  changes: (1) `core-designsystem` now `implementation(project(":shared:domain"))` —
  the intended ADR-006c/Phase-04-§2 direction (domain owns the refs, DS consumes them;
  the inward arrow stays ArchTest-forbidden); (2) `tokens.json` gained the four missing
  list-identity accent swatches (`accent.emerald #10b981`, `orange #f97316`,
  `indigo #6366f1`, `sky #0ea5e9`) — `ColorToken` has six values but only blue+rose had
  tokens — regenerated via `generateTokens` (Kotlin `FluxItColors` + the committed
  `ios-app/Generated/FluxItTokens.swift` Swift mirror). iOS `FluxItSwipeRow`
  (`.swipeActions`) lands with the iOS dashboard in Slice 7. Gate green:
  `:core:core-designsystem:check`, `:build-logic:test --rerun-tasks`. _Commit `edaa871`._

- **2026-06-01** — Slice 3: `RootStore` deep-link extension (§0 / §1 / §6, plan/06 §5).
  Added a **pure** `DeepLink` parser in `:shared:state` `navigation/DeepLink.kt`
  (`DeepLink.parse(url)` → `DeepLink.List(ListId)` / `DeepLink.Item(ItemId)` / `null`),
  matching the `fluxit://list/{id}` + `fluxit://item/{id}` shapes minted by
  `platform-reminders.ScheduledNotification.deepLink`. Extended `RootStore` with a
  `RootIntent.OpenDeepLink(url)` intent and `RootEffect.NavigateToList(ListId)` /
  `NavigateToItem(ItemId)` effects; the reduce path parses and emits the matching effect,
  logging-and-dropping anything unparseable (foreign scheme, unknown host, blank id, extra
  path segments) so a stale/garbled reminder URL leaves the user where they are. Naming
  note: the intent is `OpenDeepLink` (MVI data class, matching plan/13's `RootStore.
  OpenDeepLink(uri)` reference) rather than a bare
  `openDeepLink(url)` method — the shell dispatches it like every other intent. Unit tests:
  7-case `DeepLinkTest` (all parser branches, for the Kover ≥90% gate) + 3 Turbine reduce
  tests in `RootStoreTest` (list, item, drop-then-next-good). Gate green:
  `:shared:state:check` (incl. `koverVerify`), `:build-logic:test --rerun-tasks`,
  `scripts/test-ios.sh` (XCFramework header regenerates, 5 bridging smoke tests pass).
  _Commit `4bd7b4e`._

- **2026-06-02** — Slice 4: Android app shell & nav graph (§0 / §1 / §6). Replaced the
  single-route NavHost in `android-app/ui/FluxItRoot.kt` with the full route set
  (`dashboard` tab host, `list/{listId}`, `list/{listId}/item/{itemId}`, `create-list`,
  `settings`) — not-yet-built screens render a `Placeholder`. Added `TabHost`: a
  `FluxItScaffold` with `FluxItBottomTabBar` (4 `FluxItTabItem`s with filled active-icon
  variants) whose `selectedIndex`/`onSelect` are driven off `RootStore.currentTab` +
  `RootIntent.TabSelected`, a body that swaps on the current tab (Lists → live
  `DashboardRoute()`; Calendar/Starred → inline "Coming soon" `FluxItEmptyState`;
  Account → placeholder), and a center-docked `FluxItFab` (→ `create-list`).
  App-level deep links: a `LaunchedEffect` collects `RootStore.effects` and maps
  `NavigateToList`/`NavigateToItem` → `navController` pushes; `MainActivity` is now
  `singleTop`, calls `enableEdgeToEdge` (transparent bars, `SystemBarStyle.dark` →
  light icons on `#101822`), injects `RootStore`, and forwards the `VIEW` intent's
  `fluxit://` data to `RootIntent.OpenDeepLink` from `onCreate` + `onNewIntent`; the
  manifest gained the matching `VIEW`/`BROWSABLE` intent filter for hosts `list`+`item`.
  **Divergences** (both in §1): coming-soon is inline tab content (not a pushed
  `coming-soon/{tab}` route — the tab bar lives in the `dashboard` host), and a
  standalone `item/{itemId}` route absorbs bare item deep links until a Phase 08
  parent-list lookup exists. Config-gated Calendar/Starred routing + the real Account
  screen land in Slice 6; row/FAB store-effect wiring lands with the dashboard in
  Slice 5. Gate green: `:android-app:check` (lint + Konsist via `:build-logic:test`),
  `:android-app:assembleDebug`, `:build-logic:test --rerun-tasks`. _Commit `ea0b595`._

- **2026-06-02** — Slice 5: Android dashboard screen — the heart (§3 / §4 / §6). Fleshed
  out `:features:feature-lists` into four files: `DashboardScreen.kt` (stateless DS
  composition — `FluxItTopBarLarge` "My Lists" + settings gear, `FluxItSearchField`,
  `LoadState`-driven body), `DashboardComponents.kt` (`DashboardListRow` = `FluxItSwipeRow`
  ∘ `FluxItDashboardListItem` with `toImageVector()`/`toColor()` mappers + chevron;
  `UndoSnackbar`/`ErrorSnackbar` built from `FluxItCard`+`FluxItProgressBar`; `SkeletonList`;
  pure `subtitleFor`/`relativeTime` per §3/§12), `DashboardViewModel.kt` (scopes the store
  to `viewModelScope`), and `DashboardRoute.kt` (builds the VM via `viewModel { … koin.get
  { parametersOf(scope) } }`, exhaustive `ListsEffect` → nav-callback/snackbar mapping,
  drives the 5s undo countdown bar). Swipe-to-delete dispatches `DeleteListClicked`; the
  store's optimistic remove + `ShowUndoSnackbar` opens the snackbar, tap "Undo" →
  `UndoDeleteClicked`, and `pendingDelete == null` (expiry/finalize) hides it. Empty,
  empty-search ("No lists matching …"), error (with Retry → `Refresh`), and loading states
  all handled. `:shared:state` `StateModule` gained an optional `CoroutineScope` Koin
  parameter so the VM can thread `viewModelScope` while no-arg resolves (iOS/`KoinGraphTest`)
  keep the fresh-scope fallback. `android-app` `TabHost` now calls `DashboardRoute` with
  nav callbacks (`onOpenList`/`onCreateList`/`onOpenSettings` → `navController`). Divergences
  (in §4): inline header instead of scaffold top-slot; avatar deferred; FAB navigates
  directly (store create path handled but unproduced in-screen); loading is muted
  placeholder rows, not shimmer. `viewModel {}` resolves via activity-compose's transitive
  `lifecycle-viewmodel-compose` (no catalog change). Gate green: `:features:feature-lists:check`,
  `:shared:state:check` (incl. `koverVerify`), `:android-app:assembleDebug`,
  `:build-logic:test --rerun-tasks`. _Commit `a8dcfce`._

- **2026-06-02** — Slice 6: Coming-soon + Account + Settings stub + debug seed
  (§2 / §7 / §12). Added the **`SeedSampleData`** use case in `:shared:state`
  `commonMain/.../debug/` (kept in `commonMain`, not `androidDebug`, so the iOS
  Slice-7 seed resolves the same type) — a thin orchestrator over the existing
  `:shared:domain` use cases (`CreateList` → `AddItem` → `ToggleItemCompleted`),
  going through the same seams the stores use and never touching `:shared:data`.
  It seeds the five mockup lists (Supermarket / Home To-Do / Trip to Japan / Gift
  Ideas / Work Q4 Goals) with 3–10 items each and a few pre-completed; non-
  transactional (returns the first `DomainError` and stops). Bound as a `factory`
  in `stateModule`; unit-tested (`SeedSampleDataTest`, 3 cases — keeps
  `:shared:state` Kover ≥90%) by composing the real use cases over
  `FakeListsRepository`/`FakeItemsRepository` (the fakes don't join, so item
  counts are asserted via `observeByList`, not the list summary). **Real Account
  screen** (`android-app/ui/account/`): `AccountScreen` + `AccountViewModel`
  (scopes `AccountStore` to `viewModelScope` — `AccountStore`'s `stateModule`
  factory gained the same optional `CoroutineScope` param as `ListsDashboardStore`,
  no-arg resolves keep the fresh-scope fallback), inline `FluxItTopBarLarge`
  header (host owns the scaffold), a Settings row showing the interim version, and
  the debug seed section. **Settings stub** (`android-app/ui/settings/`):
  `SettingsScreen` behind the `settings` route — About (version from
  `AccountStore`), Privacy (no-op "Anonymous crash reports" `Switch` + "Takes
  effect on next launch" note; real Crashlytics/`platform-config` wiring deferred
  to Phase 16), Privacy Policy / ToS rows opening placeholder URLs via an
  `ACTION_VIEW` intent, and the debug seed section. **Debug-only exposure** via
  Gradle source-set selection: `android-app/src/debug/.../DebugActions.kt` renders
  the real `DebugActionsSection` (resolves `SeedSampleData` from Koin, runs it,
  surfaces the count/error inline); `src/release/.../DebugActions.kt` is a no-op
  twin — so the seed button + its `SeedSampleData` reference are stripped from
  release (mirrors the designsystem Theme Gallery in `androidDebug`; both debug +
  release compile clean). **Coming-soon decision:** kept Calendar/Starred as the
  Slice-4 **inline** "Coming soon" `FluxItEmptyState` rather than wiring
  `RootStore`/`ConfigProvider` config-gating — the inline tab content already meets
  ADR-004, and config-gated routing would add a `ConfigProvider` seam with no
  v1 behaviour change; revisit if a second gated surface needs it. **Divergences:**
  `About` has no standalone destination — it lives inside the Settings stub, so
  `AccountEffect.NavigateToAbout` routes to Settings too; the Privacy toggle is
  local/no-op (Phase 16). State + android-app only (no iOS API consumed by Swift
  changed) → skipped `test-ios.sh` per §0 (the iOS seed resolver lands Slice 7).
  Gate green: `:shared:state:check` (incl. `koverVerify`), `:android-app:check` +
  `:android-app:assembleDebug` (debug + release compile), `:build-logic:test
  --rerun-tasks` (Konsist literal-ban passes for the new app/settings code).
  _Commit `83c4ee8`._
