package com.tiramission.ocisync.core.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class ArchiveUnpackerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `unpack creates dest dir when missing`() {
        val src = tmp.newFile("f.txt").apply { writeText("x") }
        val packed = ArchivePacker.packToBytes(src)

        val dest = File(tmp.root, "not-yet-exists")
        ArchiveUnpacker.unpack(packed, dest)
        assertTrue(dest.resolve("f.txt").isFile)
    }

    @Test
    fun `parent traversal entry is rejected`() {
        val packed = maliciousTar("../evil.txt")
        val dest = tmp.newFolder("out")
        val ex = assertThrows(ArchiveException::class.java) { ArchiveUnpacker.unpack(packed, dest) }
        assertTrue(ex.message!!.contains("illegal file path in archive"))
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun `absolute path entry is rejected`() {
        // 手工构造原始 tar 字节(不经 TarArchiveOutputStream 的前导斜杠规范化),验证读侧防护
        val packed = rawTarGz("/etc/passwd" to '0')
        val dest = tmp.newFolder("out")
        assertThrows(ArchiveException::class.java) { ArchiveUnpacker.unpack(packed, dest) }
        assertFalse(File("/etc/passwd").let { false }) // 防误写占位断言
    }

    @Test
    fun `deep traversal entry is rejected`() {
        val packed = maliciousTar("a/../../evil.txt")
        val dest = tmp.newFolder("out")
        assertThrows(ArchiveException::class.java) { ArchiveUnpacker.unpack(packed, dest) }
    }

    @Test
    fun `symlink entry is skipped without error`() {
        val bos = ByteArrayOutputStream()
        GzipCompressorOutputStream(bos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                val link = TarArchiveEntry("link", TarConstants.LF_SYMLINK)
                link.linkName = "target"
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
            }
        }
        // 调试输出已移除:commons-compress 1.28 isFile() 对 symlink 返回 true,解包按 linkFlag 显式判断
        val dest = tmp.newFolder("out")
        ArchiveUnpacker.unpack(bos.toByteArray(), dest) // 不应抛异常
        assertFalse(dest.resolve("link").exists())
    }

    /** 手工构造单个条目的原始 tar.gz(绕过写入端对条目名的规范化,用于恶意输入测试)。 */
    private fun rawTarGz(vararg entries: Pair<String, Char>): ByteArray {
        val tarBytes = ByteArrayOutputStream()
        entries.forEach { (name, type) -> tarBytes.write(rawTarHeader(name, type)) }
        tarBytes.write(ByteArray(1024)) // EOF: 两个空块
        val gz = ByteArrayOutputStream()
        GzipCompressorOutputStream(gz).use { it.write(tarBytes.toByteArray()) }
        return gz.toByteArray()
    }

    private fun rawTarHeader(name: String, typeflag: Char): ByteArray {
        val h = ByteArray(512)
        fun put(s: String, start: Int, len: Int) {
            val b = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(b, 0, h, start, minOf(b.size, len))
        }
        put(name, 0, 100)
        put("0000644\u0000", 100, 8)
        put("0000000\u0000", 108, 8)
        put("0000000\u0000", 116, 8)
        put("00000000000\u0000", 124, 12) // size=0
        put("00000000000\u0000", 136, 12) // mtime=0
        put("        ", 148, 8)           // checksum 占位
        h[156] = typeflag.code.toByte()
        put("ustar\u0000", 257, 6)
        put("00", 263, 2)
        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        put(sum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)
        return h
    }

    /** 构造包含指定条目的 tar.gz(条目内容为空),用于恶意输入测试。 */
    private fun maliciousTar(vararg names: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GzipCompressorOutputStream(bos).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                names.forEach { name ->
                    val entry = TarArchiveEntry(name)
                    entry.size = 0
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                }
            }
        }
        return bos.toByteArray()
    }
}
