package com.tiramission.ocisync.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tiramission.ocisync.R
import com.tiramission.ocisync.ui.list.ListContent

/**
 * shortcut 操作台:预填 repo 查询 tag 列表 + 行操作 + 「推送新版本」入口。
 * 见 docs/06-ui-design.md §3.5。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutDetailScreen(
    name: String,
    repo: String,
    onBack: () -> Unit,
    onPullArtifact: (String) -> Unit,
    onPushNew: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
        bottomBar = {
            // 推送新版本:跳 push 页并预填 repo(docs/06 §3.5)
            Button(
                onClick = onPushNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Create, contentDescription = null)
                Text(stringResource(R.string.shortcut_push_new), modifier = Modifier.padding(start = 6.dp))
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.padding(innerPadding),
        ) {
            ListContent(initialRef = repo, onPullArtifact = onPullArtifact)
        }
    }
}
