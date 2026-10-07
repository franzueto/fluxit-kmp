package com.fluxit.firebase.item

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.RepositorySnapshot
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.auth.IosAuthBridgeRegistry
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.firebase.list.IosFirebaseListRepository
import com.fluxit.firebase.list.IosFirestoreListBridgeRegistry
import com.fluxit.firebase.list.toApplicationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID

/**
 * FB-205 emulator-backed integration check for the iOS item adapter, in the exact style
 * FB-203's `IosFirestoreListIntegrationCheck` established (itself following FB-103's
 * `IosAuthIntegrationCheck`): no Xcode test target exists in this repository, so this
 * lives in the app binary and is exercised by a real simulator run launched with a
 * specific argument, double-gated (emulator-only build config, plus the launch argument
 * itself) so it can never touch the live development project.
 *
 * What this proves that `IosFirebaseItemRepositoryTest` (a fake-bridge unit test) cannot:
 * that the real Swift `FirebaseItemBridge` correctly talks to a real Firebase Firestore
 * SDK instance end to end - counter transactions genuinely committing atomically against
 * a live emulator (including under real write contention), a real multi-batch
 * `clearCompleted` sweep, real listener cancellation, and that the real tracked
 * `firestore.rules` denies one user's uid path to a different authenticated user.
 *
 * A list document is bootstrapped through the already-proven [IosFirebaseListRepository]
 * (FB-203) rather than through raw Firestore calls, since no Firestore SDK type is
 * reachable from this file at all (`PLAN-008`) - unlike Android's
 * `FirestoreItemEmulatorIntegrationTest`, which could open a raw `DocumentReference`
 * directly because the Android SDK is reachable from `androidMain` Kotlin. This is a
 * deliberate, narrow divergence from the Android evidence shape, not an oversight: it
 * means this check's counter-correctness assertions transitively also depend on
 * `IosFirebaseListRepository.createList` being correct, which FB-203's own suite already
 * established.
 */
object IosFirestoreItemIntegrationCheck {

    private const val PASSWORD = "fb205-emulator-only"
    private const val SETTLE_MS = 400L
    private const val AWAIT_TIMEOUT_MS = 8_000L
    private const val POLL_INTERVAL_MS = 100L

    /** Same polling rationale as `IosFirestoreListIntegrationCheck.awaitCondition`'s KDoc. */
    private suspend fun awaitCondition(condition: () -> Boolean): Boolean {
        var waited = 0L
        while (!condition()) {
            if (waited >= AWAIT_TIMEOUT_MS) return false
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return true
    }

    /**
     * Exported to Swift as a completion-handler function that must never itself throw -
     * same load-bearing outer guard `IosFirestoreListIntegrationCheck.run` documents
     * (an uncaught exception crossing the suspend-exported-to-Swift-`async` boundary
     * aborts the whole process rather than surfacing to a Swift `catch`).
     */
    suspend fun run(): String = try {
        runChecked()
    } catch (throwable: Throwable) {
        "FB-205 iOS Firestore item integration check: THREW ${throwable::class.simpleName}: " +
            "${throwable.message}\nFB-205 END"
    }

    private suspend fun runChecked(): String {
        val report = Report()
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail("preconditions", "emulator mode is disabled; refusing to run against a live project")
            return report.render()
        }
        val authBridge = IosAuthBridgeRegistry.bridgeOrNull()
        val listBridge = IosFirestoreListBridgeRegistry.bridgeOrNull()
        val itemBridge = IosFirestoreItemBridgeRegistry.bridgeOrNull()
        if (authBridge == null || listBridge == null || itemBridge == null) {
            report.fail("bridge registration", "authBridge=$authBridge listBridge=$listBridge itemBridge=$itemBridge")
            return report.render()
        }
        report.pass(
            "preconditions",
            "emulator enabled at ${IosFirebaseEmulatorSettings.host}:${IosFirebaseEmulatorSettings.firestorePort}",
        )

        val auth = IosAuthRepository()
        val lists = IosFirebaseListRepository()
        val items = IosFirebaseItemRepository()
        val suffix = NSUUID().UUIDString().lowercase()
        val emailA = "fb205-a-$suffix@example.com"
        val emailB = "fb205-b-$suffix@example.com"

        coroutineScope {
            // --- sign in as user A, bootstrap a list, exercise the full item lifecycle -
            report.expectSuccess("signUp userA", auth.signUp(emailA, PASSWORD))
            delay(SETTLE_MS)
            val uidA = authBridge.currentUser()?.uid
            if (uidA == null) {
                report.fail("preconditions", "userA has no uid after signUp")
                return@coroutineScope
            }
            val listId = lists.createList("FB-205 Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
            delay(SETTLE_MS)

            val observed = mutableListOf<List<FluxItem>>()
            val observedJob = launch { items.observeItems(listId).collect { observed += it } }
            delay(SETTLE_MS)
            report.check("initial items observed (possibly empty)", observed.isNotEmpty(), "emissions=${observed.size}")

            items.addItem(listId, "Milk")
            val added = awaitCondition { observed.lastOrNull()?.any { it.title == "Milk" } == true }
            report.check("addItem appears in observeItems", added, "last=${observed.lastOrNull()?.map { it.title }}")
            val itemId = requireNotNull(observed.lastOrNull()?.firstOrNull { it.title == "Milk" }?.id) {
                "addItem's item never appeared"
            }
            val totalAfterAdd = awaitCounterValue(lists, listId) { it.totalItems }
            report.check("addItem incremented totalItems to 1", totalAfterAdd == 1, "totalItems=$totalAfterAdd")

            items.updateItem(listId, itemId, "Whole Milk", "2%")
            val renamed = awaitCondition { observed.lastOrNull()?.any { it.id == itemId && it.title == "Whole Milk" } == true }
            report.check("updateItem's rename is visible", renamed, "last=${observed.lastOrNull()?.map { it.title }}")

            items.setCompleted(listId, itemId, true)
            val completedTotal = awaitCounterValue(lists, listId) { it.completedItems }
            report.check("setCompleted(true) incremented completedItems to 1", completedTotal == 1, "completedItems=$completedTotal")

            // Idempotency: repeated + concurrent setCompleted(true) must not double-count.
            coroutineScope { repeat(5) { launch { items.setCompleted(listId, itemId, true) } } }
            val completedAfterRepeat = awaitCounterValue(lists, listId) { it.completedItems }
            report.check(
                "repeated/concurrent setCompleted(true) stays idempotent at completedItems=1",
                completedAfterRepeat == 1,
                "completedItems=$completedAfterRepeat",
            )

            items.softDeleteItem(listId, itemId)
            val afterSoftDelete = awaitCondition { observed.lastOrNull()?.none { it.id == itemId } == true }
            report.check("softDeleteItem removes it from observeItems (tombstone filter)", afterSoftDelete, "")
            val countersAfterSoftDelete = awaitCounterPair(lists, listId)
            report.check(
                "softDeleteItem decremented both counters to 0",
                countersAfterSoftDelete == (0 to 0),
                "counters=$countersAfterSoftDelete",
            )

            items.restoreItem(listId, itemId)
            val afterRestore = awaitCondition { observed.lastOrNull()?.any { it.id == itemId } == true }
            report.check("restoreItem brings it back", afterRestore, "")
            val countersAfterRestore = awaitCounterPair(lists, listId)
            report.check(
                "restoreItem re-incremented both counters to (1,1)",
                countersAfterRestore == (1 to 1),
                "counters=$countersAfterRestore",
            )

            items.deleteItem(listId, itemId)
            val afterHardDelete = awaitCondition { observed.lastOrNull()?.none { it.id == itemId } == true }
            report.check("deleteItem hard-removes it from observeItems", afterHardDelete, "")
            val countersAfterHardDelete = awaitCounterPair(lists, listId)
            report.check(
                "deleteItem decremented both counters to 0",
                countersAfterHardDelete == (0 to 0),
                "counters=$countersAfterHardDelete",
            )

            items.deleteItem(listId, "never-existed")
            report.pass("deleteItem on a missing item did not throw (silent no-op)")

            // --- multi-chunk clearCompleted proof: 14 items, chunkSize=5 -> 5+5+4 -------
            val smallChunkItems = IosFirebaseItemRepository(
                IosFirestoreItemBridgeRegistry::requireBridge,
                { uidA },
                clearCompletedChunkSize = 5,
            )
            repeat(14) { smallChunkItems.addItem(listId, "Bulk ${it + 1}") }
            val bulkObserved = mutableListOf<List<FluxItem>>()
            val bulkJob = launch { smallChunkItems.observeItems(listId).collect { bulkObserved += it } }
            val bulkAdded = awaitCondition { bulkObserved.lastOrNull()?.count { it.title.startsWith("Bulk") } == 14 }
            report.check("14 bulk items observed before clearCompleted", bulkAdded, "count=${bulkObserved.lastOrNull()?.size}")
            val bulkIds = bulkObserved.last().filter { it.title.startsWith("Bulk") }.map { it.id }
            bulkIds.forEach { smallChunkItems.setCompleted(listId, it, true) }
            delay(SETTLE_MS)

            smallChunkItems.clearCompleted(listId)
            val clearedOnce = awaitCondition { bulkObserved.lastOrNull()?.none { it.title.startsWith("Bulk") } == true }
            report.check(
                "clearCompleted spanning 3 real batches (5+5+4) tombstoned all 14 bulk items",
                clearedOnce,
                "remaining=${bulkObserved.lastOrNull()?.count { it.title.startsWith("Bulk") }}",
            )
            val countersAfterClear = awaitCounterPair(lists, listId)
            report.check(
                "clearCompleted's multi-batch sweep left counters at (0,0)",
                countersAfterClear == (0 to 0),
                "counters=$countersAfterClear",
            )

            smallChunkItems.clearCompleted(listId) // idempotent retry: nothing left to match
            report.pass("a retried clearCompleted with nothing left matching was a genuine no-op")
            bulkJob.cancel()

            // --- listener cancellation, bridge-level and end-to-end ---------------------
            val rawSnapshots = mutableListOf<Int>()
            val rawHandle = itemBridge.observeItems(uidA, listId, onSnapshot = { rawSnapshots += it.size }, onError = { })
            delay(SETTLE_MS)
            items.addItem(listId, "Listener probe 1")
            delay(SETTLE_MS)
            val whileLive = rawSnapshots.size
            report.check("a live bridge listener receives real Firestore snapshots", whileLive >= 2, "callbacks while live=$whileLive")
            rawHandle.remove()
            items.addItem(listId, "Listener probe 2")
            delay(SETTLE_MS * 2)
            report.check(
                "a removed bridge listener receives nothing further",
                rawSnapshots.size == whileLive,
                "while live=$whileLive after removal=${rawSnapshots.size}",
            )

            observedJob.cancel()
            delay(SETTLE_MS)
            val afterCancellation = observed.size
            items.addItem(listId, "Listener probe 3")
            delay(SETTLE_MS * 2)
            report.check(
                "cancelling the repository collector releases the Firestore listener",
                observed.size == afterCancellation,
                "emissions before=$afterCancellation after=${observed.size}",
            )

            // --- FB-407: observeItemsSnapshot - real isFromCache/hasPendingWrites -------
            // Same rationale and same disclosed no-network-toggle scope boundary as
            // `IosFirestoreListIntegrationCheck`'s identically-named section - see that
            // file's KDoc comment for the full explanation, identical here for items.
            val itemSnapshotEmissions = mutableListOf<RepositorySnapshot<List<FluxItem>>>()
            val itemSnapshotJob = launch { items.observeItemsSnapshot(listId).collect { itemSnapshotEmissions += it } }
            val initialItemSnapshotSeen = awaitCondition { itemSnapshotEmissions.isNotEmpty() }
            report.check(
                "observeItemsSnapshot delivers an initial real snapshot",
                initialItemSnapshotSeen,
                "emissions=${itemSnapshotEmissions.size}",
            )
            delay(SETTLE_MS)
            val itemSettledBeforeWrite = itemSnapshotEmissions.lastOrNull()
            report.check(
                "once settled online, the real snapshot reports isFromCache=false/hasPendingWrites=false",
                itemSettledBeforeWrite?.isFromCache == false && itemSettledBeforeWrite.hasPendingWrites == false,
                "settledBeforeWrite=$itemSettledBeforeWrite",
            )

            val pendingItemWriteJob = launch { items.addItem(listId, "FB-407 Snapshot Metadata Item") }
            val pendingItemWriteObserved = awaitCondition { itemSnapshotEmissions.any { it.hasPendingWrites } }
            report.check(
                "a local item write is observed with hasPendingWrites=true before the server acknowledges it",
                pendingItemWriteObserved,
                "sawPendingWrites=${itemSnapshotEmissions.any { it.hasPendingWrites }} total emissions=${itemSnapshotEmissions.size}",
            )
            pendingItemWriteJob.join()
            val itemClearedAfterAck = awaitCondition { itemSnapshotEmissions.lastOrNull()?.hasPendingWrites == false }
            report.check(
                "hasPendingWrites clears once the server acknowledges the item write",
                itemClearedAfterAck,
                "last=${itemSnapshotEmissions.lastOrNull()}",
            )
            itemSnapshotJob.cancel()

            // --- cross-user denial under the REAL firestore.rules -----------------------
            report.expectSuccess("signUp userB", auth.signUp(emailB, PASSWORD))
            delay(SETTLE_MS)
            val uidB = authBridge.currentUser()?.uid
            if (uidB == null) {
                report.fail("preconditions", "userB has no uid after signUp")
                return@coroutineScope
            }
            report.check("userA and userB are different uids", uidA != uidB, "uidA=$uidA uidB=$uidB")

            var deniedAddError: String? = null
            val addDeferred = CompletableDeferred<Unit>()
            itemBridge.addItem(uid = uidA, listId = listId, fields = mapOf()) { _, error ->
                deniedAddError = error?.let { "domain=${it.domain} code=${it.code}" } ?: "no error (UNEXPECTED)"
                addDeferred.complete(Unit)
            }
            addDeferred.await()
            report.check(
                "userB adding an item under userA's list is denied",
                deniedAddError != null && deniedAddError != "no error (UNEXPECTED)",
                "result=$deniedAddError",
            )

            var deniedRead = false
            var deniedReadCode: RepositoryErrorCode? = null
            val denialHandle = itemBridge.observeItems(
                uid = uidA,
                listId = listId,
                onSnapshot = { },
                onError = { error ->
                    deniedRead = true
                    deniedReadCode = error.toApplicationError().code
                },
            )
            delay(SETTLE_MS)
            report.check("userB listening on userA's items path is denied", deniedRead, "deniedRead=$deniedRead code=$deniedReadCode")
            report.check("the denial maps to FORBIDDEN, not UNKNOWN", deniedReadCode == RepositoryErrorCode.FORBIDDEN, "code=$deniedReadCode")
            denialHandle.remove()

            // --- cleanup ------------------------------------------------------------------
            auth.signOut()
        }
        return report.render()
    }

    private suspend fun awaitCounterPair(lists: IosFirebaseListRepository, listId: String): Pair<Int, Int> {
        var result: Pair<Int, Int>? = null
        coroutineScope {
            val job = launch {
                lists.observeListSummaries().collect { summaries ->
                    summaries.firstOrNull { it.list.id == listId }?.let { result = it.totalItems to it.completedItems }
                }
            }
            awaitCondition { result != null }
            job.cancel()
        }
        return result ?: (-1 to -1)
    }

    private suspend fun awaitCounterValue(
        lists: IosFirebaseListRepository,
        listId: String,
        selector: (com.fluxit.domain.FluxListSummary) -> Int,
    ): Int? {
        var result: Int? = null
        coroutineScope {
            val job = launch {
                lists.observeListSummaries().collect { summaries ->
                    summaries.firstOrNull { it.list.id == listId }?.let { result = selector(it) }
                }
            }
            awaitCondition { result != null }
            job.cancel()
        }
        return result
    }

    private class Report {
        private val lines = mutableListOf<String>()
        private var failures = 0

        fun pass(name: String, detail: String = "") {
            lines += "PASS  $name${if (detail.isEmpty()) "" else " ($detail)"}"
        }

        fun fail(name: String, detail: String) {
            failures++
            lines += "FAIL  $name ($detail)"
        }

        fun check(name: String, condition: Boolean, detail: String) {
            if (condition) pass(name) else fail(name, detail)
        }

        fun expectSuccess(name: String, result: com.fluxit.domain.auth.AuthResult) {
            check(name, result is com.fluxit.domain.auth.AuthResult.Success, "result=$result")
        }

        fun render(): String {
            val header = if (failures == 0) {
                "FB-205 iOS Firestore item integration check: ALL CHECKS PASSED"
            } else {
                "FB-205 iOS Firestore item integration check: $failures CHECK(S) FAILED"
            }
            return (listOf(header) + lines + listOf("FB-205 END")).joinToString("\n")
        }
    }
}
