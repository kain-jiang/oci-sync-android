package com.tiramission.ocisync.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.config.Shortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页状态:shortcuts 列表。 */
class HomeViewModel(private val configLoader: ConfigLoader) : ViewModel() {

    data class UiState(
        val shortcuts: List<Pair<String, Shortcut>> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 每次进入首页调用,保证 settings 里的改动立即可见。 */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = UiState(shortcuts = configLoader.getAllShortcuts())
        }
    }

    class Factory(private val configLoader: ConfigLoader) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(configLoader) as T
    }
}
