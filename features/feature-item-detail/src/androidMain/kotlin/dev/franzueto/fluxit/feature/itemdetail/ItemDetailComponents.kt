@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.itemdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.franzueto.fluxit.core.designsystem.components.FluxItDestructiveButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItSectionHeader
import dev.franzueto.fluxit.core.designsystem.components.FluxItTextField
import dev.franzueto.fluxit.core.designsystem.icons.FluxItIcons
import dev.franzueto.fluxit.core.designsystem.icons.Trash
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItColors
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItTypography
import dev.franzueto.fluxit.shared.state.store.ItemDetailIntent
import dev.franzueto.fluxit.shared.state.store.ItemDetailState
import dev.franzueto.fluxit.shared.state.store.NameValidation
import kotlinx.datetime.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** §5/§2 Save copy: in-flight feedback (the DS button has no spinner slot — Phase 09 debt). */
internal fun saveLabel(submitting: Boolean): String = if (submitting) "Saving…" else "Save"

/** §5 Save gate: enabled only with a clean, valid edit that isn't already saving. */
internal fun saveEnabled(state: ItemDetailState): Boolean =
    state.dirty && state.titleValidation == NameValidation.Valid && !state.submitting

/**
 * §2 inline title-error copy. Unlike Create-List there is no `validationVisible`
 * gate (the field is prefilled valid), so an error shows whenever the live title
 * is invalid — i.e. the user cleared it or ran past the 120-char cap.
 */
internal fun titleErrorMessage(state: ItemDetailState): String? =
    when (state.titleValidation) {
        NameValidation.Valid -> null
        NameValidation.Empty -> "Give this item a name."
        NameValidation.TooLong -> "Keep the name under 120 characters."
    }

private val LAST_EDITED_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** §1 footer copy, e.g. "Last edited on Jun 18, 2026" (local time zone). */
internal fun lastEditedLabel(updatedAt: Instant): String {
    val date =
        java.time.Instant
            .ofEpochMilli(updatedAt.toEpochMilliseconds())
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
    return "Last edited on ${date.format(LAST_EDITED_FORMAT)}"
}

/** §1 General Info: name (single-line) + description (multi-line) fields. */
@Composable
internal fun GeneralInfoSection(
    state: ItemDetailState,
    onIntent: OnItemDetailIntent,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleMd)) {
        FluxItSectionHeader(label = "GENERAL INFO")
        Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
            FluxItTextField(
                value = state.editing.title,
                onValueChange = { onIntent(ItemDetailIntent.TitleChanged(it)) },
                label = "Item name",
                placeholder = "e.g., Olive oil",
            )
            val message = titleErrorMessage(state)
            if (message != null) {
                Text(text = message, style = FluxItTypography.labelSm, color = FluxItColors.accentRose)
            }
        }
        FluxItTextField(
            value = state.editing.description.orEmpty(),
            onValueChange = { onIntent(ItemDetailIntent.DescriptionChanged(it.ifEmpty { null })) },
            label = "Description",
            placeholder = "Add notes, brand, quantity…",
            singleLine = false,
            minLines = DESCRIPTION_MIN_LINES,
        )
    }
}

/** §1 full-width destructive Delete row (rose-tinted DS button). */
@Composable
internal fun DeleteSection(onIntent: OnItemDetailIntent) {
    FluxItDestructiveButton(
        label = "Delete Item",
        trashIcon = FluxItIcons.Trash,
        onClick = { onIntent(ItemDetailIntent.DeleteClicked) },
    )
}

/** §1 centered "Last edited on …" footer. */
@Composable
internal fun LastEditedFooter(updatedAt: Instant) {
    Text(
        text = lastEditedLabel(updatedAt),
        style = FluxItTypography.captionXs,
        color = FluxItColors.textMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** §5 submission-failure banner above the Save dock. */
@Composable
internal fun ErrorBanner(message: String) {
    Text(
        text = message,
        style = FluxItTypography.labelSm,
        color = FluxItColors.accentRose,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val DESCRIPTION_MIN_LINES = 4
