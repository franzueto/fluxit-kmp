package com.fluxit.firebase.session

import android.content.Context
import com.fluxit.domain.session.SessionCleanup
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.storage.FirebaseStorage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Retains the stopped handle across failures. Repositories resolve the fresh handle per call. */
class AndroidSessionCleanup(
    context: Context,
    private val firestoreProvider: () -> FirebaseFirestore = FirebaseFirestore::getInstance,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) : SessionCleanup {
    private val preferences = context.applicationContext.getSharedPreferences("session-cleanup", Context.MODE_PRIVATE)
    private var startupRecovery = preferences.getBoolean("pending", false)
    private var stopped: FirebaseFirestore? = null
    private var settings: FirebaseFirestoreSettings? = null
    private var termination: Task<Void>? = null
    override val pending: Boolean get() = preferences.getBoolean("pending", false)
    override fun begin() { check(preferences.edit().putBoolean("pending", true).commit()) }
    override fun complete() { check(preferences.edit().remove("pending").commit()) }

    override suspend fun clear() {
        AndroidStorageSessionTasks.cancelAndJoin(storage)
        val old = stopped ?: firestoreProvider().also {
            settings = it.firestoreSettings
            stopped = it
        }
        // terminate() initializes a client even on cold start. Remove persisted
        // queued writes before that initialization when recovering a durable marker.
        if (startupRecovery) {
            try { old.clearPersistence().await() }
            catch (failure: FirebaseFirestoreException) {
                if (failure.code != FirebaseFirestoreException.Code.FAILED_PRECONDITION) throw failure
            }
            startupRecovery = false
        }
        val terminate = termination ?: old.terminate().also { termination = it }
        try { terminate.await() } catch (failure: Exception) { termination = null; throw failure }
        old.clearPersistence().await()
        val fresh = firestoreProvider()
        check(fresh !== old) { "Firestore recreation failed" }
        fresh.firestoreSettings = checkNotNull(settings)
        stopped = null
        termination = null
        settings = null
    }
}

private suspend fun Task<Void>.await() = suspendCancellableCoroutine<Unit> { continuation ->
    addOnCompleteListener {
        if (continuation.isActive) {
            val failure = it.exception
            if (failure != null) continuation.resumeWithException(failure)
            else if (it.isCanceled) continuation.cancel()
            else continuation.resume(Unit)
        }
    }
}
