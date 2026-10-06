package com.fluxit.firebase.item

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * FB-205 iOS counterpart of Android's `AndroidFirebaseItemRepositoryChunkSizeTest` -
 * identical test matrix, against [requireValidClearCompletedChunkSize] (this module's own
 * declaration; see [MAX_BATCH_WRITES]'s KDoc for why `androidMain`/`iosMain` each declare
 * this invariant separately rather than sharing one Kotlin declaration).
 *
 * A plain Kotlin/Native unit test - no [IosFirestoreItemBridge] is touched. The real
 * multi-chunk `clearCompleted` behavior, and its idempotent-on-retry property, are
 * exercised with a fake bridge in `IosFirebaseItemRepositoryTest` and, against a real
 * Firestore emulator, by `IosFirestoreItemIntegrationCheck`'s 5+5+4 three-batch proof.
 */
class IosFirebaseItemRepositoryChunkSizeTest {

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
