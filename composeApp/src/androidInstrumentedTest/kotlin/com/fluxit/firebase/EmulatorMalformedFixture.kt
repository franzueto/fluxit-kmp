package com.fluxit.firebase

import com.fluxit.config.FirebaseEmulatorConfig
import com.google.firebase.Timestamp
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Admin-injected historical/corrupt read fixture, never a permitted client write.
 * Fixed demo project + stock AVD loopback only. No credential or live host is accepted.
 * The real authenticated SDK still performs every read against unchanged hardened Rules. */
internal object EmulatorMalformedFixture {
    suspend fun put(project: String, path: String, fields: Map<String, Any?>) = withContext(Dispatchers.IO) {
        check(project == "demo-fluxit") { "malformed fixtures require the fixed demo emulator target" }
        check(FirebaseEmulatorConfig.HOST == "127.0.0.1" || FirebaseEmulatorConfig.HOST == "localhost")
        check(path.matches(Regex("users/[^/]+/lists/[^/]+(/items/[^/]+)?")))
        val url = URI("http://10.0.2.2:${FirebaseEmulatorConfig.FIRESTORE_PORT}/v1/projects/demo-fluxit/databases/(default)/documents/$path").toURL()
        val encoded = JSONObject()
        fields.forEach { (key, value) ->
            val wire = JSONObject()
            when (value) {
                null -> wire.put("nullValue", JSONObject.NULL)
                is String -> wire.put("stringValue", value)
                is Boolean -> wire.put("booleanValue", value)
                is Number -> wire.put("integerValue", value.toLong().toString())
                is Timestamp -> wire.put("timestampValue", Instant.ofEpochSecond(value.seconds, value.nanoseconds.toLong()).toString())
                else -> error("unsupported emulator fixture value")
            }
            encoded.put(key, wire)
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PATCH"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer owner") // emulator admin literal, not a credential
            connection.doOutput = true
            connection.outputStream.use { it.write(JSONObject().put("fields", encoded).toString().toByteArray()) }
            check(connection.responseCode == 200) { "emulator fixture write failed" }
            connection.inputStream.close()
        } finally { connection.disconnect() }
    }
}
