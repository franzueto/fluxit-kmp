package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.platformLogWriter

/**
 * Android writer list (plan/06 §2): Kermit's [platformLogWriter] resolves to a
 * `LogcatWriter`, so reminders/photo/store logs land in Logcat under their port
 * tag. The Crashlytics non-fatal/fatal bridge is added here when plan/06 §13's
 * Crashlytics question resolves — until then this is Logcat only.
 */
public actual fun platformLogWriters(): List<LogWriter> = listOf(platformLogWriter())
