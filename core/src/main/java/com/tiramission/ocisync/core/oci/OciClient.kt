package com.tiramission.ocisync.core.oci

import com.tiramission.ocisync.core.model.ArtifactInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** registry 凭据(内存态,落盘前须经 Keystore 加密,见 docs/04-crypto-security.md)。 */
data class Credential(val username: String, val password: String)

/** 认证提供者(app 侧基于 DataStore + Keystore 实现)。 */
interface RegistryAuthProvider {
    /** 返回指定 registry 的凭据(可能为 null → 匿名)。 */
    suspend fun credential(registryHost: String): Credential?
    /** 用户通过 401 引导流程保存的凭据。 */
    suspend fun saveCredential(registryHost: String, credential: Credential)
}

/** pull 结果:layer 字节流(由调用方关闭)。 */
class PullResult(val data: InputStream, val encrypted: Boolean, val size: Long)

/**
 * 轻量 OCI Distribution Spec 客户端,见 docs/03-oci-protocol.md。
 * - 401 → Bearer token 流程(拦截器,单次重试)
 * - 429/5xx → 指数退避重试(最多 3 次)
 * - 全部流式处理,不整读内存
 */
class OciClient(
    client: OkHttpClient,
    private val authProvider: RegistryAuthProvider,
    private val tokenCache: TokenCache = TokenCache(),
    private val allowInsecureHttp: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val manifestMediaType = "application/vnd.oci.image.manifest.v1+json".toMediaType()
    private val octetStream = "application/octet-stream".toMediaType()
    private val manifestAccept = "application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

    // token 请求专用 client(无认证拦截器,避免递归)
    private val tokenClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val authInterceptor = AuthInterceptor(tokenCache)

    // 认证拦截器挂到调用方 client 上(保持外部拦截器顺序,认证兜底)
    private val client: OkHttpClient = client.newBuilder().addInterceptor(authInterceptor).build()

    /** push:monolithic blob 上传 → manifest PUT(带 tag)。流式,内存 O(1)。 */
    suspend fun push(
        ref: Reference,
        data: InputStream,
        dataSize: Long,
        encrypted: Boolean,
        labels: Map<String, String>,
        onProgress: ((Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val cred = authProvider.credential(ref.registryHost)
        val tmp = File.createTempFile("oci-push-", ".blob")
        try {
            // 1. 缓冲到临时文件并计算 digest(流式,避免 OOM)
            data.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            val digest = sha256File(tmp)
            require(tmp.length() == dataSize || dataSize == 0L) {
                "data size mismatch: stream=${tmp.length()}, declared=$dataSize"
            }

            // 2. POST /v2/<name>/blobs/uploads/
            val uploadResp = executeWithRetry(
                newRequest(ref, cred)
                    .url("${baseUrl(ref)}/${ref.repository}/blobs/uploads/")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
            ) { r -> r.code == 201 }
            val location = uploadResp.header("Location")
                ?: throw OciException.Protocol("registry returned no Location for blob upload")
            uploadResp.close()

            // 3. PUT <upload-url>?digest=sha256:... (monolithic)
            val uploadUrl = location.let { if (it.startsWith("http")) it else baseUrl(ref) + it }
            val putUrl = if (uploadUrl.contains('?')) "$uploadUrl&digest=$digest" else "$uploadUrl?digest=$digest"
            val body = ProgressRequestBody(tmp, octetStream, dataSize, onProgress)
            val putResp = executeWithRetry(
                newRequest(ref, cred).url(putUrl).put(body).build()
            ) { r -> r.code == 201 }
            putResp.close()

            // 4. PUT manifest(带 tag)
            val manifest = OciManifest(
                config = OciDescriptor(
                    mediaType = "application/vnd.oci.image.config.v1+json",
                    digest = sha256(EMPTY_CONFIG),
                    size = EMPTY_CONFIG.size.toLong(),
                ),
                layers = listOf(OciDescriptor(mediaType = "application/octet-stream", digest = digest, size = tmp.length())),
                annotations = buildMap {
                    putAll(labels)
                    put("io.oci-sync.version", VERSION)
                    put("io.oci-sync.encrypted", encrypted.toString())
                },
            )
            val manifestBytes = json.encodeToString(OciManifest.serializer(), manifest).toByteArray()
            val manifestResp = executeWithRetry(
                newRequest(ref, cred)
                    .url("${baseUrl(ref)}/${ref.repository}/manifests/${ref.tag}")
                    .put(manifestBytes.toRequestBody(manifestMediaType))
                    .build()
            ) { r -> r.code == 201 }
            manifestResp.close()
        } finally {
            tmp.delete()
        }
    }

    /** 检查加密状态:仅 GET manifest,不下载 layer。 */
    suspend fun isEncrypted(ref: Reference): Boolean = withContext(Dispatchers.IO) {
        fetchManifest(ref, authProvider.credential(ref.registryHost)).manifest.annotations[ENCRYPTED_KEY] == "true"
    }

    /** pull:GET manifest + GET layer(流式,带下载进度回调)。 */
    suspend fun pull(ref: Reference, onProgress: ((Long) -> Unit)? = null): PullResult = withContext(Dispatchers.IO) {
        val cred = authProvider.credential(ref.registryHost)
        val fetched = fetchManifest(ref, cred)
        val layer = fetched.manifest.layers.firstOrNull()
            ?: throw OciException.Protocol("manifest has no layers")
        val req = newRequest(ref, cred)
            .url("${baseUrl(ref)}/${ref.repository}/blobs/${layer.digest}")
            .build()
        val resp = executeWithRetry(req) { r -> r.code == 200 }
        val body = resp.body
            ?: throw OciException.Protocol("empty blob response")
        PullResult(
            data = CountingInputStream(body.byteStream(), body.contentLength(), onProgress),
            encrypted = fetched.manifest.annotations[ENCRYPTED_KEY] == "true",
            size = layer.size,
        )
    }

    /** list tags 并过滤本工具产物(含 io.oci-sync.version annotation)。 */
    suspend fun list(ref: Reference): List<ArtifactInfo> = withContext(Dispatchers.IO) {
        val cred = authProvider.credential(ref.registryHost)
        val tags = fetchAllTags(ref, cred)
        tags.mapNotNull { tag ->
            val manifestRef = ref.copy(tag = tag)
            val fetched = try {
                fetchManifest(manifestRef, cred)
            } catch (e: OciException) {
                null // 单个 tag 失败(已删等)跳过
            } ?: return@mapNotNull null
            val version = fetched.manifest.annotations[VERSION_KEY] ?: return@mapNotNull null
            ArtifactInfo(
                fullName = "${manifestRef.registryHost}/${manifestRef.repository}:$tag",
                repo = manifestRef.repository,
                tag = tag,
                digest = fetched.digest,
                encrypted = fetched.manifest.annotations[ENCRYPTED_KEY] == "true",
                version = version,
                size = fetched.manifest.layers.firstOrNull()?.size ?: 0,
                labels = fetched.manifest.annotations.filterKeys { !it.startsWith("io.oci-sync.") },
            )
        }
    }

    /** delete:resolve tag→digest→DELETE manifest(必须用 digest),再尽力删 blob。 */
    suspend fun delete(ref: Reference) = withContext(Dispatchers.IO) {
        val cred = authProvider.credential(ref.registryHost)
        val manifestRef = if (ref.isDigestRef) ref else ref.copy(tag = ref.tag ?: "latest")
        val digest = if (manifestRef.isDigestRef) {
            manifestRef.digest!!
        } else {
            fetchManifest(manifestRef, cred).digest
        }
        val delResp = executeWithRetry(
            newRequest(manifestRef, cred)
                .url("${baseUrl(manifestRef)}/${manifestRef.repository}/manifests/$digest")
                .delete()
                .build()
        ) { r -> r.code == 202 || r.code == 200 }
        delResp.close()

        // 尽力删除 blob(registry 允许时)
        try {
            val blobDigest = fetchManifest(manifestRef, cred).manifest.layers.firstOrNull()?.digest ?: return@withContext
            val blobResp = executeWithRetry(
                newRequest(manifestRef, cred)
                    .url("${baseUrl(manifestRef)}/${manifestRef.repository}/blobs/$blobDigest")
                    .delete()
                    .build()
            ) { r -> r.code == 202 || r.code == 200 }
            blobResp.close()
        } catch (_: OciException) {
            // blob 清理失败不影响 delete 成功
        }
    }

    /** label set/unset:改 annotations → PUT 新 digest → 更新 tag。 */
    suspend fun updateAnnotations(
        ref: Reference,
        updates: Map<String, String>,
        removeKeys: List<String>,
    ) = withContext(Dispatchers.IO) {
        val cred = authProvider.credential(ref.registryHost)
        val fetched = fetchManifest(ref, cred)
        val newAnnotations = fetched.manifest.annotations.toMutableMap().apply {
            putAll(updates)
            removeKeys.forEach { remove(it) }
        }
        val newManifest = fetched.manifest.copy(annotations = newAnnotations)
        val bytes = json.encodeToString(OciManifest.serializer(), newManifest).toByteArray()
        val newDigest = sha256(bytes)

        // PUT 到不可变 digest 地址,再更新 tag
        val digestResp = executeWithRetry(
            newRequest(ref, cred)
                .url("${baseUrl(ref)}/${ref.repository}/manifests/$newDigest")
                .put(bytes.toRequestBody(manifestMediaType))
                .build()
        ) { r -> r.code == 201 }
        digestResp.close()
        val tagResp = executeWithRetry(
            newRequest(ref, cred)
                .url("${baseUrl(ref)}/${ref.repository}/manifests/${ref.tag}")
                .put(bytes.toRequestBody(manifestMediaType))
                .build()
        ) { r -> r.code == 201 }
        tagResp.close()
    }

    // ── 内部 ─────────────────────────────────────────────

    private class FetchedManifest(val manifest: OciManifest, val digest: String)

    private fun fetchManifest(ref: Reference, cred: Credential?): FetchedManifest {
        val reference = ref.tag ?: ref.digest ?: "latest"
        val resp = executeWithRetry(
            newRequest(ref, cred)
                .url("${baseUrl(ref)}/${ref.repository}/manifests/$reference")
                .header("Accept", manifestAccept)
                .build()
        ) { r -> r.code == 200 }
        resp.use {
            val body = it.body?.string()
                ?: throw OciException.Protocol("empty manifest response")
            val manifest = json.decodeFromString(OciManifest.serializer(), body)
            val digest = it.header("Docker-Content-Digest") ?: sha256(body.toByteArray())
            return FetchedManifest(manifest, digest)
        }
    }

    private fun fetchAllTags(ref: Reference, cred: Credential?): List<String> {
        val tags = mutableListOf<String>()
        var last: String? = null
        while (true) {
            val url = buildString {
                append("${baseUrl(ref)}/${ref.repository}/tags/list?n=1000")
                if (last != null) append("&last=$last")
            }
            val resp = executeWithRetry(
                newRequest(ref, cred).url(url).build()
            ) { r -> r.code == 200 }
            resp.use {
                val body = it.body?.string() ?: throw OciException.Protocol("empty tags response")
                val parsed = json.decodeFromString(TagsResponse.serializer(), body)
                tags += parsed.tags.orEmpty()
                val link = it.header("Link")
                val next = link?.let { l ->
                    Regex("<([^>]+)>;\\s*rel=\"next\"").find(l)?.groupValues?.get(1)
                }
                if (next == null || parsed.tags.isNullOrEmpty()) break
                last = parsed.tags.last()
            }
        }
        return tags.distinct()
    }

    private fun baseUrl(ref: Reference): String {
        val scheme = if (allowInsecureHttp) "http" else "https"
        return "$scheme://${ref.registryHost}/v2"
    }

    /** 构造带凭据 tag 的请求(拦截器从中取凭据发 token 挑战)。 */
    private fun newRequest(ref: Reference, cred: Credential?): Request.Builder =
        Request.Builder().tag(Credential::class.java, cred)

    /** 执行请求;429/5xx 指数退避重试(最多 3 次),其余状态由 [isSuccess] 判定并映射错误。 */
    private fun executeWithRetry(request: Request, isSuccess: (Response) -> Boolean): Response {
        var attempt = 0
        while (true) {
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                if (attempt < MAX_RETRIES) {
                    attempt++
                    sleep(backoff(attempt))
                    continue
                }
                throw OciException.Network(e)
            }
            when {
                isSuccess(response) -> return response
                response.code == 429 || response.code >= 500 -> {
                    response.close()
                    if (attempt < MAX_RETRIES) {
                        attempt++
                        sleep(backoff(attempt))
                    } else {
                        throw OciException.Protocol("registry error after $MAX_RETRIES retries")
                    }
                }
                else -> throw mapError(response, request)
            }
        }
    }

    private fun mapError(response: Response, request: Request): OciException {
        val registry = request.url.host
        return when (response.code) {
            401 -> OciException.AuthRequired(registry)
            403 -> OciException.AuthFailed(registry, "forbidden")
            404 -> OciException.NotFound(request.url.toString())
            405 -> OciException.Unsupported(registry, request.method)
            else -> OciException.Protocol("unexpected HTTP ${response.code}")
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun backoff(attempt: Int): Long = 500L * (1L shl attempt.coerceAtMost(4))

    private fun sha256(data: ByteArray): String = "sha256:${hex(MessageDigest.getInstance("SHA-256").digest(data))}"

    private fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return "sha256:${hex(md.digest())}"
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** 包装输入流,读取时按字节回调进度(用于下载进度)。 */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val total: Long,
        private val onProgress: ((Long) -> Unit)?,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) {
                count++
                onProgress?.invoke(count)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) {
                count += n
                onProgress?.invoke(count)
            }
            return n
        }

        override fun close() = delegate.close()
    }

    /** 流式上传 RequestBody,带进度回调。 */
    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType,
        private val total: Long,
        private val onProgress: ((Long) -> Unit)?,
    ) : okhttp3.RequestBody() {
        override fun contentType() = mediaType
        override fun contentLength() = file.length()
        override fun writeTo(sink: okio.BufferedSink) {
            var sent = 0L
            val buf = ByteArray(64 * 1024)
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    onProgress?.invoke(sent)
                }
            }
        }
    }

    /** 401 → Bearer token 流程(单次重试)。凭据经 Request tag 传入。 */
    private inner class AuthInterceptor(private val tokenCache: TokenCache) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val key = request.url.host
            val cached = tokenCache.get(key)
            val first = if (cached != null) {
                request.newBuilder().header("Authorization", "Bearer $cached").build()
            } else {
                request
            }
            val response = chain.proceed(first)
            if (response.code != 401) return response
            response.close()
            val challenge = response.header("WWW-Authenticate") ?: return response
            if (!challenge.startsWith("Bearer")) {
                // Basic challenge:直接带 Basic 重试一次
                val cred = request.tag(Credential::class.java)
                if (cred != null) {
                    return chain.proceed(request.newBuilder().header("Authorization", basicAuth(cred)).build())
                }
                return response
            }
            val token = fetchToken(challenge, request.tag(Credential::class.java)) ?: return response
            tokenCache.put(key, token, null)
            return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }
    }

    private fun fetchToken(challenge: String, cred: Credential?): String? {
        val realm = Regex("realm=\"([^\"]+)\"").find(challenge)?.groupValues?.get(1) ?: return null
        val service = Regex("service=\"([^\"]+)\"").find(challenge)?.groupValues?.get(1)
        val scope = Regex("scope=\"([^\"]+)\"").find(challenge)?.groupValues?.get(1)
        val url = buildString {
            append(realm)
            append(if (realm.contains('?')) "&" else "?")
            if (service != null) append("service=").append(service).append('&')
            if (scope != null) append("scope=").append(scope)
        }
        val builder = Request.Builder().url(url).get()
        if (cred != null) builder.header("Authorization", basicAuth(cred))
        val resp = try {
            tokenClient.newCall(builder.build()).execute()
        } catch (_: IOException) {
            return null
        }
        resp.use {
            if (it.code != 200) return null
            val body = it.body?.string() ?: return null
            val token = Regex("\"(?:access_)?token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            return token
        }
    }

    private fun basicAuth(cred: Credential): String {
        val raw = "${cred.username}:${cred.password}".toByteArray()
        return "Basic ${java.util.Base64.getEncoder().encodeToString(raw)}"
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val VERSION = "0.1.0"
        private const val VERSION_KEY = "io.oci-sync.version"
        private const val ENCRYPTED_KEY = "io.oci-sync.encrypted"
        private val EMPTY_CONFIG = "{}".toByteArray()
    }
}

@kotlinx.serialization.Serializable
private data class TagsResponse(
    val name: String = "",
    val tags: List<String>? = null,
)
