package com.fluxit.parity

import com.fluxit.data.*
import com.fluxit.domain.*
import com.fluxit.domain.auth.*
import com.fluxit.domain.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface SessionCleanupProbe {
    suspend fun disableNetwork()
    suspend fun cachedName(path: String): String?
    suspend fun serverName(path: String): String?
    fun recordClient()
    fun verifyRecreated()
    suspend fun verifyStorageCancellation(uid: String)
}

class RetrySessionCleanup(private val delegate: SessionCleanup) : SessionCleanup by delegate {
    var failNextClear = false
    override suspend fun clear() {
        if (failNextClear) { failNextClear = false; error("injected local cleanup failure") }
        delegate.clear()
    }
}

/** Synthetic, pre-manifested owner paths only. Invokes real repositories and SDK lifecycle. */
object SessionCleanupScenario {
    suspend fun prepareRestart(auth: AuthRepository, cleanup: RetrySessionCleanup,
        lists: ListRepository, probe: SessionCleanupProbe, email: String, password: String): String = coroutineScope {
        println("FB-709 stage restart-signin")
        check(auth.signIn(email, password) == AuthResult.Success)
        // Mutations contain only changed fields; prime the full cached document
        // before testing a persisted queue across processes.
        withTimeout(15_000) { lists.observeListSummariesSnapshot().first { !it.isFromCache && it.value.any { row -> row.list.id == "fb709-list" } } }
        lists.updateList("fb709-list", "restart-server", ListIcon.CART, ListColor.PRIMARY_BLUE)
        check(probe.cachedName("users/${(auth.session.first() as AuthSession.Authenticated).user.uid}/lists/fb709-list") == "restart-server")
        println("FB-709 stage restart-full-cache")
        probe.disableNetwork()
        val write = async { lists.updateList("fb709-list", "restart-discarded", ListIcon.HOME, ListColor.EMERALD) }
        withTimeout(15_000) { lists.observeListSummariesSnapshot().first { it.hasPendingWrites && it.value.any { row -> row.list.name == "restart-discarded" } } }
        println("FB-709 stage fail-cleanup")
        println("FB-709 stage restart-pending-observed")
        cleanup.failNextClear = true
        check(auth.signOut() == AuthResult.Failure(AuthError.CleanupFailed) && cleanup.pending)
        write.join(); check(write.isCancelled)
        "restart PREPARED pending-cleanup=true queued-write=1"
    }

    suspend fun recoverRestart(auth: AuthRepository, cleanup: RetrySessionCleanup, probe: SessionCleanupProbe,
        email: String, password: String, uid: String): String {
        check(cleanup.pending)
        probe.recordClient()
        auth.restoreSession()
        check(auth.session.first() == AuthSession.SignedOut && !cleanup.pending)
        probe.verifyRecreated()
        check(probe.cachedName("users/$uid/lists/fb709-list") == null)
        println("FB-709 stage restart-signin")
        check(auth.signIn(email, password) == AuthResult.Success)
        check(probe.serverName("users/$uid/lists/fb709-list") == "restart-server")
        check(auth.signOut() == AuthResult.Success)
        return "restart RECOVERED cached-doc=absent queued-write=discarded signedout-before-signin=true"
    }

    suspend fun run(auth: AuthRepository, cleanup: RetrySessionCleanup, lists: ListRepository,
        items: ItemRepository, photos: PhotoStorage, probe: SessionCleanupProbe,
        emailA: String, emailB: String, password: String, uidA: String, uidB: String): String = coroutineScope {
        val listA = "fb709-list"; val itemA = "fb709-item"
        val pathA = "users/$uidA/lists/$listA"
        println("FB-709 stage signin-A")
        check(auth.signIn(emailA, password) == AuthResult.Success)
        check((auth.session.first() as AuthSession.Authenticated).user.uid == uidA)
        println("FB-709 stage server-list")
        check(probe.serverName(pathA) == "A-server")
        withTimeout(15_000) { lists.observeListSummariesSnapshot().first { !it.isFromCache && it.value.any { row -> row.list.name == "A-server" } } }
        check(probe.cachedName(pathA) == "A-server")
        withTimeout(15_000) { items.observeItemsSnapshot(listA).first { !it.isFromCache && it.value.any { item -> item.title == "item-server" } } }
        check(probe.cachedName("$pathA/items/$itemA") == "item-server")
        println("FB-709 stage queued-writes")
        probe.recordClient()
        var callbacks = 0
        val listener = launch { lists.observeList(listA).collect { callbacks++ } }
        withTimeout(15_000) { while (callbacks == 0) delay(20) }
        println("FB-709 stage listeners-registered")
        probe.disableNetwork()
        val listWrite = async { lists.updateList(listA, "discarded", ListIcon.HOME, ListColor.EMERALD) }
        val itemWrite = async { items.updateItem(listA, itemA, "discarded item", "queued") }
        withTimeout(15_000) { lists.observeListSummariesSnapshot().first { it.hasPendingWrites && it.value.any { row -> row.list.name == "discarded" } } }
        println("FB-709 stage list-pending")
        withTimeout(15_000) { items.observeItemsSnapshot(listA).first { it.hasPendingWrites && it.value.any { row -> row.title == "discarded item" } } }
        println("FB-709 stage item-pending")
        check(!listWrite.isCompleted && !itemWrite.isCompleted)
        println("FB-709 stage fail-cleanup")
        println("FB-709 stage restart-pending-observed")
        cleanup.failNextClear = true
        check(auth.signOut() == AuthResult.Failure(AuthError.CleanupFailed))
        check(cleanup.pending)
        check(auth.session.first() == AuthSession.ResolutionFailed(AuthError.CleanupFailed))
        listener.join(); listWrite.join(); itemWrite.join()
        check(listener.isCancelled && listWrite.isCancelled && itemWrite.isCancelled)
        val stoppedCallbacks = callbacks
        // restore must retry local teardown before touching credential/network validation.
        println("FB-709 stage retry-cleanup")
        auth.restoreSession()
        check(auth.session.first() == AuthSession.SignedOut && !cleanup.pending)
        probe.verifyRecreated()
        check(probe.cachedName(pathA) == null)
        check(probe.cachedName("$pathA/items/$itemA") == null)
        println("FB-709 stage signin-B")
        check(auth.signIn(emailB, password) == AuthResult.Success)
        check((auth.session.first() as AuthSession.Authenticated).user.uid == uidB)
        withTimeout(15_000) { lists.observeListSummariesSnapshot().first { !it.isFromCache && it.value.any { row -> row.list.name == "B-server" } } }
        probe.disableNetwork()
        check(probe.cachedName(pathA) == null)
        check(probe.cachedName("users/$uidB/lists/$listA") == "B-server")
        // Signing in A also starts with an empty cache, and must never resubmit the old writes.
        check(auth.signIn(emailA, password) == AuthResult.Success)
        probe.disableNetwork()
        check(probe.cachedName(pathA) == null)
        check(probe.cachedName("users/$uidB/lists/$listA") == null)
        check(auth.signIn(emailA, password) == AuthResult.Success)
        check(probe.serverName(pathA) == "A-server")
        check(probe.serverName("users/$uidA/lists/$listA/items/$itemA") == "item-server")
        withTimeout(15_000) { lists.observeListSummariesSnapshot().first { !it.isFromCache && it.value.any { row -> row.list.name == "A-server" } } }
        lists.updateList(listA, "A-reused", ListIcon.HOME, ListColor.EMERALD)
        withTimeout(15_000) { lists.observeList(listA).first { it?.name == "A-reused" } }
        check(callbacks == stoppedCallbacks)
        println("FB-709 stage photos")
        val ref = photos.uploadPhoto(itemA, parityPhotoBytes)
        items.setPhotoRef(listA, itemA, ref)
        check((photos.loadPhoto(ref) as PhotoContent.Bytes).bytes.contentEquals(parityPhotoBytes))
        photos.deletePhoto(ref)
        check(photos.loadPhoto(ref) == null)
        items.setPhotoRef(listA, itemA, null)
        probe.verifyStorageCancellation(uidA)
        check(auth.signOut() == AuthResult.Success)
        "cache-A-B-sameuser PASS queued-writes=2-discarded listeners=cancelled retry=real-sdk realtime-photo=usable Storage-upload-download-cancellation=verified"
    }
}
