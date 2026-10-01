package com.fluxit.domain.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns only child jobs, never the caller's scope. Closing denies new work before cancellation. */
class SessionWork {
    private val mutex = Mutex()
    private var open = false
    private val epoch = MutableStateFlow(0L)
    private val jobs = mutableSetOf<Job>()

    suspend fun open() = mutex.withLock { open = true }

    suspend fun close() {
        val outgoing = mutex.withLock {
            open = false
            epoch.value += 1
            jobs.toList().also { it.forEach { job -> job.cancel() } }
        }
        outgoing.forEach { it.join() }
    }

    suspend fun <T> run(expectedEpoch: Long = epoch.value, block: suspend () -> T): T = coroutineScope {
        currentCoroutineContext().ensureActive()
        val job = currentCoroutineContext()[Job]!!
        mutex.withLock {
            if (!open || epoch.value != expectedEpoch) throw CancellationException("Session closed")
            jobs.add(job)
        }
        try { currentCoroutineContext().ensureActive(); block() } finally { withContext(NonCancellable) { mutex.withLock { jobs.remove(job) } } }
    }

    fun <T> observe(factory: () -> Flow<T>): Flow<T> {
        val expectedEpoch = epoch.value
        return flow { run(expectedEpoch) { factory().collect { emit(it) } } }
    }
}

/** Local SDK cleanup only; the durable marker contains no identity or document data. */
interface SessionCleanup {
    val pending: Boolean
    fun begin()
    suspend fun clear()
    fun complete()
}
