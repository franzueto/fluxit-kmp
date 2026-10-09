package com.fluxit.firebase.list

import com.fluxit.data.remote.ApplicationError
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.RepositoryException
import com.fluxit.data.remote.toApplicationError
import com.fluxit.data.remote.toRepositoryError
import com.fluxit.firebase.WebBridgeError

/**
 * Maps a Firebase JS SDK Firestore error code onto the neutral [BackendErrorCode]. The JS
 * SDK spells the same gRPC statuses the Apple (`FIRFirestoreErrorCode`) and Android
 * (`FirebaseFirestoreException.Code`) SDKs report, so this table matches theirs; every
 * other code (`cancelled`, `aborted`, `internal`, `failed-precondition`, …, and the
 * bridge's own `unknown`) falls through to [BackendErrorCode.UNKNOWN].
 */
internal fun firestoreBackendErrorCode(code: String): BackendErrorCode = when (code) {
    "unauthenticated" -> BackendErrorCode.UNAUTHENTICATED
    "permission-denied" -> BackendErrorCode.PERMISSION_DENIED
    "unavailable" -> BackendErrorCode.UNAVAILABLE
    "deadline-exceeded" -> BackendErrorCode.DEADLINE_EXCEEDED
    "not-found" -> BackendErrorCode.NOT_FOUND
    "already-exists" -> BackendErrorCode.ALREADY_EXISTS
    "invalid-argument" -> BackendErrorCode.INVALID_ARGUMENT
    "resource-exhausted" -> BackendErrorCode.RESOURCE_EXHAUSTED
    else -> BackendErrorCode.UNKNOWN
}

internal fun WebBridgeError.toApplicationError(): ApplicationError =
    firestoreBackendErrorCode(code).toRepositoryError().toApplicationError()

/** Converts a Firestore-originated bridge error into a [RepositoryException]. */
internal fun WebBridgeError.toRepositoryException(): RepositoryException =
    RepositoryException(toApplicationError())
