package com.fluxit.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FB-710 shared-container regression on the Apple target (Compose runs the same commonMain code). */
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
}
