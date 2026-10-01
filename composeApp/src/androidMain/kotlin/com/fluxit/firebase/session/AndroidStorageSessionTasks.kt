package com.fluxit.firebase.session

import com.google.android.gms.tasks.Task
import com.google.firebase.storage.StorageTask
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Includes stream downloads, which Firebase's root activeDownloadTasks omits. */
object AndroidStorageSessionTasks {
    private val tasks = mutableSetOf<StorageTask<*>>()

    fun track(task: Task<*>) {
        check(task is StorageTask<*>)
        synchronized(tasks) { tasks.add(task) }
        task.addOnCompleteListener { synchronized(tasks) { tasks.remove(task) } }
    }

    suspend fun cancelAndJoin(storage: FirebaseStorage) {
        val outgoing = synchronized(tasks) { tasks.toList() } +
            storage.reference.activeUploadTasks + storage.reference.activeDownloadTasks
        outgoing.distinct().forEach { it.cancel() }
        // cancel() can only request a transition while a network request is running.
        // Keep cleanup blocked until the SDK reports terminal completion. The common
        // cleanup budget surfaces a retryable failure if this does not finish.
        outgoing.distinct().forEach { task ->
            suspendCancellableCoroutine<Unit> { continuation ->
                task.addOnCompleteListener { if (continuation.isActive) continuation.resume(Unit) }
            }
        }
    }
}
