package com.fluxit.firebase

import com.fluxit.data.remote.FirebaseValue

/**
 * Converts the shared [FirebaseValue] map to and from the JS bridge's wire format: an
 * array of `{ key, type, text, bool, number }` entries, which `firebase-bridge.mjs` turns
 * into Firestore values (`Timestamp`, `serverTimestamp()`, `increment()`) and back.
 * Numbers cross as JS numbers, exact for the integers FluxIt stores (|n| < 2^53).
 */
internal object WebFirestoreCodec {

    fun encode(fields: Map<String, FirebaseValue>): JsArray<JsWireField> {
        val wire = JsArray<JsWireField>()
        fields.entries.forEachIndexed { index, (key, value) ->
            wire[index] = when (value) {
                FirebaseValue.Null -> wireField(key, NULL, null, false, 0.0)
                is FirebaseValue.Text -> wireField(key, TEXT, value.value, false, 0.0)
                is FirebaseValue.Bool -> wireField(key, BOOL, null, value.value, 0.0)
                is FirebaseValue.Number -> wireField(key, NUMBER, null, false, value.value.toDouble())
                is FirebaseValue.Timestamp -> wireField(key, TIMESTAMP, null, false, value.epochMillis.toDouble())
                FirebaseValue.PendingServerTimestamp -> wireField(key, SERVER_TIMESTAMP, null, false, 0.0)
            }
        }
        return wire
    }

    /** Counter deltas as `increment()` writes. */
    fun encodeIncrements(delta: Map<String, FirebaseValue.Number>): JsArray<JsWireField> {
        val wire = JsArray<JsWireField>()
        delta.entries.forEachIndexed { index, (key, value) ->
            wire[index] = wireField(key, INCREMENT, null, false, value.value.toDouble())
        }
        return wire
    }

    /** Read direction. Write-only types never come back from a snapshot; anything unknown is left out. */
    fun decode(wire: JsArray<JsWireField>): Map<String, FirebaseValue> = buildMap {
        for (index in 0 until wire.length) {
            val field = wire[index] ?: continue
            val value = when (field.type) {
                NULL -> FirebaseValue.Null
                TEXT -> field.text?.let { FirebaseValue.Text(it) }
                BOOL -> FirebaseValue.Bool(field.bool)
                NUMBER -> FirebaseValue.Number(field.number.toLong())
                TIMESTAMP -> FirebaseValue.Timestamp(field.number.toLong())
                else -> null
            }
            if (value != null) put(field.key, value)
        }
    }

    private const val NULL = "null"
    private const val TEXT = "text"
    private const val BOOL = "bool"
    private const val NUMBER = "number"
    private const val TIMESTAMP = "timestamp"
    private const val SERVER_TIMESTAMP = "serverTimestamp"
    private const val INCREMENT = "increment"
}

@Suppress("UNUSED_PARAMETER")
private fun wireField(key: String, type: String, text: String?, bool: Boolean, number: Double): JsWireField =
    js("({ key: key, type: type, text: text, bool: bool, number: number })")
