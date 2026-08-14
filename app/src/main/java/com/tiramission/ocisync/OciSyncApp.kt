package com.tiramission.ocisync

import android.app.Application
import com.tiramission.ocisync.di.AppContainer

/** 应用入口,持有手动 DI 容器(ADR-006)。 */
class OciSyncApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
