package com.fluxit.data

/** Launches the system photo picker and returns the picked image bytes, or null if cancelled. */
interface PhotoPicker {
    suspend fun pickPhoto(): ByteArray?
}

/** Copies picked image bytes into app-internal storage; returns a stable absolute path. */
interface PhotoStorage {
    suspend fun savePhoto(bytes: ByteArray): String
    suspend fun deletePhoto(path: String)
}
