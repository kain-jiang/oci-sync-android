package com.tiramission.ocisync.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tiramission.ocisync.BuildConfig
import com.tiramission.ocisync.OciSyncApp
import com.tiramission.ocisync.R
import kotlinx.coroutines.launch

/** 设置页:凭据管理 + shortcuts 管理 + 关于,见 docs/06-ui-design.md §3.7。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OciSyncApp
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app.container.configLoader))
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) } // (type, key)

    // onClick(非 Composable)中使用的文案需提前提取
    val msgCredentialAdded = stringResource(R.string.settings_credential_added)
    val msgCredentialRemoved = stringResource(R.string.settings_credential_removed)
    val msgShortcutAdded = stringResource(R.string.settings_shortcut_added)
    val msgShortcutRemoved = stringResource(R.string.settings_shortcut_removed)
    val msgInvalid = stringResource(R.string.settings_error_invalid)

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 凭据管理 ─────────────────────────────────
            item {
                Text(
                    text = stringResource(R.string.settings_credentials),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (uiState.auths.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_no_credentials),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uiState.auths, key = { it.first }) { (host, auth) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = host, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "${auth.username} · ${"•".repeat(auth.password.length.coerceAtMost(8))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { pendingDelete = "auth" to host }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_confirm_delete))
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.credentialHost,
                        onValueChange = viewModel::onCredentialHostChange,
                        label = { Text(stringResource(R.string.settings_host)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.credentialUsername,
                        onValueChange = viewModel::onCredentialUsernameChange,
                        label = { Text(stringResource(R.string.settings_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.credentialPassword,
                        onValueChange = viewModel::onCredentialPasswordChange,
                        label = { Text(stringResource(R.string.settings_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = {
                            val ok = viewModel.addCredential()
                            scope.launch {
                                snackbarHostState.showSnackbar(if (ok) msgCredentialAdded else msgInvalid)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_add_credential))
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // ── Shortcuts 管理 ───────────────────────────
            item {
                Text(
                    text = stringResource(R.string.settings_shortcuts),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (uiState.shortcuts.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.settings_no_shortcuts),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(uiState.shortcuts, key = { it.first }) { (name, shortcut) ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = shortcut.repo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { pendingDelete = "shortcut" to name }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_confirm_delete))
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = uiState.shortcutName,
                        onValueChange = viewModel::onShortcutNameChange,
                        label = { Text(stringResource(R.string.settings_shortcut_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.shortcutRepo,
                        onValueChange = viewModel::onShortcutRepoChange,
                        label = { Text(stringResource(R.string.settings_shortcut_repo)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            val ok = viewModel.addShortcut()
                            scope.launch {
                                snackbarHostState.showSnackbar(if (ok) msgShortcutAdded else msgInvalid)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_add_shortcut))
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // ── 关于 ─────────────────────────────────────
            item {
                Text(
                    text = stringResource(R.string.settings_about),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.settings_upstream),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 删除确认弹窗(凭据/shortcut 通用)
    pendingDelete?.let { (type, key) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_confirm_delete)) },
            text = { Text(key) },
            confirmButton = {
                TextButton(onClick = {
                    if (type == "auth") viewModel.removeCredential(key) else viewModel.removeShortcut(key)
                    pendingDelete = null
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (type == "auth") msgCredentialRemoved else msgShortcutRemoved
                        )
                    }
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
