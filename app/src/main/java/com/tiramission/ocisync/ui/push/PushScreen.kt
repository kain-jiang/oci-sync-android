package com.tiramission.ocisync.ui.push

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tiramission.ocisync.OciSyncApp
import com.tiramission.ocisync.R
import com.tiramission.ocisync.core.model.Stage

/** 推送页:选择文件/目录 → 配置 → 执行,见 docs/06-ui-design.md §3.2。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as OciSyncApp
    val viewModel: PushViewModel = viewModel(factory = PushViewModel.Factory(app.container.syncService, context))
    val state by viewModel.uiState.collectAsState()

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.onFilePicked(it) }
    }
    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onDirectoryPicked(it) }
    }

    val stageLabel = when (state.stage) {
        Stage.PACKING -> stringResource(R.string.stage_packing)
        Stage.ENCRYPTING -> stringResource(R.string.stage_encrypting)
        Stage.UPLOADING -> stringResource(R.string.stage_uploading)
        Stage.DONE -> stringResource(R.string.stage_done)
        else -> ""
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.push_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 文件/目录选择
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        fileLauncher.launch(arrayOf("*/*"))
                    },
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.push_select_file)) }
                OutlinedButton(
                    onClick = { dirLauncher.launch(null) },
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.push_select_dir)) }
            }
            state.selectedName?.let {
                Text(
                    text = stringResource(R.string.push_selected, it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 远程 ref
            OutlinedTextField(
                value = state.remoteRef,
                onValueChange = viewModel::onRemoteRefChange,
                label = { Text(stringResource(R.string.push_ref_hint)) },
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // 口令
            OutlinedTextField(
                value = state.passphrase,
                onValueChange = viewModel::onPassphraseChange,
                label = { Text(stringResource(R.string.push_passphrase)) },
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (state.showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = viewModel::togglePassphrase) {
                        Text(
                            if (state.showPassphrase) stringResource(R.string.common_hide)
                            else stringResource(R.string.common_show)
                        )
                    }
                },
            )

            // 标签
            state.labels.forEachIndexed { index, (k, v) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = k,
                        onValueChange = { viewModel.onLabelChange(index, it, v) },
                        label = { Text(stringResource(R.string.push_label_key)) },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = v,
                        onValueChange = { viewModel.onLabelChange(index, k, it) },
                        label = { Text(stringResource(R.string.push_label_value)) },
                        enabled = !state.isRunning,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(onClick = { viewModel.removeLabel(index) }, enabled = !state.isRunning) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    }
                }
            }
            OutlinedButton(
                onClick = viewModel::addLabel,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.push_add_label), modifier = Modifier.padding(start = 4.dp))
            }

            // 进度
            if (state.isRunning) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stageLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // 错误
            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 执行/取消
            if (state.isRunning) {
                Button(
                    onClick = viewModel::cancel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.push_cancel)) }
            } else {
                Button(
                    onClick = viewModel::startPush,
                    enabled = state.selectedFile != null && state.remoteRef.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.push_execute)) }
            }
        }
    }
}
