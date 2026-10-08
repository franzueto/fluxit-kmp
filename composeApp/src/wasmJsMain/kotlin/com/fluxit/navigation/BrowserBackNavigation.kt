package com.fluxit.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.browser.window
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.Event

/**
 * Connects the browser's back (toolbar button, Android back gesture, iOS Safari edge swipe)
 * to the app's back navigation, the way Compose web already connects the Escape key. Back
 * events go through the window's navigation-event dispatcher, so whatever handles back on
 * Android and iOS (NavDisplay popping the stack, a dialog closing) handles it here too.
 */
@Composable
internal fun BrowserBackNavigation() {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return
    DisposableEffect(dispatcher) {
        val input = BrowserBackNavigationEventInput()
        dispatcher.addInput(input)
        onDispose { dispatcher.removeInput(input) }
    }
}

/**
 * While the app can go back, one extra "guard" entry sits on top of the browser history.
 * Leaving it with the browser's back dispatches a back event to the app, and the guard is
 * pushed again if the app can still go back. When the app can no longer go back (back at
 * the dashboard, signed out), the guard is removed with our own `history.back()`, so the
 * next browser back leaves the page as usual. Browser forward into a stale guard is undone.
 */
internal class BrowserBackNavigationEventInput(
    private val history: BrowserHistory = WindowBrowserHistory,
) : NavigationEventInput() {

    private var canGoBack = false

    /** Whether the current browser history entry is a guard entry. */
    private var onGuard = false

    /** Our own `history.back()` calls whose `popstate` has not arrived yet. */
    private var ownBacks = 0

    override fun onAdded(dispatcher: NavigationEventDispatcher) {
        // A reload keeps the current history entry, which can be a guard from before.
        onGuard = history.currentIsGuard
        history.setPopStateListener(::onPopState)
        sync()
    }

    override fun onRemoved() {
        history.setPopStateListener(null)
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        canGoBack = hasEnabledHandlers
        sync()
    }

    private fun sync() {
        if (ownBacks > 0) return
        if (canGoBack && !onGuard) {
            history.pushGuard()
            onGuard = true
        } else if (!canGoBack && onGuard) {
            ownBacks++
            history.back()
        }
    }

    private fun onPopState(isGuard: Boolean) {
        val leftGuard = onGuard && !isGuard
        onGuard = isGuard
        if (ownBacks > 0) {
            ownBacks--
        } else if (leftGuard && canGoBack) {
            dispatchOnBackCompleted()
        }
        sync()
    }
}

/** The slice of `window.history` [BrowserBackNavigationEventInput] needs. */
internal interface BrowserHistory {
    val currentIsGuard: Boolean
    fun pushGuard()
    fun back()

    /** Replaces the `popstate` listener; the argument tells whether the new entry is a guard. */
    fun setPopStateListener(listener: ((isGuard: Boolean) -> Unit)?)
}

internal object WindowBrowserHistory : BrowserHistory {

    private var listener: ((Event) -> Unit)? = null

    override val currentIsGuard: Boolean get() = isGuardState(window.history.state)

    override fun pushGuard() = window.history.pushState(newGuardState(), "")

    override fun back() = window.history.back()

    override fun setPopStateListener(listener: ((isGuard: Boolean) -> Unit)?) {
        this.listener?.let { window.removeEventListener("popstate", it) }
        this.listener = listener?.let { onPop ->
            { event: Event -> onPop(isGuardState((event as PopStateEvent).state)) }
        }
        this.listener?.let { window.addEventListener("popstate", it) }
    }
}

@JsFun("() => ({ fluxitBackGuard: true })")
private external fun newGuardState(): JsAny

@JsFun("(state) => state != null && state.fluxitBackGuard === true")
private external fun isGuardState(state: JsAny?): Boolean
