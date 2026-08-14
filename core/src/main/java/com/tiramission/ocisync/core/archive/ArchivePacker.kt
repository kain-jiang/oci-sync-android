package com.tiramission.ocisync.core.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.File
import java.io.OutputStream

/**
 * 将文件/目录打包为 tar.gz。
 *
 * 字节级格式与 Go CLI(tiramission/oci-sync)完全兼容,见 docs/02-core-format.md §1:
 * - gzip mtime 显式设零(与 Go `gzip.NewWriter` 一致,保证字节级稳定)
 * - 目录条目 Name 以 `/` 结尾(linkFlag = DIRTYPE)
 * - 文件条目 size 准确,长名/大文件走 POSIX 模式
 * - 遍历按文件名排序(与 Go filepath.Walk 的字典序一致)
 */
object ArchivePacker {

    private const val DIR_MODE = 0b111101101 // 0755

    /** 将文件或目录打包为 tar.gz 写入 out(gzip 包装在内部完成)。流式处理,不整读内存。 */
    fun pack(srcPath: File, out: OutputStream) {
        require(srcPath.exists()) { "srcPath does not exist: $srcPath" }
        val params = GzipParameters().apply { modificationTime = 0 }
        GzipCompressorOutputStream(out, params).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                // 注:commons-compress 1.28 已移除 setBigFileMode(>8GB 条目自动走 PAX 扩展)
                if (srcPath.isDirectory) {
                    packDir(tar, srcPath, srcPath.name)
                } else {
                    packFile(tar, srcPath, srcPath.name)
                }
            }
        }
    }

    /** 便捷方法:返回 ByteArray(仅限小文件测试场景,生产走流式 pack)。 */
    fun packToBytes(srcPath: File): ByteArray =
        java.io.ByteArrayOutputStream().use { bos ->
            pack(srcPath, bos)
            bos.toByteArray()
        }

    private fun packDir(tar: TarArchiveOutputStream, dir: File, entryName: String) {
        val entry = TarArchiveEntry("$entryName/", TarConstants.LF_DIR).apply {
            mode = DIR_MODE
        }
        tar.putArchiveEntry(entry)
        tar.closeArchiveEntry()

        dir.listFiles()?.sortedBy { it.name }?.forEach { child ->
            val childName = "$entryName/${child.name}"
            if (child.isDirectory) packDir(tar, child, childName) else packFile(tar, child, childName)
        }
    }

    private fun packFile(tar: TarArchiveOutputStream, file: File, entryName: String) {
        val entry = TarArchiveEntry(file, entryName) // 自动携带 size/mtime/mode
        tar.putArchiveEntry(entry)
        file.inputStream().use { it.copyTo(tar) }
        tar.closeArchiveEntry()
    }
}
