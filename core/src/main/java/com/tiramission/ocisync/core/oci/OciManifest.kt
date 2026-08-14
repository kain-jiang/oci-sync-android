package com.tiramission.ocisync.core.oci

import kotlinx.serialization.Serializable

/** OCI manifest descriptor,见 docs/02-core-format.md §3。 */
@Serializable
data class OciDescriptor(
    val mediaType: String = "",
    val digest: String = "",
    val size: Long = 0,
)

/** OCI manifest(schemaVersion 2),未知字段忽略。 */
@Serializable
data class OciManifest(
    val schemaVersion: Int = 2,
    val mediaType: String = "application/vnd.oci.image.manifest.v1+json",
    val config: OciDescriptor = OciDescriptor(),
    val layers: List<OciDescriptor> = emptyList(),
    val annotations: Map<String, String> = emptyMap(),
)
