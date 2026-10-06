package com.fluxit.ui.components

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FB-710 regression coverage for [SwipeToDeleteContainer].
 *
 * The screens delete through a swipe, the data layer removes the row, and Undo re-adds the SAME
 * stable id (LazyColumn key). The re-added row must render normally and be swipeable again;
 * previously the swipe state was `rememberSaveable`, so the dismissed (EndToStart) state was
 * restored for the re-added key (stuck red row / `requireOffset` crash).
 *
 * The harness mimics the data layer with a mutable list: "Firestore delete" removes the id after
 * the container reports `onDelete`, "Undo" re-inserts it at the same position.
 */
@RunWith(AndroidJUnit4::class)
class SwipeToDeleteContainerInstrumentedTest {

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

    private fun mutateOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Composable
    private fun Harness(
        ids: SnapshotStateList<String>,
        deleted: MutableList<String>,
        enabled: () -> Boolean = { true },
    ) {
        MaterialTheme {
            LazyColumn {
                items(ids, key = { it }) { id ->
                    SwipeToDeleteContainer(
                        onDelete = { deleted += id },
                        enabled = enabled(),
                    ) {
                        Text(
                            text = "content-$id",
                            modifier = Modifier.fillMaxWidth().height(64.dp).testTag("row-$id"),
                        )
                    }
                }
            }
        }
    }

    private fun swipeRowToDelete(id: String) {
        composeRule.onNodeWithTag("row-$id").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
    }

    @Test
    fun restoredRowAfterDeleteAndUndoRendersNormally() {
        val ids = mutableStateListOf("a", "b", "c")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted) }

        composeRule.onNodeWithTag("row-b").assertIsDisplayed()
        swipeRowToDelete("b")
        assertEquals(listOf("b"), deleted.distinct())

        // Data layer removes the row, then Undo restores the same id.
        mutateOnMain { ids.remove("b") }
        composeRule.waitForIdle()
        mutateOnMain { ids.add(1, "b") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("row-b").assertIsDisplayed()
        composeRule.onNodeWithTag("row-a").assertIsDisplayed()
        composeRule.onNodeWithTag("row-c").assertIsDisplayed()
    }

    @Test
    fun restoredFirstRowAfterDeleteAndUndoDoesNotCrashAndRendersNormally() {
        // Dashboard shape: the deleted row is the first/only visible row and is restored at index 0.
        val ids = mutableStateListOf("a")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted) }

        swipeRowToDelete("a")
        assertEquals(listOf("a"), deleted.distinct())
        mutateOnMain { ids.clear() }
        composeRule.waitForIdle()
        mutateOnMain { ids.add("a") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("row-a").assertIsDisplayed()
    }

    @Test
    fun restoredRowCanBeDeletedAgain() {
        val ids = mutableStateListOf("a", "b")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted) }

        swipeRowToDelete("a")
        val deletesAfterFirstSwipe = deleted.size
        mutateOnMain { ids.remove("a") }
        composeRule.waitForIdle()
        mutateOnMain { ids.add(0, "a") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("row-a").assertIsDisplayed()

        swipeRowToDelete("a")
        assertEquals(listOf("a"), deleted.distinct())
        assertTrue(deleted.size > deletesAfterFirstSwipe, "second swipe on the restored row must delete again")
        mutateOnMain { ids.remove("a") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("row-a").assertDoesNotExist()
        composeRule.onNodeWithTag("row-b").assertIsDisplayed()
    }

    @Test
    fun oneSwipeInvokesOnDeleteExactlyOnce() {
        val ids = mutableStateListOf("a")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted) }

        swipeRowToDelete("a")

        assertEquals(listOf("a"), deleted)
    }

    @Test
    fun deleteWithoutUndoLeavesRowRemoved() {
        val ids = mutableStateListOf("a", "b")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted) }

        swipeRowToDelete("a")
        mutateOnMain { ids.remove("a") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("row-a").assertDoesNotExist()
        composeRule.onNodeWithTag("row-b").assertIsDisplayed()
        assertEquals(listOf("a"), deleted.distinct())
    }

    @Test
    fun disabledContainerBlocksSwipeAndRowStaysInPlace() {
        val ids = mutableStateListOf("a")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted, enabled = { false }) }

        composeRule.onNodeWithTag("row-a").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(emptyList(), deleted)
        composeRule.onNodeWithTag("row-a").assertIsDisplayed()
    }

    @Test
    fun startToEndSwipeNeverDeletes() {
        val ids = mutableStateListOf("a")
        val deleted = mutableListOf<String>()
        setContent { Harness(ids, deleted) }

        composeRule.onNodeWithTag("row-a").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(emptyList(), deleted)
        composeRule.onNodeWithTag("row-a").assertIsDisplayed()
    }

    @Test
    fun restoredRowContentRemainsInteractive() {
        val ids = mutableStateListOf("a")
        val deleted = mutableListOf<String>()
        val clicks = mutableStateOf(0)
        setContent {
            MaterialTheme {
                LazyColumn {
                    items(ids, key = { it }) { id ->
                        SwipeToDeleteContainer(onDelete = { deleted += id }) {
                            Text(
                                text = "content-$id",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .testTag("row-$id")
                                    .clickable { clicks.value++ },
                            )
                        }
                    }
                }
            }
        }

        swipeRowToDelete("a")
        mutateOnMain { ids.clear() }
        composeRule.waitForIdle()
        mutateOnMain { ids.add("a") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("row-a").performClick()
        composeRule.waitForIdle()
        assertEquals(1, clicks.value)
    }
}
