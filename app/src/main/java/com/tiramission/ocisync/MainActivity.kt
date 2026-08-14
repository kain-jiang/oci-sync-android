package com.tiramission.ocisync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tiramission.ocisync.ui.navigation.OciSyncAppRoot
import com.tiramission.ocisync.ui.theme.OciSyncTheme

/** 单 Activity 应用壳(ADR 决策:单 Activity + Navigation Compose)。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 36 在 Android 15+ 强制 edge-to-edge:
        // 状态栏图标颜色跟随系统深色模式(auto),避免浅色背景白字看不清
        enableEdgeToEdge()
        setContent {
            OciSyncTheme {
                OciSyncAppRoot()
            }
        }
    }
}
