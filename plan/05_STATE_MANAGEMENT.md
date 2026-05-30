# Phase 05 — State Management

> **Goal:** Provide one shared MVI store per feature in `:shared:state`, exposed to Compose (Android) as `StateFlow` and to SwiftUI (iOS) as native `AsyncSequence`/`@Observable` via SKIE. Stores are the only place use cases get composed for the UI; they own optimistic updates, undo windows, search debouncing, and one-shot side effects (toasts, navigation triggers).

**Owner:** Mobile platform
**Depends on:** Phase 01, Phase 04 (use cases + ports), Phase 03 (only transitively).
**Blocks:** All feature phases (07–10) and Phase 13 (notifications wire UI through stores).
**Exit criteria (Definition of Done):**
- `:shared:state` builds for Android + iOS; iOS framework exports stores as Swift-native types via SKIE (no `KotlinFlow` wrappers visible to SwiftUI code).
- Every store has a unit test covering: initial load, each intent → state transition, each error → effect, optimistic-then-reconcile, optimistic-then-revert.
- A "store harness" Konsist rule passes: stores expose only `state: StateFlow<S>`, `effects: Flow<E>`, and `dispatch(intent: I)`. No public mutable state. No leaked `MutableStateFlow`.
- Cold-start time of any store (`<init>` to first non-loading state) is < 50ms on a fake repository (proves no synchronous IO).

---

## 1. Module wiring

- [x] `:shared:state/build.gradle.kts` applies `fluxit.kmp.library`, applies SKIE plugin (already configured by `fluxit.kmp.library` per Phase 01 §4 — verified here for the iOS-facing module). _(Slice 2)_
- [x] Dependencies (commonMain): `:shared:domain` (as `api` + `export`ed to the framework so domain types reach Swift), `kotlinx-coroutines-core`, `kotlinx-datetime`, Kermit. **No** dependency on `:shared:data` (use cases only). _(Slice 2)_
- [x] iOS source set: SKIE annotations runtime; no other iosMain code (stores are pure common). _(Slice 2 — only the pre-existing `Platform.ios.kt` stub remains; no store code in iosMain.)_
- [x] Konsist: `:shared:state` cannot import `app.cash.sqldelight.*`, any repository **implementation**, Android, or iOS APIs. _(Slice 2 — `StateLayerArchTest` rule 1.)_

## 2. Store contract

A single shared abstraction, intentionally minimal — no MVIKotlin or Orbit dependency for v1 (re-evaluate if we hit a concrete pain point).

```kotlin
interface Store<S : Any, I : Any, E : Any> {
    val state: StateFlow<S>
    val effects: Flow<E>          // SharedFlow under the hood, replay = 0, extraBufferCapacity = 16, BufferOverflow.SUSPEND
    fun dispatch(intent: I)
}

abstract class BaseStore<S : Any, I : Any, E : Any>(
    initialState: S,
    private val scope: CoroutineScope,
    private val logger: AppLogger,
) : Store<S, I, E> {
    private val _state = MutableStateFlow(initialState)
    final override val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<E>(replay = 0, extraBufferCapacity = 16)
    final override val effects: Flow<E> = _effects.asSharedFlow()

    private val intents = Channel<I>(Channel.UNLIMITED)

    init {
        scope.launch { intents.consumeEach { reduce(it) } }
    }

    final override fun dispatch(intent: I) { intents.trySend(intent) }
    protected fun update(transform: S.() -> S) { _state.update(transform) }
    protected suspend fun emit(effect: E) { _effects.emit(effect) }
    protected abstract suspend fun reduce(intent: I)
}
```

- [x] Implement `Store` + `BaseStore` in `:shared:state/store/`. _(Slice 2)_
- [x] Intents are processed **serially** through a single `Channel` to avoid races inside a store. Cross-store concurrency is fine — they own disjoint state. _(Slice 2 — `Channel.UNLIMITED` + single consumer in the injected scope.)_
- [x] No `reducer(state, intent) -> state` purity — `reduce` may launch coroutines and call use cases. We accept impurity for ergonomics; testability is preserved by injecting fake repos/clock. _(Slice 2)_
- [x] **Effects are one-shot**: navigation requests, toasts, permission prompts, undo-action snackbars. State must always be sufficient to re-render UI without ever consuming an effect. _(Slice 2 — `SharedFlow(replay=0, extraBufferCapacity=16)`.)_

## 3. SKIE-friendly exposure (iOS)

- [x] All store classes are top-level (no nested generics on the public surface) so SKIE can synthesize Swift-native types. _(Slice 5 — verified in the generated framework: `SharedListsDashboardStore`/`SharedRootStore` are top-level; State/Intent/Effect subtypes are top-level classes.)_
- [x] State / Intent / Effect types are sealed hierarchies marked `@SealedClass` (SKIE annotation) so SwiftUI gets exhaustive `switch` checking. _(Slice 5 — **diverged**: SKIE 0.10.2 projects sealed hierarchies as Swift enums with exhaustive `switch` via `onEnum(of:)` **by default**; no `@SealedClass`/`@SealedInterface` annotation is required, and the configuration-annotations artifact isn't on the commonMain classpath. The exhaustive-switch guarantee is proven directly by the iOS smoke's `switch onEnum(of:)` over `ListsEffect`/`LoadState` — which compiles only because every case is present.)_
- [x] `Flow` returns are auto-converted by SKIE to `AsyncSequence` — verified in `ios-app` smoke target. _(Slice 5 — `StoreObserving.observe(_:into:)` iterates `store.state` with `for await`; the smoke's `compileCheck` type-checks it against a real `ListsDashboardStore`.)_
- [x] No `Result`/`Outcome` exposed from a store's public surface (errors are pre-mapped into `State.Error` or emitted as `Effect.ShowError`). SKIE handles sealed types natively, so this is a stylistic choice for SwiftUI consumption clarity. _(Slice 5 — confirmed; `LoadState.Error(message)` + `ListsEffect.ShowError(message)` are the only error surfaces, via `DomainError.userMessage`.)_
- [x] Provide a tiny Swift extension at `ios-app/Shared/StoreObserving.swift`: _(Slice 5 — landed at `ios-app/Sources/Shared/StoreObserving.swift`, written against `BaseStore<S, I, E>` since the SKIE-exposed `Store` protocol is non-generic at the Obj-C boundary.)_
  ```swift
  @MainActor func observe<S, I, E>(_ store: Store<S, I, E>, into binding: Binding<S>) async { … }
  ```
  so SwiftUI views aren't forced to litter `.task { for await s in store.state { … } }` boilerplate. Lives in app, not in the framework.

## 4. Per-feature stores

One store per screen-family, scoped to the screen's lifecycle by the platform host (Android `viewModelScope`, iOS via `@StateObject`-equivalent wrapper).

### `ListsDashboardStore` (backs Phase 07)

- **State**
  - `searchQuery: String`
  - `lists: LoadState<List<ListSummary>>` where `LoadState<T> = Loading | Empty | Loaded(T) | Error(message)`
  - `pendingDelete: PendingDelete?` (id + countdown deadline)
- **Intents**
  - `Refresh`, `SearchQueryChanged(query)`, `OpenList(id)`, `CreateListClicked`, `DeleteListClicked(id)`, `UndoDeleteClicked`, `UndoWindowExpired`, `TabSelected(Tab)`
- **Effects**
  - `NavigateToListDetail(id)`, `NavigateToCreateList`, `ShowUndoSnackbar(name, secondsRemaining)`, `ShowError(message)`, `NavigateToTab(Tab)` (Calendar/Starred → "Coming soon" placeholder per ADR-004)
- **Wiring**
  - Search query → debounced 200ms → `SearchLists.invoke(query)` → state.
  - `DeleteListClicked` → optimistic remove from `lists` → `DeleteList.invoke` → start 5s undo timer (see §6).
  - `UndoDeleteClicked` within window → `UndoDeleteList.invoke` → restore in state.

### `ListDetailStore` (backs Phase 08)

- **State**
  - `header: LoadState<ListDetail>`
  - `sections: LoadState<ItemsSection>`
  - `composerText: String`
  - `showCompleted: Boolean = true`
  - `pendingDelete: PendingDelete?` (per item)
- **Intents**
  - `Init(listId)`, `BackClicked`, `MoreClicked`, `ToggleShowCompleted`, `ItemTapped(id)`, `ItemCompletionToggled(id)`, `ItemDeleteClicked(id)`, `UndoItemDeleteClicked`, `ComposerTextChanged(text)`, `ComposerSubmit`, `ClearCompletedClicked`
- **Effects**
  - `NavigateBack`, `NavigateToEditItem(id)`, `OpenListMenu`, `ShowUndoSnackbar(...)`, `ShowError(...)`
- **Wiring**
  - `Init` launches `ObserveListDetail(id).collect { update {…} }`.
  - `ItemCompletionToggled` is **optimistic-then-reconcile**: flip the flag in state immediately; call `ToggleItemCompleted`; on failure, revert + emit `ShowError`. (See §5.)
  - `ComposerSubmit` validates non-blank → `AddItem` → clear `composerText`. Empty submits no-op.

### `CreateListStore` (backs Phase 09)

- **State**
  - `name: String`
  - `selectedIcon: FluxItIconRef`
  - `selectedColor: ColorToken`
  - `reminder: ReminderSpec?`
  - `palette: Palette` (icons + colors from `PaletteCatalog`)
  - `submission: Submission = Idle | Submitting | Success | Error(message)`
  - `validation: NameValidation = Valid | Empty | TooLong`
- **Intents**
  - `NameChanged`, `IconSelected`, `ColorSelected`, `ReminderSettingsClicked`, `ReminderConfigured(spec)`, `CancelClicked`, `CreateClicked`
- **Effects**
  - `Dismiss`, `NavigateToReminderSettings`, `NavigateToListDetail(newListId)`, `ShowError`
- **Wiring**
  - `CreateClicked` → block re-entry → `CreateList.invoke` → emit navigation effect.

### `ItemDetailStore` (backs Phase 10)

- **State**
  - `item: LoadState<Item>`
  - `editing: ItemPatch` (working copy)
  - `dirty: Boolean`
  - `photoStatus: PhotoStatus = None | Loaded(uri) | Capturing | Uploading | Error`
  - `confirmDelete: Boolean`
- **Intents**
  - `Init(itemId)`, `BackClicked`, `SaveClicked`, `TitleChanged`, `DescriptionChanged`, `UpdatePhotoClicked`, `PhotoSourceSelected(Camera | Library)`, `RemovePhotoClicked`, `DeleteClicked`, `ConfirmDelete`, `CancelDelete`
- **Effects**
  - `NavigateBack`, `RequestCameraPermission`, `RequestPhotoLibraryAccess`, `ShowError`
- **Wiring**
  - `UpdatePhotoClicked` opens a small action sheet (in-state, not effect) → `PhotoSourceSelected` calls `AttachPhotoToItem` use case which orchestrates capture + ingest + update.
  - `BackClicked` with `dirty = true` → emit `Effect.ConfirmDiscardChanges`; UI surfaces a confirm dialog.

### `AccountStore` (backs Phase 07's Account tab — minimal v1)

- **State**: `version: String`, `flags: Map<String, Boolean>` (debug only).
- **Intents**: `OpenSettings`, `OpenAbout`.
- **Effects**: `Navigate*`.
- Mostly a placeholder; exists so the tab routes somewhere real.

### `RootStore` (app-level, backs splash + tab host)

- **State**: `init: InitState = Initializing | Ready | Failed(message)`, `currentTab: Tab`.
- **Intents**: `AppStarted`, `TabSelected(Tab)`.
- **Effects**: `NavigateToOnboarding` (v2 placeholder), `ShowFatalError`.
- Runs `InitializeApp` use case (Phase 04 §7) on `AppStarted`.

## 5. Optimistic update pattern (canonical)

Encoded as a small helper used by every store that does write-on-tap UX:

```kotlin
protected suspend fun <T> optimistic(
    apply: (S) -> S,
    revert: (S) -> S,
    op: suspend () -> Outcome<T, DomainError>,
    onError: suspend (DomainError) -> Unit = { emit(@Suppress("UNCHECKED_CAST") (Effect.ShowError(it.userMessage) as E)) },
): Outcome<T, DomainError> {
    update(apply)
    return op().also { result ->
        if (result is Outcome.Failure) {
            update(revert)
            onError(result.error)
        }
    }
}
```

- [x] Implement `optimistic { … }` in `BaseStore`. _(Slice 2 — diverged from the §5 sketch: `onError` has no default, since `BaseStore` is generic over `E` and can't reference `Effect.ShowError`; each store passes its own mapping. Also matches `Outcome.Err` (the real variant name), not `Outcome.Failure`.)_
- [x] Use `apply`/`revert` pairs that are pure functions of state (so revert is correct even if other intents arrived in between — apply against current state at revert time, not by snapshotting). _(Slice 2 — `S.() -> S` applied via `update`.)_
- [x] Document the pattern in `:shared:state/README.md`. _(Slice 4 — `README.md` covers the contract, optimistic-then-reconcile, undo window, and search debounce.)_

## 6. Undo window enforcement

- [x] State carries `pendingDelete: PendingDelete?(id, expiresAt: Instant)`. _(Slice 4.)_
- [x] On `Delete*Clicked`: optimistically remove from collection; set `pendingDelete`; emit `ShowUndoSnackbar(secondsRemaining = 5)`. _(Slice 4.)_
- [x] Launch a coroutine in the store scope: `delay(5000) → if (pendingDelete still matches) dispatch UndoWindowExpired`. _(Slice 4 — the timer self-dispatches `UndoWindowExpired(id)`; the `id` makes the "still matches" check precise.)_
- [x] On `UndoWindowExpired`: clear `pendingDelete`. **Do not** call any use case — the soft-delete already happened. (We *could* hard-delete here in v2 once sync ships.) _(Slice 4.)_
- [~] On `UndoDeleteClicked`: cancel the timer, call `UndoDelete*` use case, remove `pendingDelete`. _(Slice 4 — **restore is data-layer-blocked**: no `UndoDeleteList` use case exists (the `ListsRepository` has no `deleted_at = NULL` primitive — see `DeleteList` KDoc). Timer cancel + snackbar dismissal ship; the actual restore is a documented TODO in `ListsDashboardStore` tied to that deferral. Do not invent a data-layer restore.)_
- [x] Test: rapid second delete during open undo window finalizes the first immediately and starts a new one. _(Slice 4 — `rapid_second_delete_finalizes_the_first_window_and_opens_a_new_one`.)_

## 7. Search debouncing

- [x] In `ListsDashboardStore`, transform an internal `MutableStateFlow<String>` of search query through `.debounce(200.milliseconds).distinctUntilChanged().flatMapLatest { SearchLists(it) }` and write into `state.lists`. _(Slice 4 — the debounce uses a per-value selector returning `Duration.ZERO` for a blank query so the first load / "clear search" is immediate; combined with a `refreshTick` so `Refresh` can force a re-subscribe.)_
- [x] `SearchQueryChanged` intent updates the internal flow synchronously so the text field stays responsive. _(Slice 4 — `queryFlow.value` + `state.searchQuery` both set in `reduce`.)_
- [x] Empty query short-circuits to `ObserveLists()`. _(Slice 4.)_

## 8. Scope, lifecycle, and DI

- [ ] **Scope ownership**: each store is constructed by Koin with a `@Factory` recipe taking a `CoroutineScope` parameter. Android: ViewModel passes `viewModelScope`. iOS: a thin `StoreOwner` Swift class supplies a scope tied to view lifetime via SKIE bridging.
- [ ] Stores never create their own `CoroutineScope`. Cancelling the scope cancels all in-flight reductions.
- [ ] Koin module `stateModule` declares one factory per store. Feature-specific stores pull in their use case dependencies through Koin.
- [ ] `RootStore` is `@Single` (lives the whole process); per-screen stores are `@Factory`.

## 9. Error → user message mapping

- [x] `DomainError.userMessage: String` extension lives in `:shared:state/error/` (intentionally **not** in domain, to keep domain locale-neutral). Initially returns English strings; Phase 02-style i18n token names can replace these in v2. _(Slice 3 — `DomainErrorMessages.kt`, full mapping table + exhaustive `ValidationError`/`SchedulerError`/`CaptureError` branches, covered by `DomainErrorMessagesTest`.)_
- [ ] Mapping table:
  - `Validation(field, Empty)` → "{field} can't be empty"
  - `Validation(field, TooLong(max))` → "{field} is too long (max {max})"
  - `NotFound(_,_)` → "We couldn't find that item."
  - `Conflict(msg)` → msg passthrough (already user-grade)
  - `StorageFailure` → "Something went wrong saving your changes."
  - `SchedulerFailure(PermissionDenied)` → "Allow notifications to set reminders." + paired effect to open settings.
  - `CaptureFailure(PermissionDenied)` → "Allow camera access to add photos."

## 10. Logging + analytics from the store

- [ ] Every store gets `AppLogger` injected; logs intent + resulting state delta at `Info` (debug builds) / `Warn` on errors.
- [ ] Stores **don't** emit analytics directly — that's the use case's job (per Phase 04 §5). Avoids double-counting if a use case is invoked from a non-store context (e.g. `RehydrateReminders` on app start).

## 11. Konsist rules

- [x] Public stores expose only `state`, `effects`, `dispatch`. No public mutable fields. _(Slice 4 — `StateLayerArchTest` rule 3 now asserts a `BaseStore` subclass declares **no** public functions or properties of its own, so the only public surface is the inherited contract.)_
- [x] No `MutableStateFlow` / `MutableSharedFlow` in any store's public API. _(Slice 2 — `StateLayerArchTest` rule 2.)_
- [x] No `import com.fluxit.data.*`. _(Slice 2 — `StateLayerArchTest` rule 1 bans `dev.franzueto.fluxit.shared.data` + `:platform:*` + SQLDelight + Android/iOS framework imports.)_
- [x] Every State/Intent/Effect type is `sealed` or `data` and lives in the same file as its store (or a sibling file with a `*Contract.kt` name). _(Slice 4 — `ListsState`/`ListsIntent`/`ListsEffect` are `data`/`sealed` alongside `ListsDashboardStore`, matching `RootStore`. `LoadState` is a shared `sealed interface` in `store/LoadState.kt`.)_

## 12. Testing

- [x] **Test harness**: `runStoreTest { store, scope -> … }` builds a store with fake repos, `TestScope`, `FakeClock`, and a `TurbineSubscriber` collecting `state` + `effects`. _(Slice 2 — `runStoreTest` supplies `TestScope` (via `backgroundScope` so the consumer loop is auto-cancelled) + `FakeClock`; Turbine `.test {}` is used inline on `state`/`effects`. Slice 4 — the `:shared:domain` repository fakes are now reusable here via the new `:shared:domain-testing` fixtures module, so store tests drive **real** use cases over in-memory repositories.)_
- [x] **Per-store tests** _(Slice 4 — `ListsDashboardStoreTest` on the shared fakes; `RootStoreTest` from Slice 3 covers the app store)_:
  - Initial state correctness on first collection. _(Loading → Loaded / Empty.)_
  - Each intent → expected state delta.
  - Each error path → expected effect.
  - Optimistic happy path: state flips before use case completes; reconciles on success.
  - Optimistic failure path: state reverts; error effect emitted. _(`delete_failure_reverts_the_row_and_emits_show_error`.)_
  - Undo window: timer expiration clears `pendingDelete`; explicit undo restores; rapid second delete handled. _(Expiry + rapid-second-delete covered; explicit-undo asserts the snackbar is dismissed — restore itself is data-layer-blocked, see §6.)_
  - Search debounce: typing 5 chars in 50ms triggers exactly one `SearchLists` call. _(`a_burst_of_keystrokes_debounces_to_a_single_search`.)_
- [x] **iOS smoke**: a minimal `ios-app` test that calls `dispatch(.openList(...))` from Swift and observes `effects` as `AsyncSequence`. Proves SKIE bridging. _(Slice 5 — `ios-app/Tests/StoreBridgingSmokeTests.swift` (target `FluxItTests`, run via `scripts/test-ios.sh` + CI), 4 tests green on the iOS Sim. **Compile-level** per the agreed scope: it proves the bridging SHAPE — exhaustive `switch onEnum(of:)` over `ListsEffect`/`LoadState`, native `Tab` enum, `dispatch(intent:)` callable, and `Flow→AsyncSequence` via `observe(_:into:)` type-checked against a real `ListsDashboardStore`. A fully-wired runtime store isn't Swift-constructible until Phase 06 DI (no in-memory repository is exported), so the live dispatch→effect round-trip lands then.)_
- [x] **No flakes**: no `Thread.sleep`. All time advanced via `TestScope.advanceTimeBy`. `FakeClock` advances in lockstep. _(Slice 4 — undo timers / debounce exercised via `advanceTimeBy` + `runCurrent` on virtual time.)_
- [ ] Coverage target ≥ 90% on `:shared:state`. _(Tracked to the §15 hand-off; no Kover gate wired on `:shared:state` yet.)_

## 13. ADRs to write in this phase

> **Numbering corrected (Phase 05 Slice 1):** the three sub-decisions below were originally drafted as ADR-008/008a/008b, but `00_DECISIONS.md` reserves **ADR-008 for Phase 06** (expect/actual vs. Koin-injected platform capabilities) and **ADR-014 for the MVI store contract**. They are folded into a single **ADR-014** (Proposed) — see `00_DECISIONS.md`. The three bullets are its constituent decisions:

- [x] **ADR-014** (was "ADR-008") — Hand-rolled `Store`/`BaseStore` over MVIKotlin/Orbit/Molecule. Why: tiny surface, no Android-leaning ergonomics, plays well with SKIE without adapter shims, reduces v1 dependency surface. **Drafted Proposed at Slice 1; flipped to Accepted at Slice 4 now that `BaseStore` + `RootStore` + `ListsDashboardStore` all ship green on JVM + iOS Sim.**
- [x] **ADR-014** (was "ADR-008a") — Effects channel uses `SharedFlow(replay=0)` not `Channel(BUFFERED)`. Why: SKIE renders `SharedFlow` naturally as `AsyncSequence`; `Channel` requires bridging; replay-0 prevents stale toasts on rotation/scene reuse.
- [x] **ADR-014** (was "ADR-008b") — Optimistic-then-reconcile is the default; pessimistic ("show spinner, await result") is opt-in for irreversible operations (e.g. `CreateList`'s navigate-on-success). Documented per-store.

## 14. Open questions for this phase

- [ ] **Undo window length** — 5 seconds (typical) or 7 (iOS HIG-ish)? Affects feel, not architecture.
- [ ] **Composer offline behavior** — if `AddItem` fails (storage error), keep the text in the composer or clear it? Default proposal: keep text + show inline error.
- [ ] **Multi-step optimistic chains** — e.g. attach photo (`PhotoCapture` → `ingest` → `update`) — do we want a single optimistic "shimmer" placeholder or three discrete states? Default proposal: three (`Capturing`, `Uploading`, `Loaded`); user feedback from each step.
- [ ] **Navigation effect granularity** — emit `NavigateToListDetail(id)` from store, or let UI navigate on observed `state.openListId` changes? Default proposal: effects (one-shot, no replay-on-rotation surprises).

## 15. Hand-off checklist (gate to Phase 06)

- [ ] All checkboxes above ✅.
- [~] iOS smoke test exercises one store end-to-end from Swift. _(Slice 5 — compile-level smoke green (`FluxItTests`, §3/§12); the live runtime dispatch→effect round-trip is deferred to Phase 06 when a Swift-constructible (DI-wired) store exists. See §12.)_
- [x] All store tests use virtual time; no `delay`-based flakes. _(Slices 2–4 — `runStoreTest` + `advanceTimeBy`; no `Thread.sleep`.)_
- [ ] `MASTER_PLAN.md`: Phase 05 → 🟢, ▶ Next Step → Phase 06.
- [x] `00_DECISIONS.md`: ADR-014 accepted (the single MVI store contract; folds the three §13 sub-decisions). _(Slice 4 — flipped to Accepted; the rest of §15 remains gated on the iOS smoke + Phase 06 hand-off.)_

---

## Implementation log (chronological, for traceability across sessions)

- **2026-05-29** — Slice 5: SKIE bridging (§3) + iOS smoke (§12/§15). Added the
  `ios-app/Sources/Shared/StoreObserving.swift` SwiftUI helper —
  `observe(store, into: binding)` iterates `store.state` with `for await` and
  writes a `Binding<S>`; written against `BaseStore<S, I, E>` because the
  SKIE-exposed `Store` protocol is non-generic at the Obj-C boundary. Added the
  iOS smoke `ios-app/Tests/StoreBridgingSmokeTests.swift` (new XcodeGen target
  `FluxItTests`, hosted by the `FluxIt` app, link-only against
  `Shared.xcframework`) + `scripts/test-ios.sh` (assembles the XCFramework,
  regenerates the project, runs the bundle on a dynamically-resolved iPhone sim)
  + a CI step. 4 tests green on the iOS Sim: exhaustive `switch onEnum(of:)` over
  `ListsEffect` and `LoadState`, native `Tab` enum (+ `CaseIterable`), payload
  construction, and a `compileCheck` that type-checks `dispatch(intent:)` +
  `observe(_:into:)` against a real `ListsDashboardStore`.
  **Divergences / deferrals:** (1) **No `@SealedClass`/`@SealedInterface`
  annotations** — SKIE 0.10.2 projects sealed hierarchies as exhaustive Swift
  enums (`onEnum(of:)`) **by default**; the configuration-annotations artifact
  isn't even on the commonMain classpath. The §3 annotation requirement is met
  by default behaviour, proven by the smoke's compile-time exhaustive switches
  rather than by adding a dependency. (2) **Compile-level smoke, by agreement** —
  a fully-wired store isn't Swift-constructible until Phase 06 DI (no in-memory
  repository is exported to the framework; exporting test fakes or the SQL data
  layer was rejected). The smoke proves the bridging *shape*; the live runtime
  dispatch→effect round-trip lands in Phase 06. (3) `StoreObserving.swift` lives
  at `ios-app/Sources/Shared/` (the plan wrote `ios-app/Shared/`) to match the
  existing `Sources/` tree; the test uses `@testable import FluxIt` + a
  module-qualified `FluxIt.observe(...)` since `XCTestCase` (NSObject) has its own
  KVO `observe`. Gate green: `:shared:state:check` + `:shared:domain:check` +
  `:build-logic:test --rerun-tasks` (JVM + iOS Sim + Konsist) and
  `scripts/test-ios.sh` (FluxItTests on the Simulator). _Commit `493a4a0`._

- **2026-05-29** — Slice 4: `:shared:domain-testing` fixtures module +
  `ListsDashboardStore` (§4 ListsDashboard, §5 README, §6 undo, §7 search, §11
  Konsist, §12 tests). **First**, stood up `:shared:domain-testing` (commonMain) and
  `git mv`'d the eight repository/port fakes out of `:shared:domain` commonTest into
  it — package paths unchanged, so domain's own `Fake*Test.kt` specs (which stayed)
  needed no edits; both `:shared:domain` and `:shared:state` commonTest now depend on
  it (closes the Slice 2→3→4 carry-over). `core-utils` is `api` there because the
  fake constructors take `IdGenerator` (a core-utils type the domain port aliases).
  Then landed `ListsDashboardStore`: state (`searchQuery`,
  `lists: LoadState<List<ListSummary>>` with `Loading|Empty|Loaded|Error`,
  `pendingDelete`), the full intent/effect set, search via an internal query flow
  (`debounce`+`distinctUntilChanged`+`flatMapLatest` over `SearchLists`/`ObserveLists`,
  blank query = zero debounce + full feed; a `refreshTick` lets `Refresh`
  re-subscribe), and optimistic delete + 5s undo timer. Added shared `LoadState`
  (`store/LoadState.kt`). Tightened `StateLayerArchTest` with rule 3 (a `BaseStore`
  subclass declares no public members of its own). Wrote `:shared:state/README.md`
  (contract, optimistic pattern, undo, debounce). Tests: `ListsDashboardStoreTest`
  (initial load, empty feed, synchronous query + filter, debounce-coalesces-burst,
  nav effects, tab nav, optimistic delete happy path + snackbar, delete-failure
  revert + `ShowError`, undo-window expiry, explicit-undo dismissal, rapid second
  delete) — all on the shared fakes + real use cases, virtual time only.
  **Divergences / deferrals:** (1) **Undo restore is data-layer-blocked** — there is
  no `UndoDeleteList` use case (the `ListsRepository` exposes no `deleted_at = NULL`
  restore primitive; `DeleteList`'s own KDoc says so). `UndoDeleteClicked` cancels the
  timer + dismisses the snackbar; the actual restore is a documented `TODO(data-layer)`
  in the store tied to that deferral. A live `observeAll()` feed would also clobber any
  optimistic local re-insert, so no fake restore is attempted. The test asserts
  dismissal, not restoration. (2) `UndoWindowExpired` carries the deleted `id`
  (diverges from §4's no-arg sketch) so a stale first-delete timer can't retire a
  window a rapid second delete reopened. (3) Search `debounce` uses a per-value
  selector (`ZERO` for blank, 200ms otherwise) rather than a flat 200ms, keeping the
  first load / clear-search immediate while still debouncing real queries. (4)
  `ReminderFakes.kt` (the Slice-3 local stubs) is **kept** — `RootStoreTest` still uses
  it and it's lower-risk than re-pointing that green test at the shared
  `FakeRemindersRepository`/`FakeReminderScheduler` in this slice; `ListsDashboardStore`
  tests use the shared fakes. (5) FakeClock left as two separate fixtures (domain port
  vs. state harness) — unifying them is out of scope. _Commit `42ccad7`._

- **2026-05-29** — Slice 3: `RootStore` (first real store) + §9 error mapping
  (§4 RootStore, §9). Landed `RootStore` against the ADR-014 `BaseStore`: state
  (`init: InitState = Initializing | Ready | Failed(message)`, `currentTab: Tab`),
  intents (`AppStarted`, `TabSelected`), effects (`NavigateToOnboarding` v2
  placeholder, `ShowFatalError`). `AppStarted` runs the `InitializeApp` composite
  use case and folds its `InitProgress` stream into state; a startup failure both
  lands in `InitState.Failed` and fires `ShowFatalError`. Added the shared `Tab`
  enum (`navigation/Tab.kt`, ADR-004 four-tab set) and the §9
  `DomainError.userMessage` extension (`error/DomainErrorMessages.kt`) — exhaustive
  over every `DomainError` / `ValidationError` / `SchedulerError` / `CaptureError`
  variant. Tests: `RootStoreTest` (initial state, success→Ready, scheduler
  failure→Failed+effect, tab switch) + `DomainErrorMessagesTest` (every branch).
  Gate green: `:shared:state:check` (JVM + iOS Sim) + `:build-logic:test
  --rerun-tasks`.
  **Divergences / deferrals:** (1) **Koin `stateModule` (§8) deferred to Phase 06**
  — no DI graph is assembled yet (use cases aren't Koin-registered; that's Phase 06
  per ADR-007b), so `RootStore` takes its `InitializeApp` dependency by constructor,
  exactly like the domain use cases. (2) **Reverted the Slice-2 plan to expose the
  `:shared:domain` repository fakes as a shared test fixture here** — on inspection,
  cross-module fixture sharing in KMP needs a dedicated testing module + migrating
  domain's in-module fakes (touching the green domain suite). For RootStore's single
  reminders dependency, two tiny local stubs (`ReminderFakes.kt`) are lower-risk and
  keep the slice focused; the shared testing module is deferred to **Slice 4**, where
  `ListsDashboardStore`'s richer Lists/Items fakes make the investment clearly worth
  it. (3) SKIE `@SealedClass`/`@SealedInterface` annotations (§3) not yet applied —
  plain sealed types already project under SKIE; the annotations (for exhaustive
  Swift `switch`) land with the **Slice 5** iOS smoke. _Commit `0e29c73`._

- **2026-05-29** — Slice 2: `Store`/`BaseStore` + `optimistic` helper + `AppLogger`
  port + `runStoreTest` harness (§1, §2, §5, §11, §12). Landed the `Store<S,I,E>`
  interface (`state: StateFlow` / `effects: Flow` / `dispatch`) and `BaseStore`
  (`MutableStateFlow` + `SharedFlow(replay=0, extraBufferCapacity=16)`, a single
  `Channel.UNLIMITED` consumed serially in the injected scope, `update`/`emit`/
  `currentState` protected primitives, abstract `reduce`, and the `optimistic`
  reconcile-or-revert helper). Added the §5 `AppLogger` domain port in
  `:shared:domain/port/` with a `NoOp` companion (Phase 05 is its first consumer;
  Kermit-backed actual deferred to Phase 06). Wired `:shared:state/build.gradle.kts`:
  `api(:shared:domain)` + framework `export` so domain types reach Swift,
  coroutines/datetime/Kermit, and a `testing-shared` (Turbine + coroutines-test)
  test dep. Added `StateLayerArchTest` to `:build-logic` (§1 import ban + §11
  no-public-mutable-flow). Tests: `runStoreTest` harness (`TestScope.backgroundScope`
  + `FakeClock`) + `BaseStoreTest` (initial state, intent→state, one-shot effect,
  serial-order processing, optimistic success keeps applied, optimistic failure
  reverts + emits). Gate green: `:shared:state:check` (JVM + iOS Sim) +
  `:build-logic:test --rerun-tasks`.
  **Divergences:** (1) `optimistic`'s `onError` has **no** default — `BaseStore` is
  generic over `E` and can't reference a concrete `Effect.ShowError`, so each store
  passes its own mapping (the §5 sketch's default is unrepresentable). (2) Used the
  real `Outcome.Err` variant, not the sketch's `Outcome.Failure`. (3) Konsist
  store-harness rule is the no-public-mutable-flow proxy; the full "only state/
  effects/dispatch" rule lands with the first feature stores (Slice 3+). (4) §1
  module wiring landed here (Slice 2) rather than Slice 1 — deferred until a
  consumer existed. _Commit `923b7a8`._

- **2026-05-29** — Slice 1: ADR-014 opened **Proposed** + §13 numbering fixed
  (docs-only). Wrote **ADR-014** in `00_DECISIONS.md` — the single MVI store
  contract folding the three sub-decisions §13 had drafted as ADR-008/008a/008b
  (stale: ADR-008 is reserved for Phase 06's expect/actual-vs-Koin question, and
  ADR-014 was already reserved here for the store contract). ADR-014 fixes: the
  hand-rolled `Store`/`BaseStore` surface (`state`/`effects`/`dispatch` only),
  serial-intent `Channel`, `SharedFlow(replay=0)` one-shot effects, injected
  `CoroutineScope`, `optimistic { apply; revert; op }` reconcile-or-revert as the
  default with pessimistic opt-in, `DomainError.userMessage` mapping in
  `:shared:state/error/`, and injected `AppLogger` (the §5 domain port lands this
  phase as `BaseStore`'s first consumer). Removed the ADR-014 placeholder from the
  Pending list and left a pointer; renumbered `plan/05` §13 + §15 to ADR-014.
  Recorded the §14 default resolutions to carry into the store slices: undo window
  **5s**, composer-on-failure **keep text + inline error**, photo chain **three
  discrete states**, navigation via **effects** (not observed state). **Module
  wiring (§1) deferred to Slice 2**, where `BaseStore` first consumes the
  `:shared:domain` + coroutines/datetime/Kermit dependencies (no dead wiring
  ahead of a consumer). No code change. _Commit `3e72868`._
