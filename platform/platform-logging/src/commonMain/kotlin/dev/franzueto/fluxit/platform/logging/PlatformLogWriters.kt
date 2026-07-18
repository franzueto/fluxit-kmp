package dev.franzueto.fluxit.platform.logging

import co.touchlab.kermit.LogWriter

public expect fun platformLogWriters(): List<LogWriter>
