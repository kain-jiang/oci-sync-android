package com.tiramission.ocisync.core.archive

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** 归档条目非法(路径穿越等)时抛出。 */
class ArchiveException(message: String) : Exception(message)

/**
 * 解包 tar.gz 到目标目录。
 *
 * 安全约束见 docs/02-core-format.md §1.4(强制):
 * - 每个条目解析后必须位于目标目录内,否则抛 [ArchiveException](消息与 Go 端一致)
 * - 仅处理目录与普通文件;symlink/device 等条目跳过(与 Go `default:` 分支一致)
 * - 文件权限按 tar 条目 mode 尽力设置(Android 文件系统支持有限)
 */
object ArchiveUnpacker {

    /** 解包 tar.gz 流到 destDir(不存在则创建)。 */
    fun unpack(data: InputStream, destDir: File) {
        ensureDestDir(destDir)
        val absDest = destDir.absoluteFile.canonicalFile
        GzipCompressorInputStream(data).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val target = resolve(absDest, entry.name)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        // 注意:commons-compress 1.28 的 isFile() 对 symlink 也返回 true,
                        // 必须显式按 linkFlag 判断,仅处理普通文件(与 Go 端行为对齐)
                        entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM -> {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                            applyMode(target, entry.mode)
                        }
                        else -> { /* symlink/device 等跳过,与 Go 一致 */ }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }

    /** 便捷方法:解包 ByteArray。 */
    fun unpack(data: ByteArray, destDir: File) = unpack(ByteArrayInputStream(data), destDir)

    private fun ensureDestDir(destDir: File) {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw ArchiveException("cannot create destination directory: $destDir")
        }
    }

    /** 路径穿越防护:拒绝绝对路径与逃逸目标目录的条目(与 Go 端行为一致)。 */
    private fun resolve(absDest: File, name: String): File {
        if (name.startsWith("/") || name.startsWith("\\") || name.matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
            throw ArchiveException("illegal file path in archive")
        }
        val target = File(absDest, name).canonicalFile
        val prefix = absDest.canonicalPath + File.separator
        if (!target.path.startsWith(prefix)) {
            throw ArchiveException("illegal file path in archive")
        }
        return target
    }

    /** 按 tar mode 的 owner 位尽力映射(Android 无完整 POSIX 权限)。 */
    private fun applyMode(file: File, mode: Int) {
        file.setReadable(mode and 0b100000000 != 0, true)   // 0400
        file.setWritable(mode and 0b1000000 != 0, true)     // 0200
        file.setExecutable(mode and 0b100000 != 0, true)    // 0100
    }
}
