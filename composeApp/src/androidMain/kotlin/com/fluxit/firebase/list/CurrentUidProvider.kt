package com.fluxit.firebase.list

import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.google.firebase.auth.FirebaseAuth

/**
 * Resolves the authenticated uid a Firestore path needs, at the moment it is needed.
 *
 * Deliberately not a cached/constructor-time value: the Phase 1 constraint that no
 * UID-dependent path may be resolved before authentication is resolved must hold for
 * every call this repository makes, not just the first one made after sign-in. Each
 * [currentUid] call re-reads the live auth state, so a listener started (or a mutation
 * issued) after a sign-out throws immediately instead of silently reusing a stale uid.
 *
 * Public (not `internal`): [AndroidFirebaseListRepository]'s constructor is public, so
 * this parameter type must be too - future Koin wiring (`FB-207`) and tests both
 * construct the repository from outside this package.
 */
fun interface CurrentUidProvider {
    fun currentUid(): String
}

/**
 * Production [CurrentUidProvider], backed directly by the Firebase Auth SDK's own
 * current-user snapshot (no network call - the same local check [AndroidAuthRepository]
 * uses).
 *
 * Throws [ListRepositoryException] with [RepositoryErrorCode.SESSION_REQUIRED] rather
 * than returning null when nobody is signed in, so every call site in
 * [AndroidFirebaseListRepository] gets the same neutral-error treatment as any other
 * Firestore failure, without a separate null-uid branch to remember at each use.
 */
internal class FirebaseAuthCurrentUidProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : CurrentUidProvider {
    override fun currentUid(): String =
        auth.currentUser?.uid
            ?: throw ListRepositoryException(RepositoryErrorCode.SESSION_REQUIRED.toApplicationError())
}
