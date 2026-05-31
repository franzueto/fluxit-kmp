# `:shared:state` — MVI stores

Shared, Flow-based **MVI** stores — one per feature — composing the Phase 04 use
cases for the UI. Exposed to Compose (Android) as `StateFlow` and to SwiftUI (iOS)
as native `AsyncSequence`/`@Observable` via SKIE. See `plan/05_STATE_MANAGEMENT.md`
and **ADR-014** in `plan/00_DECISIONS.md`.

## The contract

```kotlin
interface Store<S, I, E> {
    val state: StateFlow<S>   // current, always sufficient to render
    val effects: Flow<E>      // one-shot: nav, toasts, snackbars (replay = 0)
    fun dispatch(intent: I)
}
```

`BaseStore` implements it: a single `Channel.UNLIMITED` consumed **serially** in an
injected `CoroutineScope` (so reductions inside one store never race), a
`MutableStateFlow` behind `state`, and a `MutableSharedFlow(replay = 0,
extraBufferCapacity = 16)` behind `effects`. Subclasses implement `reduce(intent)`
and drive state via `update { … }` / effects via `emit(…)`. The public surface is
`final` — a store adds nothing public beyond the three contract members
(enforced by `StateLayerArchTest`).

**Navigation is an effect, never observed state** (§14): `emit(NavigateTo…)` is
one-shot, so it can't re-fire on rotation / scene reuse the way replayed state
would.

## The optimistic-then-reconcile pattern (§5)

Write-on-tap UX flips state immediately, then reconciles with the use-case result:

```kotlin
optimistic(
    apply  = { copy(lists = removeFromLists(id)) },  // flip now
    revert = { copy(lists = snapshot.toLoadState()) }, // undo if the op fails
    op     = { deleteList(id) },                       // suspend on the use case
    onError = { emit(Effect.ShowError(it.userMessage)) },
)
```

- `apply`/`revert` are functions of the **current** state (applied via `update`),
  not a snapshot taken before the op — reverts stay correct even if other intents
  landed in between.
- `onError` has **no default** (unlike the §5 sketch): `BaseStore` is generic over
  `E` and can't name a concrete `Effect.ShowError`, so each store passes its own
  mapping, typically `{ emit(Effect.ShowError(it.userMessage)) }`.
- On success the helper returns `Outcome.Ok`; the store does any follow-up
  (open an undo window, navigate, …) from there.

Pessimistic ("show spinner, await result") is the opt-in exception for irreversible
operations (e.g. `CreateList` navigating on success).

## The undo window (§6)

`DeleteListClicked` → optimistic remove → on success: set
`pendingDelete(id, expiresAt = now + 5s)`, `emit(ShowUndoSnackbar)`, and launch a
timer in the store scope that self-dispatches `UndoWindowExpired(id)` after 5s.

- `UndoWindowExpired(id)` clears `pendingDelete` **only if it still matches** — a
  rapid second delete cancels the first timer and opens a new window, so a stale
  timer must not retire the newer one. No use-case call: the soft-delete is final.
- `UndoDeleteClicked` cancels the timer and dismisses the snackbar.

> ⚠️ **Restore is data-layer-blocked.** There is no `UndoDeleteList` use case — the
> shipped `ListsRepository` exposes no `deleted_at = NULL` restore primitive. Undo
> therefore only dismisses the snackbar today; a real restore is a documented TODO
> in `ListsDashboardStore` tied to that data-layer deferral. `DeleteList` already
> returns the `cancelledReminderIds` so a future restore can reschedule them.

## Search debouncing (§7)

An internal `MutableStateFlow<String>` is updated synchronously on each keystroke
(so the text field stays responsive), then
`debounce → distinctUntilChanged → flatMapLatest { SearchLists | ObserveLists }`
into `state.lists`. A blank query has a **zero** debounce (immediate full feed via
`ObserveLists`); a real query debounces 200ms before hitting `SearchLists`.

## Testing

`runStoreTest { … }` provides a `TestScope` (the store runs on `backgroundScope`
so its consumer loop auto-cancels), a `FakeClock`, and virtual time. Drive
`state`/`effects` with Turbine's `.test { }`; advance undo timers / debounce via
`advanceTimeBy` — never `Thread.sleep`. Repository fakes come from
`:shared:domain-testing` (the shared fixtures module).
