package com.tiramission.ocisync.core.model

import kotlinx.serialization.Serializable

/** 本工具 artifact 的展示模型,见 docs/02-core-format.md §4。 */
@Serializable
data class ArtifactInfo(
    val fullName: String,      // "<registry>/<repo>:<tag>"
    val repo: String,          // 仓库名(不含 registry)
    val tag: String,
    val digest: String,        // manifest digest
    val encrypted: Boolean,
    val version: String,       // io.oci-sync.version
    val size: Long,            // layers[0].size
    val labels: Map<String, String>,  // 非 io.oci-sync.* 前缀的 annotations
)
