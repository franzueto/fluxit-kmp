package com.fluxit.feature.createlist

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Intent
import com.fluxit.MainActivity
import com.fluxit.data.remote.RepositoryErrorCode
import com.fluxit.data.remote.toApplicationError
import com.fluxit.domain.FluxList
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListColor
import com.fluxit.domain.ListIcon
import com.fluxit.domain.ListRepository
import com.fluxit.ui.components.OperationErrorFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class CreateListOperationErrorUiTest {

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

    @Test
    fun failedCreateIsVisibleAndRetryInvokesTheRealScreenCallback() {
        val repository = FailingListRepository()
        val viewModel = CreateListViewModel(null, repository)
        var createdId: String? = null
        setContent {
            MaterialTheme {
                CreateListScreen(
                    editingId = null,
                    onDismiss = {},
                    onCreated = { createdId = it },
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithText("e.g., Summer Trip").performTextInput("Trip")
        composeRule.onNodeWithText("Create List").performClick()
        composeRule.waitUntil { repository.createCalls == 1 && viewModel.uiState.value.error != null }
        composeRule.waitUntil {
            runCatching {
                composeRule.onNodeWithTag("operationErrorFeedback").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("operationErrorMessage", useUnmergedTree = true)
            .assertTextEquals("We couldn't save this list.")
        composeRule.onNodeWithTag("operationErrorDetail", useUnmergedTree = true)
            .assertTextEquals("Something went wrong while saving your change.")
        composeRule.onNodeWithTag("operationErrorRetry").assertExists()
        composeRule.onNodeWithTag("operationErrorDismiss").assertExists()

        repository.shouldFail = false
        composeRule.onNodeWithTag("operationErrorRetry").performClick()
        composeRule.waitUntil { createdId != null }
        assertEquals("created-list", createdId)
    }

    @Test
    fun dismissClearsFailureWithoutRetrying() {
        val repository = FailingListRepository()
        val viewModel = CreateListViewModel(null, repository)
        setContent {
            MaterialTheme {
                CreateListScreen(
                    editingId = null,
                    onDismiss = {},
                    onCreated = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.onNodeWithText("e.g., Summer Trip").performTextInput("Trip")
        composeRule.onNodeWithText("Create List").performClick()
        composeRule.waitUntil { repository.createCalls == 1 && viewModel.uiState.value.error != null }
        composeRule.waitUntil {
            runCatching {
                composeRule.onNodeWithTag("operationErrorFeedback").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("operationErrorDismiss").performClick()
        composeRule.onNodeWithTag("operationErrorFeedback").assertDoesNotExist()
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun permanentFailureOffersDismissWithoutRetry() {
        setContent {
            MaterialTheme {
                OperationErrorFeedback(
                    message = "We couldn't delete this item.",
                    error = RepositoryErrorCode.FORBIDDEN.toApplicationError(),
                    operationInFlight = false,
                    onRetry = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithTag("operationErrorFeedback").assertIsDisplayed()
        composeRule.onNodeWithTag("operationErrorRetry").assertDoesNotExist()
        composeRule.onNodeWithTag("operationErrorDismiss").assertIsDisplayed()
    }
    private fun setContent(content: @Composable () -> Unit) {
        checkNotNull(scenario).onActivity { activity -> activity.setContent(content = content) }
    }
}

private class FailingListRepository : ListRepository {
    var shouldFail = true
    var createCalls = 0

    override fun observeListSummaries(): Flow<List<FluxListSummary>> = flowOf(emptyList())
    override fun observeList(listId: String): Flow<FluxList?> = flowOf(null)

    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String {
        createCalls++
        if (shouldFail) error("expected create failure")
        return "created-list"
    }

    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) = Unit
    override suspend fun softDeleteList(listId: String) = Unit
    override suspend fun restoreList(listId: String) = Unit
    override suspend fun purgeExpired() = Unit
}
