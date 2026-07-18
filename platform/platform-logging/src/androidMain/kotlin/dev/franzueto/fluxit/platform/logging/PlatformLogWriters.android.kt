package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.platformLogWriter

public actual fun platformLogWriters(): List<LogWriter> = listOf(platformLogWriter())
