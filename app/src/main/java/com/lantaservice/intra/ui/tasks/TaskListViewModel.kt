package com.lantaservice.intra.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lantaservice.intra.data.model.TicketItem
import com.lantaservice.intra.data.repository.IntraNetworkException
import com.lantaservice.intra.data.repository.IntraRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.lantaservice.intra.util.DateAndTextFormatter
import kotlinx.coroutines.launch
import java.time.LocalDateTime

enum class TicketTab {
    WITHOUT_REPAIRED,
    HAD_REPAIRED
}

data class TaskListUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isAutoRefreshing: Boolean = false,
    val isResolvingHistory: Boolean = false,
    val lastUpdatedTime: String = "",
    val tickets: List<TicketItem> = emptyList(),
    val selectedTab: TicketTab = TicketTab.WITHOUT_REPAIRED,
    val searchQuery: String = "",
    val errorMessage: String? = null,
    val showVpnSettingsHint: Boolean = false
) {
    val withoutRepairedTickets: List<TicketItem>
        get() = filterByQuery(tickets.filter { !it.hasHadRepairedStatus })

    val hadRepairedTickets: List<TicketItem>
        get() = filterByQuery(tickets.filter { it.hasHadRepairedStatus })

    val countWithoutRepaired: Int
        get() = tickets.count { !it.hasHadRepairedStatus }

    val countHadRepaired: Int
        get() = tickets.count { it.hasHadRepairedStatus }

    val visibleTickets: List<TicketItem>
        get() = when (selectedTab) {
            TicketTab.WITHOUT_REPAIRED -> withoutRepairedTickets
            TicketTab.HAD_REPAIRED -> hadRepairedTickets
        }

    private fun filterByQuery(source: List<TicketItem>): List<TicketItem> {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) return source
        return source.filter { t ->
            t.id.toString().contains(q) ||
                t.name.lowercase().contains(q) ||
                t.serviceName.lowercase().contains(q) ||
                t.creatorName.lowercase().contains(q) ||
                t.statusName.lowercase().contains(q) ||
                t.descriptionPreview.lowercase().contains(q)
        }
    }
}

class TaskListViewModel(
    private val repository: IntraRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskListUiState())
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadTickets(isPullToRefresh = false)
    }

    fun selectTab(tab: TicketTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
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
            val result = repository.loadOpenTicketsProgressive { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        tickets = snapshot.tickets,
                        isResolvingHistory = snapshot.isResolvingHistory,
                        errorMessage = null,
                        showVpnSettingsHint = false
                    )
                }
            }
            result.onSuccess { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isResolvingHistory = snapshot.isResolvingHistory,
                        tickets = snapshot.tickets,
                        lastUpdatedTime = DateAndTextFormatter.formatLocalDateTime(LocalDateTime.now()),
                        errorMessage = null,
                        showVpnSettingsHint = false
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
            val result = repository.loadOpenTicketsProgressive { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        tickets = snapshot.tickets,
                        isResolvingHistory = snapshot.isResolvingHistory
                    )
                }
            }
            result.onSuccess { snapshot ->
                _uiState.update { state ->
                    state.copy(
                        isAutoRefreshing = false,
                        isResolvingHistory = snapshot.isResolvingHistory,
                        tickets = snapshot.tickets,
                        lastUpdatedTime = DateAndTextFormatter.formatLocalDateTime(LocalDateTime.now()),
                        errorMessage = null
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
        if (movedToRepairedTab) {
            _uiState.update { it.copy(selectedTab = TicketTab.HAD_REPAIRED) }
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
