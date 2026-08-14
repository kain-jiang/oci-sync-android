package com.tiramission.ocisync.core.model

import java.io.File

/** push 请求,见 docs/07-api-contract.md §7。 */
data class PushRequest(
    val localPath: File,            // 已拷贝到 cacheDir 的本地文件/目录
    val remoteRef: String,
    val passphrase: String?,        // null/空 → 不加密
    val labels: Map<String, String> = emptyMap(),
)

/** pull 请求,见 docs/07-api-contract.md §7。 */
data class PullRequest(
    val remoteRef: String,
    val destDir: File,
    val passphrase: String?,        // 加密内容必填
)

/** 编排阶段回调。 */
enum class Stage { IDLE, PACKING, ENCRYPTING, UPLOADING, DOWNLOADING, DECRYPTING, UNPACKING, DONE }
