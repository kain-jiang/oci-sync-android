package com.tiramission.ocisync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.config.RegistryAuth
import com.tiramission.ocisync.core.config.Shortcut
import com.tiramission.ocisync.core.oci.AuthCheckResult
import com.tiramission.ocisync.core.oci.Credential
import com.tiramission.ocisync.core.oci.OciClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页状态:凭据 + shortcuts 管理,见 docs/06-ui-design.md §3.7。 */
class SettingsViewModel(
    private val configLoader: ConfigLoader,
    private val ociClient: OciClient,
) : ViewModel() {

    data class UiState(
        val auths: List<Pair<String, RegistryAuth>> = emptyList(),
        val shortcuts: List<Pair<String, Shortcut>> = emptyList(),
        val credentialHost: String = "",
        val credentialUsername: String = "",
        val credentialPassword: String = "",
        val credentialVerifying: Boolean = false,
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

    /**
     * 添加凭据:先向 registry 验证凭据有效性,通过才保存。
     * 结果经 [UiState.message] 提示(成功/凭据错误/网络错误)。
     */
    fun addCredential() {
        val s = _uiState.value
        if (s.credentialHost.isBlank()) {
            _uiState.update { it.copy(message = it.message) }
            return
        }
        val host = s.credentialHost.trim()
        // 凭据统一 trim(用户可能从登录指令复制带空格)
        val credential = Credential(s.credentialUsername.trim(), s.credentialPassword.trim())
        viewModelScope.launch {
            _uiState.update { it.copy(credentialVerifying = true) }
            val result = ociClient.checkCredential(host, credential)
            when (result) {
                AuthCheckResult.VALID -> {
                    configLoader.addAuth(host, RegistryAuth(credential.username, credential.password))
                    _uiState.update {
                        it.copy(
                            credentialVerifying = false,
                            credentialHost = "",
                            credentialUsername = "",
                            credentialPassword = "",
                            message = CRED_ADDED,
                        )
                    }
                    refresh()
                }
                AuthCheckResult.INVALID -> {
                    _uiState.update { it.copy(credentialVerifying = false, message = CRED_INVALID) }
                    refresh()
                }
                AuthCheckResult.NETWORK_ERROR -> {
                    _uiState.update { it.copy(credentialVerifying = false, message = CRED_NETWORK) }
                    refresh()
                }
            }
        }
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

    class Factory(
        private val configLoader: ConfigLoader,
        private val ociClient: OciClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(configLoader, ociClient) as T
    }

    companion object {
        // 文案经 SettingsScreen 本地化资源注入?ViewModel 无 Context,用 R 需 import——改为 UI 层映射:
        // 这里仅用占位标记,UI 根据 message 值显示对应资源。
        const val CRED_ADDED = "cred_added"
        const val CRED_INVALID = "cred_invalid"
        const val CRED_NETWORK = "cred_network"
    }
}
