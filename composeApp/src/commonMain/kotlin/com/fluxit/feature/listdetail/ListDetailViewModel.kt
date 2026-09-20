package com.fluxit.feature.listdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.domain.FluxItem
import com.fluxit.domain.FluxList
import com.fluxit.domain.ItemRepository
import com.fluxit.domain.ListRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ListDetailUiState(
    val list: FluxList? = null,
    val activeItems: List<FluxItem> = emptyList(),
    val completedItems: List<FluxItem> = emptyList(),
    val showCompleted: Boolean = true,
    val composerText: String = "",
    val listDeleted: Boolean = false,
) {
    val totalCount: Int get() = activeItems.size + completedItems.size
    val completedCount: Int get() = completedItems.size
}

class ListDetailViewModel(
    private val listId: String,
    private val listRepository: ListRepository,
    private val itemRepository: ItemRepository,
) : ViewModel() {

    private val showCompleted = MutableStateFlow(true)
    private val composerText = MutableStateFlow("")
    private val listDeleted = MutableStateFlow(false)

    private val pendingUndo = MutableStateFlow<String?>(null)
    val undoItemId: StateFlow<String?> = pendingUndo.asStateFlow()
    private var undoJob: Job? = null

    val uiState: StateFlow<ListDetailUiState> = combine(
        listRepository.observeList(listId),
        itemRepository.observeItems(listId),
        showCompleted,
        composerText,
        listDeleted,
    ) { list, items, show, text, deleted ->
        ListDetailUiState(
            list = list,
            activeItems = items.filter { !it.isCompleted },
            completedItems = items.filter { it.isCompleted },
            showCompleted = show,
            composerText = text,
            listDeleted = deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListDetailUiState())

    fun onComposerChange(text: String) {
        composerText.value = text
    }

    fun submitComposer() {
        val title = composerText.value.trim()
        if (title.isEmpty()) return
        composerText.value = ""
        viewModelScope.launch { itemRepository.addItem(listId, title) }
    }

    fun toggleCompleted(item: FluxItem) {
        viewModelScope.launch { itemRepository.setCompleted(listId, item.id, !item.isCompleted) }
    }

    fun toggleShowCompleted() {
        showCompleted.value = !showCompleted.value
    }

    fun deleteItem(itemId: String) {
        undoJob?.cancel()
        viewModelScope.launch { itemRepository.softDeleteItem(listId, itemId) }
        pendingUndo.value = itemId
        undoJob = viewModelScope.launch {
            delay(5_000)
            pendingUndo.value = null
        }
    }

    fun undoDelete() {
        val id = pendingUndo.value ?: return
        undoJob?.cancel()
        pendingUndo.value = null
        viewModelScope.launch { itemRepository.restoreItem(listId, id) }
    }

    fun dismissUndo() {
        pendingUndo.value = null
    }

    fun clearCompleted() {
        viewModelScope.launch { itemRepository.clearCompleted(listId) }
    }

    fun deleteList() {
        viewModelScope.launch {
            listRepository.softDeleteList(listId)
            listDeleted.value = true
        }
    }
}
