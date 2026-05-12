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

- [ ] `:shared:state/build.gradle.kts` applies `fluxit.kmp.library`, applies SKIE plugin (already configured by `fluxit.kmp.library` per Phase 01 §4 — verified here for the iOS-facing module).
- [ ] Dependencies (commonMain): `:shared:domain`, `kotlinx-coroutines-core`, `kotlinx-datetime`, Kermit. **No** dependency on `:shared:data` (use cases only).
- [ ] iOS source set: SKIE annotations runtime; no other iosMain code (stores are pure common).
- [ ] Konsist: `:shared:state` cannot import `app.cash.sqldelight.*`, any repository **implementation**, Android, or iOS APIs.

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

- [ ] Implement `Store` + `BaseStore` in `:shared:state/store/`.
- [ ] Intents are processed **serially** through a single `Channel` to avoid races inside a store. Cross-store concurrency is fine — they own disjoint state.
- [ ] No `reducer(state, intent) -> state` purity — `reduce` may launch coroutines and call use cases. We accept impurity for ergonomics; testability is preserved by injecting fake repos/clock.
- [ ] **Effects are one-shot**: navigation requests, toasts, permission prompts, undo-action snackbars. State must always be sufficient to re-render UI without ever consuming an effect.

## 3. SKIE-friendly exposure (iOS)

- [ ] All store classes are top-level (no nested generics on the public surface) so SKIE can synthesize Swift-native types.
- [ ] State / Intent / Effect types are sealed hierarchies marked `@SealedClass` (SKIE annotation) so SwiftUI gets exhaustive `switch` checking.
- [ ] `Flow` returns are auto-converted by SKIE to `AsyncSequence` — verified in `ios-app` smoke target.
- [ ] No `Result`/`Outcome` exposed from a store's public surface (errors are pre-mapped into `State.Error` or emitted as `Effect.ShowError`). SKIE handles sealed types natively, so this is a stylistic choice for SwiftUI consumption clarity.
- [ ] Provide a tiny Swift extension at `ios-app/Shared/StoreObserving.swift`:
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

- [ ] Implement `optimistic { … }` in `BaseStore`.
- [ ] Use `apply`/`revert` pairs that are pure functions of state (so revert is correct even if other intents arrived in between — apply against current state at revert time, not by snapshotting).
- [ ] Document the pattern in `:shared:state/README.md`.

## 6. Undo window enforcement

- [ ] State carries `pendingDelete: PendingDelete?(id, expiresAt: Instant)`.
- [ ] On `Delete*Clicked`: optimistically remove from collection; set `pendingDelete`; emit `ShowUndoSnackbar(secondsRemaining = 5)`.
- [ ] Launch a coroutine in the store scope: `delay(5000) → if (pendingDelete still matches) dispatch UndoWindowExpired`.
- [ ] On `UndoWindowExpired`: clear `pendingDelete`. **Do not** call any use case — the soft-delete already happened. (We *could* hard-delete here in v2 once sync ships.)
- [ ] On `UndoDeleteClicked`: cancel the timer, call `UndoDelete*` use case, remove `pendingDelete`.
- [ ] Test: rapid second delete during open undo window finalizes the first immediately and starts a new one.

## 7. Search debouncing

- [ ] In `ListsDashboardStore`, transform an internal `MutableStateFlow<String>` of search query through `.debounce(200.milliseconds).distinctUntilChanged().flatMapLatest { SearchLists(it) }` and write into `state.lists`.
- [ ] `SearchQueryChanged` intent updates the internal flow synchronously so the text field stays responsive.
- [ ] Empty query short-circuits to `ObserveLists()`.

## 8. Scope, lifecycle, and DI

- [ ] **Scope ownership**: each store is constructed by Koin with a `@Factory` recipe taking a `CoroutineScope` parameter. Android: ViewModel passes `viewModelScope`. iOS: a thin `StoreOwner` Swift class supplies a scope tied to view lifetime via SKIE bridging.
- [ ] Stores never create their own `CoroutineScope`. Cancelling the scope cancels all in-flight reductions.
- [ ] Koin module `stateModule` declares one factory per store. Feature-specific stores pull in their use case dependencies through Koin.
- [ ] `RootStore` is `@Single` (lives the whole process); per-screen stores are `@Factory`.

## 9. Error → user message mapping

- [ ] `DomainError.userMessage: String` extension lives in `:shared:state/error/` (intentionally **not** in domain, to keep domain locale-neutral). Initially returns English strings; Phase 02-style i18n token names can replace these in v2.
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

- [ ] Public stores expose only `state`, `effects`, `dispatch`. No public mutable fields.
- [ ] No `MutableStateFlow` / `MutableSharedFlow` in any store's public API.
- [ ] No `import com.fluxit.data.*`.
- [ ] Every State/Intent/Effect type is `sealed` or `data` and lives in the same file as its store (or a sibling file with a `*Contract.kt` name).

## 12. Testing

- [ ] **Test harness**: `runStoreTest { store, scope -> … }` builds a store with fake repos, `TestScope`, `FakeClock`, and a `TurbineSubscriber` collecting `state` + `effects`.
- [ ] **Per-store tests**:
  - Initial state correctness on first collection.
  - Each intent → expected state delta.
  - Each error path → expected effect.
  - Optimistic happy path: state flips before use case completes; reconciles on success.
  - Optimistic failure path: state reverts; error effect emitted.
  - Undo window: timer expiration clears `pendingDelete`; explicit undo restores; rapid second delete handled.
  - Search debounce: typing 5 chars in 50ms triggers exactly one `SearchLists` call.
- [ ] **iOS smoke**: a minimal `ios-app` test that calls `dispatch(.openList(...))` from Swift and observes `effects` as `AsyncSequence`. Proves SKIE bridging.
- [ ] **No flakes**: no `Thread.sleep`. All time advanced via `TestScope.advanceTimeBy`. `FakeClock` advances in lockstep.
- [ ] Coverage target ≥ 90% on `:shared:state`.

## 13. ADRs to write in this phase

- [ ] **ADR-008** — Hand-rolled `BaseStore` over MVIKotlin/Orbit/Molecule. Why: tiny surface, no Android-leaning ergonomics, plays well with SKIE without adapter shims, reduces v1 dependency surface.
- [ ] **ADR-008a** — Effects channel uses `SharedFlow(replay=0)` not `Channel(BUFFERED)`. Why: SKIE renders `SharedFlow` naturally as `AsyncSequence`; `Channel` requires bridging; replay-0 prevents stale toasts on rotation/scene reuse.
- [ ] **ADR-008b** — Optimistic-then-reconcile is the default; pessimistic ("show spinner, await result") is opt-in for irreversible operations (e.g. `CreateList`'s navigate-on-success). Documented per-store.

## 14. Open questions for this phase

- [ ] **Undo window length** — 5 seconds (typical) or 7 (iOS HIG-ish)? Affects feel, not architecture.
- [ ] **Composer offline behavior** — if `AddItem` fails (storage error), keep the text in the composer or clear it? Default proposal: keep text + show inline error.
- [ ] **Multi-step optimistic chains** — e.g. attach photo (`PhotoCapture` → `ingest` → `update`) — do we want a single optimistic "shimmer" placeholder or three discrete states? Default proposal: three (`Capturing`, `Uploading`, `Loaded`); user feedback from each step.
- [ ] **Navigation effect granularity** — emit `NavigateToListDetail(id)` from store, or let UI navigate on observed `state.openListId` changes? Default proposal: effects (one-shot, no replay-on-rotation surprises).

## 15. Hand-off checklist (gate to Phase 06)

- [ ] All checkboxes above ✅.
- [ ] iOS smoke test exercises one store end-to-end from Swift.
- [ ] All store tests use virtual time; no `delay`-based flakes.
- [ ] `MASTER_PLAN.md`: Phase 05 → 🟢, ▶ Next Step → Phase 06.
- [ ] `00_DECISIONS.md`: ADR-008 (a/b) accepted.
