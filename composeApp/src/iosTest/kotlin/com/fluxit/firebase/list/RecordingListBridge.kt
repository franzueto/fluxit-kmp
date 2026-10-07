package com.fluxit.firebase.list

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import platform.Foundation.NSError

/** Builds a Foundation error without touching any Firebase symbol. */
internal fun firestoreError(code: Long, domain: String = FIREBASE_FIRESTORE_ERROR_DOMAIN): NSError =
    NSError.errorWithDomain(domain = domain, code = code, userInfo = null)

/**
 * Double for [IosFirestoreListBridge] that records listener registration/removal and
 * every write.
 *
 * The recording is the point: it is what makes "cancelling the collector releases the
 * underlying listener" an assertion instead of a promise, and what lets a test assert
 * that [createList] used a whole-document write while [updateListFields] only ever
 * carried the changed keys (`DEC-003d`/`DEC-003d-1`). This is the iOS counterpart of
 * FB-202's fakes and of `RecordingAuthBridge` (FB-103).
 */
internal class RecordingListBridge : IosFirestoreListBridge {

    var summariesAddCount: Int = 0
        private set
    var summariesRemoveCount: Int = 0
        private set
    private var summariesListener: ((List<IosFirestoreListDocument>) -> Unit)? = null
    private var summariesErrorListener: ((NSError) -> Unit)? = null

    /** `FB-407`: same recording shape as [summariesListener], for [observeListSummariesSnapshot]. */
    var summariesSnapshotAddCount: Int = 0
        private set
    var summariesSnapshotRemoveCount: Int = 0
        private set
    private var summariesSnapshotListener: ((IosFirestoreListSnapshot) -> Unit)? = null
    private var summariesSnapshotErrorListener: ((NSError) -> Unit)? = null

    var documentAddCount: Int = 0
        private set
    var documentRemoveCount: Int = 0
        private set
    private var documentListener: ((IosFirestoreListDocument?) -> Unit)? = null
    private var documentErrorListener: ((NSError) -> Unit)? = null

    data class CreateCall(val uid: String, val fields: Map<String, FirebaseValue>)
    data class UpdateCall(val uid: String, val listId: String, val fields: Map<String, FirebaseValue>)

    val createCalls: MutableList<CreateCall> = mutableListOf()
    val updateCalls: MutableList<UpdateCall> = mutableListOf()

    var createFailure: NSError? = null
    var createdIdOverride: String? = null
    var updateFailure: NSError? = null

    override fun observeListSummaries(
        uid: String,
        onSnapshot: (List<IosFirestoreListDocument>) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle {
        summariesAddCount++
        summariesListener = onSnapshot
        summariesErrorListener = onError
        return object : IosFirestoreListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                summariesRemoveCount++
                summariesListener = null
                summariesErrorListener = null
            }
        }
    }

    /** `FB-407`: mirrors [observeListSummaries]'s recording shape for the new snapshot-aware method. */
    override fun observeListSummariesSnapshot(
        uid: String,
        onSnapshot: (IosFirestoreListSnapshot) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle {
        summariesSnapshotAddCount++
        summariesSnapshotListener = onSnapshot
        summariesSnapshotErrorListener = onError
        return object : IosFirestoreListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                summariesSnapshotRemoveCount++
                summariesSnapshotListener = null
                summariesSnapshotErrorListener = null
            }
        }
    }

    override fun observeList(
        uid: String,
        listId: String,
        onSnapshot: (IosFirestoreListDocument?) -> Unit,
        onError: (NSError) -> Unit,
    ): IosFirestoreListenerHandle {
        documentAddCount++
        documentListener = onSnapshot
        documentErrorListener = onError
        return object : IosFirestoreListenerHandle {
            private var removed = false
            override fun remove() {
                if (removed) return
                removed = true
                documentRemoveCount++
                documentListener = null
                documentErrorListener = null
            }
        }
    }

    override fun createList(
        uid: String,
        fields: Map<String, FirebaseValue>,
        completion: (String?, NSError?) -> Unit,
    ) {
        createCalls += CreateCall(uid, fields)
        val failure = createFailure
        if (failure != null) {
            completion(null, failure)
        } else {
            completion(createdIdOverride ?: "generated-id-${createCalls.size}", null)
        }
    }

    override fun updateListFields(
        uid: String,
        listId: String,
        fields: Map<String, FirebaseValue>,
        completion: (NSError?) -> Unit,
    ) {
        updateCalls += UpdateCall(uid, listId, fields)
        completion(updateFailure)
    }

    /** Simulates the SDK delivering a fresh snapshot to a live `observeListSummaries` listener. */
    fun emitSummaries(documents: List<IosFirestoreListDocument>) {
        summariesListener?.invoke(documents)
    }

    fun emitSummariesError(error: NSError) {
        summariesErrorListener?.invoke(error)
    }

    /** `FB-407`: simulates the SDK delivering a fresh, metadata-carrying snapshot to a live `observeListSummariesSnapshot` listener. */
    fun emitSummariesSnapshot(documents: List<IosFirestoreListDocument>, isFromCache: Boolean = false, hasPendingWrites: Boolean = false) {
        summariesSnapshotListener?.invoke(IosFirestoreListSnapshot(documents, isFromCache, hasPendingWrites))
    }

    fun emitSummariesSnapshotError(error: NSError) {
        summariesSnapshotErrorListener?.invoke(error)
    }

    /** Simulates the SDK delivering a fresh snapshot to a live `observeList` listener. */
    fun emitDocument(document: IosFirestoreListDocument?) {
        documentListener?.invoke(document)
    }

    fun emitDocumentError(error: NSError) {
        documentErrorListener?.invoke(error)
    }

    val hasLiveSummariesListener: Boolean get() = summariesListener != null
    val hasLiveSummariesSnapshotListener: Boolean get() = summariesSnapshotListener != null
    val hasLiveDocumentListener: Boolean get() = documentListener != null
}

/** [CurrentUidProvider] test double that never touches the real Auth bridge. */
internal class FixedUidProvider(private val uid: String? = "uid-1") : CurrentUidProvider {
    override fun currentUid(): String = uid ?: throw RepositoryException(
        RepositoryErrorCode.SESSION_REQUIRED.toApplicationError(),
    )
}
