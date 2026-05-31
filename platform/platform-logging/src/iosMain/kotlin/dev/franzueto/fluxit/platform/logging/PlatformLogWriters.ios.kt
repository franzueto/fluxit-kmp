package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.platformLogWriter

/**
 * iOS writer list (plan/06 §2): Kermit's [platformLogWriter] resolves to an
 * `NSLogWriter` (os_log-backed), so logs surface in the device console under
 * their port tag. The Crashlytics writer is added here when plan/06 §13's
 * Crashlytics question resolves — until then this is os_log only.
 */
public actual fun platformLogWriters(): List<LogWriter> = listOf(platformLogWriter())
