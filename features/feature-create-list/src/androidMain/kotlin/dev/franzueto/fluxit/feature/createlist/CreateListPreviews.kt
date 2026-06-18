@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.createlist

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.franzueto.fluxit.core.designsystem.theme.FluxItTheme
import dev.franzueto.fluxit.shared.domain.model.ColorToken
import dev.franzueto.fluxit.shared.domain.model.FluxItIconRef
import dev.franzueto.fluxit.shared.state.store.CreateListState
import dev.franzueto.fluxit.shared.state.store.NameValidation
import dev.franzueto.fluxit.shared.state.store.Submission

// The §15 snapshot matrix, rendered as previews instead (snapshot infra is
// deferred to v2 — plan/09 §0 decision c).

@Preview(showBackground = true)
@Composable
private fun PreviewCreateInitial() {
    FluxItTheme {
        CreateListScreen(state = CreateListState(), onIntent = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewCreateFilled() {
    FluxItTheme {
        CreateListScreen(
            state =
                CreateListState(
                    name = "Summer Trip",
                    selectedIcon = FluxItIconRef.PLANE,
                    selectedColor = ColorToken.ACCENT_SKY,
                    validation = NameValidation.Valid,
                ),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewValidationError() {
    FluxItTheme {
        CreateListScreen(
            state =
                CreateListState(
                    name = "",
                    validation = NameValidation.Empty,
                    validationVisible = true,
                ),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSubmitting() {
    FluxItTheme {
        CreateListScreen(
            state =
                CreateListState(
                    name = "Groceries",
                    validation = NameValidation.Valid,
                    submission = Submission.Submitting,
                ),
            onIntent = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewSubmissionError() {
    FluxItTheme {
        CreateListScreen(
            state =
                CreateListState(
                    name = "Groceries",
                    validation = NameValidation.Valid,
                    submission = Submission.Error("Couldn't save your list."),
                ),
            onIntent = {},
            chrome = CreateListChrome(error = "Couldn't save your list."),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewEditPrefilled() {
    FluxItTheme {
        CreateListScreen(
            state =
                CreateListState(
                    name = "Home Renovation",
                    selectedIcon = FluxItIconRef.HOME,
                    selectedColor = ColorToken.ACCENT_EMERALD,
                    validation = NameValidation.Valid,
                    editing = true,
                ),
            onIntent = {},
        )
    }
}
