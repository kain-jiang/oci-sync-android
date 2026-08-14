package com.tiramission.ocisync.core.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceParserTest {

    @Test
    fun `single segment name gets docker hub and library prefix`() {
        val r = ReferenceParser.parse("alpine")
        assertEquals("registry-1.docker.io", r.registry)
        assertEquals("library/alpine", r.repository)
        assertEquals("latest", r.tag)
        assertNull(r.digest)
        assertNull(r.port)
    }

    @Test
    fun `two segment name gets docker hub without library prefix`() {
        val r = ReferenceParser.parse("myteam/files")
        assertEquals("registry-1.docker.io", r.registry)
        assertEquals("myteam/files", r.repository)
        assertEquals("latest", r.tag)
    }

    @Test
    fun `explicit registry with dot is preserved`() {
        val r = ReferenceParser.parse("registry.example.com/myteam/files:v1")
        assertEquals("registry.example.com", r.registry)
        assertEquals("myteam/files", r.repository)
        assertEquals("v1", r.tag)
        assertNull(r.port)
        assertNull(r.digest)
    }

    @Test
    fun `explicit registry with port`() {
        val r = ReferenceParser.parse("localhost:5000/team/repo:tag1")
        assertEquals("localhost", r.registry)
        assertEquals(5000, r.port)
        assertEquals("team/repo", r.repository)
        assertEquals("tag1", r.tag)
        assertEquals("localhost:5000", r.registryHost)
        assertEquals("https://localhost:5000/v2", r.baseUri)
    }

    @Test
    fun `digest reference is parsed`() {
        val r = ReferenceParser.parse("registry.example.com/repo@sha256:abcdef1234567890")
        assertEquals("sha256:abcdef1234567890", r.digest)
        assertTrue(r.isDigestRef)
        assertNull(r.tag) // digest 引用与 tag 互斥
    }

    @Test
    fun `fullName builds correctly`() {
        val r = ReferenceParser.parse("example.com/team/app:2.0")
        assertEquals("example.com/team/app:2.0", r.fullName)
        val d = ReferenceParser.parse("example.com/team/app@sha256:abc")
        assertEquals("example.com/team/app@sha256:abc", d.fullName)
    }

    @Test
    fun `tag colon inside repository path is not treated as tag`() {
        val r = ReferenceParser.parse("example.com/ns:sub/repo:tag")
        assertEquals("ns:sub/repo", r.repository)
        assertEquals("tag", r.tag)
    }

    @Test
    fun `blank reference is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReferenceParser.parse("") }
        assertThrows(IllegalArgumentException::class.java) { ReferenceParser.parse("   ") }
    }

    @Test
    fun `host only reference without repository is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReferenceParser.parse("localhost:5000") }
    }

    @Test
    fun `invalid port is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ReferenceParser.parse("localhost:abc/repo") }
    }
}
