package com.fluxit.domain

/**
 * The loading/loaded/fatal-session state machine shared by every screen ViewModel
 * that renders data from a [ListRepository]/[ItemRepository] observation -
 * [com.fluxit.feature.dashboard.DashboardViewModel]'s list-of-lists and
 * [com.fluxit.feature.listdetail.ListDetailViewModel]'s list-of-items.
 *
 * ** disclosed judgment call - three sealed cases, not five:** the plan/ledger note
 * for this task sketches five distinctions ("never loaded yet," "loaded and genuinely
 * empty," "loaded from cache," "has pending writes," "fatally invalid session"). [Loading]
 * and [FatalSession] are genuinely either/or presentation modes with no payload. The
 * remaining three - loaded-with-data, loaded-and-empty, loaded-from-cache/with-pending-
 * writes - are not mutually exclusive: an *empty* collection can equally be
 * `isFromCache = true` (offline, nothing has synced down yet - genuinely different from a
 * confirmed-empty server response) or carry `hasPendingWrites = true` (a local delete of the
 * last remaining row has not yet been acknowledged). Folding "empty" into its own sealed
 * case without these two flags would silently drop that distinction for the one state where
 * a caller is most likely to ask "empty, or just not synced yet?" - so [Loaded] carries
 * [Loaded.data] plus both flags together, and "empty" is the derived,
 * `data.isEmpty()`-shaped case of [Loaded] a caller (or a test) checks directly, not a
 * fourth sealed case.
 *
 * `FatalSession` deliberately reuses [com.fluxit.domain.auth.AuthSession]/
 * [com.fluxit.domain.auth.AuthError] rather than inventing a parallel
 * session-validity signal: a consuming ViewModel combines its repository observation with
 * [com.fluxit.domain.auth.AuthRepository.session] and reports [FatalSession] whenever that
 * session is not [com.fluxit.domain.auth.AuthSession.Authenticated]. In practice
 * `SessionGate` already tears this screen's whole composition down as
 * soon as the session leaves `Authenticated`, so this case is short-lived, defense-in-depth
 * rendering - it exists so a `combine`d `uiState` never briefly keeps showing stale list/item
 * data during that teardown window, not because the gate itself is expected to fail to
 * react.
 *
 * ** widened trigger, disclosed judgment call:** every ViewModel-side consumer
 * ([com.fluxit.feature.dashboard.DashboardViewModel], [com.fluxit.feature.listdetail.ListDetailViewModel],
 * [com.fluxit.feature.itemdetail.ItemDetailViewModel]) now also reports [FatalSession] when its
 * repository observation itself terminates with an error (a `callbackFlow` closed via
 * `close(exception)` on a real Firestore listener failure, e.g. `PERMISSION_DENIED` once the
 * backing auth token is invalidated), not only when [com.fluxit.domain.auth.AuthRepository.session]
 * itself reports a non-`Authenticated` value. Reused rather than split into a new sealed case
 * because, in this app's `users/{uid}/...`-scoped data model, a terminal listener error is in
 * practice always a session-validity problem - see the `.catch`/`try`-`catch` call sites'
 * own KDoc for the full rationale and the one disclosed caveat (this case can now be reached
 * without [com.fluxit.domain.auth.AuthRepository.session] itself having caught up yet).
 */
sealed interface ScreenLoadState<out T> {

    /** No emission has been observed yet - the very first frame, before the repository (and,
     * for a screen with a fatal-session check, the auth session) has reported anything. */
    data object Loading : ScreenLoadState<Nothing>

    /** The auth session backing this screen is no longer
     * [com.fluxit.domain.auth.AuthSession.Authenticated] while this screen is still composed -
     * see this interface's KDoc for why this is safe, short-lived, defense-in-depth rendering
     * rather than the primary mechanism that reacts to a session becoming invalid. */
    data object FatalSession : ScreenLoadState<Nothing>

    /**
     * At least one emission has been observed and the session is (as far as this ViewModel
     * can tell) still valid. [isFromCache]/[hasPendingWrites] are [RepositorySnapshot]'s two
     * flags, carried through unchanged - see this interface's KDoc for why "empty" is a
     * derived property of this case (`data.isEmpty()`-shaped) rather than its own sealed
     * case.
     */
    data class Loaded<T>(
        val data: T,
        val isFromCache: Boolean,
        val hasPendingWrites: Boolean,
    ) : ScreenLoadState<T>
}
