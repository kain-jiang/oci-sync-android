package com.tiramission.ocisync.core.model

import com.tiramission.ocisync.core.archive.ArchivePacker
import com.tiramission.ocisync.core.archive.ArchiveUnpacker
import com.tiramission.ocisync.core.cache.Activity
import com.tiramission.ocisync.core.cache.ActivityStore
import com.tiramission.ocisync.core.cache.ActivityType
import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.crypto.CryptoEngine
import com.tiramission.ocisync.core.oci.OciClient
import com.tiramission.ocisync.core.oci.ReferenceParser
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 编排层:把 archive + crypto + oci + config + cache 串成完整操作,
 * 对应 Go CLI 的 cmd/runPush 等。见 docs/07-api-contract.md §7。
 *
 * - push/pull 记录活动(失败也记录,带 error,见 ADR-005)
 * - pull 加密预检:加密且无口令 → 快速失败,不下载 layer
 * - 加密路径整读(受 512MB 单文件上限约束,v1 设计);明文路径流式
 */
class SyncService(
    private val ociClient: OciClient,
    private val activityStore: ActivityStore,
    private val configLoader: ConfigLoader,
) {

    /** push 完整流程:打包 → 可选加密 → 上传 → 活动记录。 */
    suspend fun push(
        request: PushRequest,
        onStage: (Stage) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> {
        val ref = try {
            ReferenceParser.parse(request.remoteRef)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val result = runCatching {
            onStage(Stage.PACKING)
            val packedFile = File.createTempFile("oci-sync-push-", ".tgz")
            try {
                packedFile.outputStream().use { out ->
                    ArchivePacker.pack(request.localPath, out)
                }
                if (request.passphrase.isNullOrEmpty()) {
                    onStage(Stage.UPLOADING)
                    val size = packedFile.length()
                    packedFile.inputStream().use { stream ->
                        ociClient.push(ref, stream, size, encrypted = false, labels = request.labels) { sent ->
                            onProgress(if (size > 0) sent.toFloat() / size else 1f)
                        }
                    }
                } else {
                    onStage(Stage.ENCRYPTING)
                    val ciphertext = CryptoEngine.encrypt(packedFile.readBytes(), request.passphrase)
                    onStage(Stage.UPLOADING)
                    ociClient.push(ref, ByteArrayInputStream(ciphertext), ciphertext.size.toLong(), encrypted = true, labels = request.labels) { sent ->
                        onProgress(sent.toFloat() / ciphertext.size)
                    }
                }
                onStage(Stage.DONE)
            } finally {
                packedFile.delete()
            }
        }
        record(ActivityType.PUSH, request.remoteRef, request.localPath.absolutePath, result)
        return result
    }

    /** pull 完整流程:预检加密 → 下载 → 可选解密 → 解包 → 活动记录。 */
    suspend fun pull(
        request: PullRequest,
        onStage: (Stage) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): Result<Unit> {
        val ref = try {
            ReferenceParser.parse(request.remoteRef)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val result = runCatching {
            onStage(Stage.DOWNLOADING)
            val encrypted = ociClient.isEncrypted(ref)
            if (encrypted && request.passphrase.isNullOrEmpty()) {
                throw IllegalStateException("artifact is encrypted, passphrase required")
            }
            // 下载进度:size 在 pull 返回后可知;流读取(pull 返回后)才触发回调,时序安全
            var pulledSize = 0L
            val pulled = ociClient.pull(ref) { bytes ->
                if (pulledSize > 0) onProgress(bytes.toFloat() / pulledSize)
            }
            pulledSize = pulled.size
            if (encrypted) {
                pulled.data.use { stream ->
                    onStage(Stage.DECRYPTING)
                    val plaintext = CryptoEngine.decrypt(stream.readBytes(), request.passphrase!!)
                    onStage(Stage.UNPACKING)
                    ArchiveUnpacker.unpack(ByteArrayInputStream(plaintext), request.destDir)
                }
            } else {
                pulled.data.use { stream ->
                    onStage(Stage.UNPACKING)
                    ArchiveUnpacker.unpack(stream, request.destDir)
                }
            }
            onStage(Stage.DONE)
        }
        record(ActivityType.PULL, request.remoteRef, request.destDir.absolutePath, result)
        return result
    }

    /** list + 按 label 过滤。 */
    suspend fun list(ref: String, labelFilters: List<String>): Result<List<ArtifactInfo>> {
        val parsed = try {
            ReferenceParser.parse(ref)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        return runCatching {
            val artifacts = ociClient.list(parsed)
            if (labelFilters.isEmpty()) {
                artifacts
            } else {
                artifacts.filter { a ->
                    labelFilters.all { f -> matchesFilter(a.labels, f) }
                }
            }
        }
    }

    /** delete(记录活动)。 */
    suspend fun delete(ref: String): Result<Unit> {
        val parsed = try {
            ReferenceParser.parse(ref)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val result = runCatching { ociClient.delete(parsed) }
        record(ActivityType.DELETE, ref, null, result)
        return result
    }

    /** label set(记录活动)。 */
    suspend fun setLabels(ref: String, updates: Map<String, String>): Result<Unit> {
        val parsed = try {
            ReferenceParser.parse(ref)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val result = runCatching { ociClient.updateAnnotations(parsed, updates, emptyList()) }
        record(ActivityType.LABEL, ref, null, result, labels = updates.keys.toList())
        return result
    }

    /** label unset(记录活动)。 */
    suspend fun unsetLabels(ref: String, keys: List<String>): Result<Unit> {
        val parsed = try {
            ReferenceParser.parse(ref)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val result = runCatching { ociClient.updateAnnotations(parsed, emptyMap(), keys) }
        record(ActivityType.LABEL, ref, null, result, labels = keys)
        return result
    }

    // ── 内部 ─────────────────────────────────────────────

    private suspend fun record(type: ActivityType, remoteRef: String, localPath: String?, result: Result<*>, labels: List<String> = emptyList()) {
        activityStore.add(
            Activity(
                type = type,
                timestamp = System.currentTimeMillis(),
                remoteRef = remoteRef,
                localPath = localPath,
                labels = labels,
                success = result.isSuccess,
                error = result.exceptionOrNull()?.message,
            )
        )
    }

    /** label 过滤规则:"k" 存在即可;"k=v" 需匹配值。 */
    private fun matchesFilter(labels: Map<String, String>, filter: String): Boolean {
        val eq = filter.indexOf('=')
        return if (eq >= 0) {
            labels[filter.substring(0, eq)] == filter.substring(eq + 1)
        } else {
            labels.containsKey(filter)
        }
    }
}
