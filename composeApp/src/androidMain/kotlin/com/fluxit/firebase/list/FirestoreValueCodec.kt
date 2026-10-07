package com.fluxit.firebase.list

import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentDto
import com.fluxit.data.remote.FirebaseValue
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue

/**
 * The only place in `androidMain` that translates between the Firebase-neutral
 * [FirebaseValue] contract and the plain Kotlin representation the Android Firestore
 * SDK reads and writes (`DocumentSnapshot.getData()` and `DocumentReference.set`/
 * `update`).
 *
 * Every function here is pure - no [com.google.firebase.firestore.FirebaseFirestore]
 * instance, listener, or network call is touched - so this file is exercised entirely
 * by plain JVM unit tests ([FirestoreValueCodecTest]), not instrumented ones.
 */
internal object FirestoreValueCodec {

    /** Encodes a field-scoped [FieldPatch] into the map `set()`/`update()` expect. */
    fun encode(patch: FieldPatch): Map<String, Any?> = patch.fields.mapValues { (_, value) -> encode(value) }

    /** Encodes a single neutral value into the SDK's plain-Kotlin field representation. */
    fun encode(value: FirebaseValue): Any? = when (value) {
        is FirebaseValue.Text -> value.value
        is FirebaseValue.Bool -> value.value
        is FirebaseValue.Number -> value.value
        is FirebaseValue.Timestamp -> value.epochMillis.toFirestoreTimestamp()
        FirebaseValue.PendingServerTimestamp -> FieldValue.serverTimestamp()
        // An explicit Kotlin `null` field value (present, not deleted) - distinct from
        // FieldValue.delete(), which this repository never uses: field-scoped
        // patches only ever set or clear a value, never remove a schema field entirely.
        FirebaseValue.Null -> null
    }

    /**
     * Decodes a raw Firestore document map into the [FirebaseDocumentDto].
     *
     * A field entirely absent from [data] is left out of the resulting map, matching
     * [FirebaseDocumentDto]'s "missing" branch (`fields[field] == null`). A field
     * present with an explicit null value becomes [FirebaseValue.Null] instead, so the
     * two are never conflated - this is exactly the distinction the
     * `requireActiveAndCurrentSchema`/`required*` helpers rely on.
     *
     * A field whose SDK value type this repository does not itself write (anything
     * other than String/Boolean/Long/Int/Double/[Timestamp]/null - for example a
     * GeoPoint or nested Map some other client wrote) is dropped as if missing rather
     * than guessed at. Mapper then reports `MISSING_FIELD`, which is a safe,
     * always-defined outcome for a shape this repository never produces itself.
     */
    fun decode(id: String, data: Map<String, Any?>?, clientFallbackMillis: Long): FirebaseDocumentDto {
        val fields = buildMap {
            data?.forEach { (key, raw) ->
                val value = decodeValue(raw) ?: return@forEach
                put(key, value)
            }
        }
        return FirebaseDocumentDto(id = id, fields = fields, clientFallbackMillis = clientFallbackMillis)
    }

    private fun decodeValue(raw: Any?): FirebaseValue? = when (raw) {
        null -> FirebaseValue.Null
        is String -> FirebaseValue.Text(raw)
        is Boolean -> FirebaseValue.Bool(raw)
        is Long -> FirebaseValue.Number(raw)
        is Int -> FirebaseValue.Number(raw.toLong())
        is Double -> FirebaseValue.Number(raw.toLong())
        is Timestamp -> FirebaseValue.Timestamp(raw.toDate().time)
        else -> null
    }

    private fun Long.toFirestoreTimestamp(): Timestamp {
        val seconds = Math.floorDiv(this, 1000L)
        val nanos = (Math.floorMod(this, 1000L) * 1_000_000L).toInt()
        return Timestamp(seconds, nanos)
    }
}
