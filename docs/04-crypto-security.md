# 04 · 加密与安全设计

> 版本:0.1.0 | 更新时间:2026-08-13

## 1. 威胁模型

| 威胁 | 场景 |
|------|------|
| 密文泄露 | artifact 在公共/半公共 registry 上被下载 |
| 口令暴力破解 | 攻击者离线拿到密文,尝试弱口令 |
| 篡改 | 传输/存储过程中密文被修改 |
| 凭据泄露 | 设备丢失/被 root,registry token 或口令明文落盘 |
| 路径穿越 | 恶意 artifact 解包时写出目标目录(已由 02-core-format.md §1.4 防护) |

## 2. 数据加密(与 Go CLI 完全兼容)

算法与字节格式见 **02-core-format.md §2**,要点:

- scrypt(N=32768, r=8, p=1)派生 32B 密钥:内存成本 ~32MB,显著提高暴力破解成本(OWASP 推荐最低参数)
- AES-256-GCM:认证加密,同时提供保密性与完整性;认证失败立即报错(防篡改、口令错误检测)
- 每次加密随机 salt + nonce:相同明文密文不同
- **口令本身永不存储**,仅在使用时从 UI/Keystore 获取

## 3. 凭据保护(Android Keystore)

### 3.1 目标

配置文件中的 `auths.<registry>.password`(token)与用户输入的 passphrase 不得明文落盘。

### 3.2 方案:Android Keystore 包装密钥(Key Wrapping)

```
┌────────────────────────────────────────────┐
│ Android Keystore(硬件/系统级,不可导出)      │
│  RSA-OAEP 密钥对 或 AES-GCM 密钥(alias)     │
└──────────────────┬─────────────────────────┘
                   │ 加密/解密
┌──────────────────▼─────────────────────────┐
│ 应用私有存储(DataStore / SharedPreferences) │
│  ciphertext = Encrypt(keystoreKey, secret)  │
└────────────────────────────────────────────┘
```

**推荐:AES-GCM Keystore 密钥**(API 23+ 支持,minSdk 26 满足):

```kotlin
object KeystoreCrypto {
    private const val ALIAS = "oci_sync_master_key"

    fun encrypt(plaintext: String): String {
        // 1. 获取或创建 AES/GCM 密钥(Keystore,setUserAuthenticationRequired(false))
        // 2. 随机 12B IV
        // 3. ciphertext = AES/GCM/NoPadding(key, IV, plaintext)
        // 4. 返回 base64(iv || ciphertext)
    }

    fun decrypt(ciphertextB64: String): String { /* 逆操作 */ }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(ALIAS, PURPOSE_ENCRYPT or PURPOSE_DECRYPT)
                .setBlockModes(BLOCK_MODE_GCM)
                .setEncryptionPaddings(ENCRYPTION_PADDING_NONE)
                .build())
            gen.generateKey()
        }
    }
}
```

- `setUserAuthenticationRequired(false)`:不绑定生物识别,避免每次操作弹锁屏(可后续迭代加生物识别门禁,ADR 记录)
- Keystore 密钥不可导出:即使设备被 root,攻击者拿不到主密钥,只能拿到密文
- 所有写入 `auths` 的 password 均经此加密;读取时解密到内存

### 3.3 passphrase 处理

- push/pull 时用户输入的 passphrase:**仅内存中使用**,不持久化(除非用户在设置中明确选择"保存口令"——v1 不支持,ADR 记录)
- UI 输入框使用 `PasswordVisualTransformation` + 键盘 `KeyboardType.Password`
- 内存中尽快置零(ByteArray.fill(0)),字符串无法置零,仅引用丢弃

## 4. 传输安全

- 生产环境强制 HTTPS;`OciClient` 构造参数 `allowInsecureHttp: Boolean = false`,仅测试/自建内网 registry 时开启,UI 必须显示"不安全连接"警告
- OkHttp 默认校验证书;不全局信任自签名证书(用户可通过系统证书安装)
- TLS 1.2+(Android 默认)

## 5. 应用内安全实践

| 项 | 要求 |
|----|------|
| 日志 | 禁止打印 passphrase、token、完整凭据;错误信息脱敏(registry host 可打印,password 不打印) |
| WebView | 本项目无 WebView |
| 外部存储 | 解包目标由用户通过 SAF 选择;应用自身缓存目录 `cacheDir` 为应用私有 |
| 临时文件 | push 前文件拷贝到 `cacheDir/push/<uuid>/`,用完即删 |
| minSdk | 26(Keystore AES-GCM 支持) |
| ProGuard/R8 | 混淆开启;core 模块模型类加 `@Serializable` 保持字段名(见 build.gradle 配置) |
| 导出组件 | 仅 `MainActivity` 可导出(launcher),其余组件不导出 |
| 备份 | `android:allowBackup="false"`(防止凭据密文经 ADB 备份泄露) |

## 6. 安全验收清单(实施完成后逐项核对)

- [ ] `auths` 中 password 落盘为 Keystore 加密密文,文件内无明文
- [ ] 日志中无 passphrase/token/密码
- [ ] 解包路径穿越防护有单元测试覆盖(恶意 tar 条目 `../evil` 被拒绝)
- [ ] 错误口令解密返回明确错误且不产生部分文件
- [ ] pull 加密 artifact 未提供口令时,在下载前快速失败
- [ ] 断网/超时/401 错误信息不泄露内部细节(如 token 内容)
- [ ] 构建开启 R8 混淆,`core/model` 序列化字段名保持不变
- [ ] allowBackup=false 已配置
- [ ] 仅 MainActivity 可导出
