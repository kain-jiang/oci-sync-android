package com.tiramission.ocisync.core.model

import com.tiramission.ocisync.core.cache.ActivityStore
import com.tiramission.ocisync.core.cache.ActivityType
import com.tiramission.ocisync.core.cache.InMemoryActivityStore
import com.tiramission.ocisync.core.config.ConfigLoader
import com.tiramission.ocisync.core.config.KeyValueStore
import com.tiramission.ocisync.core.config.SecretCodec
import com.tiramission.ocisync.core.oci.Credential
import com.tiramission.ocisync.core.oci.OciClient
import com.tiramission.ocisync.core.oci.RegistryAuthProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class SyncServiceTest {

    @get:Rule
    val server = MockWebServer()

    @get:Rule
    val tmp = TemporaryFolder()

    private val activities: ActivityStore = InMemoryActivityStore()

    private val authProvider = object : RegistryAuthProvider {
        override suspend fun credential(registryHost: String): Credential? = null
        override suspend fun saveCredential(registryHost: String, credential: Credential) {}
    }

    private fun newService(): SyncService {
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val oci = OciClient(http, authProvider, allowInsecureHttp = true)
        val store = object : KeyValueStore {
            private val data = mutableMapOf<String, String>()
            override fun get(key: String): String? = data[key]
            override fun put(key: String, value: String) {
                data[key] = value
            }
        }
        val codec = object : SecretCodec {
            override fun encrypt(plaintext: String): String =
                java.util.Base64.getEncoder().encodeToString(plaintext.toByteArray())
            override fun decrypt(ciphertext: String): String =
                String(java.util.Base64.getDecoder().decode(ciphertext))
        }
        return SyncService(oci, activities, ConfigLoader(store, codec))
    }

    private fun ref(): String = "localhost:${server.port}/team/repo:v1"

    private fun manifestBody(encrypted: Boolean = false): String = """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.manifest.v1+json",
          "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "digest": "sha256:cfg", "size": 2},
          "layers": [{"mediaType": "application/octet-stream", "digest": "sha256:layer1", "size": 0}],
          "annotations": {"io.oci-sync.version": "0.1.0", "io.oci-sync.encrypted": "$encrypted"}
        }
    """.trimIndent()

    private fun manifestBodyWith(extraAnnotation: String): String = manifestBody().replace(
        "\"io.oci-sync.encrypted\": \"false\"",
        "\"io.oci-sync.encrypted\": \"false\", $extraAnnotation"
    )

    // ── push 端到端 ───────────────────────────────────────

    @Test
    fun `push uploads sequence and records success activity`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/v2/team/repo/blobs/uploads/u1"))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))

        val src = tmp.newFolder("data")
        src.resolve("hello.txt").writeText("hello from sync")

        val stages = mutableListOf<Stage>()
        val result = newService().push(
            PushRequest(src, ref(), passphrase = null, labels = mapOf("env" to "test")),
            onStage = { stages += it },
        )
        assertTrue(result.isSuccess)

        // 请求序列:POST uploads → PUT blob → PUT manifest
        val r1 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", r1.method)
        assertEquals("/v2/team/repo/blobs/uploads/", r1.path)
        val r2 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", r2.method)
        assertTrue(r2.path!!.contains("digest=sha256"))
        val r3 = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", r3.method)
        assertEquals("/v2/team/repo/manifests/v1", r3.path)

        // 阶段顺序
        assertEquals(listOf(Stage.PACKING, Stage.UPLOADING, Stage.DONE), stages)

        // 活动记录
        val recent = activities.recent(1)
        assertEquals(1, recent.size)
        assertEquals(ActivityType.PUSH, recent[0].type)
        assertTrue(recent[0].success)
        assertEquals(ref(), recent[0].remoteRef)
    }

    @Test
    fun `push with passphrase encrypts before upload`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/v2/team/repo/blobs/uploads/u1"))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))

        val src = tmp.newFile("secret.txt").apply { writeText("top secret") }
        val result = newService().push(PushRequest(src, ref(), passphrase = "hunter2"))
        assertTrue(result.isSuccess)

        val stages = mutableListOf<Stage>()
        // 重新 push 收集阶段
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", "/v2/team/repo/blobs/uploads/u2"))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        newService().push(PushRequest(src, ref(), passphrase = "hunter2"), onStage = { stages += it })
        assertTrue(stages.contains(Stage.ENCRYPTING))
        assertTrue(stages.indexOf(Stage.ENCRYPTING) < stages.indexOf(Stage.UPLOADING))
    }

    // ── pull 端到端 ───────────────────────────────────────

    @Test
    fun `pull unpacks plaintext artifact and records activity`() = runTest {
        // 构造 tar.gz 产物(Go 打包样本直接复用)
        val packed = javaClass.getResourceAsStream("/interop/go-pack.tgz")!!.readBytes()

        // isEncrypted 预检 + pull 各自需要 manifest 响应,共 3 个
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = false)))
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = false)))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(packed)))

        val dest = tmp.newFolder("out")
        val result = newService().pull(PullRequest(ref(), dest, passphrase = null))
        assertTrue(result.isSuccess)

        assertEquals("hello from go\n", dest.resolve("sample/hello.txt").readText())
        assertEquals("nested content 42\n", dest.resolve("sample/sub/nested.txt").readText())

        val recent = activities.recent(1)
        assertEquals(ActivityType.PULL, recent[0].type)
        assertTrue(recent[0].success)
    }

    @Test
    fun `pull encrypted artifact without passphrase fails fast`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody(encrypted = true)))
        // 注意:预检失败不应请求 blob

        val dest = tmp.newFolder("out")
        val result = newService().pull(PullRequest(ref(), dest, passphrase = null))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("passphrase"))

        // 只发生了 manifest 请求,无 blob 请求
        assertEquals(1, server.requestCount)
        val recent = activities.recent(1)
        assertEquals(ActivityType.PULL, recent[0].type)
        assertFalse(recent[0].success)
        assertTrue(recent[0].error!!.contains("passphrase"))
    }

    // ── list / delete / labels ────────────────────────────

    @Test
    fun `list filters by label`() = runTest {
        // 3 次 list 调用,每次消耗 1 组响应(tags + 2 manifest)
        repeat(3) {
            server.enqueue(MockResponse().setBody("""{"name":"team/repo","tags":["v1","v2"]}"""))
            server.enqueue(MockResponse().setBody(manifestBodyWith("\"env\": \"prod\"")))
            server.enqueue(MockResponse().setBody(manifestBody()))
        }

        val all = newService().list(ref(), emptyList()).getOrThrow()
        assertEquals(2, all.size)

        val prod = newService().list(ref(), listOf("env=prod")).getOrThrow()
        assertEquals(1, prod.size)
        assertEquals("v1", prod[0].tag)

        val none = newService().list(ref(), listOf("missing")).getOrThrow()
        assertEquals(0, none.size)
    }

    @Test
    fun `delete records activity`() = runTest {
        server.enqueue(MockResponse().setBody(manifestBody()).setHeader("Docker-Content-Digest", "sha256:m-digest"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setBody(manifestBody()))
        server.enqueue(MockResponse().setResponseCode(202))

        val result = newService().delete(ref())
        assertTrue(result.isSuccess)
        val recent = activities.recent(1)
        assertEquals(ActivityType.DELETE, recent[0].type)
        assertTrue(recent[0].success)
    }

    @Test
    fun `invalid remote ref fails without network`() = runTest {
        val result = newService().push(PushRequest(tmp.newFile("f"), "", passphrase = null))
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
        val recent = activities.recent(1)
        assertTrue(recent.isEmpty()) // 解析失败不记录活动
    }
}
