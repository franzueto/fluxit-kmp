package com.fluxit.firebase.list

import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseValue
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * unit tests for [FirestoreValueCodec]: pure translation between the
 * neutral [FirebaseValue] contract and the plain Kotlin shapes the Firestore SDK
 * reads/writes. No [com.google.firebase.firestore.FirebaseFirestore] instance is
 * touched, so these run as plain JVM unit tests.
 */
class FirestoreValueCodecTest {

    // --- encode --------------------------------------------------------------------

    @Test
    fun encodesTextBoolAndNumberAsThemselves() {
        assertEquals("Groceries", FirestoreValueCodec.encode(FirebaseValue.Text("Groceries")))
        assertEquals(true, FirestoreValueCodec.encode(FirebaseValue.Bool(true)))
        assertEquals(3L, FirestoreValueCodec.encode(FirebaseValue.Number(3)))
    }

    @Test
    fun encodesNullAsALiteralKotlinNullNotFieldDelete() {
        val encoded = FirestoreValueCodec.encode(FirebaseValue.Null)
        assertNull(encoded)
    }

    @Test
    fun encodesPendingServerTimestampAsAFieldValueServerTimestampSentinel() {
        val encoded = FirestoreValueCodec.encode(FirebaseValue.PendingServerTimestamp)
        assertTrue(encoded is FieldValue, "expected a FieldValue sentinel, got $encoded")
    }

    @Test
    fun encodesATimestampRoundTrippingToTheSameEpochMillis() {
        val millis = 1_726_000_123_456L

        val encoded = FirestoreValueCodec.encode(FirebaseValue.Timestamp(millis)) as Timestamp

        assertEquals(millis, encoded.toDate().time)
    }

    @Test
    fun encodesAFieldPatchAsAPlainMapOverEveryPatchedKey() {
        val patch = FieldPatch(
            mapOf(
                "name" to FirebaseValue.Text("Groceries"),
                "deletedAt" to FirebaseValue.Null,
            )
        )

        val encoded = FirestoreValueCodec.encode(patch)

        assertEquals(setOf("name", "deletedAt"), encoded.keys)
        assertEquals("Groceries", encoded["name"])
        assertNull(encoded["deletedAt"])
        assertTrue(encoded.containsKey("deletedAt"), "an explicit null must remain a present key")
    }

    // --- decode --------------------------------------------------------------------

    @Test
    fun decodesEveryRecognizedSdkTypeToItsNeutralCounterpart() {
        val timestamp = Timestamp(1_726_000_000L, 0)
        val raw = mapOf(
            "name" to "Groceries",
            "isCompleted" to true,
            "totalItems" to 3L,
            "asInt" to 3,
            "asDouble" to 3.0,
            "createdAt" to timestamp,
            "deletedAt" to null,
        )

        val dto = FirestoreValueCodec.decode("list-1", raw, clientFallbackMillis = 42L)

        assertEquals("list-1", dto.id)
        assertEquals(42L, dto.clientFallbackMillis)
        assertEquals(FirebaseValue.Text("Groceries"), dto.fields["name"])
        assertEquals(FirebaseValue.Bool(true), dto.fields["isCompleted"])
        assertEquals(FirebaseValue.Number(3), dto.fields["totalItems"])
        assertEquals(FirebaseValue.Number(3), dto.fields["asInt"])
        assertEquals(FirebaseValue.Number(3), dto.fields["asDouble"])
        assertEquals(FirebaseValue.Timestamp(timestamp.toDate().time), dto.fields["createdAt"])
        assertEquals(FirebaseValue.Null, dto.fields["deletedAt"])
    }

    @Test
    fun distinguishesAMissingFieldFromAFieldPresentWithAnExplicitNull() {
        val raw = mapOf("deletedAt" to null) // "name" is entirely absent

        val dto = FirestoreValueCodec.decode("list-1", raw, clientFallbackMillis = 0L)

        assertFalse(dto.fields.containsKey("name"), "an absent field must not appear in the map at all")
        assertTrue(dto.fields.containsKey("deletedAt"), "an explicit null must remain a present key")
        assertEquals(FirebaseValue.Null, dto.fields["deletedAt"])
    }

    @Test
    fun dropsAnUnrecognizedSdkValueTypeAsIfTheFieldWereMissing() {
        val raw = mapOf("location" to com.google.firebase.firestore.GeoPoint(1.0, 2.0))

        val dto = FirestoreValueCodec.decode("list-1", raw, clientFallbackMillis = 0L)

        assertFalse(dto.fields.containsKey("location"))
    }

    @Test
    fun decodingANullDataMapProducesAnEmptyFieldsMap() {
        val dto = FirestoreValueCodec.decode("list-1", data = null, clientFallbackMillis = 7L)

        assertTrue(dto.fields.isEmpty())
        assertEquals(7L, dto.clientFallbackMillis)
    }
}
