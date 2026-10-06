package com.fluxit.parity

import kotlin.io.encoding.Base64

internal val parityPhotoBytes: ByteArray
    get() = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
