package com.tiramission.ocisync.core.oci

import java.util.concurrent.ConcurrentHashMap

/**
 * Bearer token 缓存,见 docs/03-oci-protocol.md §3.2。
 * key = registry host;token 有 expires_in 时提前 60s 过期。
 */
class TokenCache {
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(key: String): String? =
        map[key]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.token

    fun put(key: String, token: String, expiresInSeconds: Long?) {
        val expiresAt = if (expiresInSeconds != null && expiresInSeconds > 60) {
            System.currentTimeMillis() + (expiresInSeconds - 60) * 1000
        } else {
            Long.MAX_VALUE
        }
        map[key] = Entry(token, expiresAt)
    }

    private class Entry(val token: String, val expiresAt: Long)
}
