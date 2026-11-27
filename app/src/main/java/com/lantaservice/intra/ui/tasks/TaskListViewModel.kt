package com.lantaservice.intra.ui.tasks

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.model.TicketItem
import com.lantaservice.intra.data.repository.IntraNetworkException
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.storage.PendingOutboxEntry
import com.lantaservice.intra.util.DateAndTextFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime

enum class TicketTab {
    WITHOUT_REPAIRED,
    HAD_REPAIRED
}

@Immutable
data class TaskListUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
    val isResolvingHistory: Boolean = false,
    val lastUpdatedTime: String = "",
    val tickets: List<TicketItem> = emptyList(),
    val withoutRepairedTickets: List<TicketItem> = emptyList(),
    val hadRepairedTickets: List<TicketItem> = emptyList(),
    val countWithoutRepaired: Int = 0,
    val countHadRepaired: Int = 0,
    val outboxByTaskId: Map<Int, PendingOutboxEntry> = emptyMap(),
    val outboxCount: Int = 0,
    val isSyncingOutbox: Boolean = false,
    val selectedTab: TicketTab = TicketTab.WITHOUT_REPAIRED,
    val searchQuery: String = "",
    val errorMessage: String? = null,
    val showVpnSettingsHint: Boolean = false
) {
    val visibleTickets: List<TicketItem>
        get() = when (selectedTab) {
            TicketTab.WITHOUT_REPAIRED -> withoutRepairedTickets
            TicketTab.HAD_REPAIRED -> hadRepairedTickets
        }

    companion object {
        fun recompute(state: TaskListUiState): TaskListUiState {
            val q = state.searchQuery.trim().lowercase()
            val filtered = if (q.isEmpty()) {
                state.tickets
            } else {
                state.tickets.filter { t ->
                    t.id.toString().contains(q) ||
                        t.name.lowercase().contains(q) ||
                        t.serviceName.lowercase().contains(q) ||
                        t.creatorName.lowercase().contains(q) ||
                        t.statusName.lowercase().contains(q) ||
                        t.descriptionPreview.lowercase().contains(q)
                }
            }
            val withoutRep = mutableListOf<TicketItem>()
            val hadRep = mutableListOf<TicketItem>()
            var totalWithout = 0
            var totalHad = 0

            state.tickets.forEach { t ->
                if (t.hasHadRepairedStatus) totalHad++ else totalWithout++
            }
            filtered.forEach { t ->
                if (t.hasHadRepairedStatus) hadRep += t else withoutRep += t
            }
            return state.copy(
                withoutRepairedTickets = withoutRep,
                hadRepairedTickets = hadRep,
                countWithoutRepaired = totalWithout,
                countHadRepaired = totalHad
            )
        }
    }
}

class TaskListViewModel(
    private val repository: IntraRepository
) : ViewModel() {

    private val initialCached = repository.getCachedTicketsForCurrentUser()
    private val initialOutbox = repository.outboxEntries.value.associateBy { it.taskId }

    private val _uiState = MutableStateFlow(
        TaskListUiState.recompute(
            TaskListUiState(
                isLoading = initialCached.isEmpty(),
                tickets = initialCached,
                outboxByTaskId = initialOutbox,
                outboxCount = initialOutbox.size
            )
        )
    )
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            repository.outboxEntries.collect { entries ->
                val map = entries.associateBy { it.taskId }
                _uiState.update { state ->
                    state.copy(outboxByTaskId = map, outboxCount = entries.size)
                }
            }
        }
        viewModelScope.launch {
            repository.isSyncingOutbox.collect { syncing ->
                _uiState.update { it.copy(isSyncingOutbox = syncing) }
            }
        }
        viewModelScope.launch {
            repository.outboxSyncedEvents.collect { notice ->
                if (notice.movedToRepairedTab) {
                    _uiState.update { it.copy(selectedTab = TicketTab.HAD_REPAIRED) }
                }
                silentAutoRefresh()
            }
        }
        loadTickets(isPullToRefresh = false)
    }

    fun selectTab(tab: TicketTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { state ->
            TaskListUiState.recompute(state.copy(searchQuery = query))
        }
    }

    fun syncOutboxNow() {
        viewModelScope.launch {
            repository.syncPendingOutboxNow()
        }
    }

    fun loadTickets(isPullToRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    isLoading = state.tickets.isEmpty() && !isPullToRefresh,
                    isRefreshing = isPullToRefresh,
                    isAutoRefreshing = false,
                    errorMessage = null,
                    showVpnSettingsHint = false
                )
            }
            if (_uiState.value.outboxCount > 0) {
                repository.triggerOutboxSyncAsync()
            }
            val result = repository.loadOpenTicketsProgressive { snapshot ->
                _uiState.update { state ->
                    TaskListUiState.recompute(
                        state.copy(
                            isLoading = false,
                            tickets = snapshot.tickets,
                            isResolvingHistory = snapshot.isResolvingHistory,
                            errorMessage = null,
                            showVpnSettingsHint = false
                        )
                    )
                }
            }
            result.onSuccess { snapshot ->
                _uiState.update { state ->
                    TaskListUiState.recompute(
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isResolvingHistory = snapshot.isResolvingHistory,
                            tickets = snapshot.tickets,
                            lastUpdatedTime = DateAndTextFormatter.formatLocalDateTime(LocalDateTime.now()),
                            errorMessage = null,
                            showVpnSettingsHint = false
                        )
                    )
                }
            }.onFailure { err ->
                val isVpn = (err as? IntraNetworkException)?.isVpnBlocked == true
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isResolvingHistory = false,
                        errorMessage = err.message ?: "Не удалось загрузить заявки",
                        showVpnSettingsHint = isVpn
                    )
                }
            }
        }
    }

    /**
     * Silent background refresh triggered every 60 seconds while screen is active.
     */
    fun silentAutoRefresh() {
        val cur = _uiState.value
        if (cur.isLoading || cur.isRefreshing || cur.isAutoRefreshing) return
        if (loadJob?.isActive == true) return

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isAutoRefreshing = true) }
            if (_uiState.value.outboxCount > 0) {
                repository.triggerOutboxSyncAsync()
            }
            val result = repository.loadOpenTicketsProgressive { snapshot ->
                _uiState.update { state ->
                    TaskListUiState.recompute(
                        state.copy(
                            tickets = snapshot.tickets,
                            isResolvingHistory = snapshot.isResolvingHistory
                        )
                    )
                }
            }
            result.onSuccess { snapshot ->
                _uiState.update { state ->
                    TaskListUiState.recompute(
                        state.copy(
                            isAutoRefreshing = false,
                            isResolvingHistory = snapshot.isResolvingHistory,
                            tickets = snapshot.tickets,
                            lastUpdatedTime = DateAndTextFormatter.formatLocalDateTime(LocalDateTime.now()),
                            errorMessage = null
                        )
                    )
                }
            }.onFailure { err ->
                val isVpn = (err as? IntraNetworkException)?.isVpnBlocked == true
                _uiState.update { state ->
                    state.copy(
                        isAutoRefreshing = false,
                        isResolvingHistory = false,
                        errorMessage = if (state.tickets.isEmpty()) err.message else state.errorMessage,
                        showVpnSettingsHint = if (state.tickets.isEmpty()) isVpn else state.showVpnSettingsHint
                    )
                }
            }
        }
    }

    fun onTicketSaved(movedToRepairedTab: Boolean) {
        val cached = repository.getCachedTicketsForCurrentUser()
        _uiState.update { state ->
            TaskListUiState.recompute(
                state.copy(
                    tickets = cached,
                    selectedTab = if (movedToRepairedTab) TicketTab.HAD_REPAIRED else state.selectedTab
                )
            )
        }
        loadTickets(isPullToRefresh = false)
    }

    fun logoutImmediate() {
        repository.logoutImmediate()
    }

    class Factory(private val repository: IntraRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TaskListViewModel(repository) as T
        }
    }
}
