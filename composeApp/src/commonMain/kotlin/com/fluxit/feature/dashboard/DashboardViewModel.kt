package com.fluxit.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxit.data.DebugSeeder
import com.fluxit.domain.FluxListSummary
import com.fluxit.domain.ListRepository
import com.fluxit.domain.auth.SessionTrace
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardUiState(
    val lists: List<FluxListSummary> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
)

class DashboardViewModel(
    private val listRepository: ListRepository,
    private val seeder: DebugSeeder,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    private val pendingUndo = MutableStateFlow<String?>(null)
    val undoListId: StateFlow<String?> = pendingUndo.asStateFlow()
    private var undoJob: Job? = null

    val uiState: StateFlow<DashboardUiState> =
        combine(
            listRepository.observeListSummaries()
                // FB-104 evidence hook: this is the first user-scoped data listener the
                // app starts (Room today, Firestore from Phase 2). Tracing it lets the
                // manual matrix show, from an ordinary log capture, that it never starts
                // before the session gate has resolved.
                .onStart { SessionTrace.event("user-scoped list listener STARTED") },
            searchQuery,
        ) { lists, query ->
            DashboardUiState(
                lists = if (query.isBlank()) lists
                else lists.filter { it.list.name.contains(query.trim(), ignoreCase = true) },
                searchQuery = query,
                isLoading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        SessionTrace.event("DashboardViewModel created (user-scoped consumer)")
        viewModelScope.launch { listRepository.purgeExpired() }
    }

    fun onSearchChange(query: String) {
        searchQuery.value = query
    }

    fun deleteList(listId: String) {
        undoJob?.cancel()
        viewModelScope.launch { listRepository.softDeleteList(listId) }
        pendingUndo.value = listId
        undoJob = viewModelScope.launch {
            delay(5_000)
            pendingUndo.value = null
        }
    }

    fun undoDelete() {
        val id = pendingUndo.value ?: return
        undoJob?.cancel()
        pendingUndo.value = null
        viewModelScope.launch { listRepository.restoreList(id) }
    }

    fun dismissUndo() {
        pendingUndo.value = null
    }

    fun seedSampleData() {
        viewModelScope.launch { seeder.seed() }
    }
}
