package com.fluxit.firebase

/**
 * A Firebase JS SDK failure as it crosses the bridge: the SDK's string `code` (for
 * example `auth/invalid-credential`) and its message. Only the adapters' error mappers
 * interpret it; it never reaches `commonMain`. The web counterpart of the `NSError`
 * the iOS bridges hand back.
 */
internal data class WebBridgeError(val code: String, val message: String)

internal fun JsBridgeError.toWebBridgeError(): WebBridgeError = WebBridgeError(code, message)
