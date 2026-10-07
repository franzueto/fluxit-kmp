package com.fluxit.firebase.item

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * unit-testable proof of the `clearCompleted` chunk-size invariant
 * ([requireValidClearCompletedChunkSize]): item writes per batch must always leave room
 * for the one trailing list-counter-update write in the same
 * [com.google.firebase.firestore.WriteBatch], so `chunkSize + 1` must never exceed
 * Firestore's hard 500-write-per-batch cap ([MAX_BATCH_WRITES]).
 *
 * A plain JVM unit test - no [com.google.firebase.firestore.FirebaseFirestore] instance is
 * touched (it needs a live `FirebaseApp`/Android context this module doesn't have in a
 * unit test, which is exactly why this invariant was pulled out to a standalone,
 * SDK-object-free function rather than left only reachable through the repository's
 * constructor). This is deliberately not a contrived, unused-in-production pure "chunk
 * partitioner" function that models the whole multi-chunk loop: `clearCompleted`'s real
 * chunking is inherently query-driven (it re-queries "active and completed" at the top of
 * every iteration - see its own KDoc), not a precomputed plan a pure function could model
 * without duplicating that live loop. The multi-chunk behavior itself, and its
 * idempotent-on-retry property, are proven separately against a real Firestore emulator by
 * `FirestoreItemEmulatorIntegrationTest.clearCompletedSpansMultipleRealBatchesAndIsIdempotentOnRetry`.
 */
class AndroidFirebaseItemRepositoryChunkSizeTest {

    @Test
    fun acceptsTheDefaultChunkSizeAndLeavesHeadroomUnderTheHardBatchCap() {
        // Must not throw: the 400 default plus the trailing counter write is 401, well
        // under Firestore's 500-write-per-batch cap.
        requireValidClearCompletedChunkSize(DEFAULT_CLEAR_COMPLETED_CHUNK_SIZE)
    }

    @Test
    fun acceptsTheLargestChunkSizeThatStillLeavesRoomForTheTrailingCounterWrite() {
        // 499 item writes + 1 counter write == 500, exactly at (not over) the cap.
        requireValidClearCompletedChunkSize(MAX_BATCH_WRITES - 1)
    }

    @Test
    fun rejectsAChunkSizeThatWouldLeaveNoRoomForTheTrailingCounterWrite() {
        // 500 item writes + 1 counter write == 501, over the cap.
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
        // Proves the arithmetic itself (chunkSize + 1 <= cap), not just the hard-coded
        // production constant: with a hypothetical cap of 10, 9 is the largest valid
        // chunk size and 10 is already one too many.
        requireValidClearCompletedChunkSize(9, maxBatchWrites = 10)
        assertFailsWith<IllegalArgumentException> {
            requireValidClearCompletedChunkSize(10, maxBatchWrites = 10)
        }
    }
}
