package com.tiramission.ocisync.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tiramission.ocisync.OciSyncApp
import com.tiramission.ocisync.R
import com.tiramission.ocisync.core.model.ArtifactInfo
import java.util.Locale

/** 仓库浏览页外壳(带标题栏),见 docs/06-ui-design.md §3.4。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    initialRef: String? = null,
    onPullArtifact: (String) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.browse_title)) }) },
    ) { innerPadding ->
        ListContent(
            initialRef = initialRef,
            onPullArtifact = onPullArtifact,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * 仓库浏览内容层(无外壳):查询 + 结果表格 + 行操作。
 * 供 Browse 页(自带标题栏)与 ShortcutDetail 页(自带标题栏 + 底部按钮)复用,
 * 避免嵌套 Scaffold 造成双层标题栏与空白。
 */
@Composable
fun ListContent(
    initialRef: String? = null,
    onPullArtifact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OciSyncApp
    val viewModel: ListViewModel = viewModel(factory = ListViewModel.Factory(app.container.syncService))
    val state by viewModel.uiState.collectAsState()

    // 预填初始 ref 并自动查询(shortcut 操作台进入时)
    androidx.compose.runtime.LaunchedEffect(initialRef) {
        if (initialRef != null) {
            viewModel.onRefChange(initialRef)
            viewModel.search()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            // 顶部:ref + 标签筛选 + 查询(紧凑,固定不滚动)
            OutlinedTextField(
                value = state.ref,
                onValueChange = viewModel::onRefChange,
                label = { Text(stringResource(R.string.browse_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text(state.ref) }, // 完整引用(超出滚动)显示在下方
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.labelFilter,
                    onValueChange = viewModel::onFilterChange,
                    label = { Text(stringResource(R.string.browse_label_filter)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    supportingText = { Text(state.labelFilter.takeIf { it.isNotBlank() } ?: "") }, // 长标签输入完整显示
                )
                Button(onClick = viewModel::search) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Text(stringResource(R.string.browse_search))
                }
            }

            state.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 结果区:占剩余空间,可滚动
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.artifacts.isEmpty() && !state.error.isNullOrEmpty() -> Unit

                    state.artifacts.isEmpty() -> Text(
                        text = stringResource(R.string.browse_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.artifacts, key = { it.digest }) { artifact ->
                            ArtifactRow(
                                artifact = artifact,
                                onPull = { onPullArtifact(artifact.fullName) },
                                onDelete = { viewModel.requestDelete(artifact) },
                                onLabels = { viewModel.openLabelDialog(artifact) },
                            )
                        }
                    }
                }
            }
    }

    // 删除确认
    state.deletingRef?.let { ref ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text(stringResource(R.string.common_confirm_delete_title)) },
            text = { Text(ref) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 标签管理弹窗
    state.labelingArtifact?.let { artifact ->
        LabelDialog(
            artifact = artifact,
            onDismiss = viewModel::dismissLabelDialog,
            onApply = { updates, removeKeys -> viewModel.applyLabels(artifact, updates, removeKeys) },
        )
    }
}

@Composable
private fun ArtifactRow(
    artifact: ArtifactInfo,
    onPull: () -> Unit,
    onDelete: () -> Unit,
    onLabels: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = artifact.tag,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (artifact.encrypted) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.list_encrypted),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                Text(
                    text = formatSize(artifact.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "v${artifact.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (artifact.labels.isNotEmpty()) {
                Text(
                    text = artifact.labels.entries.joinToString(", ") { "${it.key}=${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                TextButton(onClick = onPull) { Text(stringResource(R.string.list_action_pull)) }
                TextButton(onClick = onLabels) { Text(stringResource(R.string.list_action_labels)) }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.list_action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabelDialog(
    artifact: ArtifactInfo,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>, List<String>) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var removeKeys by remember { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.list_label_dialog_title, artifact.tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.list_label_dialog_current),
                    style = MaterialTheme.typography.bodySmall,
                )
                artifact.labels.forEach { (k, v) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("$k=$v", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { removeKeys = removeKeys + k }) {
                            Text(stringResource(R.string.list_label_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.push_label_key)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.push_label_value)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val updates = if (key.isNotBlank()) mapOf(key.trim() to value) else emptyMap()
                onApply(updates, removeKeys)
            }) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1fGB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1fMB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1fKB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
