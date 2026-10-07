package com.fluxit.feature.swipeundo

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.MainActivity
import com.fluxit.data.DebugSeeder
import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.AuthRepository
import com.fluxit.domain.auth.AuthResult
import com.fluxit.domain.auth.AuthSession
import com.fluxit.domain.auth.AuthUser
import com.fluxit.feature.dashboard.DashboardScreen
import com.fluxit.feature.dashboard.DashboardViewModel
import com.fluxit.feature.listdetail.ListDetailScreen
import com.fluxit.feature.listdetail.ListDetailViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * screen-level regression: the real [DashboardScreen] / [ListDetailScreen] and view models
 * over in-memory repositories. Swipe-delete a row, wait for the data layer to remove it, tap the
 * snackbar's Undo, and require the restored row to be displayed again (no crash, not a stuck
 * dismissed swipe background), then delete it a second time.
 */
@RunWith(AndroidJUnit4::class)
class SwipeUndoScreensInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun launchActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    private fun setContent(content: @Composable () -> Unit) {
        checkNotNull(scenario).onActivity { activity -> activity.setContent(content = content) }
    }

    private fun awaitText(text: String, present: Boolean = true) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTextCompat(text) == present
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCompat(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun dashboardListUndoRestoresDisplayedRowAndAllowsSecondDelete() {
        val lists = InMemoryListRepository(
            listOf(list("l1", "zzswipeundo First"), list("l2", "zzswipeundo Second")),
        )
        val items = InMemoryItemRepository()
        val viewModel = DashboardViewModel(lists, DebugSeeder(lists, items), authenticated())
        setContent {
            MaterialTheme {
                DashboardScreen(onOpenList = {}, onCreateList = {}, viewModel = viewModel)
            }
        }

        awaitText("zzswipeundo First")
        repeat(2) { round ->
            composeRule.onNodeWithText("zzswipeundo First").performTouchInput { swipeLeft() }
            awaitText("zzswipeundo First", present = false)
            composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.onAllNodesWithTextCompat("Undo") }
            composeRule.onNodeWithText("Undo").performClick()
            awaitText("zzswipeundo First")
            composeRule.waitForIdle()
            composeRule.onNodeWithText("zzswipeundo First").assertIsDisplayed()
            composeRule.onNodeWithText("zzswipeundo Second").assertIsDisplayed()
            assertEquals(round + 1, lists.restoreCalls)
        }
        assertTrue(lists.softDeleteCalls >= 2)
    }

    private fun rowX(text: String): Float =
        composeRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.x

    @Test
    fun dashboardFailedSwipeDeleteReturnsTheRowToRest() {
        val lists = InMemoryListRepository(listOf(list("l1", "zzpm02 First")))
        lists.failSoftDelete = IllegalStateException("boom")
        val viewModel = DashboardViewModel(lists, DebugSeeder(lists, InMemoryItemRepository()), authenticated())
        setContent {
            MaterialTheme {
                DashboardScreen(onOpenList = {}, onCreateList = {}, viewModel = viewModel)
            }
        }

        awaitText("zzpm02 First")
        composeRule.onNodeWithText("zzpm02 First").performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = 10_000) { lists.softDeleteCalls == 1 }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("zzpm02 First").assertIsDisplayed()
        assertTrue(rowX("zzpm02 First") >= -1f, "a failed delete must return the row to rest")
    }

    @Test
    fun listDetailFailedSwipeDeleteReturnsTheRowToRest() {
        val lists = InMemoryListRepository(listOf(list("l1", "zzpm02 List")))
        val items = InMemoryItemRepository(listOf(item("i1", "zzpm02 active", completed = false, order = 1.0)))
        items.failSoftDelete = IllegalStateException("boom")
        val viewModel = ListDetailViewModel("l1", lists, items, authenticated())
        setContent {
            MaterialTheme {
                ListDetailScreen(listId = "l1", onBack = {}, onEditList = {}, onOpenItem = {}, viewModel = viewModel)
            }
        }

        awaitText("zzpm02 active")
        composeRule.onNodeWithText("zzpm02 active").performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = 10_000) { items.softDeleteCalls == 1 }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("zzpm02 active").assertIsDisplayed()
        assertTrue(rowX("zzpm02 active") >= -1f, "a failed delete must return the row to rest")
    }

    @Test
    fun listDetailItemUndoRestoresDisplayedActiveAndCompletedRows() {
        val lists = InMemoryListRepository(listOf(list("l1", "zzswipeundo List")))
        val items = InMemoryItemRepository(
            listOf(
                item("i1", "zzswipeundo active", completed = false, order = 1.0),
                item("i2", "zzswipeundo done", completed = true, order = 2.0),
            ),
        )
        val viewModel = ListDetailViewModel("l1", lists, items, authenticated())
        setContent {
            MaterialTheme {
                ListDetailScreen(listId = "l1", onBack = {}, onEditList = {}, onOpenItem = {}, viewModel = viewModel)
            }
        }

        awaitText("zzswipeundo active")
        awaitText("zzswipeundo done")

        for (title in listOf("zzswipeundo active", "zzswipeundo done")) {
            repeat(2) {
                composeRule.onNodeWithText(title).performTouchInput { swipeLeft() }
                awaitText(title, present = false)
                composeRule.waitUntil(timeoutMillis = 10_000) { composeRule.onAllNodesWithTextCompat("Undo") }
                composeRule.onNodeWithText("Undo").performClick()
                awaitText(title)
                composeRule.waitForIdle()
                composeRule.onNodeWithText(title).assertIsDisplayed()
            }
        }
        assertTrue(items.restoreCalls >= 4)
    }
}

private fun authenticated() = object : AuthRepository {
    override val session = MutableStateFlow<AuthSession>(
        AuthSession.Authenticated(AuthUser("swipeundo-uid", email = null)),
    )
    override suspend fun restoreSession() = Unit
    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success
    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success
    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success
    override suspend fun signOut(): AuthResult = AuthResult.Success
}

private fun list(id: String, name: String) =
    FluxList(id, name, ListIcon.CART, ListColor.ORANGE, sortOrder = id.hashCode().toDouble(), createdAt = 0, updatedAt = 0)

private fun item(id: String, title: String, completed: Boolean, order: Double) =
    FluxItem(id, "l1", title, null, completed, null, order, 0, 0)

private class InMemoryListRepository(initial: List<FluxList>) : ListRepository {
    private data class Row(val list: FluxList, val deleted: Boolean = false)

    private val rows = MutableStateFlow(initial.map { Row(it) })
    var softDeleteCalls = 0
    var restoreCalls = 0
    var failSoftDelete: Throwable? = null

    override fun observeListSummaries(): Flow<List<FluxListSummary>> =
        rows.map { all -> all.filter { !it.deleted }.sortedBy { it.list.sortOrder }.map { FluxListSummary(it.list, 0, 0) } }

    override fun observeList(listId: String): Flow<FluxList?> =
        rows.map { all -> all.firstOrNull { it.list.id == listId && !it.deleted }?.list }

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String = error("unused")
    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) = Unit

    override suspend fun softDeleteList(listId: String) {
        softDeleteCalls++
        failSoftDelete?.let { throw it }
        rows.value = rows.value.map { if (it.list.id == listId) it.copy(deleted = true) else it }
    }

    override suspend fun restoreList(listId: String) {
        restoreCalls++
        rows.value = rows.value.map { if (it.list.id == listId) it.copy(deleted = false) else it }
    }

    override suspend fun purgeExpired() = Unit
}

private class InMemoryItemRepository(initial: List<FluxItem> = emptyList()) : ItemRepository {
    private data class Row(val item: FluxItem, val deleted: Boolean = false)

    private val rows = MutableStateFlow(initial.map { Row(it) })
    var restoreCalls = 0
    var softDeleteCalls = 0
    var failSoftDelete: Throwable? = null

    override fun observeItems(listId: String): Flow<List<FluxItem>> =
        rows.map { all -> all.filter { !it.deleted && it.item.listId == listId }.sortedBy { it.item.sortOrder }.map { it.item } }

    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> =
        rows.map { all -> all.firstOrNull { it.item.id == itemId && !it.deleted }?.item }

    override suspend fun addItem(listId: String, title: String) = Unit
    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) = Unit
    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) = Unit
    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?) = Unit

    override suspend fun softDeleteItem(listId: String, itemId: String) {
        softDeleteCalls++
        failSoftDelete?.let { throw it }
        rows.value = rows.value.map { if (it.item.id == itemId) it.copy(deleted = true) else it }
    }

    override suspend fun restoreItem(listId: String, itemId: String) {
        restoreCalls++
        rows.value = rows.value.map { if (it.item.id == itemId) it.copy(deleted = false) else it }
    }

    override suspend fun deleteItem(listId: String, itemId: String) = Unit
    override suspend fun clearCompleted(listId: String) = Unit
}
