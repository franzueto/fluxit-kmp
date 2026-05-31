package dev.franzueto.fluxit.shared.state.store

import dev.franzueto.fluxit.shared.domain.port.AppLogger
import kotlinx.coroutines.CoroutineScope

/**
 * Minimal store backing the Account tab (Phase 07; `plan/05` §4, ADR-004).
 *
 * A v1 placeholder: it exists so the fourth tab routes somewhere real. It holds
 * the app [version][AccountState.version] (supplied by the host) plus a debug
 * [flags][AccountState.flags] map, and turns the two menu taps into navigation
 * [effects][AccountEffect]. No use cases yet — settings/about destinations are
 * fleshed out in later phases.
 */
public class AccountStore(
    scope: CoroutineScope,
    logger: AppLogger,
    version: String,
    flags: Map<String, Boolean> = emptyMap(),
) : BaseStore<AccountState, AccountIntent, AccountEffect>(
        AccountState(version = version, flags = flags),
        scope,
        logger,
    ) {
    override suspend fun reduce(intent: AccountIntent) {
        when (intent) {
            AccountIntent.OpenSettings -> emit(AccountEffect.NavigateToSettings)
            AccountIntent.OpenAbout -> emit(AccountEffect.NavigateToAbout)
        }
    }
}

// ---- AccountStore contract (§11: lives alongside its store). ----

public data class AccountState(
    val version: String = "",
    val flags: Map<String, Boolean> = emptyMap(),
)

public sealed interface AccountIntent {
    public data object OpenSettings : AccountIntent

    public data object OpenAbout : AccountIntent
}

public sealed interface AccountEffect {
    public data object NavigateToSettings : AccountEffect

    public data object NavigateToAbout : AccountEffect
}
