package com.fluxit.firebase.session

import com.fluxit.domain.session.SessionCleanup
import com.fluxit.firebase.WebBridgeError
import com.fluxit.firebase.WebFirebase
import com.fluxit.firebase.clearSessionData
import com.fluxit.firebase.toWebBridgeError
import kotlin.coroutines.resume
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Web [SessionCleanup]. The durable marker lives in `localStorage` (the counterpart of
 * Android's SharedPreferences and iOS's UserDefaults), so a credential change interrupted
 * by closing the tab is cleaned up on the next load. It holds no identity or data.
 *
 * [clear] asks the bridge to terminate the Firestore instance, dropping its in-memory
 * cache, listeners and queued writes; the next use creates a fresh one. Firestore on web
 * keeps no disk cache (decision D4), so there is nothing else to wipe. A failed
 * termination is retried by the next [clear]. Phase 4 adds Storage task cancellation.
 */
internal class WebSessionCleanup(
    private val markerStore: MarkerStore = LocalStorageMarkerStore,
    private val clearData: ((WebBridgeError?) -> Unit) -> Unit = { done ->
        WebFirebase.ensureStarted()
        clearSessionData { done(it?.toWebBridgeError()) }
    },
) : SessionCleanup {

    override val pending: Boolean get() = markerStore.read()

    override fun begin() {
        check(markerStore.write(true)) { "Cleanup marker could not be persisted" }
    }

    override fun complete() {
        check(markerStore.write(false)) { "Cleanup marker could not be removed" }
    }

    override suspend fun clear() {
        val failed = suspendCancellableCoroutine { continuation ->
            clearData { if (continuation.isActive) continuation.resume(it != null) }
        }
        check(!failed) { "Local session cleanup failed" }
    }

    /** Storage for the pending marker; `write` reports whether the value was stored. */
    interface MarkerStore {
        fun read(): Boolean
        fun write(pending: Boolean): Boolean
    }

    /** `localStorage` can throw (blocked site data); that counts as a failed write, like a failed commit. */
    private object LocalStorageMarkerStore : MarkerStore {
        private const val KEY = "fluxit.session-cleanup-pending"

        override fun read(): Boolean =
            runCatching { window.localStorage.getItem(KEY) == "true" }.getOrDefault(false)

        override fun write(pending: Boolean): Boolean = runCatching {
            if (pending) window.localStorage.setItem(KEY, "true") else window.localStorage.removeItem(KEY)
        }.isSuccess
    }
}
