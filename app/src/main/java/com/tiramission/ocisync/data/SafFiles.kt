package com.tiramission.ocisync.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * SAF 文件/目录辅助,见 docs/06-ui-design.md §5。
 *
 * - 单文件:拷贝到 cacheDir/push/<uuid>/<name>(统一走 File 路径,便于流式打包)
 * - 目录:DocumentFile 递归拷贝到 cacheDir/push/<uuid>/
 * - pull 目标:SAF tree 无 File 映射,先解包到 cacheDir 临时目录再复制回 tree
 */
object SafFiles {

    private const val MAX_NAME_LENGTH = 80

    /** 解析 content uri 的文件名(tree uri 等不支持 query 的返回 null → 兜底)。 */
    fun queryName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.let { return it }
                }
            }
            uri.lastPathSegment ?: "file"
        } catch (e: Exception) {
            // SAF tree/document uri 可能不支持 query(SecurityException/IllegalArgumentException 等)
            uri.lastPathSegment ?: "file"
        }
    }

    /** 解析 content uri 的文件大小(未知或不可查询返回 -1)。 */
    fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(idx)
                    if (size >= 0) return size
                }
            }
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** 将单文件 content uri 拷贝到 cacheDir,返回本地文件。 */
    fun copySingleFileToCache(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "push/${UUID.randomUUID()}").apply { mkdirs() }
        val name = sanitizeName(queryName(context, uri))
        val dest = File(dir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("cannot open $uri")
        return dest
    }

    /** 将 SAF 目录树递归拷贝到 cacheDir,返回本地目录。 */
    fun copyTreeToCache(context: Context, uri: Uri): File {
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("invalid tree uri")
        val dest = File(context.cacheDir, "push/${UUID.randomUUID()}")
        dest.mkdirs()
        copyTree(context, tree, dest)
        return dest
    }

    private fun copyTree(context: Context, doc: DocumentFile, dest: File) {
        doc.listFiles().forEach { child ->
            val name = sanitizeName(child.name ?: return@forEach)
            val target = File(dest, name)
            when {
                child.isDirectory -> {
                    target.mkdirs()
                    copyTree(context, child, target)
                }
                child.isFile -> {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    /**
     * 将本地目录树复制到 SAF tree uri(pull 解包结果回写用户选择的位置)。
     * 覆盖同名文件,不删除目标树中的额外文件。
     */
    fun copyDirToTree(context: Context, srcDir: File, treeUri: Uri) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("invalid tree uri")
        copyDir(context, srcDir, tree)
    }

    private fun copyDir(context: Context, srcDir: File, destDoc: DocumentFile) {
        srcDir.listFiles()?.forEach { child ->
            when {
                child.isDirectory -> {
                    val childDoc = destDoc.findFile(child.name) ?: destDoc.createDirectory(child.name)
                    if (childDoc != null) copyDir(context, child, childDoc)
                }
                child.isFile -> {
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(child.extension.lowercase()) ?: "application/octet-stream"
                    val childDoc = destDoc.findFile(child.name) ?: destDoc.createFile(mime, child.name)
                    if (childDoc != null) {
                        context.contentResolver.openOutputStream(childDoc.uri, "wt")?.use { output ->
                            child.inputStream().use { input -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]"), "_")
            .take(MAX_NAME_LENGTH)
            .ifBlank { "file" }
}
