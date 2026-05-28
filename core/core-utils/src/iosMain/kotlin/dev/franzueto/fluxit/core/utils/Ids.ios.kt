package dev.franzueto.fluxit.core.utils

import platform.Foundation.NSUUID

public actual fun newId(): String = NSUUID().UUIDString().lowercase()
