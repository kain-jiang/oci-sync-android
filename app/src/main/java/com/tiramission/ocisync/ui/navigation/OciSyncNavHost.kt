package com.tiramission.ocisync.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tiramission.ocisync.R
import com.tiramission.ocisync.ui.history.HistoryScreen
import com.tiramission.ocisync.ui.home.HomeScreen
import com.tiramission.ocisync.ui.list.ListScreen
import com.tiramission.ocisync.ui.pull.PullScreen
import com.tiramission.ocisync.ui.push.PushScreen
import com.tiramission.ocisync.ui.settings.SettingsScreen

/** 路由定义,见 docs/06-ui-design.md §2 页面地图。 */
object Routes {
    const val HOME = "home"
    const val BROWSE = "browse"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val PUSH = "push?ref={ref}"
    const val PUSH_ARG_REF = "ref"
    const val PULL = "pull?ref={ref}"
    const val PULL_ARG_REF = "ref"
    const val SHORTCUT = "shortcut/{name}/{repo}"
    const val SHORTCUT_ARG_NAME = "name"
    const val SHORTCUT_ARG_REPO = "repo"

    fun push(ref: String = ""): String = "push?ref=${java.net.URLEncoder.encode(ref, "UTF-8")}"
    fun pull(ref: String): String = "pull?ref=${java.net.URLEncoder.encode(ref, "UTF-8")}"
    fun shortcut(name: String, repo: String): String =
        "shortcut/${java.net.URLEncoder.encode(name, "UTF-8")}/${java.net.URLEncoder.encode(repo, "UTF-8")}"
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, R.string.tab_home, Icons.Filled.Home),
    BottomTab(Routes.BROWSE, R.string.tab_browse, Icons.Filled.List),
    BottomTab(Routes.HISTORY, R.string.tab_history, Icons.Filled.DateRange),
)

/** 应用根:Scaffold + 底部导航 + NavHost。 */
@Composable
fun OciSyncAppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        // 外层只负责底部导航,不处理系统栏 inset(各页面 Scaffold/TopAppBar 自行消费,
        // 避免与内层 Scaffold 重复加状态栏 padding 导致顶部空白)
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (bottomTabs.any { tab -> currentRoute?.startsWith(tab.route.substringBefore("?") ) == true }) {
                NavigationBar {
                    bottomTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute?.startsWith(tab.route.substringBefore("?")) == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenPush = { navController.navigate(Routes.push()) },
                    onOpenPull = { navController.navigate(Routes.pull("")) },
                    onOpenShortcut = { name, repo -> navController.navigate(Routes.shortcut(name, repo)) },
                )
            }
            composable(Routes.BROWSE) {
                ListScreen(
                    initialRef = null,
                    onPullArtifact = { ref -> navController.navigate(Routes.pull(ref)) },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(
                route = Routes.PUSH,
                arguments = listOf(navArgument(Routes.PUSH_ARG_REF) {
                    type = NavType.StringType
                    defaultValue = ""
                }),
            ) { entry ->
                val initialRef = entry.arguments?.getString(Routes.PUSH_ARG_REF).orEmpty()
                PushScreen(
                    initialRef = initialRef,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.PULL,
                arguments = listOf(navArgument(Routes.PULL_ARG_REF) {
                    type = NavType.StringType
                    defaultValue = ""
                }),
            ) { entry ->
                val initialRef = entry.arguments?.getString(Routes.PULL_ARG_REF).orEmpty()
                PullScreen(
                    initialRef = initialRef,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = Routes.SHORTCUT,
                arguments = listOf(
                    navArgument(Routes.SHORTCUT_ARG_NAME) { type = NavType.StringType },
                    navArgument(Routes.SHORTCUT_ARG_REPO) { type = NavType.StringType },
                ),
            ) { entry ->
                val name = entry.arguments?.getString(Routes.SHORTCUT_ARG_NAME).orEmpty()
                val repo = entry.arguments?.getString(Routes.SHORTCUT_ARG_REPO).orEmpty()
                ShortcutDetailScreen(
                    name = name,
                    repo = repo,
                    onBack = { navController.popBackStack() },
                    onPullArtifact = { ref -> navController.navigate(Routes.pull(ref)) },
                    onPushNew = { navController.navigate(Routes.push(repo)) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
