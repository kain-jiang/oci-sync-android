package com.tiramission.ocisync.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** DataStoreKeyValueStore Robolectric 测试(docs/09-testing.md §4)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStoreKeyValueStoreTest {

    private lateinit var store: DataStoreKeyValueStore
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        file = File(context.cacheDir, "test_prefs_${System.nanoTime()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        store = DataStoreKeyValueStore(dataStore)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `put and get round-trip`() = runTest {
        store.put("key1", "value1")
        assertEquals("value1", store.get("key1"))
    }

    @Test
    fun `missing key returns null`() = runTest {
        assertNull(store.get("nope"))
    }

    @Test
    fun `overwrite replaces value`() = runTest {
        store.put("k", "old")
        store.put("k", "new")
        assertEquals("new", store.get("k"))
    }

    @Test
    fun `chinese content round-trip`() = runTest {
        store.put("note", "中文内容测试")
        assertEquals("中文内容测试", store.get("note"))
    }
}
