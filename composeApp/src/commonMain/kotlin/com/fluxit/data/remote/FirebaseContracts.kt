package com.fluxit.data.remote

import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon

/** Firebase-neutral schema contract. Platform adapters translate SDK values into these values. */
object FirebaseSchema {
    const val USERS = "users"
    const val LISTS = "lists"
    const val ITEMS = "items"

    object Fields {
        const val LIST_ID = "listId"
        const val NAME = "name"
        const val ICON = "icon"
        const val COLOR = "color"
        const val TITLE = "title"
        const val DESCRIPTION = "description"
        const val IS_COMPLETED = "isCompleted"
        const val PHOTO_REF = "photoRef"
        const val CREATED_AT = "createdAt"
        const val UPDATED_AT = "updatedAt"
        const val DELETED_AT = "deletedAt"
        const val TOTAL_ITEMS = "totalItems"
        const val COMPLETED_ITEMS = "completedItems"
        const val SCHEMA_VERSION = "schemaVersion"
    }

    const val CURRENT_VERSION = 1L

    fun listPath(uid: String, listId: String) = "users/$uid/lists/$listId"
    fun itemPath(uid: String, listId: String, itemId: String) =
        "users/$uid/lists/$listId/items/$itemId"

    /** PLAN-005 deliberately has no list segment. */
    fun photoRef(uid: String, itemId: String, photoId: String) =
        "users/$uid/items/$itemId/$photoId"
}

sealed interface FirebaseValue {
    data class Text(val value: String) : FirebaseValue
    data class Bool(val value: Boolean) : FirebaseValue
    data class Number(val value: Long) : FirebaseValue
    data class Timestamp(val epochMillis: Long) : FirebaseValue
    data object PendingServerTimestamp : FirebaseValue
    data object Null : FirebaseValue
}

data class FirebaseDocumentDto(
    val id: String,
    val fields: Map<String, FirebaseValue>,
    /** Captured when the local snapshot is received; used only while a server timestamp is pending. */
    val clientFallbackMillis: Long,
)

enum class ContractErrorCode { MISSING_FIELD, WRONG_TYPE, INVALID_VALUE, PATH_MISMATCH }

data class ContractError(
    val code: ContractErrorCode,
    val field: String,
)

sealed interface ContractResult<out T> {
    data class Value<T>(val value: T) : ContractResult<T>
    data class Malformed(val error: ContractError) : ContractResult<Nothing>
    data object FilteredDeleted : ContractResult<Nothing>
}

object FirebaseDocumentMapper {
    fun list(document: FirebaseDocumentDto): ContractResult<FluxListSummary> = mapping {
        document.requireActiveAndCurrentSchema()
        val name = document.requiredText(FirebaseSchema.Fields.NAME)
        val createdAt = document.requiredTimestamp(FirebaseSchema.Fields.CREATED_AT)
        val updatedAt = document.requiredTimestamp(FirebaseSchema.Fields.UPDATED_AT)
        val total = document.requiredNonNegativeInt(FirebaseSchema.Fields.TOTAL_ITEMS)
        val completed = document.requiredNonNegativeInt(FirebaseSchema.Fields.COMPLETED_ITEMS)
        if (completed > total) malformed(ContractErrorCode.INVALID_VALUE, FirebaseSchema.Fields.COMPLETED_ITEMS)
        FluxListSummary(
                list = FluxList(
                    id = document.id,
                    name = name,
                    icon = enumOrDefault(document.optionalText(FirebaseSchema.Fields.ICON), ListIcon.CART),
                    color = enumOrDefault(document.optionalText(FirebaseSchema.Fields.COLOR), ListColor.PRIMARY_BLUE),
                    sortOrder = createdAt.toDouble(),
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                ),
                totalItems = total,
                completedItems = completed,
        )
    }

    fun item(expectedListId: String, document: FirebaseDocumentDto): ContractResult<FluxItem> = mapping {
        document.requireActiveAndCurrentSchema()
        val listId = document.requiredText(FirebaseSchema.Fields.LIST_ID)
        if (listId != expectedListId) malformed(ContractErrorCode.PATH_MISMATCH, FirebaseSchema.Fields.LIST_ID)
        val title = document.requiredText(FirebaseSchema.Fields.TITLE)
        val completed = document.requiredBool(FirebaseSchema.Fields.IS_COMPLETED)
        val createdAt = document.requiredTimestamp(FirebaseSchema.Fields.CREATED_AT)
        val updatedAt = document.requiredTimestamp(FirebaseSchema.Fields.UPDATED_AT)
        FluxItem(
                id = document.id,
                listId = listId,
                title = title,
                description = document.optionalText(FirebaseSchema.Fields.DESCRIPTION),
                isCompleted = completed,
                photoRef = document.optionalText(FirebaseSchema.Fields.PHOTO_REF),
                sortOrder = createdAt.toDouble(),
                createdAt = createdAt,
                updatedAt = updatedAt,
        )
    }

    val listOrdering = compareBy<FluxListSummary>({ it.list.createdAt }, { it.list.id })
    val itemOrdering = compareBy<FluxItem>({ it.createdAt }, { it.id })

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}

private class MappingFailure(val contractError: ContractError) : RuntimeException()
private class FilteredDocument : RuntimeException()

private inline fun <T> mapping(block: () -> T): ContractResult<T> = try {
    ContractResult.Value(block())
} catch (failure: MappingFailure) {
    ContractResult.Malformed(failure.contractError)
} catch (_: FilteredDocument) {
    ContractResult.FilteredDeleted
}

private fun FirebaseDocumentDto.requireActiveAndCurrentSchema() {
    when (fields[FirebaseSchema.Fields.DELETED_AT]) {
        null, FirebaseValue.Null -> Unit
        is FirebaseValue.Timestamp, FirebaseValue.PendingServerTimestamp -> throw FilteredDocument()
        else -> malformed(ContractErrorCode.WRONG_TYPE, FirebaseSchema.Fields.DELETED_AT)
    }
    if (requiredNonNegativeInt(FirebaseSchema.Fields.SCHEMA_VERSION).toLong() != FirebaseSchema.CURRENT_VERSION) {
        malformed(ContractErrorCode.INVALID_VALUE, FirebaseSchema.Fields.SCHEMA_VERSION)
    }
}

private fun FirebaseDocumentDto.requiredText(field: String): String = when (val value = fields[field]) {
    is FirebaseValue.Text -> value.value.takeIf { it.isNotBlank() }
        ?: fail(ContractErrorCode.INVALID_VALUE, field)
    null -> fail(ContractErrorCode.MISSING_FIELD, field)
    else -> fail(ContractErrorCode.WRONG_TYPE, field)
}

private fun FirebaseDocumentDto.optionalText(field: String): String? = when (val value = fields[field]) {
    null, FirebaseValue.Null -> null
    is FirebaseValue.Text -> value.value
    else -> malformed(ContractErrorCode.WRONG_TYPE, field)
}

private fun FirebaseDocumentDto.requiredBool(field: String): Boolean = when (val value = fields[field]) {
    is FirebaseValue.Bool -> value.value
    null -> fail(ContractErrorCode.MISSING_FIELD, field)
    else -> fail(ContractErrorCode.WRONG_TYPE, field)
}

private fun FirebaseDocumentDto.requiredTimestamp(field: String): Long = when (val value = fields[field]) {
    is FirebaseValue.Timestamp -> value.epochMillis
    FirebaseValue.PendingServerTimestamp -> clientFallbackMillis
    null -> fail(ContractErrorCode.MISSING_FIELD, field)
    else -> fail(ContractErrorCode.WRONG_TYPE, field)
}

private fun FirebaseDocumentDto.requiredNonNegativeInt(field: String): Int = when (val value = fields[field]) {
    is FirebaseValue.Number -> value.value.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        ?: fail(ContractErrorCode.INVALID_VALUE, field)
    null -> fail(ContractErrorCode.MISSING_FIELD, field)
    else -> fail(ContractErrorCode.WRONG_TYPE, field)
}

private fun fail(code: ContractErrorCode, field: String): Nothing = malformed(code, field)

private fun malformed(code: ContractErrorCode, field: String): Nothing =
    throw MappingFailure(ContractError(code, field))

/** A field-scoped mutation. Whole-document writes are intentionally not representable. */
class FieldPatch(fields: Map<String, FirebaseValue>) {
    val fields: Map<String, FirebaseValue> = fields.toMap()

    init {
        require(this.fields.isNotEmpty())
        require(FirebaseSchema.Fields.TOTAL_ITEMS !in this.fields)
        require(FirebaseSchema.Fields.COMPLETED_ITEMS !in this.fields)
    }
}

/** Models DEC-003d: later writes win only for keys they actually contain. */
fun applyFieldPatches(
    original: Map<String, FirebaseValue>,
    patchesInCommitOrder: Iterable<FieldPatch>,
): Map<String, FirebaseValue> = patchesInCommitOrder.fold(original) { document, patch ->
    document + patch.fields
}

enum class BackendErrorCode {
    UNAUTHENTICATED, PERMISSION_DENIED, UNAVAILABLE, DEADLINE_EXCEEDED,
    NOT_FOUND, ALREADY_EXISTS, INVALID_ARGUMENT, RESOURCE_EXHAUSTED, UNKNOWN,
}

enum class RepositoryErrorCode {
    SESSION_REQUIRED, FORBIDDEN, OFFLINE, TIMEOUT, NOT_FOUND, CONFLICT, INVALID_DATA, QUOTA, UNKNOWN,
}

data class ApplicationError(
    val code: RepositoryErrorCode,
    val canRetry: Boolean,
    val requiresFreshSession: Boolean = false,
)

fun RepositoryErrorCode.toApplicationError(): ApplicationError = when (this) {
    RepositoryErrorCode.SESSION_REQUIRED -> ApplicationError(this, canRetry = false, requiresFreshSession = true)
    RepositoryErrorCode.OFFLINE, RepositoryErrorCode.TIMEOUT, RepositoryErrorCode.UNKNOWN ->
        ApplicationError(this, canRetry = true)
    RepositoryErrorCode.FORBIDDEN, RepositoryErrorCode.NOT_FOUND, RepositoryErrorCode.CONFLICT,
    RepositoryErrorCode.INVALID_DATA, RepositoryErrorCode.QUOTA -> ApplicationError(this, canRetry = false)
}

fun BackendErrorCode.toRepositoryError(): RepositoryErrorCode = when (this) {
    BackendErrorCode.UNAUTHENTICATED -> RepositoryErrorCode.SESSION_REQUIRED
    BackendErrorCode.PERMISSION_DENIED -> RepositoryErrorCode.FORBIDDEN
    BackendErrorCode.UNAVAILABLE -> RepositoryErrorCode.OFFLINE
    BackendErrorCode.DEADLINE_EXCEEDED -> RepositoryErrorCode.TIMEOUT
    BackendErrorCode.NOT_FOUND -> RepositoryErrorCode.NOT_FOUND
    BackendErrorCode.ALREADY_EXISTS -> RepositoryErrorCode.CONFLICT
    BackendErrorCode.INVALID_ARGUMENT -> RepositoryErrorCode.INVALID_DATA
    BackendErrorCode.RESOURCE_EXHAUSTED -> RepositoryErrorCode.QUOTA
    BackendErrorCode.UNKNOWN -> RepositoryErrorCode.UNKNOWN
}
