package com.tiramission.ocisync.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tiramission.ocisync.R
import com.tiramission.ocisync.ui.list.ListScreen

/**
 * shortcut 操作台(简化为复用 ListScreen 的查询/操作):
 * 预填 repo 并自动查询 tag 列表,行操作同浏览页。
 * 见 docs/06-ui-design.md §3.5。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutDetailScreen(
    name: String,
    repo: String,
    onBack: () -> Unit,
    onPullArtifact: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(innerPadding),
        ) {
            ListScreen(initialRef = repo, onPullArtifact = onPullArtifact)
        }
    }
}
