package dev.franzueto.fluxit.shared.state.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single shared MVI abstraction (ADR-014, `plan/05_STATE_MANAGEMENT.md` §2).
 * Intentionally minimal — no MVIKotlin/Orbit dependency for v1.
 *
 * The public surface is exactly three members so platform hosts (Compose,
 * SwiftUI-via-SKIE) have nothing else to reach for, and a Konsist rule (§11) can
 * enforce that no store leaks mutable state:
 *
 *  - [state]   — the current screen state, always sufficient to render the UI
 *                without consuming an effect. Hot, conflated [StateFlow].
 *  - [effects] — one-shot side effects (navigation, toasts, permission prompts).
 *                Backed by a `SharedFlow(replay = 0)` so nothing replays on
 *                rotation / iOS scene reuse, and so SKIE projects it as a Swift
 *                `AsyncSequence` with no bridging.
 *  - [dispatch]— fire-and-forget intent submission. Intents are processed
 *                serially inside the store (see [BaseStore]).
 *
 * SKIE note: keep [S], [I], [E] as top-level sealed/data hierarchies so SKIE can
 * synthesize Swift-native types and exhaustive `switch`. Never expose
 * `Outcome`/`Result` here — errors are pre-mapped into `state` or an effect.
 */
public interface Store<S : Any, I : Any, E : Any> {
    public val state: StateFlow<S>
    public val effects: Flow<E>

    public fun dispatch(intent: I)
}
