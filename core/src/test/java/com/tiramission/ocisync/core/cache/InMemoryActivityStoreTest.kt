package com.tiramission.ocisync.core.cache

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryActivityStoreTest {

    private fun activity(i: Int, type: ActivityType = ActivityType.PUSH) = Activity(
        type = type,
        timestamp = 1_700_000_000_000L + i,
        remoteRef = "registry.example.com/team/repo:v$i",
        success = true,
    )

    @Test
    fun `recent returns newest first`() = runTest {
        val store = InMemoryActivityStore()
        store.add(activity(1))
        store.add(activity(2))
        store.add(activity(3))

        val recent = store.recent()
        assertEquals(3, recent.size)
        assertEquals("registry.example.com/team/repo:v3", recent[0].remoteRef)
        assertEquals("registry.example.com/team/repo:v1", recent[2].remoteRef)
    }

    @Test
    fun `entries are truncated at max`() = runTest {
        val store = InMemoryActivityStore(maxEntries = 3)
        repeat(10) { store.add(activity(it)) }

        val recent = store.recent()
        assertEquals(3, recent.size)
        assertEquals("registry.example.com/team/repo:v9", recent[0].remoteRef)
        assertEquals("registry.example.com/team/repo:v7", recent[2].remoteRef)
    }

    @Test
    fun `recent respects limit`() = runTest {
        val store = InMemoryActivityStore()
        repeat(10) { store.add(activity(it)) }
        assertEquals(2, store.recent(2).size)
    }

    @Test
    fun `clear empties store`() = runTest {
        val store = InMemoryActivityStore()
        store.add(activity(1))
        store.clear()
        assertTrue(store.recent().isEmpty())
        assertEquals(0, store.stats().total)
    }

    @Test
    fun `stats aggregates by type`() = runTest {
        val store = InMemoryActivityStore()
        store.add(activity(1, ActivityType.PUSH))
        store.add(activity(2, ActivityType.PUSH))
        store.add(activity(3, ActivityType.DELETE))

        val stats = store.stats()
        assertEquals(3, stats.total)
        assertEquals(2, stats.byType["PUSH"])
        assertEquals(1, stats.byType["DELETE"])
    }
}
