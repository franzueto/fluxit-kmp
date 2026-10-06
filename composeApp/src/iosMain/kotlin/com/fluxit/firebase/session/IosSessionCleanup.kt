package com.fluxit.firebase.session

import com.fluxit.domain.session.SessionCleanup
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError

interface IosSessionCleanupBridge {
    val pending: Boolean
    fun begin(): Boolean
    fun complete(): Boolean
    fun clear(completion: (NSError?) -> Unit)
}

object IosSessionCleanupBridgeRegistry {
    private var bridge: IosSessionCleanupBridge? = null
    fun register(bridge: IosSessionCleanupBridge) { this.bridge = bridge }
    fun requireBridge(): IosSessionCleanupBridge = checkNotNull(bridge) { "Session cleanup bridge missing" }
}

class IosSessionCleanup : SessionCleanup {
    private val bridge get() = IosSessionCleanupBridgeRegistry.requireBridge()
    override val pending get() = bridge.pending
    override fun begin() { check(bridge.begin()) { "Cleanup marker could not be persisted" } }
    override fun complete() { check(bridge.complete()) { "Cleanup marker could not be removed" } }
    override suspend fun clear() {
        val failed = suspendCancellableCoroutine<Boolean> { continuation ->
            bridge.clear { if (continuation.isActive) continuation.resume(it != null) }
        }
        check(!failed) { "Local session cleanup failed" }
    }
}
