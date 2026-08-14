package com.tiramission.ocisync.core.oci

/**
 * OCI 引用,见 docs/03-oci-protocol.md §2 与 ADR-010。
 * 规则(与 Docker/oras 一致):单段名隐式补 docker.io/library;
 * 含 "." ":" 或 "localhost" 视为显式 registry。
 */
data class Reference(
    val registry: String,      // 不含 scheme/port,如 "registry-1.docker.io"
    val port: Int?,            // 显式端口,如 5000
    val repository: String,    // 如 "myteam/files"
    val tag: String?,          // null 表示无 tag
    val digest: String?,       // "sha256:..." 或 null
) {
    val registryHost: String get() = if (port != null) "$registry:$port" else registry

    val fullName: String
        get() = when {
            digest != null -> "$registryHost/$repository@$digest"
            tag != null -> "$registryHost/$repository:$tag"
            else -> "$registryHost/$repository"
        }

    val baseUri: String get() = "https://$registryHost/v2"

    val isDigestRef: Boolean get() = digest != null
}

object ReferenceParser {

    private const val DOCKER_IO_HOST = "registry-1.docker.io"

    /** 解析 OCI 引用。非法引用抛 [IllegalArgumentException]。 */
    fun parse(ref: String): Reference {
        require(ref.isNotBlank()) { "reference is empty" }

        var rest = ref
        // 1. digest 引用:最后(且唯一)的 '@'
        var digest: String? = null
        val at = rest.indexOf('@')
        if (at >= 0) {
            digest = rest.substring(at + 1)
            rest = rest.substring(0, at)
            require(digest.isNotBlank()) { "digest is empty: $ref" }
        }

        // 2. tag:位于最后一个 '/' 之后的最后一个 ':'
        var tag: String? = null
        val lastSlash = rest.lastIndexOf('/')
        val lastColon = rest.lastIndexOf(':')
        if (lastColon > lastSlash) {
            tag = rest.substring(lastColon + 1)
            rest = rest.substring(0, lastColon)
            require(tag.isNotBlank()) { "tag is empty: $ref" }
        }

        // 3. registry vs repository
        val firstSlash = rest.indexOf('/')
        val (registryRaw, repository) = if (firstSlash < 0) {
            val name = rest
            if (isExplicitHost(name)) {
                // 单段显式 host(如 "localhost:5000")→ 无仓库,非法
                throw IllegalArgumentException("repository is empty: $ref")
            } else {
                DOCKER_IO_HOST to "library/$name"
            }
        } else {
            val host = rest.substring(0, firstSlash)
            val repo = rest.substring(firstSlash + 1)
            if (isExplicitHost(host)) {
                host to repo
            } else {
                // "myteam/files" → docker.io/myteam/files(仅单段名才加 library/)
                DOCKER_IO_HOST to "$host/$repo"
            }
        }
        require(repository.isNotBlank()) { "repository is empty: $ref" }

        // 4. 分离显式端口
        var registry = registryRaw
        var port: Int? = null
        val colon = registry.lastIndexOf(':')
        if (colon >= 0) {
            val portStr = registry.substring(colon + 1)
            require(portStr.isNotEmpty() && portStr.all { it.isDigit() }) { "invalid port: $ref" }
            port = portStr.toInt()
            registry = registry.substring(0, colon)
        }

        return Reference(
            registry = registry,
            port = port,
            repository = repository,
            // digest 引用与 tag 互斥:digest ref 时 tag 保持 null
            tag = if (digest != null) null else tag ?: "latest",
            digest = digest,
        )
    }

    private fun isExplicitHost(host: String): Boolean =
        host.contains('.') || host.contains(':') || host == "localhost"
}
