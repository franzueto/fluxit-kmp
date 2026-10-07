package com.fluxit.firebase

import com.fluxit.data.remote.FirebaseValue
import kotlin.test.Test
import kotlin.test.assertEquals

@JsModule("./firebase-bridge-test-support.mjs")
private external object CodecSupport : JsAny {
    fun describeFirestoreData(wire: JsArray<JsWireField>): String
    fun sampleSnapshotWire(): JsArray<JsWireField>
}

/**
 * The `FirebaseValue` ⇄ Firestore conversion end to end: Kotlin encodes, the production
 * JS bridge builds real Firestore values (checked in JS), and real Firestore data comes
 * back through the bridge and the Kotlin decoder.
 */
class WebFirestoreCodecTest {

    @Test
    fun everyWritableValueBecomesTheMatchingFirestoreValue() {
        val wire = WebFirestoreCodec.encode(
            linkedMapOf(
                "name" to FirebaseValue.Text("Groceries"),
                "isCompleted" to FirebaseValue.Bool(true),
                "schemaVersion" to FirebaseValue.Number(1),
                "deletedAt" to FirebaseValue.Null,
                "createdAt" to FirebaseValue.Timestamp(1_700_000_000_123),
                "updatedAt" to FirebaseValue.PendingServerTimestamp,
            ),
        )

        assertEquals(
            """{"name":"string:Groceries","isCompleted":"boolean:true","schemaVersion":"number:1",""" +
                """"deletedAt":"null","createdAt":"timestamp:1700000000123","updatedAt":"serverTimestamp"}""",
            CodecSupport.describeFirestoreData(wire),
        )
    }

    @Test
    fun counterDeltasBecomeIncrements() {
        val wire = WebFirestoreCodec.encodeIncrements(
            linkedMapOf("totalItems" to FirebaseValue.Number(-1), "completedItems" to FirebaseValue.Number(1)),
        )

        assertEquals(
            """{"totalItems":"increment:-1","completedItems":"increment:1"}""",
            CodecSupport.describeFirestoreData(wire),
        )
    }

    @Test
    fun firestoreDataDecodesLikeTheIosBridge() {
        val decoded = WebFirestoreCodec.decode(CodecSupport.sampleSnapshotWire())

        assertEquals(
            mapOf(
                "name" to FirebaseValue.Text("Groceries"),
                "isCompleted" to FirebaseValue.Bool(true),
                "totalItems" to FirebaseValue.Number(3),
                "fractional" to FirebaseValue.Number(2), // truncated, as iOS's int64Value
                "deletedAt" to FirebaseValue.Null, // explicit null kept, distinct from missing
                "createdAt" to FirebaseValue.Timestamp(1_700_000_000_123),
                // nested map and array left out: the mapper sees them as missing
            ),
            decoded,
        )
    }

    @Test
    fun readableValuesRoundTripThroughTheWireFormat() {
        val fields = mapOf(
            "title" to FirebaseValue.Text("Milk"),
            "description" to FirebaseValue.Null,
            "isCompleted" to FirebaseValue.Bool(false),
            "completedItems" to FirebaseValue.Number(9_007_199_254_740_991), // 2^53 - 1
            "updatedAt" to FirebaseValue.Timestamp(0),
        )

        assertEquals(fields, WebFirestoreCodec.decode(WebFirestoreCodec.encode(fields)))
    }
}
