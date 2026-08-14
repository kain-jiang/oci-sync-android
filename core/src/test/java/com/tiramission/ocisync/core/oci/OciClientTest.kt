package com.tiramission.ocisync.core.oci

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

class OciClientTest {

    @get:Rule
    val server = MockWebServer()

    // 指向 MockWebServer 的引用(getter:端口在 @Rule 启动后才可用)
    private val ref: Reference
        get() = ReferenceParser.parse("localhost:${server.port}/team/repo:v1")

    private val authProvider = object : RegistryAuthProvider {
        override suspend fun credential(registryHost: String): Credential? = null
        override suspend fun saveCredential(registryHost: String, credential: Credential) {}
    }

    private fun newClient(tokenCache: TokenCache = TokenCache()): OciClient {
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        return OciClient(http, authProvider, tokenCache, allowInsecureHttp = true)
    }

    private fun manifestBody(encrypted: Boolean = false, extraAnnotations: Map<String, String> = emptyMap()): String {
        val annotations = buildMap {
            put("io.oci-sync.version", "0.1.0")
            put("io.oci-sync.encrypted", encrypted.toString())
            putAll(extraAnnotations)
        }
        return """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.manifest.v1+json",
          "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": "sha256:config", "size": 2},
          "layers": [{"mediaType": "application/octet-stream", "digest": "sha256:layer1", "size": 11}],
          "annotations": ${annotationsToJson(annotations)}
        }
        """.trimIndent()
    }

    private fun annotationsToJson(annotations: Map<String, String>): String =
        annotations.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" }

    // ── push ──────────────────────────────────────────────

    @Test
    fun `push performs monolithic upload then manifest put`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/v2/team/repo/blobs/uploads/u1"))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/v2/team/repo/manifests/sha256:xxx"))

        val payload = "hello blob content".toByteArray()
        newClient().push(ref, ByteArrayInputStream(payload), payload.size.toLong(), encrypted = true, labels = mapOf("env" to "prod"))

        val r1 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", r1.method)
        assertEquals("/v2/team/repo/blobs/uploads/", r1.path)

        val r2 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", r2.method)
        assertTrue(r2.path!!.contains("digest=sha256")) // query 中 ':' 可能被 URL 编码,放宽断言
        assertEquals(payload.size.toString(), r2.getHeader("Content-Length"))

        val r3 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", r3.method)
        assertEquals("/v2/team/repo/manifests/v1", r3.path)
        assertEquals("application/vnd.oci.image.manifest.v1+json", r3.getHeader("Content-Type"))
        val body = r3.body.readUtf8()
        assertTrue(body.contains("\"io.oci-sync.encrypted\":\"true\""))
        assertTrue(body.contains("\"env\":\"prod\""))
    }

    @Test
    fun `push with auth challenge gets bearer token and retries`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.substringBefore("?") == "/token" -> MockResponse().setBody("""{"token":"tok-123"}""")
                    request.getHeader("Authorization") == "Bearer tok-123" ->
                        when {
                            request.method == "POST" -> MockResponse().setResponseCode(201).setHeader("Location", "/v2/team/repo/blobs/uploads/u1")
                            request.method == "PUT" && request.path!!.contains("digest=") -> MockResponse().setResponseCode(201)
                            else -> MockResponse().setResponseCode(201)
                        }
                    else -> MockResponse()
                        .setResponseCode(401)
                        .setHeader("WWW-Authenticate", "Bearer realm=\"${server.url("/token")}\",service=\"test-svc\",scope=\"repository:team/repo:pull,push\"")
                }
            }
        }
        val payload = "data".toByteArray()
        newClient().push(ref, ByteArrayInputStream(payload), payload.size.toLong(), encrypted = false, labels = emptyMap())

        // 顺序:初始 POST(401)→ GET /token → 重试 POST + PUT ×2(均带 Bearer)
        val initial = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v2/team/repo/blobs/uploads/", initial.path)
        assertTrue(initial.getHeader("Authorization") == null)

        val tokenReq = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/token", tokenReq.path?.substringBefore("?"))
        assertTrue(tokenReq.getHeader("Authorization") == null) // 匿名 → token 请求不带认证

        val authed = (1..3).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
        assertTrue(authed.all { it.getHeader("Authorization") == "Bearer tok-123" })
    }

    // ── pull / isEncrypted ────────────────────────────────

    @Test
    fun `pull returns layer stream and encrypted flag`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = true)))
        server.enqueue(MockResponse().setBody("layer-bytes"))

        val result = newClient().pull(ref)

        val r1 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", r1.method)
        assertEquals("/v2/team/repo/manifests/v1", r1.path)
        val r2 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v2/team/repo/blobs/sha256:layer1", r2.path)

        assertTrue(result.encrypted)
        assertEquals(11L, result.size)
        assertEquals("layer-bytes", result.data.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun `isEncrypted reflects manifest annotation`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = true)))
        assertTrue(newClient().isEncrypted(ref))
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = false)))
        assertFalse(newClient().isEncrypted(ref))
    }

    // ── list ──────────────────────────────────────────────

    @Test
    fun `list filters artifacts without version annotation`() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"team/repo","tags":["v1","v2","foreign"]}"""))
        server.enqueue(MockResponse().setBody(manifestBody(extraAnnotations = mapOf("team" to "core"))))
        server.enqueue(MockResponse().setBody(manifestBody()))
        server.enqueue(MockResponse().setBody("""{"schemaVersion":2,"config":{},"layers":[]}""")) // 无 version annotation

        val artifacts = newClient().list(ref)

        assertEquals(2, artifacts.size)
        assertEquals("v1", artifacts[0].tag)
        assertEquals(11L, artifacts[0].size)
        assertEquals(mapOf("team" to "core"), artifacts[0].labels)
        assertTrue(artifacts[0].fullName.endsWith(":v1"))
    }

    @Test
    fun `list follows pagination link header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"team/repo","tags":["v1","v2"]}""")
            .setHeader("Link", "</v2/team/repo/tags/list?last=v2&n=1000>; rel=\"next\""))
        server.enqueue(MockResponse().setBody("""{"name":"team/repo","tags":["v3"]}"""))
        server.enqueue(MockResponse().setBody(manifestBody()))
        server.enqueue(MockResponse().setBody(manifestBody()))
        server.enqueue(MockResponse().setBody(manifestBody()))

        val artifacts = newClient().list(ref)
        assertEquals(listOf("v1", "v2", "v3"), artifacts.map { it.tag })
    }

    // ── delete ────────────────────────────────────────────

    @Test
    fun `delete resolves digest then deletes manifest and blob`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody()).setHeader("Docker-Content-Digest", "sha256:manifest-digest"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setBody(manifestBody())) // blob 删除前再次解析 layers
        server.enqueue(MockResponse().setResponseCode(202))

        newClient().delete(ref)

        val r1 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", r1.method)
        val r2 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("DELETE", r2.method)
        assertEquals("/v2/team/repo/manifests/sha256:manifest-digest", r2.path)
    }

    // ── updateAnnotations ─────────────────────────────────

    @Test
    fun `updateAnnotations puts new digest then updates tag`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody(extraAnnotations = mapOf("old" to "1"))))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))

        newClient().updateAnnotations(ref, updates = mapOf("new" to "2"), removeKeys = listOf("old"))

        val r1 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", r1.method)
        val r2 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", r2.method)
        assertTrue(r2.path!!.startsWith("/v2/team/repo/manifests/sha256:"))
        val putBody = r2.body.readUtf8()
        assertTrue(putBody.contains("\"new\":\"2\""))
        assertFalse(putBody.contains("old"))
        val r3 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v2/team/repo/manifests/v1", r3.path)
    }

    // ── 错误映射 ──────────────────────────────────────────

    @Test
    fun `not found maps to NotFound`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val ex = try {
            newClient().isEncrypted(ref)
            null
        } catch (e: OciException) {
            e
        }
        assertTrue(ex is OciException.NotFound)
    }

    @Test
    fun `forbidden maps to AuthFailed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val ex = try {
            newClient().isEncrypted(ref)
            null
        } catch (e: OciException) {
            e
        }
        assertTrue(ex is OciException.AuthFailed)
    }

    @Test
    fun `server error retries then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = true)))
        assertTrue(newClient().isEncrypted(ref))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `too many requests retries then succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = true)))
        assertTrue(newClient().isEncrypted(ref))
        assertEquals(2, server.requestCount)
    }

    // ── 凭据验证 ─────────────────────────────────────────

    @Test
    fun `checkCredential valid when v2 returns 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = newClient().checkCredential("localhost:${server.port}", Credential("user", "pass"))
        assertEquals(AuthCheckResult.VALID, result)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v2/", req.path)
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `checkCredential invalid on 401 without bearer challenge`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = newClient().checkCredential("localhost:${server.port}", Credential("user", "wrong"))
        assertEquals(AuthCheckResult.INVALID, result)
    }

    @Test
    fun `checkCredential valid via bearer token flow`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.substringBefore("?") == "/token" -> MockResponse().setBody("""{"token":"tok"}""")
                else -> MockResponse()
                    .setResponseCode(401)
                    .setHeader("WWW-Authenticate", "Bearer realm=\"${server.url("/token")}\",service=\"s\",scope=\"registry:catalog:*\"")
            }
        }
        val result = newClient().checkCredential("localhost:${server.port}", Credential("user", "pass"))
        assertEquals(AuthCheckResult.VALID, result)
    }

    @Test
    fun `checkCredential invalid when token flow fails`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", "Bearer realm=\"${server.url("/token")}\",service=\"s\"")
        )
        server.enqueue(MockResponse().setResponseCode(401)) // token 端点拒绝
        val result = newClient().checkCredential("localhost:${server.port}", Credential("user", "wrong"))
        assertEquals(AuthCheckResult.INVALID, result)
    }

    @Test
    fun `checkCredential network error on unreachable host`() = runTest {
        val client = OciClient(
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build(),
            authProvider,
            allowInsecureHttp = true,
        )
        // 无监听端口 → 连接失败
        val result = client.checkCredential("localhost:1", Credential("u", "p"))
        assertEquals(AuthCheckResult.NETWORK_ERROR, result)
    }

    // ── 协议选择(修复:debug 全开 http 导致真实 registry 降级) ──

    @Test
    fun `domain registry never uses cleartext even with allowInsecureHttp`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200)) // 不应被消费
        val client = newClient() // allowInsecureHttp = true
        val domainRef = ReferenceParser.parse("registry.example.com/team/repo:v1")
        val ex = try {
            client.isEncrypted(domainRef)
            null
        } catch (e: OciException) {
            e
        }
        // 域名走 https,不会用 http 到达本地 mock;无论网络错误与否,本地 server 不应收到请求
        assertEquals(0, server.requestCount)
        assertTrue(ex is OciException.Network)
    }

    @Test
    fun `ip host uses cleartext when allowInsecureHttp`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = true)))
        // localhost:port 解析为显式 IP?localhost 非 IP,但 schemeFor 把 localhost 归为本地地址 → http ✓
        assertTrue(newClient().isEncrypted(ref))
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v2/team/repo/manifests/v1", req.path)
    }
}
