package com.fluxit.feature.itemdetail

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxit.MainActivity
import com.fluxit.data.PhotoContent
import com.fluxit.data.PhotoPicker
import com.fluxit.data.PhotoStorage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ItemDetailOperationErrorUiTest {

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
    fun delayedSaveFailureRemainsVisibleAfterUserScrollsAwayFromTop() {
        val itemRepository = DelayedFailingItemRepository()
        val viewModel = ItemDetailViewModel(
            listId = "list",
            itemId = "item",
            itemRepository = itemRepository,
            listRepository = ItemDetailListRepository(),
            photoPicker = EmptyPhotoPicker,
            photoStorage = EmptyPhotoStorage,
            authRepository = SignedInAuthRepository,
        )
        checkNotNull(scenario).onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    ItemDetailScreen("list", "item", onBack = {}, viewModel = viewModel)
                }
            }
        }

        composeRule.waitUntil { !viewModel.uiState.value.isLoading }
        composeRule.onNodeWithText("Milk").performTextReplacement("Changed milk")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil { itemRepository.updateCalls == 1 }
        composeRule.onNodeWithText("Delete Item").performScrollTo()

        itemRepository.failureGate.complete(Unit)
        composeRule.waitUntil { viewModel.uiState.value.saveError != null }
        composeRule.waitUntil {
            runCatching {
                composeRule.onNodeWithTag("operationErrorFeedback").assertIsDisplayed()
            }.isSuccess
        }
        composeRule.onNodeWithTag("operationErrorMessage", useUnmergedTree = true)
            .assertTextEquals("We couldn't save this item.")
        composeRule.onNodeWithTag("operationErrorRetry").assertIsDisplayed()
        composeRule.onNodeWithTag("operationErrorDismiss").assertIsDisplayed()
    }
}

private class DelayedFailingItemRepository : ItemRepository {
    val failureGate = CompletableDeferred<Unit>()
    @Volatile var updateCalls = 0

    private val item = FluxItem(
        id = "item",
        listId = "list",
        title = "Milk",
        description = "A long enough form to scroll",
        isCompleted = false,
        photoRef = null,
        sortOrder = 0.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    override fun observeItems(listId: String): Flow<List<FluxItem>> = flowOf(listOf(item))
    override fun observeItem(listId: String, itemId: String): Flow<FluxItem?> = flowOf(item)
    override suspend fun addItem(listId: String, title: String) = Unit
    override suspend fun updateItem(listId: String, itemId: String, title: String, description: String?) {
        updateCalls++
        failureGate.await()
        error("expected delayed save failure")
    }
    override suspend fun setCompleted(listId: String, itemId: String, completed: Boolean) = Unit
    override suspend fun setPhotoRef(listId: String, itemId: String, photoRef: String?) = Unit
    override suspend fun softDeleteItem(listId: String, itemId: String) = Unit
    override suspend fun restoreItem(listId: String, itemId: String) = Unit
    override suspend fun deleteItem(listId: String, itemId: String) = Unit
    override suspend fun clearCompleted(listId: String) = Unit
}

private class ItemDetailListRepository : ListRepository {
    private val list = FluxList("list", "Groceries", ListIcon.CART, ListColor.PRIMARY_BLUE, 0.0, 1L, 1L)
    override fun observeListSummaries(): Flow<List<FluxListSummary>> = flowOf(emptyList())
    override fun observeList(listId: String): Flow<FluxList?> = flowOf(list)
    override suspend fun createList(name: String, icon: ListIcon, color: ListColor): String = "list"
    override suspend fun updateList(listId: String, name: String, icon: ListIcon, color: ListColor) = Unit
    override suspend fun softDeleteList(listId: String) = Unit
    override suspend fun restoreList(listId: String) = Unit
    override suspend fun purgeExpired() = Unit
}

private object EmptyPhotoPicker : PhotoPicker {
    override suspend fun pickPhoto(): ByteArray? = null
}

private object EmptyPhotoStorage : PhotoStorage {
    override suspend fun uploadPhoto(itemId: String, bytes: ByteArray): String = "photo"
    override suspend fun loadPhoto(photoRef: String): PhotoContent? = null
    override suspend fun deletePhoto(photoRef: String) = Unit
}

private object SignedInAuthRepository : AuthRepository {
    override val session: Flow<AuthSession> = flowOf(AuthSession.Authenticated(AuthUser("user", null)))
    override suspend fun restoreSession() = Unit
    override suspend fun signUp(email: String, password: String): AuthResult = AuthResult.Success
    override suspend fun signIn(email: String, password: String): AuthResult = AuthResult.Success
    override suspend fun sendPasswordResetEmail(email: String): AuthResult = AuthResult.Success
    override suspend fun signOut(): AuthResult = AuthResult.Success
}
