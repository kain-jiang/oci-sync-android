package com.tiramission.ocisync.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {

    @Test
    fun `serialization round-trip works`() {
        val info = BuildInfo("oci-sync-android-core", OciSyncCore.VERSION)
        val json = Json.encodeToString(BuildInfo.serializer(), info)
        val decoded = Json.decodeFromString(BuildInfo.serializer(), json)
        assertEquals(info, decoded)
    }

    @Test
    fun `version is defined`() {
        assertEquals("0.1.0", OciSyncCore.VERSION)
    }
}
