package dev.franzueto.fluxit.core.utils

import java.util.UUID

public actual fun newId(): String = UUID.randomUUID().toString()
