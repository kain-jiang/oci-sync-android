package com.tiramission.ocisync.core.oci

import java.io.IOException

/** OCI 操作异常层次,见 docs/03-oci-protocol.md §6。 */
sealed class OciException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthRequired(val registry: String) : OciException("Authentication required for $registry")
    class AuthFailed(val registry: String, reason: String) : OciException("Auth failed for $registry: $reason")
    class NotFound(val ref: String) : OciException("Not found: $ref")
    class Unsupported(val registry: String, op: String) : OciException("$registry does not support $op")
    class TooLarge(val size: Long, val limit: Long) : OciException("Artifact too large: $size > $limit")
    class Network(cause: IOException) : OciException("Network error", cause)
    class Protocol(message: String) : OciException(message)
}
