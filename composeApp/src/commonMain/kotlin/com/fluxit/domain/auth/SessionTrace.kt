package com.fluxit.domain.auth

/**
 * Ordered, platform-neutral trace of session-gate events (FB-104).
 *
 * This exists to make the FB-104 acceptance criterion - "no user-scoped listener starts
 * before session resolution completes" - *observable* rather than merely structural. Each
 * event carries a monotonically increasing sequence number, so the relative order of
 * "session resolved" and "user-scoped listener started" can be read off a plain log
 * capture on either platform (logcat on Android, the simulator console on iOS).
 *
 * It is deliberately dependency-free: `println` is the only cross-platform sink available
 * in `commonMain`, and no Firebase or platform type appears here. Callers must never pass
 * a credential, password, or token - only session-shape facts (state names, uid presence).
 */
object SessionTrace {

    /** Grep anchor used by the FB-104 manual matrix on both platforms. */
    const val TAG: String = "FluxItSessionGate"

    /** Allows tests (and any future release-build hardening) to silence the trace. */
    var enabled: Boolean = true

    private var sequence: Int = 0

    /** Records [message] with the next sequence number. Never logs user secrets. */
    fun event(message: String) {
        if (!enabled) return
        sequence += 1
        println("$TAG #$sequence $message")
    }

    /** Test hook: restarts numbering so assertions do not depend on suite ordering. */
    fun resetForTest() {
        sequence = 0
    }
}
