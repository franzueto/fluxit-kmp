package dev.franzueto.fluxit.platform.photo

import kotlin.test.Test
import kotlin.test.assertEquals

class MimeExtensionsTest {
    @Test
    fun maps_known_image_types_to_their_extension() {
        assertEquals("jpg", mimeToExtension("image/jpeg"))
        assertEquals("png", mimeToExtension("image/png"))
        assertEquals("webp", mimeToExtension("image/webp"))
        assertEquals("heic", mimeToExtension("image/heic"))
    }

    @Test
    fun is_case_and_parameter_insensitive() {
        assertEquals("jpg", mimeToExtension("IMAGE/JPEG"))
        assertEquals("png", mimeToExtension("image/png; charset=binary"))
    }

    @Test
    fun unknown_or_non_image_falls_back_to_jpg() {
        assertEquals("jpg", mimeToExtension("application/octet-stream"))
        assertEquals("jpg", mimeToExtension(""))
    }
}
