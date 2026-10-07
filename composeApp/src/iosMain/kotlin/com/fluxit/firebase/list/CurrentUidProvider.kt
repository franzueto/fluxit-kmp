package com.fluxit.firebase.list

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.firebase.auth.IosAuthBridge
import com.fluxit.firebase.auth.IosAuthBridgeRegistry

/**
 * Resolves the authenticated uid a Firestore path needs, at the moment it is needed.
 *
 * Same shape and same rationale as Android's `CurrentUidProvider`
 * (`com.fluxit.firebase.list.CurrentUidProvider` in `androidMain`): deliberately not a
 * cached/constructor-time value, so the Phase 1 constraint that no UID-dependent path
 * may be resolved before authentication is resolved holds for every call, including one
 * made after a sign-out.
 *
 * Different mechanism, though: there is no Firebase Auth SDK type reachable from
 * `iosMain`, so this reads the uid through the already-registered
 * [IosAuthBridge] - the same Swift-backed seam the [com.fluxit.firebase.auth.IosAuthRepository]
 * uses - rather than a Firestore-specific mechanism. `currentUser()` is documented not to
 * hit the network, matching Android's local-only `FirebaseAuth.getInstance().currentUser`
 * check.
 */
fun interface CurrentUidProvider {
    fun currentUid(): String
}

/**
 * Production [CurrentUidProvider]. Throws [RepositoryException] with
 * [RepositoryErrorCode.SESSION_REQUIRED] - rather than returning null - when nobody is
 * signed in or the Auth bridge has not been registered yet, so every call site in
 * [IosFirebaseListRepository] gets the same neutral-error treatment as any other
 * Firestore failure.
 */
internal class IosAuthBridgeCurrentUidProvider(
    private val bridgeProvider: () -> IosAuthBridge? = IosAuthBridgeRegistry::bridgeOrNull,
) : CurrentUidProvider {
    override fun currentUid(): String =
        bridgeProvider()?.currentUser()?.uid
            ?: throw RepositoryException(RepositoryErrorCode.SESSION_REQUIRED.toApplicationError())
}
