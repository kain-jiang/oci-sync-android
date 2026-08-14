package com.tiramission.ocisync.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.model.ArtifactInfo
import com.tiramission.ocisync.core.model.SyncService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 仓库浏览状态:查询 + 结果 + 行操作,见 docs/06-ui-design.md §3.4。 */
class ListViewModel(private val syncService: SyncService) : ViewModel() {

    data class UiState(
        val ref: String = "",
        val labelFilter: String = "",
        val artifacts: List<ArtifactInfo> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val deletingRef: String? = null,          // 等待删除确认的 artifact
        val labelingArtifact: ArtifactInfo? = null, // 标签弹窗目标
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onRefChange(v: String) = _uiState.update { it.copy(ref = v) }
    fun onFilterChange(v: String) = _uiState.update { it.copy(labelFilter = v) }

    /** 查询(可按 label 筛选,逗号分隔的 k 或 k=v)。 */
    fun search() {
        val state = _uiState.value
        if (state.ref.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val filters = state.labelFilter.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val result = syncService.list(state.ref.trim(), filters)
            _uiState.update {
                it.copy(
                    loading = false,
                    artifacts = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun requestDelete(artifact: ArtifactInfo) = _uiState.update { it.copy(deletingRef = artifact.fullName) }

    fun confirmDelete() {
        val ref = _uiState.value.deletingRef ?: return
        _uiState.update { it.copy(deletingRef = null) }
        viewModelScope.launch {
            syncService.delete(ref)
            search()
        }
    }

    fun dismissDelete() = _uiState.update { it.copy(deletingRef = null) }

    fun openLabelDialog(artifact: ArtifactInfo) = _uiState.update { it.copy(labelingArtifact = artifact) }

    fun dismissLabelDialog() = _uiState.update { it.copy(labelingArtifact = null) }

    /** label set/unset 后刷新。 */
    fun applyLabels(artifact: ArtifactInfo, updates: Map<String, String>, removeKeys: List<String>) {
        viewModelScope.launch {
            syncService.setLabels(artifact.fullName, updates)
            syncService.unsetLabels(artifact.fullName, removeKeys)
            _uiState.update { it.copy(labelingArtifact = null) }
            search()
        }
    }

    class Factory(private val syncService: SyncService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ListViewModel(syncService) as T
    }
}
