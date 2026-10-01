package com.fluxit.parity

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.fluxit.config.FirebaseDevFlags
import com.fluxit.data.*
import com.fluxit.domain.*
import com.fluxit.domain.auth.*
import com.fluxit.firebase.IosFirebaseEmulatorSettings
import com.fluxit.firebase.item.IosFirebaseItemRepository
import com.fluxit.firebase.list.IosFirebaseListRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.mp.KoinPlatform
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.NSFileManager
import kotlinx.cinterop.ExperimentalForeignApi

/** Only compiled with fluxit.parity.enabled=true. Swift adds an independent launch gate. */
@OptIn(ExperimentalForeignApi::class)
object IosFirebaseRoomParityCheck {
    /** Swift disables the real default SDK connection before entry and reenables it
     * through this callback after local cache/pending assertions; no production bridge API. */
    suspend fun runOffline(email: String, password: String, marker: String, reconnect: () -> Unit): String {
        var stage = "preconditions"
        return try {
            check(IosFirebaseEmulatorSettings.enabled && FirebaseDevFlags.USE_FIREBASE_REPOSITORIES)
            var attempts = 0
            while (runCatching { KoinPlatform.getKoin() }.isFailure && attempts++ < 100) delay(100)
            val graph = KoinPlatform.getKoin()
            val auth = graph.get<AuthRepository>()
            check(auth.signIn(email, password) == AuthResult.Success)
            val lists = graph.get<ListRepository>()
            val items = graph.get<ItemRepository>()
            coroutineScope {
                stage = "cached-read"
                withTimeout(15_000) { lists.observeListSummariesSnapshot().first { it.isFromCache } }
                val writes = mutableListOf<Deferred<*>>()
                try {
                    stage = "pending-list"
                    val create = async { lists.createList("$marker-offline", ListIcon.CART, ListColor.PRIMARY_BLUE) }
                    writes += create
                    val pending = withTimeout(15_000) { lists.observeListSummariesSnapshot().first { snapshot ->
                        snapshot.hasPendingWrites && snapshot.value.any { it.list.name == "$marker-offline" }
                    } }
                    check(!create.isCompleted)
                    val listId = pending.value.single { it.list.name == "$marker-offline" }.list.id
                    check(withTimeout(15_000) { lists.observeList(listId).first { it != null } }?.name == "$marker-offline")
                    stage = "pending-item"
                    val add = async { items.addItem(listId, "offline item") }
                    writes += add
                    val pendingItems = withTimeout(15_000) { items.observeItemsSnapshot(listId).first {
                        it.hasPendingWrites && it.value.any { i -> i.title == "offline item" }
                    } }
                    check(!add.isCompleted)
                    val itemId = pendingItems.value.single().id
                    check(withTimeout(15_000) { items.observeItem(listId, itemId).first { it != null } }?.title == "offline item")
                    writes += async { items.updateItem(listId, itemId, "offline edited", "offline description") }
                    withTimeout(15_000) { items.observeItem(listId, itemId).first { it?.title == "offline edited" } }
                    stage = "pending-tombstone-undo"
                    writes += async { lists.softDeleteList(listId) }
                    withTimeout(15_000) { lists.observeList(listId).first { it == null } }
                    writes += async { lists.restoreList(listId) }
                    withTimeout(15_000) { lists.observeList(listId).first { it != null } }
                } finally { reconnect() }
                stage = "server-ack"
                writes.forEach { withTimeout(15_000) { it.await() } }
                withTimeout(15_000) { lists.observeListSummariesSnapshot().first { snapshot ->
                    !snapshot.isFromCache && !snapshot.hasPendingWrites && snapshot.value.any {
                        it.list.name == "$marker-offline" && it.totalItems == 1 && it.completedItems == 0
                    }
                } }
            }
            "FB-701 iOS offline PASS cached-read pending-list-item edit-tombstone-undo reconnect-server-ack"
        } catch (error: Throwable) {
            reconnect()
            "FB-701 iOS offline FAILED stage=$stage ${error::class.simpleName}"
        }
    }

    suspend fun run(email: String, password: String, marker: String): String = try {
        check(IosFirebaseEmulatorSettings.enabled && FirebaseDevFlags.USE_FIREBASE_REPOSITORIES)
        // Wait for ContentView to construct the real app graph; do not create an alternate graph.
        var attempts = 0
        while (runCatching { KoinPlatform.getKoin() }.isFailure && attempts++ < 100) delay(100)
        val graph = KoinPlatform.getKoin()
        val auth = graph.get<AuthRepository>()
        check(auth.signIn(email, password) == AuthResult.Success)
        val uid = withTimeout(15_000) { auth.session.first { it is AuthSession.Authenticated } }.uidOrNull!!
        val lists = graph.get<ListRepository>()
        val items = graph.get<ItemRepository>()
        check(lists is IosFirebaseListRepository && items is IosFirebaseItemRepository)
        val path = NSTemporaryDirectory() + "fb701-" + NSUUID().UUIDString + ".db"
        val db = Room.databaseBuilder<FluxItDatabase>(path).setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default).build()
        try {
            val room = RepositoryParityScenario.run(RoomListRepository(db), RoomItemRepository(db)) { "fixture/$it" }
            val firebase = RepositoryParityScenario.run(lists, items) { "users/$uid/items/$it/parity.jpg" }
            if (room != firebase) {
                println("FB-701 parity Room trace=$room")
                println("FB-701 parity Firebase trace=$firebase")
                error("Room/Firebase observable trace differs")
            }
            println("FB-701 iOS parity PASS checkpoints=${firebase.size} real-app-DI=Firebase")
            coroutineScope {
                val shared = lists.createList("$marker-ready", ListIcon.CART, ListColor.PRIMARY_BLUE)
                val seen = MutableStateFlow<FluxList?>(null)
                val listener = launch { lists.observeList(shared).collect { seen.value = it } }
                try {
                    withTimeout(60_000) { seen.first { it?.name == "$marker-android" } }
                    lists.updateList(shared, "$marker-ios", ListIcon.FOOD, ListColor.ROSE)
                    val item = withTimeout(60_000) { items.observeItems(shared).first { it.size == 1 } }.single()
                    items.setCompleted(shared, item.id, true)
                    val counts = withTimeout(15_000) { lists.observeListSummaries().first { r -> r.any { it.list.id == shared && it.completedItems == 1 } } }
                        .first { it.list.id == shared }
                    check(counts.totalItems == 1)
                    val photos = graph.get<PhotoStorage>()
                    val androidPhoto = withTimeout(60_000) { items.observeItem(shared, item.id).first { it?.photoRef != null } }!!.photoRef!!
                    check((photos.loadPhoto(androidPhoto) as? PhotoContent.Bytes)?.bytes?.contentEquals(parityPhotoBytes) == true)
                    val applePhoto = photos.uploadPhoto(item.id, parityPhotoBytes)
                    items.setPhotoRef(shared, item.id, applePhoto)
                    photos.deletePhoto(androidPhoto)
                    check(photos.loadPhoto(androidPhoto) == null)
                    withTimeout(60_000) { items.observeItem(shared, item.id).first { it?.photoRef == null } }
                    println("FB-701 iOS cross-platform-photo PASS bytes=equal replacements=1 deleted=1")
                    println("FB-701 iOS realtime PASS Android-list-edit Android-item-create counters=1/1")
                } finally { listener.cancelAndJoin() }
            }
            "FB-701 iOS ALL CHECKS PASSED\nFB-701 END"
        } finally {
            db.close()
            listOf(path, "$path-wal", "$path-shm").forEach { NSFileManager.defaultManager.removeItemAtPath(it, null) }
            auth.signOut()
        }
    } catch (error: Throwable) { "FB-701 iOS FAILED ${error::class.simpleName}\nFB-701 END" }
}
