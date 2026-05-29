package dev.franzueto.fluxit.shared.state.testing

import dev.franzueto.fluxit.shared.domain.port.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * A controllable [Clock] for store tests (`plan/05_STATE_MANAGEMENT.md` §12). The
 * returned [Instant] is advanced explicitly by tests so undo-window / debounce
 * logic can be exercised in lockstep with [TestScope.testScheduler] virtual time —
 * no `Thread.sleep`, no wall-clock reads.
 */
public class FakeClock(
    private var instant: Instant = Instant.fromEpochSeconds(0),
) : Clock {
    override fun now(): Instant = instant

    /** Move the clock to [target]. Pair with `advanceTimeBy` to keep both in step. */
    public fun setNow(target: Instant) {
        instant = target
    }

    /** Move the clock forward by [millis] milliseconds. */
    public fun advanceBy(millis: Long) {
        instant = instant.plus(millis.milliseconds)
    }
}

/**
 * The environment a [runStoreTest] body receives. [scope] is the test's
 * `backgroundScope` — pass it to the store under test so its intent-consumer
 * coroutine is auto-cancelled when the test ends (using the plain [TestScope]
 * would make `runTest` hang waiting on the never-completing consumer loop).
 * [clock] is a shared [FakeClock]; advance virtual time through [testScope].
 */
public class StoreTestEnv(
    public val testScope: TestScope,
    public val scope: CoroutineScope,
    public val clock: FakeClock,
)

/**
 * Builds a [StoreTestEnv] on the coroutines test runtime and runs [body] inside
 * it. Stores are constructed in the body with [StoreTestEnv.scope]; assert on
 * `state`/`effects` with Turbine's `.test { }`.
 */
public fun runStoreTest(body: suspend StoreTestEnv.() -> Unit): TestResult =
    runTest {
        StoreTestEnv(testScope = this, scope = backgroundScope, clock = FakeClock()).body()
    }
