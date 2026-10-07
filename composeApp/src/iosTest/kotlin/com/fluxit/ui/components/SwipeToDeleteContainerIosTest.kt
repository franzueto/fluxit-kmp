package com.fluxit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** shared-container regression on the Apple target (Compose runs the same commonMain code). */
@OptIn(ExperimentalTestApi::class)
class SwipeToDeleteContainerIosTest {
    @Test
    fun restoredRowAfterDeleteAndUndoRendersAndCanBeDeletedAgain() = runComposeUiTest {
        val ids = mutableStateListOf("a", "b")
        val deleted = mutableListOf<String>()
        setContent {
            MaterialTheme {
                LazyColumn {
                    items(ids, key = { it }) { id ->
                        SwipeToDeleteContainer(onDelete = { deleted += id }) {
                            Text("content-$id", Modifier.fillMaxWidth().height(64.dp).testTag("row-$id"))
                        }
                    }
                }
            }
        }

        repeat(2) { round ->
            val deletesBefore = deleted.size
            onNodeWithTag("row-a").performTouchInput { swipeLeft() }
            waitForIdle()
            assertTrue(deleted.size > deletesBefore, "swipe $round must delete")
            assertEquals(listOf("a"), deleted.distinct())
            ids.remove("a")
            waitForIdle()
            ids.add(0, "a")
            waitForIdle()
            onNodeWithTag("row-a").assertIsDisplayed()
            onNodeWithTag("row-b").assertIsDisplayed()
        }
    }

    @Test
    fun resetSignalReturnsASwipedRowToRestAndItCanBeSwipedAgain() = runComposeUiTest {
        val ids = mutableStateListOf("a")
        val deleted = mutableListOf<String>()
        val resetSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        setContent {
            MaterialTheme {
                LazyColumn {
                    items(ids, key = { it }) { id ->
                        SwipeToDeleteContainer(onDelete = { deleted += id }, resetSignal = resetSignal) {
                            Text("content-$id", Modifier.fillMaxWidth().height(64.dp).testTag("row-$id"))
                        }
                    }
                }
            }
        }

        onNodeWithTag("row-a").performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(listOf("a"), deleted)
        assertTrue(rowLeft("a") < -1f, "the row stays swiped away until the delete outcome is known")

        resetSignal.tryEmit(Unit)
        waitForIdle()
        assertTrue(rowLeft("a") >= -1f, "a failed delete must return the row to rest")

        onNodeWithTag("row-a").performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(listOf("a", "a"), deleted, "the reset row must be deletable again")
    }

    @Test
    fun containerDisabledMidSwipeSkipsOnDeleteAndRowReturnsToRest() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        val enabled = mutableStateOf(true)
        setContent {
            MaterialTheme {
                LazyColumn {
                    items(listOf("a"), key = { it }) { id ->
                        SwipeToDeleteContainer(onDelete = { deleted += id }, enabled = enabled.value) {
                            Text("content-$id", Modifier.fillMaxWidth().height(64.dp).testTag("row-$id"))
                        }
                    }
                }
            }
        }

        onNodeWithTag("row-a").performTouchInput {
            down(Offset(width * 0.9f, centerY))
            moveTo(Offset(width * 0.1f, centerY))
        }
        enabled.value = false
        onNodeWithTag("row-a").performTouchInput { up() }
        waitForIdle()

        assertEquals(emptyList(), deleted, "a row disabled mid-swipe must not delete")
        assertTrue(rowLeft("a") >= -1f, "a row disabled mid-swipe must return to rest, not stay swiped away")
    }

    @Test
    fun containerDisabledWhileSettlingSkipsOnDeleteAndRowReturnsToRest() = runComposeUiTest {
        val deleted = mutableListOf<String>()
        val enabled = mutableStateOf(true)
        setContent {
            MaterialTheme {
                LazyColumn {
                    items(listOf("a"), key = { it }) { id ->
                        SwipeToDeleteContainer(onDelete = { deleted += id }, enabled = enabled.value) {
                            Text("content-$id", Modifier.fillMaxWidth().height(64.dp).testTag("row-$id"))
                        }
                    }
                }
            }
        }

        // Release the finger past the threshold, then disable the row while it is still settling.
        mainClock.autoAdvance = false
        onNodeWithTag("row-a").performTouchInput { swipeLeft() }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        enabled.value = false
        mainClock.autoAdvance = true
        waitForIdle()

        assertEquals(emptyList(), deleted, "a row disabled while settling must not delete")
        assertTrue(rowLeft("a") >= -1f, "a row disabled while settling must return to rest, not stay swiped away")
    }

    /** Horizontal position of the row content in the root; about 0 at rest, far negative once swiped away. */
    private fun ComposeUiTest.rowLeft(id: String): Float =
        onNodeWithTag("row-$id").fetchSemanticsNode().positionInRoot.x
}
