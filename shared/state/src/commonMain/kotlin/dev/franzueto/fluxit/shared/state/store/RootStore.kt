package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.model.ItemId
import dev.franzueto.fluxit.shared.domain.model.ListId
import dev.franzueto.fluxit.shared.domain.port.AppLogger
import dev.franzueto.fluxit.shared.domain.usecase.app.InitProgress
import dev.franzueto.fluxit.shared.domain.usecase.app.InitializeApp
import dev.franzueto.fluxit.shared.state.error.userMessage
import dev.franzueto.fluxit.shared.state.navigation.DeepLink
import dev.franzueto.fluxit.shared.state.navigation.Tab
import kotlinx.coroutines.CoroutineScope

/**
 * App-level store backing the splash + tab host (`plan/05` §4, ADR-014).
 *
 * On [RootIntent.AppStarted] it runs the [InitializeApp] composite use case and
 * folds its [InitProgress] stream into [RootState.init]; a startup failure both
 * lands in state (so the splash can show a retry surface) **and** fires a
 * one-shot [RootEffect.ShowFatalError].
 *
 * Unlike per-screen stores (which are `@Factory`-scoped), `RootStore` is meant
 * to live the whole process (`@Single`) — but Koin wiring is Phase 06's job (no
 * DI graph is assembled yet; use cases aren't registered). For now it takes its
 * single use-case dependency by constructor, exactly like the domain use cases
 * (ADR-007b). See `plan/05` §8.
 */
public class RootStore(
    scope: CoroutineScope,
    logger: AppLogger,
    private val initializeApp: InitializeApp,
) : BaseStore<RootState, RootIntent, RootEffect>(RootState(), scope, logger) {
    override suspend fun reduce(intent: RootIntent) {
        when (intent) {
            RootIntent.AppStarted -> initialize()
            is RootIntent.TabSelected -> update { copy(currentTab = intent.tab) }
            is RootIntent.OpenDeepLink -> openDeepLink(intent.url)
        }
    }

    /**
     * Route a `fluxit://` deep link (reminder taps, plan/06 §5) to the matching
     * detail screen. A malformed / unknown URL is logged and dropped — the user
     * stays wherever they were rather than being yanked to an error surface.
     */
    private suspend fun openDeepLink(url: String) {
        when (val link = DeepLink.parse(url)) {
            is DeepLink.List -> emit(RootEffect.NavigateToList(link.id))
            is DeepLink.Item -> emit(RootEffect.NavigateToItem(link.id))
            null -> logger.warn(TAG, "ignoring unparseable deep link: $url")
        }
    }

    private suspend fun initialize() {
        initializeApp().collect { progress ->
            when (progress) {
                InitProgress.Started -> update { copy(init = InitState.Initializing) }
                InitProgress.RemindersRehydrated -> logger.info(TAG, "reminders rehydrated")
                InitProgress.Completed -> update { copy(init = InitState.Ready) }
                is InitProgress.Failed -> {
                    val message = progress.error.userMessage
                    logger.warn(TAG, "app startup failed: $message")
                    update { copy(init = InitState.Failed(message)) }
                    emit(RootEffect.ShowFatalError(message))
                }
            }
        }
    }

    private companion object {
        const val TAG = "RootStore"
    }
}

// ---- RootStore contract (§11: lives alongside its store). ----

public data class RootState(
    val init: InitState = InitState.Initializing,
    val currentTab: Tab = Tab.Lists,
)

/** Startup lifecycle. `state` is always sufficient to render the splash / shell. */
public sealed interface InitState {
    public data object Initializing : InitState

    public data object Ready : InitState

    public data class Failed(
        val message: String,
    ) : InitState
}

public sealed interface RootIntent {
    /** Fired once when the app process / scene starts. Runs `InitializeApp`. */
    public data object AppStarted : RootIntent

    public data class TabSelected(
        val tab: Tab,
    ) : RootIntent

    /**
     * A `fluxit://` deep link arrived (reminder tap; plan/06 §5). The shell
     * hands the raw URL string straight through — parsing lives in
     * [DeepLink.parse] so it stays platform-agnostic and unit-tested.
     */
    public data class OpenDeepLink(
        val url: String,
    ) : RootIntent
}

public sealed interface RootEffect {
    /** v2 placeholder — onboarding isn't built yet (no consumer in v1). */
    public data object NavigateToOnboarding : RootEffect

    /** A `fluxit://list/{id}` deep link resolved — push that list's detail. */
    public data class NavigateToList(
        val id: ListId,
    ) : RootEffect

    /** A `fluxit://item/{id}` deep link resolved — push that item's detail. */
    public data class NavigateToItem(
        val id: ItemId,
    ) : RootEffect

    public data class ShowFatalError(
        val message: String,
    ) : RootEffect
}
