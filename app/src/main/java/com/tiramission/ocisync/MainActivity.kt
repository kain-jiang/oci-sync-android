package com.tiramission.ocisync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tiramission.ocisync.ui.navigation.OciSyncAppRoot
import com.tiramission.ocisync.ui.theme.OciSyncTheme

/** 单 Activity 应用壳(ADR 决策:单 Activity + Navigation Compose)。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OciSyncTheme {
                OciSyncAppRoot()
            }
        }
    }
}
