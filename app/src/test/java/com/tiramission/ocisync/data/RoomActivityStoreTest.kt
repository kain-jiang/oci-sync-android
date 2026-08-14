package com.tiramission.ocisync.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tiramission.ocisync.core.cache.Activity
import com.tiramission.ocisync.core.cache.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RoomActivityStore Robolectric 测试(in-memory Room,docs/09-testing.md §4)。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomActivityStoreTest {

    private lateinit var db: OciSyncDatabase
    private lateinit var store: RoomActivityStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, OciSyncDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomActivityStore(db.activityDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun activity(i: Int, type: ActivityType = ActivityType.PUSH) = Activity(
        type = type,
        timestamp = 1_700_000_000_000L + i,
        remoteRef = "registry.example.com/team/repo:v$i",
        labels = listOf("env=prod"),
        success = true,
    )

    @Test
    fun `add and recent returns newest first with labels`() = runTest {
        store.add(activity(1))
        store.add(activity(2))

        val recent = store.recent()
        assertEquals(2, recent.size)
        assertEquals("registry.example.com/team/repo:v2", recent[0].remoteRef)
        assertEquals(listOf("env=prod"), recent[0].labels)
    }

    @Test
    fun `entries truncated at 100`() = runTest {
        repeat(120) { store.add(activity(it)) }
        assertEquals(100, store.recent(500).size)
        // 最旧的 20 条被删除
        assertEquals("registry.example.com/team/repo:v119", store.recent(500)[0].remoteRef)
    }

    @Test
    fun `clear empties store`() = runTest {
        store.add(activity(1))
        store.clear()
        assertTrue(store.recent().isEmpty())
        assertEquals(0, store.stats().total)
    }

    @Test
    fun `stats aggregates by type`() = runTest {
        store.add(activity(1, ActivityType.PUSH))
        store.add(activity(2, ActivityType.PUSH))
        store.add(activity(3, ActivityType.DELETE))

        val stats = store.stats()
        assertEquals(3, stats.total)
        assertEquals(2, stats.byType["PUSH"])
        assertEquals(1, stats.byType["DELETE"])
    }

    @Test
    fun `failure activity with error persists`() = runTest {
        store.add(
            Activity(
                type = ActivityType.PULL,
                timestamp = 42L,
                remoteRef = "registry.example.com/team/repo:v1",
                success = false,
                error = "artifact is encrypted, passphrase required",
            )
        )
        val recent = store.recent(1)
        assertEquals(ActivityType.PULL, recent[0].type)
        assertEquals(false, recent[0].success)
        assertEquals("artifact is encrypted, passphrase required", recent[0].error)
    }
}
