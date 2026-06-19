@file:Suppress("ktlint:standard:function-naming")

package dev.franzueto.fluxit.feature.itemdetail

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.state.store.ItemDetailEffect
import dev.franzueto.fluxit.shared.state.store.ItemDetailIntent
import org.koin.compose.getKoin
import org.koin.core.parameter.parametersOf

/**
 * Koin/ViewModel glue for the Edit-Item screen (plan/10 §8). Builds an
 * [ItemDetailViewModel] (scoping the store to `viewModelScope`, dispatching
 * `Init`), collects state, and maps the store's one-shot [ItemDetailEffect]s to
 * the back callback + the confirm-discard alert / error banner / §4 permission
 * banner UI state, before handing everything to the stateless [ItemDetailScreen].
 *
 * The `when` over [ItemDetailEffect] is exhaustive, so a new effect variant breaks
 * the build here (the §14 effect-mapping contract).
 *
 * @param itemId the route argument (`item/{itemId}` or `list/{listId}/item/{itemId}`).
 * @param onBack pop the screen.
 */
@Composable
fun ItemDetailRoute(
    itemId: String,
    onBack: () -> Unit,
) {
    val koin = getKoin()
    val context = LocalContext.current
    val id = remember(itemId) { ItemId(itemId) }
    val viewModel =
        viewModel {
            ItemDetailViewModel(id) { scope -> koin.get { parametersOf(scope) } }
        }
    val store = viewModel.store
    val state by store.state.collectAsState()

    var confirmDiscard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionBanner by remember { mutableStateOf<PermissionTarget?>(null) }

    LaunchedEffect(store) {
        store.effects.collect { effect ->
            when (effect) {
                ItemDetailEffect.NavigateBack -> onBack()
                ItemDetailEffect.ConfirmDiscardChanges -> confirmDiscard = true
                ItemDetailEffect.RequestCameraPermission -> permissionBanner = PermissionTarget.Camera
                ItemDetailEffect.RequestPhotoLibraryAccess -> permissionBanner = PermissionTarget.Library
                is ItemDetailEffect.ShowError -> error = effect.message
            }
        }
    }

    // System back is BackClicked, not a silent pop — the store owns the dirty check (§5).
    BackHandler { store.dispatch(ItemDetailIntent.BackClicked) }

    ItemDetailScreen(
        state = state,
        // Clear a stale permission banner once the user re-attempts a photo (§4).
        onIntent = { intent ->
            if (intent is ItemDetailIntent.UpdatePhotoClicked) permissionBanner = null
            store.dispatch(intent)
        },
        chrome =
            ItemDetailChrome(
                error = error,
                confirmDiscard = confirmDiscard,
                // Store only emits ConfirmDiscardChanges (no DiscardConfirmed intent) —
                // the host owns the choice, so Discard just pops (§5).
                onDiscard = {
                    confirmDiscard = false
                    onBack()
                },
                onKeepEditing = { confirmDiscard = false },
                permissionBanner = permissionBanner,
                onOpenSettings = { context.startActivity(appSettingsIntent(context.packageName)) },
            ),
    )
}

/** Deep link to this app's system settings page (§4 "Open Settings"). */
private fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Convenience for `store::dispatch` readability at call sites. */
internal typealias OnItemDetailIntent = (ItemDetailIntent) -> Unit
