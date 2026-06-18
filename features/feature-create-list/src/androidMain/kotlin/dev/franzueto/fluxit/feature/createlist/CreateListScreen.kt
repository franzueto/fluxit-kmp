@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.createlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import dev.franzueto.fluxit.core.designsystem.components.FluxItPrimaryButton
import dev.franzueto.fluxit.core.designsystem.components.FluxItScaffold
import dev.franzueto.fluxit.core.designsystem.components.FluxItTextField
import dev.franzueto.fluxit.core.designsystem.components.FluxItTopBarCentered
import dev.franzueto.fluxit.core.designsystem.tokens.FluxItSpacing
import dev.franzueto.fluxit.shared.state.store.CreateListIntent
import dev.franzueto.fluxit.shared.state.store.CreateListState
import dev.franzueto.fluxit.shared.state.store.NameValidation
import dev.franzueto.fluxit.shared.state.store.Submission

/**
 * One-shot-effect chrome for [CreateListScreen] (error banner + §6
 * confirm-discard alert), bundled so the screen signature stays under the
 * detekt parameter cap (cf. `ListDetailChrome`).
 */
data class CreateListChrome(
    val error: String? = null,
    val onErrorDismiss: () -> Unit = {},
    val confirmDiscard: Boolean = false,
    val onDiscard: () -> Unit = {},
    val onKeepEditing: () -> Unit = {},
)

/**
 * Stateless Create/Edit List modal (plan/09 §1/§2): renders [CreateListState]
 * and forwards user actions through [onIntent]. Composed entirely from
 * `core-designsystem` primitives (§13 literal-ban). The top bar's leading text
 * button is Cancel (it dispatches [CreateListIntent.CancelClicked] — the store
 * owns the §6 dirty check); the sticky bottom dock holds the submit button,
 * disabled until the name validates (§4) or while a submit is in flight (§7).
 *
 * One-shot effects are handled in [CreateListRoute] and surfaced back via
 * [chrome], so this composable stays pure state-in → UI-out.
 */
@Composable
fun CreateListScreen(
    state: CreateListState,
    onIntent: OnCreateListIntent,
    chrome: CreateListChrome = CreateListChrome(),
) {
    FluxItScaffold(
        topBar = {
            FluxItTopBarCentered(
                title = if (state.editing) "Edit List" else "New List",
                backLabel = "Cancel",
                onBack = { onIntent(CreateListIntent.CancelClicked) },
            )
        },
        bottomBar = { SubmitDock(state = state, onIntent = onIntent, error = chrome.error) },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = FluxItSpacing.containerPadding,
                        vertical = FluxItSpacing.scaleLg,
                    ),
            verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleXl),
        ) {
            NameSection(state = state, onIntent = onIntent)
            IconGridSection(state = state, onIntent = onIntent)
            ColorRowSection(state = state, onIntent = onIntent)
            ReminderSection(state = state, onIntent = onIntent)
        }
    }

    if (chrome.confirmDiscard) {
        AlertDialog(
            onDismissRequest = chrome.onKeepEditing,
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this list will be lost.") },
            confirmButton = { TextButton(onClick = chrome.onDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = chrome.onKeepEditing) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun NameSection(
    state: CreateListState,
    onIntent: OnCreateListIntent,
) {
    val focusRequester = remember { FocusRequester() }
    // Track "has been focused" so only a real focus *loss* dispatches NameBlurred (§4).
    val hadFocus = remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm)) {
        FluxItTextField(
            value = state.name,
            onValueChange = { onIntent(CreateListIntent.NameChanged(it)) },
            label = "List name",
            placeholder = "e.g., Summer Trip",
            modifier =
                Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hadFocus.value = true
                        } else if (hadFocus.value) {
                            onIntent(CreateListIntent.NameBlurred)
                        }
                    },
        )
        val message = nameErrorMessage(state)
        if (message != null) {
            InlineFieldError(message = message)
        }
    }

    // §3: auto-focus in create mode only (no surprise keyboard over prefilled edits).
    LaunchedEffect(state.editing) {
        if (!state.editing) focusRequester.requestFocus()
    }
}

/** §4 inline error copy — visible only after first blur or a submit attempt. */
internal fun nameErrorMessage(state: CreateListState): String? {
    if (!state.validationVisible) return null
    return when (state.validation) {
        NameValidation.Valid -> null
        NameValidation.Empty -> "Give your list a name."
        NameValidation.TooLong -> "Keep the name under 60 characters."
    }
}

/** §2/§7 submit copy: mode + in-flight feedback (DS button has no spinner slot yet). */
internal fun submitLabel(state: CreateListState): String =
    when {
        state.submission is Submission.Submitting -> if (state.editing) "Saving…" else "Creating…"
        state.editing -> "Save"
        else -> "Create List"
    }

@Composable
private fun SubmitDock(
    state: CreateListState,
    onIntent: OnCreateListIntent,
    error: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(
                    horizontal = FluxItSpacing.containerPadding,
                    vertical = FluxItSpacing.scaleLg,
                ),
        verticalArrangement = Arrangement.spacedBy(FluxItSpacing.scaleSm),
    ) {
        // §7: submission failure keeps the modal open with a banner above the button.
        if (error != null) {
            ErrorBanner(message = error)
        }
        FluxItPrimaryButton(
            label = submitLabel(state),
            onClick = { onIntent(CreateListIntent.CreateClicked) },
            enabled = state.validation == NameValidation.Valid && state.submission !is Submission.Submitting,
        )
    }
}
