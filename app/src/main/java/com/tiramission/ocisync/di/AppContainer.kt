package com.tiramission.ocisync.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.tiramission.ocisync.BuildConfig
import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.model.SyncService
import com.tiramission.ocisync.core.oci.OciClient
import com.tiramission.ocisync.core.oci.TokenCache
import com.tiramission.ocisync.data.ConfigAuthProvider
import com.tiramission.ocisync.data.DataStoreKeyValueStore
import com.tiramission.ocisync.data.KeystoreCrypto
import com.tiramission.ocisync.data.OciSyncDatabase
import com.tiramission.ocisync.data.RoomActivityStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手动 DI 容器(ADR-006:不引入 Hilt,项目规模小)。
 * 单 Activity 应用,Application 持有此容器并注入 ViewModel。
 */
class AppContainer(context: Context) {

    // ── 存储 ──────────────────────────────────────────────
    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("oci_sync") }
        )
    }

    private val database: OciSyncDatabase by lazy {
        Room.databaseBuilder(context, OciSyncDatabase::class.java, "oci_sync.db").build()
    }

    // ── 数据层 ────────────────────────────────────────────
    val configLoader: ConfigLoader by lazy {
        ConfigLoader(DataStoreKeyValueStore(dataStore), KeystoreCrypto())
    }

    val activityStore: RoomActivityStore by lazy {
        RoomActivityStore(database.activityDao())
    }

    // ── 网络 ──────────────────────────────────────────────
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)      // 大 blob 下载
            .writeTimeout(60, TimeUnit.SECONDS)
            // OkHttp 5 默认 callTimeout=0(不设总超时),此处省略;retryOnConnectionFailure 默认 true
            .build()
    }

    val ociClient: OciClient by lazy {
        OciClient(
            client = httpClient,
            authProvider = ConfigAuthProvider(configLoader),
            tokenCache = TokenCache(),
            // 生产(release)强制 HTTPS(docs/04 §4);debug 构建允许 http,便于内网/模拟器测试
            allowInsecureHttp = BuildConfig.DEBUG,
        )
    }

    // ── 编排 ──────────────────────────────────────────────
    val syncService: SyncService by lazy {
        SyncService(ociClient, activityStore, configLoader)
    }
}
