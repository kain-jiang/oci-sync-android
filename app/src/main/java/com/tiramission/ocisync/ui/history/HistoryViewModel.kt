package com.tiramission.ocisync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.cache.Activity
import com.tiramission.ocisync.core.cache.ActivityStore
import com.tiramission.ocisync.core.cache.ActivityType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 历史页状态,见 docs/06-ui-design.md §3.6(含类型筛选)。 */
class HistoryViewModel(private val activityStore: ActivityStore) : ViewModel() {

    data class UiState(
        val activities: List<Activity> = emptyList(),
        val filter: ActivityType? = null,   // null = 全部
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState(filter = _uiState.value.filter, activities = activityStore.recent(50))
        }
    }

    fun setFilter(filter: ActivityType?) {
        _uiState.value = _uiState.value.copy(filter = filter)
        viewModelScope.launch {
            _uiState.value = UiState(
                filter = filter,
                activities = activityStore.recent(50).filter { filter == null || it.type == filter },
            )
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
