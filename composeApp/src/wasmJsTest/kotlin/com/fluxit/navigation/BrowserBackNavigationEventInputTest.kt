package com.fluxit.navigation

import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [BrowserBackNavigationEventInput] against a real navigation-event dispatcher and a scripted
 * browser history. `FakeHistory` models the history stack; popstate is delivered when the
 * test calls [FakeHistory.deliverPops], as browsers deliver it asynchronously.
 */
class BrowserBackNavigationEventInputTest {

    private class FakeHistory(initial: List<Boolean> = listOf(false)) : BrowserHistory {
        /** true = guard entry. */
        val entries = initial.toMutableList()
        var index = entries.lastIndex
        private var listener: ((Boolean) -> Unit)? = null
        private val pendingPops = mutableListOf<Boolean>()

        override val currentIsGuard: Boolean get() = entries[index]

        override fun pushGuard() {
            while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
            entries += true
            index++
        }

        override fun back() = userBack()

        override fun setPopStateListener(listener: ((isGuard: Boolean) -> Unit)?) {
            this.listener = listener
        }

        /** The browser's back button; popstate follows on [deliverPops]. */
        fun userBack() {
            if (index == 0) return
            index--
            pendingPops += entries[index]
        }

        fun userForward() {
            if (index == entries.lastIndex) return
            index++
            pendingPops += entries[index]
        }

        fun deliverPops() {
            while (pendingPops.isNotEmpty()) listener?.invoke(pendingPops.removeAt(0))
        }
    }

    /** Stands in for NavDisplay: back is enabled while more than one entry is on the stack. */
    private class FakeNavStack(var depth: Int) : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = NavigationEventInfo.None,
        isBackEnabled = depth > 1,
    ) {
        override fun onBackCompleted() {
            depth--
            isBackEnabled = depth > 1
        }

        fun push() {
            depth++
            isBackEnabled = depth > 1
        }

        fun inAppBack() = onBackCompleted()
    }

    private fun setUp(depth: Int, history: FakeHistory = FakeHistory()): Pair<FakeNavStack, FakeHistory> {
        val dispatcher = NavigationEventDispatcher()
        val stack = FakeNavStack(depth)
        dispatcher.addHandler(stack)
        dispatcher.addInput(BrowserBackNavigationEventInput(history))
        return stack to history
    }

    @Test
    fun noGuardIsAddedWhileTheAppCannotGoBack() {
        val (_, history) = setUp(depth = 1)

        assertEquals(listOf(false), history.entries)
    }

    @Test
    fun browserBackPopsTheAppAndRearmsWhileItCanStillGoBack() {
        val (stack, history) = setUp(depth = 1)
        stack.push()
        stack.push()
        assertEquals(listOf(false, true), history.entries)

        history.userBack()
        history.deliverPops()

        assertEquals(2, stack.depth)
        assertEquals(true, history.currentIsGuard)
    }

    @Test
    fun browserBackAtTheLastInnerScreenLeavesNoGuardBehind() {
        val (stack, history) = setUp(depth = 2)

        history.userBack()
        history.deliverPops() // app pops to the root; the input re-arms, then sees back disabled
        history.deliverPops() // its own history.back() lands

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
        assertEquals(false, history.currentIsGuard)

        // The next browser back leaves the app instead of being swallowed.
        history.userBack()
        history.deliverPops()
        assertEquals(1, stack.depth)
    }

    @Test
    fun inAppBackToTheRootRemovesTheGuard() {
        val (stack, history) = setUp(depth = 2)

        stack.inAppBack()
        history.deliverPops()

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
    }

    @Test
    fun browserForwardIntoAStaleGuardIsUndoneWithoutNavigating() {
        val (stack, history) = setUp(depth = 2)
        stack.inAppBack()
        history.deliverPops()

        history.userForward()
        history.deliverPops()
        history.deliverPops()

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
    }

    @Test
    fun aGuardLeftCurrentByAReloadIsRemovedOnStart() {
        val (stack, history) = setUp(depth = 1, history = FakeHistory(listOf(false, true)))

        history.deliverPops()

        assertEquals(1, stack.depth)
        assertEquals(0, history.index)
    }
}
