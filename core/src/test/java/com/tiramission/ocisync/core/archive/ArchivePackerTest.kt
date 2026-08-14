package com.tiramission.ocisync.core.archive

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivePackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `pack single file then unpack yields identical content`() {
        val src = tmp.newFile("hello.txt")
        src.writeText("hello oci-sync")

        val packed = ArchivePacker.packToBytes(src)

        val dest = tmp.newFolder("out")
        ArchiveUnpacker.unpack(packed, dest)
        val extracted = dest.resolve("hello.txt")
        assertTrue(extracted.isFile)
        assertEquals("hello oci-sync", extracted.readText())
    }

    @Test
    fun `pack empty file round-trip`() {
        val src = tmp.newFile("empty.txt")

        val packed = ArchivePacker.packToBytes(src)
        val dest = tmp.newFolder("out")
        ArchiveUnpacker.unpack(packed, dest)

        assertTrue(dest.resolve("empty.txt").isFile)
        assertEquals(0, dest.resolve("empty.txt").length())
    }

    @Test
    fun `pack nested directory tree preserves structure`() {
        val root = tmp.newFolder("data")
        root.resolve("a.txt").writeText("A")
        root.resolve("sub").mkdirs()
        root.resolve("sub/b.txt").writeText("BB")
        root.resolve("sub/deep").mkdirs()
        root.resolve("sub/deep/c.txt").writeText("CCC")

        val packed = ArchivePacker.packToBytes(root)

        // 目录条目 Name 必须以 / 结尾,且根为 "data"
        val entryNames = mutableListOf<String>()
        GzipCompressorInputStream(packed.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    entryNames += e.name
                    e = tar.nextEntry
                }
            }
        }
        assertEquals(
            listOf("data/", "data/a.txt", "data/sub/", "data/sub/b.txt", "data/sub/deep/", "data/sub/deep/c.txt"),
            entryNames
        )

        val dest = tmp.newFolder("out")
        ArchiveUnpacker.unpack(packed, dest)
        assertEquals("A", dest.resolve("data/a.txt").readText())
        assertEquals("BB", dest.resolve("data/sub/b.txt").readText())
        assertEquals("CCC", dest.resolve("data/sub/deep/c.txt").readText())
        assertTrue(dest.resolve("data/sub/deep").isDirectory)
    }

    @Test
    fun `pack empty directory produces single root entry`() {
        val root = tmp.newFolder("empty-dir")

        val packed = ArchivePacker.packToBytes(root)
        val entryNames = mutableListOf<String>()
        GzipCompressorInputStream(packed.inputStream()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var e = tar.nextEntry
                while (e != null) {
                    entryNames += e.name
                    e = tar.nextEntry
                }
            }
        }
        assertEquals(listOf("empty-dir/"), entryNames)
    }

    @Test
    fun `packed bytes start with gzip magic and directory entries end with slash`() {
        val root = tmp.newFolder("x")
        root.resolve("f.txt").writeText("data")

        val packed = ArchivePacker.packToBytes(root)
        // gzip 魔数 1f 8b
        assertArrayEquals(byteArrayOf(0x1f.toByte(), 0x8b.toByte()), packed.copyOfRange(0, 2))
    }
}
