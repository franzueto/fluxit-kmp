package dev.franzueto.fluxit.shared.state.store

import app.cash.turbine.test
import dev.franzueto.fluxit.shared.domain.error.DomainError
import dev.franzueto.fluxit.shared.domain.error.Outcome
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.state.testing.runStoreTest
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---- Fixture: the smallest possible BaseStore, exercising every primitive. ----

private data class CounterState(
    val count: Int = 0,
)

private sealed interface CounterIntent {
    data object Increment : CounterIntent

    data class Announce(
        val message: String,
    ) : CounterIntent

    data object TryOptimistic : CounterIntent
}

private sealed interface CounterEffect {
    data class Toast(
        val message: String,
    ) : CounterEffect
}

private class CounterStore(
    scope: CoroutineScope,
    logger: AppLogger = AppLogger.NoOp,
    private val op: suspend () -> Outcome<Unit, DomainError> = { Outcome.Ok(Unit) },
) : BaseStore<CounterState, CounterIntent, CounterEffect>(CounterState(), scope, logger) {
    override suspend fun reduce(intent: CounterIntent) {
        when (intent) {
            CounterIntent.Increment -> update { copy(count = count + 1) }
            is CounterIntent.Announce -> emit(CounterEffect.Toast(intent.message))
            CounterIntent.TryOptimistic ->
                optimistic(
                    apply = { copy(count = count + 1) },
                    revert = { copy(count = count - 1) },
                    op = op,
                    onError = { emit(CounterEffect.Toast("reverted")) },
                )
        }
    }
}

class BaseStoreTest {
    @Test
    fun initial_state_is_exposed_on_first_collection() =
        runStoreTest {
            val store = CounterStore(scope)
            assertEquals(CounterState(count = 0), store.state.value)
        }

    @Test
    fun increment_intent_updates_state() =
        runStoreTest {
            val store = CounterStore(scope)
            store.state.test {
                assertEquals(0, awaitItem().count)
                store.dispatch(CounterIntent.Increment)
                assertEquals(1, awaitItem().count)
            }
        }

    @Test
    fun announce_intent_emits_one_shot_effect() =
        runStoreTest {
            val store = CounterStore(scope)
            store.effects.test {
                store.dispatch(CounterIntent.Announce("hi"))
                assertEquals(CounterEffect.Toast("hi"), awaitItem())
            }
        }

    @Test
    fun intents_are_processed_serially_in_order() =
        runStoreTest {
            val store = CounterStore(scope)
            store.state.test {
                assertEquals(0, awaitItem().count)
                repeat(50) { store.dispatch(CounterIntent.Increment) }
                // Conflated StateFlow may collapse intermediate values; the
                // terminal value proves all 50 reductions ran exactly once.
                var latest = awaitItem().count
                while (latest < 50) {
                    latest = awaitItem().count
                }
                assertEquals(50, latest)
            }
        }

    @Test
    fun optimistic_success_keeps_the_applied_state() =
        runStoreTest {
            val store = CounterStore(scope, op = { Outcome.Ok(Unit) })
            store.state.test {
                assertEquals(0, awaitItem().count)
                store.dispatch(CounterIntent.TryOptimistic)
                assertEquals(1, awaitItem().count)
                expectNoEvents()
            }
        }

    @Test
    fun optimistic_failure_reverts_state_and_emits_error_effect() =
        runStoreTest {
            val store =
                CounterStore(scope, op = { Outcome.Err(DomainError.StorageFailure(cause = null)) })
            store.effects.test {
                // The op here doesn't suspend, so the conflated StateFlow may
                // collapse the optimistic apply (count=1) and its revert into a
                // single terminal emission. We therefore assert the *net* result:
                // state is back at 0 and the error effect fired. The "apply
                // sticks on success" half is proven by the success test above.
                store.dispatch(CounterIntent.TryOptimistic)
                assertTrue(awaitItem() is CounterEffect.Toast)
                assertEquals(0, store.state.value.count)
            }
        }
}
