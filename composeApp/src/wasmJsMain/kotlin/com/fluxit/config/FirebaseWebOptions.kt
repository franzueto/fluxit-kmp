package com.fluxit.config

/**
 * The Firebase web app options, as shown in Console → Project settings → Your apps → Web app.
 * Supplied at build time by the generated [FirebaseWebConfig]. These values identify the
 * project; they are not credentials, but they stay out of git like the mobile configs.
 */
internal data class FirebaseWebOptions(
    val apiKey: String,
    val authDomain: String,
    val projectId: String,
    val storageBucket: String,
    val messagingSenderId: String,
    val appId: String,
)
