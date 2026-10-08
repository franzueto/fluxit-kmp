package com.fluxit.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/** The back-stack guards used by [AppNavHost]: back can never empty the stack. */
class BackStackGuardTest {

    @Test
    fun systemBackPopsTheTopEntryButNeverTheRoot() {
        val stack = mutableListOf<AppRoute>(DashboardRoute, ListDetailRoute("l1"))

        stack.popUnlessRoot()
        stack.popUnlessRoot()
        stack.popUnlessRoot()

        assertEquals(listOf<AppRoute>(DashboardRoute), stack)
    }

    @Test
    fun aScreensBackPopsOnlyWhileItIsTheTopEntry() {
        val item = ItemDetailRoute("l1", "i1")
        val stack = mutableListOf(DashboardRoute, ListDetailRoute("l1"), item)

        stack.popIfTop(item)
        // Repeated taps on the item screen while it animates out must not pop the list.
        stack.popIfTop(item)
        stack.popIfTop(item)

        assertEquals(listOf(DashboardRoute, ListDetailRoute("l1")), stack)
    }

    @Test
    fun rapidBackTapsOnEveryScreenStopAtTheDashboard() {
        val list = ListDetailRoute("l1")
        val stack = mutableListOf(DashboardRoute, list)

        repeat(3) { stack.popIfTop(list) }
        stack.popIfTop(DashboardRoute)

        assertEquals(listOf<AppRoute>(DashboardRoute), stack)
    }
}
