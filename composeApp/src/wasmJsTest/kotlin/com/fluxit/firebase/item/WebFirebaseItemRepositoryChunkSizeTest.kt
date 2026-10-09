package com.fluxit.firebase.item

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The clear-completed chunk-size invariant (room for the trailing counter write under
 * Firestore's 500-writes-per-batch cap), ported from the iOS test. No bridge involved.
 */
class WebFirebaseItemRepositoryChunkSizeTest {

    @Test
    fun acceptsTheDefaultChunkSizeAndLeavesHeadroomUnderTheHardBatchCap() {
        requireValidClearCompletedChunkSize(DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE)
    }

    @Test
    fun acceptsTheLargestChunkSizeThatStillLeavesRoomForTheTrailingCounterWrite() {
        requireValidClearCompletedChunkSize(MAX_BATCH_WRITES - 1)
    }

    @Test
    fun rejectsAChunkSizeThatWouldLeaveNoRoomForTheTrailingCounterWrite() {
        assertFailsWith<IllegalArgumentException> {
            requireValidClearCompletedChunkSize(MAX_BATCH_WRITES)
        }
    }

    @Test
    fun rejectsAZeroChunkSize() {
        assertFailsWith<IllegalArgumentException> {
            requireValidClearCompletedChunkSize(0)
        }
    }

    @Test
    fun rejectsANegativeChunkSize() {
        assertFailsWith<IllegalArgumentException> {
            requireValidClearCompletedChunkSize(-1)
        }
    }

    @Test
    fun theInvariantScalesWithAnInjectedHypotheticalBatchCap() {
        requireValidClearCompletedChunkSize(9, maxBatchWrites = 10)
        assertFailsWith<IllegalArgumentException> {
            requireValidClearCompletedChunkSize(10, maxBatchWrites = 10)
        }
    }
}
