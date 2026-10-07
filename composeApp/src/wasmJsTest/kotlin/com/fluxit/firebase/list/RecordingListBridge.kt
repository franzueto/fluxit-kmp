package com.fluxit.firebase.list

import com.fluxit.data.remote.FirebaseValue
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.firebase.WebBridgeError

/** A Firestore failure as the JS bridge reports it. */
internal fun firestoreError(code: String): WebBridgeError = WebBridgeError(code, "Firestore: $code")

/**
 * Double for [WebFirestoreListBridge] that records listener registration/removal and
 * every write.
 *
 * The recording is the point: it is what makes "cancelling the collector releases the
 * underlying listener" an assertion instead of a promise, and what lets a test assert
 * that [createList] used a whole-document write while [updateListFields] only ever
 * carried the changed keys. Ported from the iOS double of the same name.
 */
internal class RecordingListBridge : WebFirestoreListBridge {

    var summariesAddCount: Int = 0
        private set
    var summariesRemoveCount: Int = 0
        private set
    private var summariesListener: ((List<WebFirestoreListDocument>) -> Unit)? = null
    private var summariesErrorListener: ((WebBridgeError) -> Unit)? = null

    /** Same recording shape as [summariesListener], for [observeListSummariesSnapshot]. */
    var summariesSnapshotAddCount: Int = 0
        private set
    var summariesSnapshotRemoveCount: Int = 0
        private set
    private var summariesSnapshotListener: ((WebFirestoreListSnapshot) -> Unit)? = null
    private var summariesSnapshotErrorListener: ((WebBridgeError) -> Unit)? = null

    var documentAddCount: Int = 0
        private set
    var documentRemoveCount: Int = 0
        private set
    private var documentListener: ((WebFirestoreListDocument?) -> Unit)? = null
    private var documentErrorListener: ((WebBridgeError) -> Unit)? = null

    data class CreateCall(val uid: String, val fields: Map<String, FirebaseValue>)
    data class UpdateCall(val uid: String, val listId: String, val fields: Map<String, FirebaseValue>)

    val createCalls: MutableList<CreateCall> = mutableListOf()
    val updateCalls: MutableList<UpdateCall> = mutableListOf()

    var createFailure: WebBridgeError? = null
    var createdIdOverride: String? = null
    var updateFailure: WebBridgeError? = null

    override fun observeListSummaries(
        uid: String,
        onSnapshot: (List<WebFirestoreListDocument>) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        summariesAddCount++
        summariesListener = onSnapshot
        summariesErrorListener = onError
        return object : WebFirestoreListenerHandle {
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

    /** Mirrors [observeListSummaries]'s recording shape for the new snapshot-aware method. */
    override fun observeListSummariesSnapshot(
        uid: String,
        onSnapshot: (WebFirestoreListSnapshot) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        summariesSnapshotAddCount++
        summariesSnapshotListener = onSnapshot
        summariesSnapshotErrorListener = onError
        return object : WebFirestoreListenerHandle {
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
        onSnapshot: (WebFirestoreListDocument?) -> Unit,
        onError: (WebBridgeError) -> Unit,
    ): WebFirestoreListenerHandle {
        documentAddCount++
        documentListener = onSnapshot
        documentErrorListener = onError
        return object : WebFirestoreListenerHandle {
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
        completion: (String?, WebBridgeError?) -> Unit,
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
        completion: (WebBridgeError?) -> Unit,
    ) {
        updateCalls += UpdateCall(uid, listId, fields)
        completion(updateFailure)
    }

    /** Simulates the SDK delivering a fresh snapshot to a live `observeListSummaries` listener. */
    fun emitSummaries(documents: List<WebFirestoreListDocument>) {
        summariesListener?.invoke(documents)
    }

    fun emitSummariesError(error: WebBridgeError) {
        summariesErrorListener?.invoke(error)
    }

    /** Simulates the SDK delivering a fresh, metadata-carrying snapshot to a live `observeListSummariesSnapshot` listener. */
    fun emitSummariesSnapshot(documents: List<WebFirestoreListDocument>, isFromCache: Boolean = false, hasPendingWrites: Boolean = false) {
        summariesSnapshotListener?.invoke(WebFirestoreListSnapshot(documents, isFromCache, hasPendingWrites))
    }

    fun emitSummariesSnapshotError(error: WebBridgeError) {
        summariesSnapshotErrorListener?.invoke(error)
    }

    /** Simulates the SDK delivering a fresh snapshot to a live `observeList` listener. */
    fun emitDocument(document: WebFirestoreListDocument?) {
        documentListener?.invoke(document)
    }

    fun emitDocumentError(error: WebBridgeError) {
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
