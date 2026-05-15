package dev.franzueto.fluxit.core.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResultExtTest {
    @Test
    fun `andThen chains a successful transform`() {
        val result = Result.success(2).andThen { Result.success(it * 3) }

        assertEquals(6, result.getOrThrow())
    }

    @Test
    fun `andThen short-circuits on initial failure`() {
        val boom = IllegalStateException("upstream")
        var transformCalled = false

        val result =
            Result.failure<Int>(boom).andThen<Int, Int> {
                transformCalled = true
                Result.success(it * 3)
            }

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
        assertEquals(false, transformCalled)
    }

    @Test
    fun `andThen propagates failure returned by the transform`() {
        val boom = IllegalArgumentException("downstream")

        val result = Result.success(2).andThen<Int, Int> { Result.failure(boom) }

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }
}
