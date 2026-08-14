package com.tiramission.ocisync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.cache.Activity
import com.tiramission.ocisync.core.cache.ActivityStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 历史页状态,见 docs/06-ui-design.md §3.6。 */
class HistoryViewModel(private val activityStore: ActivityStore) : ViewModel() {

    data class UiState(
        val activities: List<Activity> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState(activities = activityStore.recent(50))
        }
    }

    fun clear() {
        viewModelScope.launch {
            activityStore.clear()
            refresh()
        }
    }

    class Factory(private val activityStore: ActivityStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(activityStore) as T
    }
}
