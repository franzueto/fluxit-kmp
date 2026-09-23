package com.fluxit.firebase.list

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.auth.IosAuthBridgeRegistry
import com.fluxit.firebase.auth.IosAuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID

/**
 * FB-203 emulator-backed integration check for the iOS list adapter, in the style
 * FB-103's `IosAuthIntegrationCheck` established: no Xcode test target exists in this
 * repository, so this lives in the app binary and is exercised by a real simulator run
 * launched with a specific argument, guarded twice (emulator-only build config, plus the
 * launch argument itself) so it can never touch the live development project.
 *
 * What this proves that `IosFirebaseListRepositoryTest` (a fake-bridge unit test) cannot:
 * that the real Swift `FirebaseListBridge` correctly talks to a real Firebase Firestore
 * SDK instance end to end, and - the FB-202 parity requirement this task calls out
 * explicitly - that the real, tracked `firestore.rules` genuinely denies one user's uid
 * path to a different authenticated user against a live emulator.
 *
 * This check earned its keep during development, not just after the fact: running it for
 * real against the emulator caught three defects no fake-bridge unit test could have -
 * (1) an `async throws` Swift implementation of a Kotlin completion-handler-shaped
 * protocol method compiles fine but aborts the process on a thrown error at runtime (see
 * `FirebaseListBridge.createList`'s KDoc, now a plain completion handler instead); (2) an
 * uncaught Kotlin exception crossing the suspend-exported-to-Swift-`async` boundary also
 * aborts rather than surfacing to Swift's `catch` (`run()`'s outer try/catch below); (3)
 * `Bool` bridging from *any* 0/1-valued `NSNumber`, which silently mis-decoded every
 * fresh list's `schemaVersion`/`totalItems`/`completedItems` as booleans (see
 * `FirebaseListBridge.decode`'s KDoc).
 */
object IosFirestoreListIntegrationCheck {

    private const val PASSWORD = "fb203-emulator-only"
    private const val SETTLE_MS = 400L
    private const val AWAIT_TIMEOUT_MS = 8_000L
    private const val POLL_INTERVAL_MS = 100L

    /**
     * Polls [condition] until it is true or [AWAIT_TIMEOUT_MS] elapses.
     *
     * A fixed [SETTLE_MS] delay is fine for local Auth state propagation (in-process,
     * effectively synchronous), but is not reliable for Firestore round trips even
     * against a local emulator: the first snapshot a listener sees after a write is
     * often the *locally cached, not-yet-server-acknowledged* write, whose pending
     * `serverTimestamp()` fields decode as [com.fluxit.data.remote.FirebaseValue.Null]
     * (a genuinely absent-server-value state Firestore itself reports, not a decoding
     * bug), which FB-201's `FirebaseDocumentMapper` correctly treats as malformed and
     * drops until the *next* snapshot arrives with the server-resolved timestamp. Mirrors
     * Android's `FirestoreListEmulatorIntegrationTest`'s `flow.first { predicate }`
     * idiom, which absorbs the same eventual-consistency window.
     */
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
     * Exported to Swift as a completion-handler function that must never itself throw
     * (mirrors [com.fluxit.firebase.auth.IosAuthIntegrationCheck.run]'s contract): every
     * failure this check finds is recorded into [Report] instead. A real, uncaught
     * `IllegalStateException`/`IllegalArgumentException` escaping across the Kotlin
     * suspend-exported-to-Swift-`async` boundary was reproduced during development to
     * abort the whole process (`Kotlin_ObjCExport_runCompletionFailure` ->
     * `terminateWithUnhandledException`) rather than surface as a Swift `catch`, so this
     * outer guard is load-bearing, not defensive-programming boilerplate.
     */
    suspend fun run(): String = try {
        runChecked()
    } catch (throwable: Throwable) {
        "FB-203 iOS Firestore list integration check: THREW ${throwable::class.simpleName}: " +
            "${throwable.message}\nFB-203 END"
    }

    private suspend fun runChecked(): String {
        val report = Report()
        if (!IosFirebaseEmulatorSettings.enabled) {
            report.fail("preconditions", "emulator mode is disabled; refusing to run against a live project")
            return report.render()
        }
        val authBridge = IosAuthBridgeRegistry.bridgeOrNull()
        val listBridge = IosFirestoreListBridgeRegistry.bridgeOrNull()
        if (authBridge == null || listBridge == null) {
            report.fail("bridge registration", "authBridge=$authBridge listBridge=$listBridge")
            return report.render()
        }
        report.pass(
            "preconditions",
            "emulator enabled at ${IosFirebaseEmulatorSettings.host}:${IosFirebaseEmulatorSettings.firestorePort}",
        )

        val auth = IosAuthRepository()
        val repository = IosFirebaseListRepository()
        val suffix = NSUUID().UUIDString().lowercase()
        val emailA = "fb203-a-$suffix@example.com"
        val emailB = "fb203-b-$suffix@example.com"

        coroutineScope {
            // --- sign in as user A, exercise the full list lifecycle ------------------
            report.expectSuccess("signUp userA", auth.signUp(emailA, PASSWORD))
            delay(SETTLE_MS)
            val uidA = authBridge.currentUser()?.uid
            if (uidA == null) {
                report.fail("preconditions", "userA has no uid after signUp")
                return@coroutineScope
            }

            val summaries = mutableListOf<List<FluxListSummary>>()
            val summariesJob = launch {
                repository.observeListSummaries().collect { summaries += it }
            }
            delay(SETTLE_MS)
            report.check("initial summaries observed (possibly empty)", summaries.isNotEmpty(), "emissions=${summaries.size}")

            val listId = repository.createList("FB-203 Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE)
            val created = awaitCondition {
                summaries.lastOrNull()?.any { it.list.id == listId && it.list.name == "FB-203 Groceries" } == true
            }
            report.check(
                "created list appears in observeListSummaries",
                created,
                "last=${summaries.lastOrNull()?.map { it.list.id to it.list.name }}",
            )

            repository.updateList(listId, "FB-203 Renamed", ListIcon.CART, ListColor.PRIMARY_BLUE)
            val renamed = awaitCondition {
                summaries.lastOrNull()?.any { it.list.id == listId && it.list.name == "FB-203 Renamed" } == true
            }
            report.check(
                "updateList's rename is visible",
                renamed,
                "last=${summaries.lastOrNull()?.map { it.list.id to it.list.name }}",
            )

            repository.softDeleteList(listId)
            val softDeleted = awaitCondition { summaries.lastOrNull()?.none { it.list.id == listId } == true }
            report.check(
                "softDeleteList removes it from summaries (tombstone filter)",
                softDeleted,
                "last=${summaries.lastOrNull()?.map { it.list.id }}",
            )

            repository.restoreList(listId)
            val restored = awaitCondition { summaries.lastOrNull()?.any { it.list.id == listId } == true }
            report.check(
                "restoreList brings it back",
                restored,
                "last=${summaries.lastOrNull()?.map { it.list.id }}",
            )

            val directRead = mutableListOf<com.fluxit.domain.FluxList?>()
            val directJob = launch { repository.observeList(listId).collect { directRead += it } }
            val directResolved = awaitCondition { directRead.lastOrNull()?.id == listId }
            report.check(
                "observeList resolves the same document",
                directResolved,
                "last=${directRead.lastOrNull()}",
            )
            directJob.cancel()

            // --- listener cancellation, bridge-level and end-to-end -------------------
            val rawSnapshots = mutableListOf<Int>()
            val rawHandle = listBridge.observeListSummaries(
                uid = uidA,
                onSnapshot = { rawSnapshots += it.size },
                onError = { },
            )
            delay(SETTLE_MS)
            repository.createList("FB-203 Second", ListIcon.CART, ListColor.PRIMARY_BLUE)
            delay(SETTLE_MS)
            val whileLive = rawSnapshots.size
            report.check(
                "a live bridge listener receives real Firestore snapshots",
                whileLive >= 2,
                "snapshot callbacks while live=$whileLive",
            )
            rawHandle.remove()
            repository.createList("FB-203 Third", ListIcon.CART, ListColor.PRIMARY_BLUE)
            delay(SETTLE_MS * 2)
            report.check(
                "a removed bridge listener receives nothing further",
                rawSnapshots.size == whileLive,
                "while live=$whileLive after removal=${rawSnapshots.size}",
            )

            summariesJob.cancel()
            delay(SETTLE_MS)
            val afterCancellation = summaries.size
            repository.createList("FB-203 Fourth", ListIcon.CART, ListColor.PRIMARY_BLUE)
            delay(SETTLE_MS * 2)
            report.check(
                "cancelling the repository collector releases the Firestore listener",
                summaries.size == afterCancellation,
                "emissions before=$afterCancellation after=${summaries.size}",
            )

            // --- cross-user denial under the REAL firestore.rules ---------------------
            report.expectSuccess("signUp userB", auth.signUp(emailB, PASSWORD))
            delay(SETTLE_MS)
            val uidB = authBridge.currentUser()?.uid
            if (uidB == null) {
                report.fail("preconditions", "userB has no uid after signUp")
                return@coroutineScope
            }
            report.check("userA and userB are different uids", uidA != uidB, "uidA=$uidA uidB=$uidB")

            // Bypasses the repository's own currentUid resolution on purpose: the bridge
            // takes uid as an explicit parameter, so userB's authenticated client can be
            // pointed at userA's path directly - exactly what a malicious/buggy client
            // would attempt, and exactly what the tracked firestore.rules must deny.
            var deniedRead = false
            var deniedReadCode: RepositoryErrorCode? = null
            val denialHandle = listBridge.observeListSummaries(
                uid = uidA,
                onSnapshot = { },
                onError = { error ->
                    deniedRead = true
                    deniedReadCode = error.toApplicationError().code
                },
            )
            delay(SETTLE_MS)
            report.check(
                "userB listening on userA's path is denied by the real firestore.rules",
                deniedRead,
                "deniedRead=$deniedRead mappedCode=$deniedReadCode",
            )
            report.check(
                "the denial maps to FORBIDDEN, not a generic UNKNOWN",
                deniedReadCode == RepositoryErrorCode.FORBIDDEN,
                "mappedCode=$deniedReadCode",
            )
            denialHandle.remove()

            var deniedCreateError: String? = null
            val createDeferred = CompletableDeferred<Unit>()
            listBridge.createList(
                uid = uidA,
                fields = mapOf(),
            ) { _, error ->
                deniedCreateError = error?.let { "domain=${it.domain} code=${it.code}" } ?: "no error (UNEXPECTED)"
                createDeferred.complete(Unit)
            }
            createDeferred.await()
            report.check(
                "userB creating a document under userA's path is denied",
                deniedCreateError != null && deniedCreateError != "no error (UNEXPECTED)",
                "result=$deniedCreateError",
            )

            // --- cleanup ----------------------------------------------------------------
            auth.signOut()
        }
        return report.render()
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

        fun note(detail: String) {
            lines += "INFO  $detail"
        }

        fun expectSuccess(name: String, result: com.fluxit.domain.auth.AuthResult) {
            check(name, result is com.fluxit.domain.auth.AuthResult.Success, "result=$result")
        }

        fun render(): String {
            val header = if (failures == 0) {
                "FB-203 iOS Firestore list integration check: ALL CHECKS PASSED"
            } else {
                "FB-203 iOS Firestore list integration check: $failures CHECK(S) FAILED"
            }
            return (listOf(header) + lines + listOf("FB-203 END")).joinToString("\n")
        }
    }
}
