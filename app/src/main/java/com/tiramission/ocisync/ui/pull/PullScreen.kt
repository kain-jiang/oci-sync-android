package com.tiramission.ocisync.ui.pull

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/** 拉取页:ref 输入 + 目标目录 + 加密预检 + 进度,见 docs/06-ui-design.md §3.3。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullScreen(
    initialRef: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OciSyncApp
    val viewModel: PullViewModel = viewModel(factory = PullViewModel.Factory(app.container.syncService, context))
    val state by viewModel.uiState.collectAsState()

    // 预填初始 ref(来自浏览页行操作)
    androidx.compose.runtime.LaunchedEffect(initialRef) {
        if (initialRef.isNotBlank()) viewModel.onRemoteRefChange(initialRef)
    }

    val dirLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.onDestPicked(it) }
    }

    // 提示消息(转入后台任务等)
    val snackbarHostState = remember { SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    val stageLabel = when (state.stage) {
        Stage.DOWNLOADING -> stringResource(R.string.stage_downloading)
        Stage.DECRYPTING -> stringResource(R.string.stage_decrypting)
        Stage.UNPACKING -> stringResource(R.string.stage_unpacking)
        Stage.DONE -> stringResource(R.string.stage_done)
        else -> ""
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.pull_title)) },
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
            OutlinedTextField(
                value = state.remoteRef,
                onValueChange = viewModel::onRemoteRefChange,
                label = { Text(stringResource(R.string.pull_ref_hint)) },
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedButton(
                onClick = { dirLauncher.launch(null) },
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.destName != null) R.string.pull_dest_selected else R.string.pull_select_dest,
                        state.destName ?: ""
                    )
                )
            }

            OutlinedTextField(
                value = state.passphrase,
                onValueChange = viewModel::onPassphraseChange,
                label = { Text(stringResource(R.string.pull_passphrase)) },
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

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (state.isRunning) {
                Button(
                    onClick = viewModel::cancel,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.push_cancel)) }
            } else {
                Button(
                    onClick = viewModel::startPull,
                    enabled = state.destTreeUri != null && state.remoteRef.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pull_execute)) }
            }
        }
    }
}
