package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * **Serial intents.** Every [dispatch] enqueues onto a single unbounded
 * [Channel]; a single consumer coroutine in [scope] runs [reduce] one intent at a
 * time, so reductions inside one store never race. Cross-store concurrency is
 * fine — stores own disjoint state.
 */
public abstract class BaseStore<S : Any, I : Any, E : Any>(
    initialState: S,
    scope: CoroutineScope,
    protected val logger: AppLogger,
) : Store<S, I, E> {
    private val _state = MutableStateFlow(initialState)
    final override val state: StateFlow<S> = _state.asStateFlow()

    // replay = 0 so one-shot effects never re-deliver on rotation / scene reuse
    // (ADR-014); extraBufferCapacity = 16 lets emit() proceed without a collector
    // present and without suspending under normal bursts.
    private val _effects = MutableSharedFlow<E>(replay = 0, extraBufferCapacity = 16)
    final override val effects: Flow<E> = _effects.asSharedFlow()

    private val intents = Channel<I>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (intent in intents) {
                reduce(intent)
            }
        }
    }

    final override fun dispatch(intent: I) {
        intents.trySend(intent)
    }

    /** The current state — read it at use time, not snapshotted, so reverts are correct. */
    protected val currentState: S
        get() = _state.value

    /** Atomically transform state. [transform] runs against the latest value. */
    protected fun update(transform: S.() -> S) {
        _state.update(transform)
    }

    /** Emit a one-shot effect. Suspends only if the 16-slot buffer is full. */
    protected suspend fun emit(effect: E) {
        _effects.emit(effect)
    }

    /** Reduce a single intent. May launch coroutines and call use cases (ADR-014: no reducer purity). */
    protected abstract suspend fun reduce(intent: I)

    /**
     * [apply] and [revert] are functions of the *current* state (evaluated at
     * call time via [update]), so a revert stays correct even if other intents
     * mutated state in between — never snapshot-and-restore.
     */
    protected suspend fun <T> optimistic(
        apply: S.() -> S,
        revert: S.() -> S,
        op: suspend () -> Outcome<T, DomainError>,
        onError: suspend (DomainError) -> Unit,
    ): Outcome<T, DomainError> {
        update(apply)
        val result = op()
        if (result is Outcome.Err) {
            update(revert)
            onError(result.error)
        }
        return result
    }
}
