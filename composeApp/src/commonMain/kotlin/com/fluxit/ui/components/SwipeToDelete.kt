package com.fluxit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxit.ui.theme.FluxCardShape
import fluxit.composeapp.generated.resources.Res
import fluxit.composeapp.generated.resources.content_description_delete
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.stringResource

/**
 * Swipe-to-delete wrapper (end-to-start only).
 *
 * The swipe state is intentionally `remember`ed, NOT `rememberSaveable` (which is what
 * `rememberSwipeToDismissBoxState` uses). A deleted row settles at `EndToStart`; inside a keyed
 * `LazyColumn` a saveable state would be restored for the same key when Undo re-adds the row,
 * leaving it dismissed (stuck error-colored row, or an `AnchoredDraggableState` "offset was read
 * before being initialized" crash). A plain `remember` is discarded with the removed row, so a
 * restored row always starts `Settled`.
 *
 * [onDelete] fires once per swipe, after the row has settled at the dismissed position, instead of
 * from the deprecated `confirmValueChange` callback (which can be invoked repeatedly per gesture).
 *
 * The row always returns to its resting position when the delete is not carried out: if [enabled]
 * turned false while the swipe was settling, [onDelete] is skipped and the row resets by itself;
 * if the delete was started but failed (the row stays in the list), the caller emits on
 * [resetSignal] to bring it back.
 */
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetSignal: Flow<Unit> = emptyFlow(),
    content: @Composable () -> Unit,
) {
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionalThreshold,
        )
    }
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }
            .filter { it == SwipeToDismissBoxValue.EndToStart }
            .collect { if (currentEnabled) currentOnDelete() else state.reset() }
    }
    LaunchedEffect(state, resetSignal) {
        resetSignal.collect { if (state.currentValue != SwipeToDismissBoxValue.Settled) state.reset() }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error, FluxCardShape)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.content_description_delete),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        content = { content() },
    )
}
