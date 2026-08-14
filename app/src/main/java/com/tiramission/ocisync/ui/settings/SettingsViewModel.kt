package com.tiramission.ocisync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.config.RegistryAuth
import com.tiramission.ocisync.core.config.Shortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 设置页状态:凭据 + shortcuts 管理,见 docs/06-ui-design.md §3.7。 */
class SettingsViewModel(private val configLoader: ConfigLoader) : ViewModel() {

    data class UiState(
        val auths: List<Pair<String, RegistryAuth>> = emptyList(),
        val shortcuts: List<Pair<String, Shortcut>> = emptyList(),
        val credentialHost: String = "",
        val credentialUsername: String = "",
        val credentialPassword: String = "",
        val shortcutName: String = "",
        val shortcutRepo: String = "",
        val message: String? = null,       // 操作结果/错误提示(Snackbar)
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                auths = configLoader.load().auths.toList(),
                shortcuts = configLoader.getAllShortcuts(),
            )
        }
    }

    fun onCredentialHostChange(v: String) {
        _uiState.value = _uiState.value.copy(credentialHost = v)
    }

    fun onCredentialUsernameChange(v: String) {
        _uiState.value = _uiState.value.copy(credentialUsername = v)
    }

    fun onCredentialPasswordChange(v: String) {
        _uiState.value = _uiState.value.copy(credentialPassword = v)
    }

    fun onShortcutNameChange(v: String) {
        _uiState.value = _uiState.value.copy(shortcutName = v)
    }

    fun onShortcutRepoChange(v: String) {
        _uiState.value = _uiState.value.copy(shortcutRepo = v)
    }

    fun onMessageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /** 添加凭据;host 非空即保存。返回是否成功。 */
    fun addCredential(): Boolean {
        val s = _uiState.value
        if (s.credentialHost.isBlank()) return false
        configLoader.addAuth(
            s.credentialHost.trim(),
            RegistryAuth(username = s.credentialUsername.trim(), password = s.credentialPassword),
        )
        _uiState.value = _uiState.value.copy(
            credentialHost = "",
            credentialUsername = "",
            credentialPassword = "",
        )
        refresh()
        return true
    }

    fun removeCredential(host: String) {
        configLoader.removeAuth(host)
        refresh()
    }

    /** 添加 shortcut;走 ConfigLoader 校验(无 @ / 无 tag)。返回是否成功。 */
    fun addShortcut(): Boolean {
        val s = _uiState.value
        if (s.shortcutName.isBlank() || s.shortcutRepo.isBlank()) return false
        val result = configLoader.addShortcut(s.shortcutName.trim(), s.shortcutRepo.trim())
        if (result.isFailure) return false
        _uiState.value = _uiState.value.copy(shortcutName = "", shortcutRepo = "")
        refresh()
        return true
    }

    fun removeShortcut(name: String) {
        configLoader.removeShortcut(name)
        refresh()
    }

    class Factory(private val configLoader: ConfigLoader) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(configLoader) as T
    }
}
