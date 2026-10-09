package com.fluxit.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation3.scene.SceneInfo
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHistory
import androidx.navigationevent.NavigationEventInfo
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
 * Mirrors the app's back depth in the browser history: entry `k` above the page's own entry
 * stands for "k back steps available". NavDisplay reports one back-history entry per screen
 * below the current one; an open overlay (dialog, menu) on top adds one more, so it gets its
 * own entry, added by the tap that opened it, and back closing it never needs an entry re-added.
 *
 * Entries are only added when the app goes deeper, which happens right after a tap. Chrome
 * skips, on back, a page that added history entries without a user tap (its history
 * manipulation intervention), so entries are never re-added after a back press: the browser's
 * back just moves down the existing entries and each step is dispatched to the app as a back
 * event. When the app goes back on its own (an in-app back button), the history follows with
 * `history.go(-n)`; the entries left above become forward entries, which the next deeper
 * navigation replaces. Browser forward into them is undone, and a reload that lands on one of
 * them returns to the page's own entry.
 */
internal class BrowserBackNavigationEventInput(
    private val history: BrowserHistory = WindowBrowserHistory,
    private val schedule: (() -> Unit) -> Unit = ::queueMicrotask,
    private val isScreen: (NavigationEventInfo) -> Boolean = { it is SceneInfo<*> },
) : NavigationEventInput() {

    private var canGoBack = false

    /** Back steps NavDisplay last reported (screens below the current one). */
    private var screenSteps = 0

    /** Whether the active back handler is an overlay rather than NavDisplay. */
    private var overlayOpen = false

    /** Depth of the current browser history entry (0 = the page's own entry). */
    private var depth = 0

    /** Depth our own `history.go()` is moving to; its `popstate` has not arrived yet. */
    private var pendingDepth: Int? = null

    private var syncScheduled = false

    override fun onAdded(dispatcher: NavigationEventDispatcher) {
        depth = history.currentDepth
        history.setPopStateListener(::onPopState)
        sync()
    }

    override fun onRemoved() {
        history.setPopStateListener(null)
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        canGoBack = hasEnabledHandlers
        scheduleSync()
    }

    override fun onHistoryChanged(history: NavigationEventHistory) {
        val current = history.mergedHistory.getOrNull(history.currentIndex)
        when {
            current == null -> {
                screenSteps = 0
                overlayOpen = false
            }
            isScreen(current) -> {
                screenSteps = history.currentIndex
                overlayOpen = false
            }
            else -> overlayOpen = true
        }
        scheduleSync()
    }

    /**
     * Syncs once the current batch of updates has settled. One navigation updates the back
     * history and the enabled state separately; reacting to each would briefly push an entry
     * on the way back to the dashboard, without a user tap.
     */
    private fun scheduleSync() {
        if (syncScheduled) return
        syncScheduled = true
        schedule {
            syncScheduled = false
            sync()
        }
    }

    private val targetDepth: Int
        get() = if (canGoBack) maxOf(1, screenSteps + if (overlayOpen) 1 else 0) else 0

    private fun sync() {
        if (pendingDepth != null) return
        val target = targetDepth
        when {
            depth < target -> while (depth < target) history.pushEntry(++depth)
            depth > target -> {
                pendingDepth = target
                history.go(target - depth)
            }
        }
    }

    private fun onPopState(newDepth: Int) {
        val pending = pendingDepth
        pendingDepth = null
        val steps = if (pending != null && newDepth == pending) 0 else depth - newDepth
        depth = newDepth
        if (steps > 0) {
            // The app catches up through onHistoryChanged once it has popped. Syncing here would
            // push an entry back (the app has not popped yet), and without a user tap.
            repeat(steps) { if (canGoBack) dispatchOnBackCompleted() }
        } else {
            sync()
        }
    }
}

/** The slice of `window.history` [BrowserBackNavigationEventInput] needs. */
internal interface BrowserHistory {
    /** Depth recorded in the current entry's state; 0 for entries this app did not add. */
    val currentDepth: Int

    fun pushEntry(depth: Int)
    fun go(delta: Int)

    /** Replaces the `popstate` listener; the argument is the new entry's depth. */
    fun setPopStateListener(listener: ((depth: Int) -> Unit)?)
}

internal object WindowBrowserHistory : BrowserHistory {

    private var listener: ((Event) -> Unit)? = null

    override val currentDepth: Int get() = depthOf(window.history.state)

    override fun pushEntry(depth: Int) = window.history.pushState(newDepthState(depth), "")

    override fun go(delta: Int) = window.history.go(delta)

    override fun setPopStateListener(listener: ((depth: Int) -> Unit)?) {
        this.listener?.let { window.removeEventListener("popstate", it) }
        this.listener = listener?.let { onPop ->
            { event: Event -> onPop(depthOf((event as PopStateEvent).state)) }
        }
        this.listener?.let { window.addEventListener("popstate", it) }
    }
}

// Internal, not private: for a private top-level external that takes a Kotlin lambda, the
// Wasm compiler embeds a file-based signature, i.e. this file's absolute path on the build
// machine, in the bundle. See checkWebDistributionForLocalPaths in composeApp/build.gradle.kts.
@JsFun("(task) => queueMicrotask(task)")
internal external fun queueMicrotask(task: () -> Unit)

@JsFun("(depth) => ({ fluxitBackDepth: depth })")
private external fun newDepthState(depth: Int): JsAny

@JsFun("(state) => (state != null && Number.isInteger(state.fluxitBackDepth) && state.fluxitBackDepth > 0) ? state.fluxitBackDepth : 0")
private external fun depthOf(state: JsAny?): Int
