package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.LogWriter

/**
 * The platform-specific Kermit [LogWriter] list that backs [loggingModule]'s
 * [co.touchlab.kermit.Logger] (plan/06 §2). Android contributes a Logcat writer;
 * iOS an os_log writer. This is the seam where a Crashlytics writer is added per
 * platform when that open question (plan/06 §13) resolves — `loggingModule` and
 * [KermitAppLogger] stay unchanged.
 */
public expect fun platformLogWriters(): List<LogWriter>
