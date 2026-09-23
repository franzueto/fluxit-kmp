package com.fluxit.firebase.crossclient

import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.domain.FluxItem
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.auth.IosAuthBridgeRegistry
import com.fluxit.firebase.auth.IosAuthRepository
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.item.IosFirestoreItemBridgeRegistry
import com.fluxit.firebase.list.IosFirebaseListRepository
import com.fluxit.firebase.list.IosFirestoreListBridgeRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID

/**
 * FB-206 emulator-backed cross-client checks for the iOS Firestore adapters: counter
 * consistency, conflict (field-level LWW per `DEC-003d`), malformed-document (no
 * `valueOf` crash), and reconnect, against the same real Firestore emulator
 * `IosFirestoreListIntegrationCheck` (FB-203) and `IosFirestoreItemIntegrationCheck`
 * (FB-205) exercise. Same no-Xcode-test-target rationale as those two: this lives in the
 * app binary, double-gated (emulator-only build config plus a dedicated launch
 * argument), exercised by a real simulator run.
 *
 * **"Cross-client" on iOS, and its one honest limitation, stated up front:** unlike
 * Android (which can open several independently named [com.google.firebase.FirebaseApp]
 * instances, each with its own Firestore connection/local cache/network state - see
 * `com.fluxit.firebase.item.CrossClientItemEmulatorIntegrationTest`), this app registers
 * exactly one Swift [com.fluxit.firebase.list.IosFirestoreListBridge]/
 * [com.fluxit.firebase.item.IosFirestoreItemBridge] implementation against the single
 * default `FirebaseApp` (`FirebaseBootstrap.start()`), and neither bridge protocol
 * exposes a network on/off toggle. Two [IosFirebaseListRepository]/
 * [IosFirebaseItemRepository] instances below are therefore two independent *Kotlin
 * objects* issuing calls through the *same* underlying SDK connection - a real,
 * meaningful "two clients of the same account" simulation for counter
 * consistency/conflict/malformed-document/listener-resync, but **not** independent
 * enough to prove genuine offline-queue (pending-write) isolation the way the Android
 * suite does. Adding a network-toggle bridge method would mean changing the production
 * `IosFirestoreListBridge`/`IosFirestoreItemBridge` protocols and their Swift
 * implementations - explicitly out of this task's scope (see the task brief: production
 * repository/bridge changes are for bug fixes found while testing, not new capability).
 * This check therefore intentionally records the pending-write category as a documented,
 * non-blocking iOS evidence gap ([Report.skip]) rather than silently claiming coverage
 * it does not have - see the check's own trailing note.
 */
object IosFirestoreCrossClientIntegrationCheck {

    private const val PASSWORD = "fb206-emulator-only"
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
     * same load-bearing outer guard `IosFirestoreItemIntegrationCheck.run`'s KDoc
     * documents.
     */
    suspend fun run(): String = try {
        runChecked()
    } catch (throwable: Throwable) {
        "FB-206 iOS cross-client integration check: THREW ${throwable::class.simpleName}: " +
            "${throwable.message}\nFB-206 END"
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
        val clientAList = IosFirebaseListRepository()
        val clientBList = IosFirebaseListRepository()
        val clientAItems = IosFirebaseItemRepository()
        val clientBItems = IosFirebaseItemRepository()
        val suffix = NSUUID().UUIDString().lowercase()
        val email = "fb206-$suffix@example.com"

        coroutineScope {
            report.expectSuccess("signUp", auth.signUp(email, PASSWORD))
            delay(SETTLE_MS)
            val uid = authBridge.currentUser()?.uid
            if (uid == null) {
                report.fail("preconditions", "signed-up user has no uid")
                return@coroutineScope
            }
            val listId = clientAList.createList("FB-206 Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
            delay(SETTLE_MS)

            // --- cross-client counter consistency -----------------------------------
            clientAItems.addItem(listId, "Milk")
            val afterAdd = awaitCounterValue(clientAList, listId) { it.totalItems }
            val itemId = requireNotNull(
                withTimeoutObserveFirst(clientAItems, listId) { items -> items.firstOrNull { it.title == "Milk" } }?.id
            ) { "addItem's item never appeared" }
            report.check("client A's addItem incremented totalItems to 1", afterAdd == 1, "totalItems=$afterAdd")

            coroutineScope {
                launch { clientAItems.setCompleted(listId, itemId, true) }
                launch { clientBItems.setCompleted(listId, itemId, true) }
            }
            val completedAfterRace = awaitCounterValue(clientAList, listId) { it.completedItems }
            report.check(
                "concurrent setCompleted(true) from two independent client instances converges to one increment",
                completedAfterRace == 1,
                "completedItems=$completedAfterRace",
            )

            coroutineScope {
                launch { clientAItems.addItem(listId, "From client A") }
                launch { clientBItems.addItem(listId, "From client B") }
            }
            var totalAfterBothAdd: Int? = null
            var waited = 0L
            while (totalAfterBothAdd != 3 && waited < AWAIT_TIMEOUT_MS) {
                totalAfterBothAdd = readTotals(clientAList, listId)?.first
                if (totalAfterBothAdd != 3) {
                    delay(POLL_INTERVAL_MS)
                    waited += POLL_INTERVAL_MS
                }
            }
            report.check(
                "concurrent addItem from both clients both incremented totalItems",
                totalAfterBothAdd == 3,
                "totalItems=$totalAfterBothAdd",
            )

            // --- conflict: DEC-003d field-level LWW ---------------------------------
            coroutineScope {
                launch { clientAItems.updateItem(listId, itemId, "Whole Milk", "2%") }
                launch { clientBItems.setPhotoPath(listId, itemId, "users/$uid/items/$itemId/photo-1") }
            }
            val afterMerge = withTimeoutObserveFirst(clientAItems, listId) { items -> items.firstOrNull { it.id == itemId } }
            report.check(
                "different-field concurrent patches (title+description vs photoRef) merge, neither clobbers the other",
                afterMerge?.title == "Whole Milk" && afterMerge.description == "2%" && afterMerge.photoPath == "users/$uid/items/$itemId/photo-1",
                "item=$afterMerge",
            )

            clientAItems.updateItem(listId, itemId, "Client A's title", "2%")
            clientBItems.updateItem(listId, itemId, "Client B's title", "2%") // strictly later
            val afterLww = withTimeoutObserveFirst(clientAItems, listId) { items -> items.firstOrNull { it.id == itemId } }
            report.check(
                "a same-field collision resolves to the later writer silently",
                afterLww?.title == "Client B's title",
                "title=${afterLww?.title}",
            )

            // --- malformed-document: no valueOf/decode crash -------------------------
            val malformedItemFields = mapOf(
                FirebaseSchema.Fields.LIST_ID to FirebaseValue.Text(listId),
                FirebaseSchema.Fields.TITLE to FirebaseValue.Text("Malformed"),
                FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Null,
                // Wrong type on purpose: a real Bool field written as Text - exactly the
                // "unknown/invalid field value" class the plan's Phase 2 note warns about.
                FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Text("not-a-boolean"),
                FirebaseSchema.Fields.PHOTO_REF to FirebaseValue.Null,
                FirebaseSchema.Fields.CREATED_AT to FirebaseValue.PendingServerTimestamp,
                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
                FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(FirebaseSchema.CURRENT_VERSION),
            )
            var malformedItemId: String? = null
            val malformedItemDeferred = CompletableDeferred<Unit>()
            itemBridge.addItem(uid, listId, malformedItemFields) { id, error ->
                malformedItemId = id
                report.check("raw malformed item write itself succeeded (SDK does not validate field types)", error == null, "error=$error")
                malformedItemDeferred.complete(Unit)
            }
            malformedItemDeferred.await()
            delay(SETTLE_MS)
            val itemsAfterMalformed = awaitObserveOnce(clientBItems, listId)
            report.check(
                "a malformed item (wrong-typed isCompleted) is excluded from observeItems without crashing",
                itemsAfterMalformed != null && itemsAfterMalformed.none { it.id == malformedItemId } && itemsAfterMalformed.any { it.title == "Whole Milk" || it.title == "Client B's title" },
                "malformedId=$malformedItemId items=${itemsAfterMalformed?.map { it.title }}",
            )

            val malformedListFields = mapOf(
                FirebaseSchema.Fields.NAME to FirebaseValue.Text("Malformed List"),
                // Unknown enum literal: the exact "no valueOf crash" concern.
                FirebaseSchema.Fields.ICON to FirebaseValue.Text("NOT_A_REAL_ICON_VALUE"),
                FirebaseSchema.Fields.COLOR to FirebaseValue.Text("PRIMARY_BLUE"),
                FirebaseSchema.Fields.CREATED_AT to FirebaseValue.PendingServerTimestamp,
                FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.PendingServerTimestamp,
                FirebaseSchema.Fields.DELETED_AT to FirebaseValue.Null,
                FirebaseSchema.Fields.TOTAL_ITEMS to FirebaseValue.Number(0),
                FirebaseSchema.Fields.COMPLETED_ITEMS to FirebaseValue.Number(0),
                FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(FirebaseSchema.CURRENT_VERSION),
            )
            var malformedListId: String? = null
            val malformedListDeferred = CompletableDeferred<Unit>()
            listBridge.createList(uid, malformedListFields) { id, error ->
                malformedListId = id
                report.check("raw malformed list write itself succeeded", error == null, "error=$error")
                malformedListDeferred.complete(Unit)
            }
            malformedListDeferred.await()
            delay(SETTLE_MS)
            val fallbackIcon = malformedListId?.let { id ->
                var result: ListIcon? = null
                val job = launch { clientBList.observeList(id).collect { list -> if (list != null) result = list.icon } }
                awaitCondition { result != null }
                job.cancel()
                result
            }
            report.check(
                "an unrecognized icon enum literal falls back to the default instead of crashing via valueOf",
                fallbackIcon == ListIcon.CART,
                "icon=$fallbackIcon",
            )

            // --- reconnect: a torn-down-and-recreated listener catches up -----------
            val firstObserved = mutableListOf<List<FluxItem>>()
            val firstJob = launch { clientBItems.observeItems(listId).collect { firstObserved += it } }
            awaitCondition { firstObserved.isNotEmpty() }
            firstJob.cancel()
            delay(SETTLE_MS)

            clientAItems.addItem(listId, "Added while client B was not observing")
            delay(SETTLE_MS)

            val resyncedObserved = mutableListOf<List<FluxItem>>()
            val resyncJob = launch { clientBItems.observeItems(listId).collect { resyncedObserved += it } }
            val resynced = awaitCondition {
                resyncedObserved.lastOrNull()?.any { it.title == "Added while client B was not observing" } == true
            }
            report.check(
                "a freshly (re-)registered client B listener immediately reflects changes made while it was not observing",
                resynced,
                "last=${resyncedObserved.lastOrNull()?.map { it.title }}",
            )
            resyncJob.cancel()

            report.skip(
                "pendingWrite: offline-queued write invisible to another client until reconnect",
                "iOS has one shared FirebaseApp/Firestore connection (PLAN-008) and neither " +
                    "IosFirestoreListBridge nor IosFirestoreItemBridge exposes a network on/off " +
                    "toggle; adding one is a production bridge-protocol change out of FB-206's " +
                    "scope (Android's CrossClientItemEmulatorIntegrationTest/" +
                    "CrossClientListEmulatorIntegrationTest cover this category with two " +
                    "independently named FirebaseApp instances instead). Flagged for the " +
                    "reviewer/orchestrator as a documented, non-blocking iOS evidence gap.",
            )

            auth.signOut()
        }
        return report.render()
    }

    private suspend fun withTimeoutObserveFirst(
        items: IosFirebaseItemRepository,
        listId: String,
        selector: (List<FluxItem>) -> FluxItem?,
    ): FluxItem? {
        var result: FluxItem? = null
        coroutineScope {
            val job = launch { items.observeItems(listId).collect { list -> selector(list)?.let { result = it } } }
            awaitCondition { result != null }
            job.cancel()
        }
        return result
    }

    private suspend fun awaitObserveOnce(items: IosFirebaseItemRepository, listId: String): List<FluxItem>? {
        var result: List<FluxItem>? = null
        coroutineScope {
            val job = launch { items.observeItems(listId).collect { result = it } }
            awaitCondition { result != null }
            job.cancel()
        }
        return result
    }

    private suspend fun readTotals(lists: IosFirebaseListRepository, listId: String): Pair<Int, Int>? =
        awaitCounterPair(lists, listId).let { if (it == (-1 to -1)) null else it }

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

        /** A category this check deliberately does not attempt, with a stated reason - never counted as a failure. */
        fun skip(name: String, reason: String) {
            lines += "SKIP  $name ($reason)"
        }

        fun expectSuccess(name: String, result: com.fluxit.domain.auth.AuthResult) {
            check(name, result is com.fluxit.domain.auth.AuthResult.Success, "result=$result")
        }

        fun render(): String {
            val header = if (failures == 0) {
                "FB-206 iOS cross-client integration check: ALL CHECKS PASSED"
            } else {
                "FB-206 iOS cross-client integration check: $failures CHECK(S) FAILED"
            }
            return (listOf(header) + lines + listOf("FB-206 END")).joinToString("\n")
        }
    }
}
