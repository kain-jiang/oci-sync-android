# 03 · OCI Distribution Spec 交互设计

> 版本:0.1.0 | 更新时间:2026-08-13
> 参考:OCI Distribution Spec v1.1(https://github.com/opencontainers/distribution-spec)

## 1. 设计目标

自研轻量 OCI 客户端(`core/oci/OciClient`),基于 OkHttp 实现 Go CLI 版 `oras-go/v2` 所覆盖的全部操作:

| 操作 | OCI API |
|------|---------|
| push(layer/config) | blob upload(monolithic) |
| push(manifest) | PUT manifest |
| pull | GET manifest + GET blob |
| list(tags) | GET tags list |
| list(repos) | GET catalog(registry 支持时) |
| delete | DELETE manifest(by digest)+ DELETE blob |
| label set/unset | GET manifest → PUT manifest(新 digest)→ PUT manifest(带 tag) |

## 2. 参考解析(Reference)

```
<registry>[:port]/<repository>[:tag|@digest]
```

`ReferenceParser` 负责解析,规则(与 oras-go / docker 语义一致):

- registry:第一个 `/` 之前的部分;若包含 `.`、`:` 或等于 `localhost`,则为完整 registry 主机(如 `docker.io`、`registry-1.docker.io:5000`);否则(单段且无 `.`/`:`),**隐式补全 `docker.io` 并加上 `library/` 前缀**(即 `alpine` → `docker.io/library/alpine`)
- repository:最后一个 `/` 之后的路径部分,可含嵌套命名空间
- reference:最后一个 `:` 之后且位于最后一个 `/` 之后的部分为 tag;含 `@` 则为 digest 引用
- 无 tag 时默认 `latest`

```kotlin
data class Reference(
    val registry: String,      // 不含 scheme/port 分离后
    val port: Int?,            // 显式端口,默认按 scheme 推断(https=443)
    val repository: String,    // "myteam/files"
    val tag: String?,          // null 表示无 tag
    val digest: String?,       // "sha256:..." 或 null
) {
    val registryHost: String   // "registry.example.com:5000"
    val fullName: String       // "<registryHost>/<repository>:<tag>"
    val baseUri: String        // "https://<registryHost>/v2"
}
```

**默认 registry 规则**(与 Docker/oras 一致):显式包含 `.`、`:` 或为 `localhost` 的视为显式主机;否则隐式 `docker.io` + `library/`。

## 3. 认证流程

### 3.1 认证优先级(与 Go CLI 一致)

1. **配置文件 auths**(`auths.<registry>.username/password`)
2. **无配置凭据** → 匿名访问(registry 要求认证时按 3.2 走 token 流程;也可复用 Android 上用户手动输入的凭据缓存)

> 说明:Go 版第二步是 Docker credential store。Android 无 docker CLI,等价方案为"用户在 App 设置中保存的凭据"(经 Keystore 加密)。若 App 内已保存该 registry 的凭据,则使用之;否则匿名请求触发 401 时,引导用户输入凭据。

### 3.2 Bearer Token 流程(registry 匿名/凭据挑战)

```
1. 匿名或带 Basic 凭据请求目标 API
2. 收到 401 + WWW-Authenticate: Bearer realm="...",service="...",scope="..."
3. 解析 realm/service/scope
4. 若有凭据:GET realm?service=...&scope=... 带 Basic 认证
   若匿名:GET realm?service=...&scope=... 不带认证
5. 响应 {"token": "..."} 或 {"access_token": "..."}
6. 缓存 token(按 registry+scope 缓存,过期后重新获取)
7. 后续请求带 Authorization: Bearer <token>
```

**实现要点**:
- OkHttp `Authenticator` 或拦截器实现;推荐拦截器 + 手动处理 401(避免 OkHttp 自动重试竞态)
- token 缓存:`ConcurrentHashMap<CacheKey, TokenEntry>`;token 有 `expires_in` 字段时提前 60s 过期
- 若 realm 返回的 WWW-Authenticate 是 `Basic`,则直接用凭据重试
- Docker Hub 特例:`docker.io` 的 registry 主机是 `registry-1.docker.io`,`auth.docker.io` 是 token realm;scope 为 `repository:<name>:pull,push`

### 3.3 匿名请求策略

- 每次请求先带已有缓存 token(若有)
- 401 → 走 3.2 流程一次
- 最终失败:返回明确的 `RegistryAuthException`(提示用户检查凭据)

## 4. API 细节

所有请求基础路径:`{baseUri} = https://{registryHost}/v2`。生产环境必须 HTTPS(测试环境可用 `http://localhost` 例外,由 `OciClient` 构造参数允许)。

### 4.1 Push(blob monolithic upload)

```
1. POST /v2/<name>/blobs/uploads/          → 201, Location: <upload-url>
   (header: Content-Length: 0)
2. PUT <upload-url>?digest=sha256:<hex>    → 201, 请求体为 blob 字节
   (header: Content-Type: application/octet-stream)
```

- 大 blob 可选 chunked 上传(本设计 v1 仅 monolithic,单文件上限 512MB,超过提示用户)
- 上传 URL 是 registry 返回的绝对/相对 URL,原样使用

### 4.2 Push(manifest)

```
PUT /v2/<name>/manifests/<tag|digest>
Header: Content-Type: application/vnd.oci.image.manifest.v1+json
Body: manifest JSON(见 02-core-format.md §3)
→ 201, Location: /v2/<name>/manifests/sha256:...
```

### 4.3 Pull

```
GET /v2/<name>/manifests/<tag|digest>
Accept: application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json
→ 200, body = manifest JSON(解析 annotations / layers[0] descriptor)

GET /v2/<name>/blobs/<digest>             → 200, body = layer 字节(流式)
```

### 4.4 List tags

```
GET /v2/<name>/tags/list?n=<pageSize>&last=<lastTag>
→ 200, {"name": "...", "tags": ["v1", "v2", ...]}
```

- 分页:response 含 `Link: </v2/<name>/tags/list?last=...&n=...>; rel="next"` 时继续翻页
- v1:支持分页,但先实现"拉全量(不传 n,或 n=1000 循环直到无 Link)"

### 4.5 List repositories(catalog)

```
GET /v2/_catalog?n=<pageSize>&last=<lastRepo>
→ 200, {"repositories": ["a/b", "c", ...]}
```

- 注意:多数 registry 默认**关闭 catalog**(Docker Hub 不支持)。失败时返回友好提示"该 registry 不支持仓库枚举",UI 降级为仅支持指定 repo 查询

### 4.6 Delete

```
1. GET /v2/<name>/manifests/<tag>     → resolve 出 manifest digest(从 Docker-Content-Digest header 或重新计算)
2. DELETE /v2/<name>/manifests/<sha256:...>   → 202
3. DELETE /v2/<name>/blobs/<sha256:...>       → 202 (blob 清理,registry 允许时)
```

- **必须用 digest 删除 manifest**(与 Go `repo.Delete(ctx, desc)` 一致),不能用 tag
- 部分 registry 禁止 delete(如 Docker Hub):返回明确错误

### 4.7 Label set/unset

```
1. GET /v2/<name>/manifests/<tag>         → 解析出 manifest
2. 修改 annotations(map 合并/删除)
3. PUT /v2/<name>/manifests/<sha256:新digest>   → 推送新 manifest(不可变寻址)
4. PUT /v2/<name>/manifests/<tag>                → 更新 tag 指向新 digest
```

## 5. OkHttp 配置

```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)      // 大 blob 下载
    .writeTimeout(60, TimeUnit.SECONDS)
    .callTimeout(0)                          // 不设总超时,大文件可能很久
    .retryOnConnectionFailure(true)
    .addInterceptor(AuthInterceptor(authProvider, tokenCache))
    .build()
```

- 每个 registry 可单独配置(host 不同用不同 client,便于测试)
- 进度:大 blob 读写通过 OkHttp 拦截器或自定义 `RequestBody` / `ResponseBody` 回调 `onProgress(bytes, total)`(见 06-ui-design.md 进度条)

## 6. 错误模型

```kotlin
sealed class OciException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthRequired(registry: String) : OciException("Authentication required for $registry")
    class AuthFailed(registry: String, reason: String) : OciException("Auth failed for $registry: $reason")
    class NotFound(ref: String) : OciException("Not found: $ref")
    class Unsupported(registry: String, op: String) : OciException("$registry does not support $op")
    class TooLarge(size: Long, limit: Long) : OciException("Artifact too large...")
    class Network(override val cause: IOException) : OciException("Network error", cause)
    class Protocol(message: String) : OciException(message)
}
```

HTTP 状态码映射:

| 状态码 | 含义 | 异常 |
|--------|------|------|
| 401 | 未认证 | AuthRequired(触发 token 流程后仍失败则 AuthFailed) |
| 403 | 无权限 | AuthFailed |
| 404 | 不存在 | NotFound |
| 405 | 方法不允许(delete 被禁)| Unsupported |
| 429 | 限流 | 重试(指数退避,最多 3 次) |
| 5xx | 服务端错误 | 重试(最多 3 次) |

## 7. 测试策略(对应 09-testing.md)

- 单元测试:OkHttp **MockWebServer** 模拟全部端点(401→token 流程、blob 上传、manifest PUT 等)
- 互操作测试:本地起 `registry:2`(docker)或使用测试 registry,与 Go CLI 双向验证
