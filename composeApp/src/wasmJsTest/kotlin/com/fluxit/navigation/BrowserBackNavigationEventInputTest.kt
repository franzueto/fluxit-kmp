package com.fluxit.navigation

import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [BrowserBackNavigationEventInput] against a real navigation-event dispatcher and a scripted
 * browser history. `FakeHistory` models the history stack, delivers popstate when the test
 * calls [FakeHistory.deliverPops] (browsers deliver it asynchronously), and models Chrome's
 * history manipulation intervention: an entry the page was on when it pushed without a user
 * tap is skipped by the back button.
 */
class BrowserBackNavigationEventInputTest {

    private class FakeHistory(initialDepths: List<Int> = listOf(0)) : BrowserHistory {
        /** Depth stored in each entry; index 0 is the page's own entry. */
        val entries = initialDepths.toMutableList()
        val skippable = MutableList(initialDepths.size) { false }
        var index = entries.lastIndex
        var userActivation = false
        var leftThePage = false
        private var listener: ((Int) -> Unit)? = null
        private val pendingPops = mutableListOf<Int>()
        private val tasks = mutableListOf<() -> Unit>()

        /** The input's scheduler: like a microtask, tasks run once the current event is done. */
        fun schedule(task: () -> Unit) {
            tasks += task
        }

        fun runTasks() {
            while (tasks.isNotEmpty()) tasks.removeAt(0)()
        }

        override val currentDepth: Int get() = entries[index]

        override fun pushEntry(depth: Int) {
            while (entries.lastIndex > index) {
                entries.removeAt(entries.lastIndex)
                skippable.removeAt(skippable.lastIndex)
            }
            if (!userActivation) skippable[index] = true
            entries += depth
            skippable += false
            index++
        }

        override fun go(delta: Int) = moveTo(index + delta)

        override fun setPopStateListener(listener: ((depth: Int) -> Unit)?) {
            this.listener = listener
        }

        /** The browser's back button or gesture, skipping entries Chrome would skip. */
        fun userBack() {
            var target = index - 1
            while (target >= 0 && skippable[target]) target--
            if (target < 0) leftThePage = true else moveTo(target)
        }

        fun userForward() = moveTo(index + 1)

        private fun moveTo(target: Int) {
            if (target !in entries.indices || target == index) return
            index = target
            pendingPops += entries[index]
        }

        fun deliverPops() {
            runTasks()
            while (pendingPops.isNotEmpty()) {
                listener?.invoke(pendingPops.removeAt(0))
                runTasks()
            }
        }

        /** Runs [action] as if inside a user tap; its follow-up tasks still count as the tap. */
        fun tap(action: () -> Unit) {
            userActivation = true
            action()
            runTasks()
            userActivation = false
        }
    }

    /** Stands in for NavDisplay's SceneInfo. */
    private object ScreenInfo : NavigationEventInfo()

    /** Stands in for NavDisplay: one back-history entry per screen below the current one. */
    private class FakeNavStack(depth: Int) : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = ScreenInfo,
        isBackEnabled = depth > 1,
    ) {
        var depth = depth
            private set

        init {
            publish()
        }

        override fun onBackCompleted() {
            depth--
            publish()
        }

        fun push() {
            depth++
            publish()
        }

        fun inAppBack() = onBackCompleted()

        private fun publish() {
            setInfo(ScreenInfo, List(depth - 1) { ScreenInfo }, emptyList())
            isBackEnabled = depth > 1
        }
    }

    private class Overlay : NavigationEventHandler<NavigationEventInfo>(NavigationEventInfo.None, isBackEnabled = true) {
        var closed = false

        override fun onBackCompleted() {
            closed = true
            remove()
        }
    }

    private fun setUp(depth: Int, history: FakeHistory = FakeHistory()): Triple<NavigationEventDispatcher, FakeNavStack, FakeHistory> {
        val dispatcher = NavigationEventDispatcher()
        val stack = FakeNavStack(depth)
        dispatcher.addHandler(stack)
        dispatcher.addInput(BrowserBackNavigationEventInput(history, history::schedule) { it === ScreenInfo })
        history.runTasks()
        return Triple(dispatcher, stack, history)
    }

    @Test
    fun noEntryIsAddedWhileTheAppCannotGoBack() {
        val (_, _, history) = setUp(depth = 1)

        assertEquals(listOf(0), history.entries)
    }

    @Test
    fun goingDeeperAddsOneEntryPerScreen() {
        val (_, stack, history) = setUp(depth = 1)

        history.tap { stack.push() }
        history.tap { stack.push() }

        assertEquals(listOf(0, 1, 2), history.entries)
        assertEquals(2, history.index)
    }

    @Test
    fun repeatedBrowserBackWalksBackToTheDashboardEvenWithChromesIntervention() {
        // The reported Android case: item detail -> back -> list -> back must reach the dashboard.
        val (_, stack, history) = setUp(depth = 1)
        history.tap { stack.push() }
        history.tap { stack.push() }

        history.userBack()
        history.deliverPops()
        assertEquals(2, stack.depth)

        history.userBack()
        history.deliverPops()
        assertEquals(1, stack.depth)
        assertEquals(false, history.leftThePage)

        history.userBack()
        assertEquals(true, history.leftThePage)
    }

    @Test
    fun browserBackNeverAddsEntries() {
        val (_, stack, history) = setUp(depth = 1)
        history.tap { stack.push() }
        history.tap { stack.push() }

        history.userBack()
        history.deliverPops()
        history.userBack()
        history.deliverPops()

        assertEquals(listOf(0, 1, 2), history.entries)
        assertEquals(listOf(false, false, false), history.skippable)
    }

    @Test
    fun twoQuickBrowserBacksPopTwoScreens() {
        val (_, stack, history) = setUp(depth = 1)
        history.tap { stack.push() }
        history.tap { stack.push() }

        history.userBack()
        history.userBack()
        history.deliverPops()

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
    }

    @Test
    fun inAppBackMovesTheHistoryBackAndTheNextScreenReplacesTheStaleEntry() {
        val (_, stack, history) = setUp(depth = 1)
        history.tap { stack.push() }
        history.tap { stack.push() }

        history.tap { stack.inAppBack() }
        history.deliverPops()
        assertEquals(1, history.index)

        history.tap { stack.push() }
        assertEquals(listOf(0, 1, 2), history.entries)
        assertEquals(2, history.index)
    }

    @Test
    fun browserForwardIntoAStaleEntryIsUndoneWithoutNavigating() {
        val (_, stack, history) = setUp(depth = 2)
        history.tap { stack.inAppBack() }
        history.deliverPops()

        history.userForward()
        history.deliverPops()
        history.deliverPops()

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
    }

    @Test
    fun browserBackClosesAnOpenDialog() {
        val (dispatcher, _, history) = setUp(depth = 1)
        val dialog = Overlay()
        history.tap { dispatcher.addHandler(dialog) }
        assertEquals(1, history.index)

        history.userBack()
        history.deliverPops()

        assertEquals(true, dialog.closed)
        assertEquals(0, history.index)
        assertEquals(listOf(false, false), history.skippable)
    }

    @Test
    fun aDialogOnAnInnerScreenGetsItsOwnEntrySoClosingItWithBackAddsNone() {
        // Review case: item detail -> delete dialog -> back closes it -> back -> back.
        val (dispatcher, stack, history) = setUp(depth = 1)
        history.tap { stack.push() }
        history.tap { stack.push() }
        val dialog = Overlay()
        history.tap { dispatcher.addHandler(dialog) }
        assertEquals(listOf(0, 1, 2, 3), history.entries)

        history.userBack()
        history.deliverPops()
        assertEquals(true, dialog.closed)
        assertEquals(3, stack.depth)

        history.userBack()
        history.deliverPops()
        assertEquals(2, stack.depth)
        history.userBack()
        history.deliverPops()
        assertEquals(1, stack.depth)
        assertEquals(false, history.leftThePage)
        assertEquals(listOf(false, false, false, false), history.skippable)
    }

    @Test
    fun aDialogClosedInTheAppDropsItsEntry() {
        val (dispatcher, stack, history) = setUp(depth = 2)
        val dialog = Overlay()
        history.tap { dispatcher.addHandler(dialog) }
        assertEquals(2, history.index)

        history.tap { dialog.remove() }
        history.deliverPops()

        assertEquals(1, history.index)
        assertEquals(2, stack.depth)
    }

    @Test
    fun aReloadOnADeepEntryReturnsToThePagesOwnEntry() {
        val (_, stack, history) = setUp(depth = 1, history = FakeHistory(listOf(0, 1, 2)))

        history.deliverPops()

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
    }
}
