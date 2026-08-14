package com.tiramission.ocisync.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tiramission.ocisync.core.config.KeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * DataStore Preferences 实现的 [KeyValueStore]。
 *
 * core 的 [KeyValueStore] 是同步接口,DataStore 是异步 Flow——此处用 runBlocking 桥接。
 * 调用方均为轻量配置读写(单 key 小 JSON),阻塞开销可接受;
 * 如需彻底异步可在 M7 重构接口为 suspend。
 */
class DataStoreKeyValueStore(
    private val dataStore: DataStore<Preferences>,
) : KeyValueStore {

    override fun get(key: String): String? = runBlocking {
        dataStore.data.first()[stringPreferencesKey(key)]
    }

    override fun put(key: String, value: String) {
        runBlocking {
            dataStore.edit { it[stringPreferencesKey(key)] = value }
        }
    }
}
