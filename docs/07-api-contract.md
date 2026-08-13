# 07 · 应用层 API 契约

> 版本:0.1.0 | 更新时间:2026-08-13
> 本文档定义 core 模块全部公开类/方法签名。AI 实现时必须按此签名落地,单元测试按此契约编写。

## 1. core 模块包结构

```
com.tiramission.ocisync.core
├── archive/   ArchivePacker, ArchiveUnpacker
├── crypto/    CryptoEngine
├── oci/       OciClient, ReferenceParser, RegistryAuthProvider, OciException
├── config/    ConfigLoader, AppConfig, Shortcut, RegistryAuth
├── cache/     ActivityStore, Activity, ActivityType, ActivityStats
└── model/     ArtifactInfo, PushRequest, PullResult
```

## 2. archive

```kotlin
package com.tiramission.ocisync.core.archive

object ArchivePacker {
    /**
     * 将文件或目录打包为 tar.gz。
     * @param srcPath 文件或目录路径(File 对象)
     * @param out 目标输出流(gzip 包装由本方法内部完成)
     * 格式细节见 docs/02-core-format.md §1。
     */
    fun pack(srcPath: File, out: OutputStream)

    /** 便捷方法:返回 ByteArray(仅限小文件测试场景,生产走流式) */
    fun packToBytes(srcPath: File): ByteArray
}

object ArchiveUnpacker {
    /**
     * 解包 tar.gz 到 destDir(不存在则创建)。
     * @throws ArchiveException 路径穿越等非法条目
     * 安全细节见 docs/02-core-format.md §1.4。
     */
    fun unpack(data: InputStream, destDir: File)
    fun unpack(data: ByteArray, destDir: File)
}

class ArchiveException(message: String) : Exception(message)
```

## 3. crypto

```kotlin
package com.tiramission.ocisync.core.crypto

object CryptoEngine {
    /**
     * 加密:[salt(32)][nonce(12)][ct+tag],scrypt(N=32768,r=8,p=1)。
     * 格式见 docs/02-core-format.md §2。与 Go CLI 完全兼容。
     */
    fun encrypt(data: ByteArray, passphrase: String): ByteArray

    /**
     * 解密。口令错误/数据损坏时抛 CryptoException("decrypt failed (wrong passphrase?)")。
     * @throws CryptoException 密文过短(<60B)或 GCM 认证失败
     */
    fun decrypt(data: ByteArray, passphrase: String): ByteArray
}

class CryptoException(message: String) : Exception(message)
```

## 4. oci

```kotlin
package com.tiramission.ocisync.core.oci

// ── 参考解析 ──────────────────────────────────────────────
data class Reference(
    val registry: String,     // 如 "registry-1.docker.io"
    val port: Int?,           // 显式端口
    val repository: String,   // 如 "myteam/files"
    val tag: String?,         // 如 "latest"
    val digest: String?,      // 如 "sha256:..."
) {
    val registryHost: String get() = if (port != null) "$registry:$port" else registry
    val fullName: String      // "registryHost/repository:tag"
    val baseUri: String       // "https://registryHost/v2"
    val isDigestRef: Boolean
}

object ReferenceParser {
    /**
     * 解析 OCI 引用。
     * 规则(与 Docker/oras 一致):单段名隐式补 docker.io/library;
     * 含 "." ":" 或 "localhost" 视为显式 registry。
     * @throws IllegalArgumentException 非法引用
     */
    fun parse(ref: String): Reference
}

// ── 认证 ─────────────────────────────────────────────────
interface RegistryAuthProvider {
    /** 返回指定 registry 的凭据(可能为 null → 匿名) */
    suspend fun credential(registryHost: String): Credential?
    /** 用户通过 401 引导流程保存的凭据 */
    suspend fun saveCredential(registryHost: String, credential: Credential)
}

data class Credential(val username: String, val password: String)

// ── 主客户端 ─────────────────────────────────────────────
class OciClient(
    private val client: OkHttpClient,        // 依赖注入,测试可替换
    private val authProvider: RegistryAuthProvider,
    private val tokenCache: TokenCache = TokenCache(),
    private val allowInsecureHttp: Boolean = false,
) {
    /** push:流式上传 layer + config + manifest(带 tag)。encrypted 为 manifest annotation。 */
    suspend fun push(ref: Reference, data: InputStream, dataSize: Long, encrypted: Boolean,
                     labels: Map<String, String>,
                     onProgress: ((Long) -> Unit)? = null)

    /** 检查加密状态:仅 GET manifest,不下载 layer。 */
    suspend fun isEncrypted(ref: Reference): Boolean

    /** pull:下载 layer 字节流。manifest annotations 已解析。 */
    suspend fun pull(ref: Reference): PullResult

    /** list tags 并过滤本工具产物(含 io.oci-sync.version annotation)。 */
    suspend fun list(ref: Reference): List<ArtifactInfo>

    /** delete:resolve tag→digest→DELETE manifest(+尝试 blob)。 */
    suspend fun delete(ref: Reference)

    /** label set/unset:改 manifest annotations → 推新 digest → 更新 tag。 */
    suspend fun updateAnnotations(ref: Reference, updates: Map<String, String>, removeKeys: List<String>)
}

class PullResult(val data: InputStream, val encrypted: Boolean, val size: Long)
class TokenCache { /* 内部:key=registryHost+scope,value=token+expiry */ }

// ── 异常 ─────────────────────────────────────────────────
sealed class OciException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthRequired(val registry: String) : OciException("Authentication required for $registry")
    class AuthFailed(val registry: String, reason: String) : OciException("Auth failed for $registry: $reason")
    class NotFound(val ref: String) : OciException("Not found: $ref")
    class Unsupported(val registry: String, op: String) : OciException("$registry does not support $op")
    class TooLarge(val size: Long, val limit: Long) : OciException("Artifact too large: $size > $limit")
    class Network(cause: IOException) : OciException("Network error", cause)
    class Protocol(message: String) : OciException(message)
}
```

## 5. config

```kotlin
package com.tiramission.ocisync.core.config

@Serializable data class RegistryAuth(val username: String = "", val password: String = "")
@Serializable data class Shortcut(val repo: String = "")
@Serializable data class AppConfig(
    val auths: Map<String, RegistryAuth> = emptyMap(),
    val shortcuts: Map<String, Shortcut> = emptyMap(),
)

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

interface SecretCodec {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

class ConfigLoader(
    private val store: KeyValueStore,
    private val secretCodec: SecretCodec,
) {
    fun load(): AppConfig                                   // 无配置时返回空 AppConfig
    fun save(config: AppConfig)                             // password 加密后写盘
    fun getRegistryAuth(host: String): RegistryAuth?        // 返回明文(内存)
    fun getShortcutRepo(name: String): Result<String>       // 校验:无 @、无 tag
    fun getAllShortcuts(): List<Pair<String, Shortcut>>
    fun addShortcut(name: String, repo: String): Result<Unit>   // 校验 + 保存
    fun removeShortcut(name: String)
    fun addAuth(host: String, auth: RegistryAuth)
    fun removeAuth(host: String)
}
```

## 6. cache

```kotlin
package com.tiramission.ocisync.core.cache

enum class ActivityType { PUSH, PULL, DELETE, LABEL }

@Serializable data class Activity(
    val type: ActivityType,
    val timestamp: Long,               // epoch millis
    val remoteRef: String,
    val localPath: String? = null,
    val labels: List<String> = emptyList(),
    val success: Boolean,
    val error: String? = null,
)

@Serializable data class ActivityStats(
    val total: Int,
    val byType: Map<String, Int>,
    val recent: List<Activity>,
)

interface ActivityStore {
    suspend fun add(activity: Activity)
    suspend fun recent(limit: Int = 20): List<Activity>   // 时间倒序
    suspend fun clear()
    suspend fun stats(limit: Int = 20): ActivityStats
}
```

## 7. model(门面,供 app 层调用)

```kotlin
package com.tiramission.ocisync.core.model

@Serializable data class ArtifactInfo(
    val fullName: String, val repo: String, val tag: String,
    val digest: String, val encrypted: Boolean, val version: String,
    val size: Long, val labels: Map<String, String>,
)

/** 编排层:把 archive+crypto+oci+config+cache 串成完整操作(对应 Go cmd/runPush 等)。 */
class SyncService(
    private val ociClient: OciClient,
    private val activityStore: ActivityStore,
    private val configLoader: ConfigLoader,
) {
    /** push 完整流程。onStage: 打包/加密/上传 阶段回调;onProgress: 0..1 */
    suspend fun push(request: PushRequest, onStage: (Stage) -> Unit, onProgress: (Float) -> Unit): Result<Unit>

    /** pull 完整流程(含加密状态预检:加密且无口令 → 快速失败)。 */
    suspend fun pull(request: PullRequest, onStage: (Stage) -> Unit, onProgress: (Float) -> Unit): Result<Unit>

    /** list + 按 label 过滤。 */
    suspend fun list(ref: String, labelFilters: List<String>): Result<List<ArtifactInfo>>

    /** delete(记录活动)。 */
    suspend fun delete(ref: String): Result<Unit>

    /** label set/unset(记录活动)。 */
    suspend fun setLabels(ref: String, updates: Map<String, String>): Result<Unit>
    suspend fun unsetLabels(ref: String, keys: List<String>): Result<Unit>
}

data class PushRequest(
    val localPath: File,            // 已拷贝到 cacheDir 的本地文件/目录
    val remoteRef: String,
    val passphrase: String?,        // null/空 → 不加密
    val labels: Map<String, String> = emptyMap(),
)

data class PullRequest(
    val remoteRef: String,
    val destDir: File,
    val passphrase: String?,        // 加密内容必填
)

enum class Stage { IDLE, PACKING, ENCRYPTING, UPLOADING, DOWNLOADING, DECRYPTING, UNPACKING, DONE }
```

## 8. 契约测试(实现时的红线)

1. `CryptoEngine.encrypt/decrypt` 往返一致;错误口令抛 `CryptoException`
2. `ArchivePacker.pack` + `ArchiveUnpacker.unpack` 往返一致;恶意 `../` 条目被拒
3. `ReferenceParser.parse("alpine")` → registry=`registry-1.docker.io`(docker.io 规范主机),repository=`library/alpine`
4. `ReferenceParser.parse("ghcr.io/u/repo:v1")` → tag=v1
5. `OciClient` 全部操作在 MockWebServer 上可跑通(含 401 token 流程)
6. `SyncService.push/pull` 端到端(真实本地文件 → MockWebServer registry)→ 活动记录写入
