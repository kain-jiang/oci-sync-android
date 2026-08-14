package com.tiramission.ocisync.ui.pull

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.model.PullRequest
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
import java.util.UUID

/** 拉取页状态,见 docs/06-ui-design.md §3.3。 */
class PullViewModel(
    private val syncService: SyncService,
    private val context: Context,
) : ViewModel() {

    data class UiState(
        val destTreeUri: Uri? = null,
        val destName: String? = null,
        val remoteRef: String = "",
        val passphrase: String = "",
        val showPassphrase: Boolean = false,
        val isRunning: Boolean = false,
        val stage: Stage = Stage.IDLE,
        val progress: Float = 0f,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pullJob: Job? = null

    fun onDestPicked(uri: Uri) {
        _uiState.update {
            it.copy(destTreeUri = uri, destName = SafFiles.queryName(context, uri), error = null)
        }
    }

    fun onRemoteRefChange(v: String) = _uiState.update { it.copy(remoteRef = v) }
    fun onPassphraseChange(v: String) = _uiState.update { it.copy(passphrase = v) }
    fun togglePassphrase() = _uiState.update { it.copy(showPassphrase = !it.showPassphrase) }

    fun startPull() {
        val state = _uiState.value
        val treeUri = state.destTreeUri ?: return
        if (state.remoteRef.isBlank()) return
        pullJob?.cancel()
        pullJob = viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, error = null, progress = 0f, stage = Stage.DOWNLOADING) }
            val result = runCatching {
                // 1. 解包到 cacheDir 临时目录(SAF tree 无 File 映射)
                val tmpDir = File(context.cacheDir, "pull/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    syncService.pull(
                        PullRequest(
                            remoteRef = state.remoteRef.trim(),
                            destDir = tmpDir,
                            passphrase = state.passphrase.ifBlank { null },
                        ),
                        onStage = { stage -> _uiState.update { it.copy(stage = stage) } },
                        onProgress = { p -> _uiState.update { it.copy(progress = p) } },
                    ).getOrThrow()
                    // 2. 复制到用户选择的 SAF 目录
                    SafFiles.copyDirToTree(context, tmpDir, treeUri)
                } finally {
                    tmpDir.deleteRecursively()
                }
            }
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
        pullJob?.cancel()
        _uiState.update { it.copy(isRunning = false, stage = Stage.IDLE) }
    }

    class Factory(
        private val syncService: SyncService,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PullViewModel(syncService, context) as T
    }
}
