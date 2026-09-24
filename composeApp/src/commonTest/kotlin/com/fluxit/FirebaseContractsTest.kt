package com.fluxit

import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.data.remote.BackendErrorCode
import com.fluxit.data.remote.ContractErrorCode
import com.fluxit.data.remote.ContractResult
import com.fluxit.data.remote.FieldPatch
import com.fluxit.data.remote.FirebaseDocumentDto
import com.fluxit.data.remote.FirebaseDocumentMapper
import com.fluxit.data.remote.FirebaseSchema
import com.fluxit.data.remote.FirebaseValue
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.applyFieldPatches
import com.fluxit.data.remote.toRepositoryError
import com.fluxit.data.remote.toApplicationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FirebaseContractsTest {
    @Test
    fun malformedRequiredFieldReturnsTypedContractError() {
        val result = FirebaseDocumentMapper.item(
            "list-1",
            itemDocument(fields = validItemFields + (FirebaseSchema.Fields.TITLE to FirebaseValue.Number(4))),
        )

        val error = assertIs<ContractResult.Malformed>(result).error
        assertEquals(ContractErrorCode.WRONG_TYPE, error.code)
        assertEquals(FirebaseSchema.Fields.TITLE, error.field)
    }

    @Test
    fun malformedOptionalFieldIsNotSilentlyDiscarded() {
        val result = FirebaseDocumentMapper.item(
            "list-1",
            itemDocument(fields = validItemFields + (FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Bool(true))),
        )

        assertEquals(
            ContractErrorCode.WRONG_TYPE,
            assertIs<ContractResult.Malformed>(result).error.code,
        )
    }

    @Test
    fun unknownEnumsUseDocumentedDefaults() {
        val result = FirebaseDocumentMapper.list(
            listDocument(
                fields = validListFields + mapOf(
                    FirebaseSchema.Fields.ICON to FirebaseValue.Text("FUTURE_ICON"),
                    FirebaseSchema.Fields.COLOR to FirebaseValue.Text("FUTURE_COLOR"),
                )
            )
        )

        val list = assertIs<ContractResult.Value<*>>(result).value as com.fluxit.domain.FluxListSummary
        assertEquals(ListIcon.CART, list.list.icon)
        assertEquals(ListColor.PRIMARY_BLUE, list.list.color)
    }

    @Test
    fun pendingServerTimestampUsesSnapshotClientFallback() {
        val result = FirebaseDocumentMapper.item(
            "list-1",
            itemDocument(
                clientFallbackMillis = 9_999,
                fields = validItemFields + (FirebaseSchema.Fields.CREATED_AT to FirebaseValue.PendingServerTimestamp),
            ),
        )

        assertEquals(9_999, assertIs<ContractResult.Value<*>>(result).let {
            (it.value as com.fluxit.domain.FluxItem).createdAt
        })
    }

    @Test
    fun orderingUsesDocumentIdAsStableTimestampTieBreaker() {
        val a = mappedItem("a", createdAt = 10)
        val b = mappedItem("b", createdAt = 10)
        val earlier = mappedItem("z", createdAt = 9)

        assertEquals(listOf("z", "a", "b"), listOf(b, a, earlier).sortedWith(FirebaseDocumentMapper.itemOrdering).map { it.id })
    }

    @Test
    fun fieldLevelLwwMergesDifferentFieldsAndLaterSameFieldWins() {
        val result = applyFieldPatches(
            original = mapOf(
                FirebaseSchema.Fields.TITLE to FirebaseValue.Text("original"),
                FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Text("old"),
            ),
            patchesInCommitOrder = listOf(
                FieldPatch(mapOf(FirebaseSchema.Fields.TITLE to FirebaseValue.Text("device-a"))),
                FieldPatch(mapOf(FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Text("device-b"))),
                FieldPatch(mapOf(FirebaseSchema.Fields.TITLE to FirebaseValue.Text("device-c"))),
            ),
        )

        assertEquals(FirebaseValue.Text("device-c"), result[FirebaseSchema.Fields.TITLE])
        assertEquals(FirebaseValue.Text("device-b"), result[FirebaseSchema.Fields.DESCRIPTION])
    }

    @Test
    fun countersCannotBeExpressedAsLwwFieldPatches() {
        assertFailsWith<IllegalArgumentException> {
            FieldPatch(mapOf(FirebaseSchema.Fields.TOTAL_ITEMS to FirebaseValue.Number(1)))
        }
    }

    @Test
    fun fieldPatchSnapshotsMutableCallerMap() {
        val callerFields = mutableMapOf<String, FirebaseValue>(
            FirebaseSchema.Fields.TITLE to FirebaseValue.Text("captured"),
        )
        val patch = FieldPatch(callerFields)

        callerFields[FirebaseSchema.Fields.TITLE] = FirebaseValue.Text("late change")
        callerFields[FirebaseSchema.Fields.TOTAL_ITEMS] = FirebaseValue.Number(999)
        val result = applyFieldPatches(emptyMap(), listOf(patch))

        assertEquals(FirebaseValue.Text("captured"), result[FirebaseSchema.Fields.TITLE])
        assertEquals(null, result[FirebaseSchema.Fields.TOTAL_ITEMS])
        assertEquals(
            mapOf(FirebaseSchema.Fields.TITLE to FirebaseValue.Text("captured")),
            patch.fields,
        )
    }

    @Test
    fun pathsMatchNestedFirestoreAndPlan005StorageContracts() {
        assertEquals("users/u/lists/l/items/i", FirebaseSchema.itemPath("u", "l", "i"))
        assertEquals("users/u/items/i/p", FirebaseSchema.photoRef("u", "i", "p"))
    }

    // FB-302: PLAN-006 makes the photoRef shape load-bearing (deployed Storage Rules match
    // this exact depth, no recursive wildcard) - these tests prove FirebaseSchema.photoRef
    // enforces it rather than merely documenting it.

    @Test
    fun photoRefIsExactlyFiveSegmentsWithTheFixedLiterals() {
        val ref = FirebaseSchema.photoRef("uid-1", "item-1", "photo-1")
        val segments = ref.split('/')
        assertEquals(listOf("users", "uid-1", "items", "item-1", "photo-1"), segments)
    }

    @Test
    fun photoRefRejectsASlashInPhotoId() {
        assertFailsWith<IllegalArgumentException> {
            FirebaseSchema.photoRef("uid-1", "item-1", "sneaky/photo-1")
        }
    }

    @Test
    fun photoRefRejectsASlashInUidOrItemId() {
        assertFailsWith<IllegalArgumentException> { FirebaseSchema.photoRef("u/id", "item-1", "photo-1") }
        assertFailsWith<IllegalArgumentException> { FirebaseSchema.photoRef("uid-1", "it/em", "photo-1") }
    }

    @Test
    fun photoRefRejectsBlankSegments() {
        assertFailsWith<IllegalArgumentException> { FirebaseSchema.photoRef("", "item-1", "photo-1") }
        assertFailsWith<IllegalArgumentException> { FirebaseSchema.photoRef("uid-1", "", "photo-1") }
        assertFailsWith<IllegalArgumentException> { FirebaseSchema.photoRef("uid-1", "item-1", "") }
    }

    @Test
    fun itemIdFromPhotoRefKeysOnItemIdOnlyPerPlan007() {
        // PLAN-007: no listId segment exists anywhere in a photoRef, so the future orphan
        // sweep (FB-502/FB-503) can only recover the itemId - never a listId - from the ref.
        val ref = FirebaseSchema.photoRef("uid-1", "item-42", "photo-1")
        assertEquals("item-42", FirebaseSchema.itemIdFromPhotoRef(ref))
    }

    @Test
    fun itemIdFromPhotoRefRejectsMalformedInput() {
        assertEquals(null, FirebaseSchema.itemIdFromPhotoRef("not/a/photo/ref"))
        assertEquals(null, FirebaseSchema.itemIdFromPhotoRef("users/u/lists/l/items/i"))
        assertEquals(null, FirebaseSchema.itemIdFromPhotoRef(""))
    }

    @Test
    fun backendErrorsMapWithoutSdkTypes() {
        val expected = mapOf(
            BackendErrorCode.UNAUTHENTICATED to RepositoryErrorCode.SESSION_REQUIRED,
            BackendErrorCode.PERMISSION_DENIED to RepositoryErrorCode.FORBIDDEN,
            BackendErrorCode.UNAVAILABLE to RepositoryErrorCode.OFFLINE,
            BackendErrorCode.DEADLINE_EXCEEDED to RepositoryErrorCode.TIMEOUT,
            BackendErrorCode.NOT_FOUND to RepositoryErrorCode.NOT_FOUND,
            BackendErrorCode.ALREADY_EXISTS to RepositoryErrorCode.CONFLICT,
            BackendErrorCode.INVALID_ARGUMENT to RepositoryErrorCode.INVALID_DATA,
            BackendErrorCode.RESOURCE_EXHAUSTED to RepositoryErrorCode.QUOTA,
            BackendErrorCode.UNKNOWN to RepositoryErrorCode.UNKNOWN,
        )
        assertEquals(expected, BackendErrorCode.entries.associateWith { it.toRepositoryError() })
        assertEquals(true, RepositoryErrorCode.OFFLINE.toApplicationError().canRetry)
        assertEquals(true, RepositoryErrorCode.SESSION_REQUIRED.toApplicationError().requiresFreshSession)
    }

    @Test
    fun pendingDeleteTimestampFiltersDocumentImmediately() {
        val result = FirebaseDocumentMapper.item(
            "list-1",
            itemDocument(fields = validItemFields + (FirebaseSchema.Fields.DELETED_AT to FirebaseValue.PendingServerTimestamp)),
        )

        assertEquals(ContractResult.FilteredDeleted, result)
    }

    private fun mappedItem(id: String, createdAt: Long) =
        (FirebaseDocumentMapper.item(
            "list-1",
            itemDocument(id = id, fields = validItemFields + (FirebaseSchema.Fields.CREATED_AT to FirebaseValue.Timestamp(createdAt))),
        ) as ContractResult.Value).value

    private fun itemDocument(
        id: String = "item-1",
        fields: Map<String, FirebaseValue> = validItemFields,
        clientFallbackMillis: Long = 1_000,
    ) = FirebaseDocumentDto(id, fields, clientFallbackMillis)

    private fun listDocument(
        fields: Map<String, FirebaseValue> = validListFields,
    ) = FirebaseDocumentDto("list-1", fields, 1_000)

    private val validItemFields = mapOf(
        FirebaseSchema.Fields.LIST_ID to FirebaseValue.Text("list-1"),
        FirebaseSchema.Fields.TITLE to FirebaseValue.Text("Milk"),
        FirebaseSchema.Fields.DESCRIPTION to FirebaseValue.Null,
        FirebaseSchema.Fields.IS_COMPLETED to FirebaseValue.Bool(false),
        FirebaseSchema.Fields.PHOTO_REF to FirebaseValue.Null,
        FirebaseSchema.Fields.CREATED_AT to FirebaseValue.Timestamp(100),
        FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.Timestamp(100),
        FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(1),
    )

    private val validListFields = mapOf(
        FirebaseSchema.Fields.NAME to FirebaseValue.Text("Groceries"),
        FirebaseSchema.Fields.ICON to FirebaseValue.Text("CART"),
        FirebaseSchema.Fields.COLOR to FirebaseValue.Text("ORANGE"),
        FirebaseSchema.Fields.CREATED_AT to FirebaseValue.Timestamp(100),
        FirebaseSchema.Fields.UPDATED_AT to FirebaseValue.Timestamp(100),
        FirebaseSchema.Fields.TOTAL_ITEMS to FirebaseValue.Number(2),
        FirebaseSchema.Fields.COMPLETED_ITEMS to FirebaseValue.Number(1),
        FirebaseSchema.Fields.SCHEMA_VERSION to FirebaseValue.Number(1),
    )
}
