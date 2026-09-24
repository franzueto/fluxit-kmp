package com.fluxit

import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeListRepository : ListRepository {
    data class Row(val list: FluxList, val deleted: Boolean = false)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    private var counter = 0

    override fun observeListSummaries(): Flow<List<FluxListSummary>> =
        rows.map { all ->
            all.filter { !it.deleted }.map { FluxListSummary(it.list, 0, 0) }
        }

    override fun observeList(listId: String): Flow<FluxList?> =
        rows.map { all -> all.firstOrNull { it.list.id == listId && !it.deleted }?.list }

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String {
        val id = "list-${counter++}"
        rows.value += Row(FluxList(id, name, icon, color, counter.toDouble(), 0, 0))
        return id
    }

    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) {
        rows.value = rows.value.map {
            if (it.list.id == listId) it.copy(list = it.list.copy(name = name, icon = icon, color = color))
            else it
        }
    }

    override suspend fun softDeleteList(listId: String) {
        rows.value = rows.value.map { if (it.list.id == listId) it.copy(deleted = true) else it }
    }

    override suspend fun restoreList(listId: String) {
        rows.value = rows.value.map { if (it.list.id == listId) it.copy(deleted = false) else it }
    }

    override suspend fun purgeExpired() = Unit
}

class FakeItemRepository : ItemRepository {
    data class Row(val item: FluxItem, val deleted: Boolean = false)

    val rows = MutableStateFlow<List<Row>>(emptyList())
    private var counter = 0

    /**
     * `FB-306`: when non-null, every [setPhotoRef] call throws this instead of mutating
     * anything - lets `ItemDetailViewModelTest` inject a document-write failure partway
     * through a replace/remove without touching `FakePhotoStorage`, mirroring
     * [FakePhotoStorage.failUpload]/[FakePhotoStorage.failDelete]'s shape (also not
     * single-shot: a test sets it back to `null` itself before asserting a retry succeeds).
     */
    var failSetPhotoRef: Throwable? = null

    override fun observeItems(listId: String): Flow<List<FluxItem>> =
        rows.map { all -> all.filter { it.item.listId == listId && !it.deleted }.map { it.item } }

    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> =
        rows.map { all -> all.firstOrNull { it.item.listId == listId && it.item.id == itemId && !it.deleted }?.item }

    override suspend fun addItem(listId: String, title: String) {
        val id = "item-${counter++}"
        rows.value += Row(FluxItem(id, listId, title, null, false, null, counter.toDouble(), 0, 0))
    }

    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) {
        mutate(listId, itemId) { it.copy(title = title, description = description) }
    }

    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) {
        mutate(listId, itemId) { it.copy(isCompleted = completed) }
    }

    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?) {
        failSetPhotoRef?.let { throw it }
        mutate(listId, itemId) { it.copy(photoRef = photoRef) }
    }

    override suspend fun softDeleteItem(listId: String, itemId: String) {
        rows.value = rows.value.map { if (it.item.listId == listId && it.item.id == itemId) it.copy(deleted = true) else it }
    }

    override suspend fun restoreItem(listId: String, itemId: String) {
        rows.value = rows.value.map { if (it.item.listId == listId && it.item.id == itemId) it.copy(deleted = false) else it }
    }

    override suspend fun deleteItem(listId: String, itemId: String) {
        rows.value = rows.value.filter { it.item.listId != listId || it.item.id != itemId }
    }

    override suspend fun clearCompleted(listId: String) {
        rows.value = rows.value.map {
            if (it.item.listId == listId && it.item.isCompleted) it.copy(deleted = true) else it
        }
    }

    private fun mutate(listId: String, itemId: String, transform: (FluxItem) -> FluxItem) {
        rows.value = rows.value.map {
            if (it.item.listId == listId && it.item.id == itemId) it.copy(item = transform(it.item)) else it
        }
    }
}

/**
 * In-memory [PhotoStorage] test double (`FB-302`), with configurable failure injection at
 * upload/delete time so contract tests can exercise every documented failure branch of
 * `replacePhoto`'s safe-replace ordering without touching either platform's real local-file
 * stub. Every `photoRef` it mints is built by the same [FirebaseSchema.photoRef] production
 * uses, so a test asserting on the returned ref is asserting on the real PLAN-006 shape, not
 * a simplified stand-in.
 */
class FakePhotoStorage(private val uid: String = "fake-uid") : PhotoStorage {

    /** Bytes currently "stored" per photoRef; a ref is gone once absent from this map. */
    val objects = mutableMapOf<String, ByteArray>()
    private var counter = 0

    /** When non-null, the next [uploadPhoto] call throws this instead of storing anything. */
    var failUpload: Throwable? = null

    /** When non-null, every [deletePhoto] call throws this instead of removing the object. */
    var failDelete: Throwable? = null

    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String {
        failUpload?.let { throw it }
        val ref = FirebaseSchema.photoRef(uid, itemId, "photo-${counter++}")
        objects[ref] = bytes
        return ref
    }

    override suspend fun loadPhoto(photoRef: String): PhotoContent? =
        objects[photoRef]?.let { PhotoContent.Bytes(it) }

    override suspend fun deletePhoto(photoRef: String) {
        failDelete?.let { throw it }
        objects.remove(photoRef)
    }
}

/** In-memory [PhotoPicker] test double: returns [nextPick] once, then null until reset. */
class FakePhotoPicker : PhotoPicker {
    var nextPick: ByteArray? = null

    override suspend fun pickPhoto(): ByteArray? {
        val bytes = nextPick
        nextPick = null
        return bytes
    }
}
