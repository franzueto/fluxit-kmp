package com.fluxit.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Which session the currently composed UI belongs to.
 *
 * There are exactly two: the signed-out shell, and one specific signed-in user. They are
 * modelled as distinct values rather than as "uid or null" so that "user A" and "user B"
 * can never compare equal, and so that the signed-out shell gets its own teardown
 * boundary too - the auth form is user-derived state as much as a dashboard list is.
 */
sealed interface SessionScope {

    /** No resolved user: the resolving screen, the auth form, the failure screen. */
    data object SignedOut : SessionScope

    /** A resolved user. [uid] is the only thing that distinguishes one from another. */
    data class User(val uid: String) : SessionScope
}

/** The scope a gate state belongs to. Every non-[SessionGateState.Ready] state is signed-out. */
fun SessionGateState.sessionScope(): SessionScope = when (this) {
    is SessionGateState.Ready -> SessionScope.User(user.uid)
    SessionGateState.Resolving,
    SessionGateState.SignedOut,
    is SessionGateState.ResolutionFailed,
    -> SessionScope.SignedOut
}

/**
 * Owns the [ViewModelStore] for the active [SessionScope], and destroys the previous
 * one the moment the scope changes.
 *
 * ## Why this exists: `key(uid)` alone does not do it
 *
 * wrapped the navigation subtree in `key(state.user.uid)`, on the assumption that
 * a change of user would tear down that subtree's `ViewModelStore` along with its
 * composition. Reading the actual Navigation3 sources (`ViewModelStoreNavEntryDecorator`
 * and `DecoratedNavEntries`, both `1.1.1`/`2.10.0`) shows it does not:
 *
 * - `rememberViewModelStoreNavEntryDecorator()` defaults its `viewModelStoreOwner` to
 *   `LocalViewModelStoreOwner.current`, which at the application root is the Activity
 *   (Android) or the root Compose view controller (iOS) - an owner that long outlives
 *   any `key()` block.
 * - Per-entry stores are held in an `EntryViewModel` *inside that owner's* store, keyed
 *   by `NavEntry.contentKey`. `DashboardRoute` is a `data object`, so its content key is
 *   identical for every user.
 * - Those stores are cleared only from `NavEntryDecorator.onPop`, and
 *   `DecoratedNavEntries` invokes `onPop` only for a content key that has left the
 *   *back stack*. Disposing the composition (which is all `key()` does) leaves the back
 *   stack intact, so nothing is popped and nothing is cleared.
 *
 * The consequence is concrete: user A's `DashboardViewModel` - with A's list data in its
 * `StateFlow` and A's Firestore listener in its `viewModelScope` - would be handed
 * straight back to user B.
 *
 * ## What this does instead
 *
 * This holder lives in the root store (so it survives configuration changes, unlike a
 * composition-scoped owner), but hands out a *fresh* [ViewModelStore] per
 * [SessionScope]. Switching scope clears the outgoing store, which calls `onCleared()`
 * on every ViewModel in it, including Navigation3's own `EntryViewModel` - whose
 * `onCleared()` in turn clears every per-entry store it was holding. One boundary,
 * everything user-scoped behind it.
 *
 * It is a plain class with no Compose dependency precisely so the teardown can be
 * asserted in an ordinary unit test rather than only in a composition.
 */
class SessionScopedViewModelStores : ViewModel() {

    private var currentScope: SessionScope? = null
    private var currentStore: ViewModelStore? = null

    /** The scope whose store is currently live, or `null` before the first request. */
    val activeScope: SessionScope? get() = currentScope

    /**
     * The store for [scope], creating it if the active scope is a different one and
     * clearing the outgoing store first. Calling it repeatedly for the same scope
     * returns the same store, so an ordinary recomposition never destroys anything.
     */
    fun storeFor(scope: SessionScope): ViewModelStore {
        val existing = currentStore
        if (existing != null && currentScope == scope) return existing
        clearActiveScope()
        val store = ViewModelStore()
        currentScope = scope
        currentStore = store
        return store
    }

    /** A [ViewModelStoreOwner] over [storeFor], for callers that need the owner type. */
    fun ownerFor(scope: SessionScope): ViewModelStoreOwner {
        val store = storeFor(scope)
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = store
        }
    }

    /**
     * Destroys the active scope's store, clearing every ViewModel in it. Idempotent.
     */
    fun clearActiveScope() {
        if (currentScope == null) return
        currentStore?.clear()
        currentStore = null
        currentScope = null
    }

    override fun onCleared() {
        clearActiveScope()
    }
}
