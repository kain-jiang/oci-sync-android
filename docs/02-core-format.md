# 02 · 核心数据格式规范(字节级,与 Go CLI 兼容)

> 版本:0.1.0 | 更新时间:2026-08-13
> 本规范是**硬性约束**。Android 端产物必须与 Go CLI 端产物完全互操作。

## 1. 打包格式(tar.gz)

### 1.1 压缩

- 压缩算法:gzip(标准 DEFLATE)
- Go 实现:`compress/gzip` 默认参数 → Android 对应:`GzipCompressorOutputStream`(commons-compress)或 `java.util.zip.GZIPOutputStream`
- **注意**:gzip header 默认含 mtime。Go `gzip.NewWriter` 写入零值 mtime;Android `GZIPOutputStream` 会写当前时间。**互操作不受影响**(gzip 解压不校验 mtime),但建议显式设零以保证字节级稳定(可选)。

### 1.2 tar 结构

- 打包入口:`Pack(srcPath)`
  - srcPath 为**文件**时:tar 内单条目,`hdr.Name = filepath.Base(srcPath)`(仅文件名,无目录前缀)
  - srcPath 为**目录**时:以 `filepath.Base(srcPath)` 为根目录打包整个树;目录条目 Name 以 `/` 结尾;子文件 Name 为 `baseDir/相对路径`
- Header 字段:`tar.FileInfoHeader(info, "")` 生成(含 mode/uid/gid/size/mtime),然后覆盖 `Name`
- 符号链接:Go 端 `packDir` 用 `filepath.Walk`,`info.IsDir()` 为 false 的 symlink 会按普通文件打开读取(**跟随链接**)。Android 端在 app 缓存目录内操作,无 symlink 场景,行为差异可接受。

### 1.3 Android 端等价实现(commons-compress)

```kotlin
// Pack(流式)
fun pack(srcPath: Path, out: OutputStream) {
    GzipCompressorOutputStream(out).use { gz ->
        TarArchiveOutputStream(gz).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            if (Files.isDirectory(srcPath)) {
                packDir(tar, srcPath, srcPath.fileName.toString())
            } else {
                val entry = TarArchiveEntry(srcPath.fileName.toString(), /* linkFlag */ 0)
                entry.size = Files.size(srcPath)
                tar.putArchiveEntry(entry)
                Files.newInputStream(srcPath).use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }
    }
}
```

**目录条目约定**(必须与 Go 一致):
- 目录条目:Name = `baseDir/相对路径 + "/"`,linkFlag = DIRTYPE
- 文件条目:Name = `baseDir/相对路径`,size 必须准确
- 大文件(tar >2GB 或条目 >8GB):设置 `BIGFILE` / `LONGFILE_POSIX` 模式

### 1.4 解包(Unpack)

- 目标:`Unpack(data, destPath)`,destPath 不存在则创建(0755)
- **路径穿越防护(强制)**:解包前对每个条目计算 `targetPath = absDestPath.join(entry.name)`,校验 `targetPath.startsWith(absDestPath + separator)`,否则拒绝并报错 `illegal file path in archive`
- 支持条目类型:DIRTYPE(建目录)、普通文件(建父目录 + 写文件);其他类型(symlink、device 等)跳过(与 Go `default:` 分支一致)
- 文件权限:按 tar 条目 mode 设置(Go 用 `hdr.FileInfo().Mode()`)

## 2. 加密格式

### 2.1 算法参数(必须与 Go 完全一致)

| 参数 | 值 |
|------|-----|
| KDF | scrypt |
| N | 32768(2^15) |
| r | 8 |
| p | 1 |
| 派生密钥长度 | 32 字节(AES-256) |
| 对称加密 | AES-256-GCM(GCM 认证标签 16 字节) |
| salt 长度 | 32 字节(随机) |
| nonce 长度 | 12 字节(随机) |
| AAD | 无(nil) |

### 2.2 存储布局(字节级)

```
[salt(32B)][nonce(12B)][ciphertext + GCM tag(16B)]
```

- 每次加密生成**新的随机 salt 和 nonce**
- 相同明文 + 相同口令,每次加密结果不同(随机性保证)
- 最小密文长度:`32 + 12 + 16 = 60` 字节,短于该值视为无效密文

### 2.3 解密流程

1. 校验 `len(data) >= 60`,否则报 `ciphertext too short`
2. `salt = data[0:32]`、`nonce = data[32:44]`、`ciphertext = data[44:]`
3. `key = scrypt(passphrase, salt, N=32768, r=8, p=1, 32)`
4. `plaintext = AES-GCM-Decrypt(key, nonce, ciphertext)` — 认证失败(口令错误)报 `decrypt failed (wrong passphrase?)`

### 2.4 Android 端实现

```kotlin
object CryptoEngine {
    private const val SALT_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val KEY_SIZE = 32
    private const val SCRYPT_N = 32768
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1

    fun encrypt(data: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val key = SCrypt.generate(passphrase.toByteArray(Charsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ct = cipher.doFinal(data)
        return salt + nonce + ct
    }

    fun decrypt(data: ByteArray, passphrase: String): ByteArray { /* 按 2.3 流程 */ }
}
```

**scrypt 库**:`org.bouncycastle:bcprov-jdk18on`,类 `org.bouncycastle.crypto.generators.SCrypt`。

## 3. OCI Artifact 结构

### 3.1 Manifest(schemaVersion 2)

```json
{
  "schemaVersion": 2,
  "mediaType": "application/vnd.oci.image.manifest.v1+json",
  "config": {
    "mediaType": "application/vnd.oci.image.config.v1+json",
    "digest": "sha256:...",   // 空 JSON "{}" 的摘要
    "size": 2
  },
  "layers": [
    {
      "mediaType": "application/octet-stream",
      "digest": "sha256:...",  // 打包后(加密后)数据的摘要
      "size": 12345
    }
  ],
  "annotations": {
    "io.oci-sync.version": "0.1.0",
    "io.oci-sync.encrypted": "true|false",
    "<用户label k>": "<v>"
  }
}
```

### 3.2 字段约定

| 字段 | 值 | 说明 |
|------|-----|------|
| config 内容 | `{}`(2 字节空 JSON) | 与 Go 端 `emptyConfigBytes()` 一致 |
| layer mediaType | `application/octet-stream` | 不定义自定义类型 |
| layer 内容 | 打包数据(**加密时是密文**)| push 前先加密再作 layer |
| `io.oci-sync.version` | 工具版本字符串 | **list 过滤标记**,无此 annotation 的镜像不是本工具产物 |
| `io.oci-sync.encrypted` | `"true"` / `"false"` | 加密状态 |
| 用户 label | 任意 key=value | 存入 annotations;**key 不得以 `io.oci-sync.` 开头**(保留前缀) |

### 3.3 Push 顺序(与 Go 一致)

1. PUT layer blob(`POST /v2/<name>/blobs/uploads/` → monolithic upload)
2. PUT config blob(同样走 blob upload)
3. PUT manifest(带 tag reference)→ 注册 tag

### 3.4 摘要计算

- 全部使用 **SHA-256**,格式 `sha256:<hex>`
- layer digest = SHA-256(加密后数据)
- config digest = SHA-256("{}")
- manifest digest = SHA-256(manifest JSON 字节,`json.Marshal` 序列化结果)

### 3.5 Label 语义(与 Go 一致)

- set:合并进 annotations(k 已存在则覆盖,`maps.Copy` 语义)
- unset:从 annotations 删除指定 key
- 修改后:重新 marshal manifest → PUT manifest(新 digest)→ **Tag 指向新 digest**(`repo.Tag`,等价 PUT manifest with tag)
- 注意:label 操作会改变 manifest digest,旧 digest 的 blob 成为孤儿(registry GC 处理)

## 4. ArtifactInfo 数据模型

```kotlin
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
```

## 5. 配置格式

### 5.1 shortcuts / auths(与 CLI 配置语义一致)

```yaml
shortcuts:
  x:
    repo: registry.example.com/myteam/files
auths:
  registry.example.com:
    username: myuser
    password: mytoken
```

Android 端存储为 JSON(DataStore),**字段名与 YAML 一致**,便于迁移:

```json
{
  "shortcuts": { "x": { "repo": "registry.example.com/myteam/files" } },
  "auths": { "registry.example.com": { "username": "myuser", "password": "<keystore-encrypted>" } }
}
```

**password 存储**:经 Android Keystore 加密后再落盘(见 04-crypto-security.md),内存中解密为明文供请求使用。

### 5.2 配置校验规则(移植自 Go `GetShortcutRepo`)

- shortcut repo 不得包含 `@`(digest 引用)
- repo 中 `:` 只能在最后一个 `/` 之前出现(不得含 tag)
- 校验失败返回明确错误

## 6. 兼容性验证矩阵

| 场景 | 验证方式 |
|------|----------|
| Go push → Android pull(明文) | 互操作测试(09-testing.md §3) |
| Go push → Android pull(加密) | 同上,含 passphrase |
| Android push → Go pull | 同上 |
| Android push 产物被 Go list 识别 | `oci-sync list` 应显示版本与标签 |
| Go label set 后 Android list 显示新 label | 同上 |
| 错误口令解密 | 两端均报错且不产出数据 |
