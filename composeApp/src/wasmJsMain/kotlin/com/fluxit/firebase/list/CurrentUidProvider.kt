package com.fluxit.firebase.list

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.firebase.auth.JsWebAuthBridge
import com.fluxit.firebase.auth.WebAuthBridge

/**
 * Resolves the signed-in uid at the moment a Firestore path needs it, never cached, so a
 * call made after a sign-out cannot reach the previous user's paths. Same contract as
 * the iOS and Android providers.
 */
internal fun interface CurrentUidProvider {
    fun currentUid(): String
}

/** Reads the uid through the auth bridge; [RepositoryErrorCode.SESSION_REQUIRED] when nobody is signed in. */
internal class WebAuthBridgeCurrentUidProvider(
    private val bridgeProvider: () -> WebAuthBridge = { JsWebAuthBridge },
) : CurrentUidProvider {
    override fun currentUid(): String =
        bridgeProvider().currentUser()?.uid
            ?: throw RepositoryException(RepositoryErrorCode.SESSION_REQUIRED.toApplicationError())
}
