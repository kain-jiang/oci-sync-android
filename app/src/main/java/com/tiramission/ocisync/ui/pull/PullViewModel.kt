package com.tiramission.ocisync.ui.pull

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.R
import com.tiramission.ocisync.core.model.PullRequest
import com.tiramission.ocisync.core.model.Stage
import com.tiramission.ocisync.core.model.SyncService
import com.tiramission.ocisync.data.SafFiles
import com.tiramission.ocisync.service.SyncForegroundService
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
        val message: String? = null,          // 提示(Snackbar),如"已转入后台任务"
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pullJob: Job? = null

    fun onDestPicked(uri: Uri) {
        // 持久化 tree 授权:前台服务(Activity 可能已销毁)执行 pull 时需要
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // 部分 provider 不支持持久化,忽略(服务内仍可尝试)
        }
        _uiState.update {
            it.copy(destTreeUri = uri, destName = SafFiles.queryName(context, uri), error = null)
        }
    }

    fun onRemoteRefChange(v: String) = _uiState.update { it.copy(remoteRef = v) }
    fun onPassphraseChange(v: String) = _uiState.update { it.copy(passphrase = v) }
    fun togglePassphrase() = _uiState.update { it.copy(showPassphrase = !it.showPassphrase) }

    /**
     * 启动拉取。统一走前台服务(通知显示进度,退后台/锁屏不中断,见 docs/06 §6)。
     */
    fun startPull() {
        val state = _uiState.value
        val treeUri = state.destTreeUri ?: return
        if (state.remoteRef.isBlank()) return
        SyncForegroundService.startPull(
            context,
            state.remoteRef.trim(),
            treeUri,
            state.passphrase.ifBlank { null },
        )
        _uiState.update {
            it.copy(error = null, message = context.getString(R.string.pull_bg_started))
        }
    }

    fun cancel() {
        // 取消经通知栏 action 完成;此处仅重置 UI 状态
        _uiState.update { it.copy(isRunning = false, stage = Stage.IDLE) }
    }

    fun onMessageShown() = _uiState.update { it.copy(message = null) }

    class Factory(
        private val syncService: SyncService,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PullViewModel(syncService, context) as T
    }
}
