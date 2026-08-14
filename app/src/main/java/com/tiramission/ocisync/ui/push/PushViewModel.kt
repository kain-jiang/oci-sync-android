package com.tiramission.ocisync.ui.push

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.model.PushRequest
import com.tiramission.ocisync.core.model.Stage
import com.tiramission.ocisync.core.model.SyncService
import com.tiramission.ocisync.data.SafFiles
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 推送页状态,见 docs/06-ui-design.md §3.2。 */
class PushViewModel(
    private val syncService: SyncService,
    private val context: Context,
) : ViewModel() {

    data class UiState(
        val selectedName: String? = null,     // 显示用(文件名/目录名)
        val selectedFile: File? = null,       // 已拷贝到 cacheDir 的路径
        val remoteRef: String = "",
        val passphrase: String = "",
        val showPassphrase: Boolean = false,
        val labels: List<Pair<String, String>> = emptyList(),
        val isRunning: Boolean = false,
        val stage: Stage = Stage.IDLE,
        val progress: Float = 0f,             // 0..1
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pushJob: Job? = null

    /** SAF 选择文件后调用(拷贝到 cacheDir)。 */
    fun onFilePicked(uri: Uri) {
        runCatching { SafFiles.copySingleFileToCache(context, uri) }
            .onSuccess { file ->
                _uiState.update { it.copy(selectedName = file.name, selectedFile = file, error = null) }
            }
            .onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
    }

    /** SAF 选择目录后调用(递归拷贝到 cacheDir)。 */
    fun onDirectoryPicked(uri: Uri) {
        runCatching { SafFiles.copyTreeToCache(context, uri) }
            .onSuccess { dir ->
                _uiState.update { it.copy(selectedName = dir.name, selectedFile = dir, error = null) }
            }
            .onFailure { e ->
                _uiState.update { it.copy(error = e.message) }
            }
    }

    fun onRemoteRefChange(v: String) = _uiState.update { it.copy(remoteRef = v) }
    fun onPassphraseChange(v: String) = _uiState.update { it.copy(passphrase = v) }
    fun togglePassphrase() = _uiState.update { it.copy(showPassphrase = !it.showPassphrase) }

    fun onLabelChange(index: Int, key: String, value: String) {
        _uiState.update { state ->
            val labels = state.labels.toMutableList()
            if (index < labels.size) labels[index] = key to value
            state.copy(labels = labels)
        }
    }

    fun addLabel() = _uiState.update { it.copy(labels = it.labels + ("" to "")) }

    fun removeLabel(index: Int) {
        _uiState.update { it.copy(labels = it.labels.filterIndexed { i, _ -> i != index }) }
    }

    fun startPush() {
        val state = _uiState.value
        val file = state.selectedFile ?: return
        if (state.remoteRef.isBlank()) return
        pushJob?.cancel()
        pushJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, error = null, progress = 0f, stage = Stage.PACKING) }
            val result = syncService.push(
                PushRequest(
                    localPath = file,
                    remoteRef = state.remoteRef.trim(),
                    passphrase = state.passphrase.ifBlank { null },
                    labels = state.labels.filter { it.first.isNotBlank() }.toMap(),
                ),
                onStage = { stage -> _uiState.update { it.copy(stage = stage) } },
                onProgress = { p -> _uiState.update { it.copy(progress = p) } },
            )
            _uiState.update {
                it.copy(
                    isRunning = false,
                    stage = Stage.IDLE,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun cancel() {
        pushJob?.cancel()
        _uiState.update { it.copy(isRunning = false, stage = Stage.IDLE) }
    }

    class Factory(
        private val syncService: SyncService,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PushViewModel(syncService, context) as T
    }
}
