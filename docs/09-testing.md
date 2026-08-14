# 09 · 测试策略

> 版本:0.1.0 | 更新时间:2026-08-13

## 1. 测试金字塔

```
        ┌────────────┐
        │  e2e(少量) │  ← 真实 registry 互操作(手动/CI 可选)
        ├────────────┤
        │ 仪器测试    │  ← Robolectric / connectedAndroidTest(Keystore、Room、UI 冒烟)
        ├────────────┤
        │ JVM 单测    │  ← core 全部逻辑(MockWebServer 模拟 registry)
        └────────────┘
```

## 2. 单元测试(core,JVM)

### 2.1 archive

| 用例 | 断言 |
|------|------|
| pack 文件 → unpack 往返 | 内容一致、文件名正确 |
| pack 目录(嵌套)→ 往返 | 目录结构保留、目录条目以 `/` 结尾 |
| 空目录 | 往返为空目录 |
| 大文件(>10MB) | 流式无 OOM、往返一致 |
| 恶意 tar(`../evil`、`/abs/path`) | 抛 `ArchiveException` |
| 符号链接条目 | 跳过(默认分支) |

### 2.2 crypto

| 用例 | 断言 |
|------|------|
| encrypt→decrypt 往返 | 明文一致 |
| 相同明文两次加密 | 密文不同(随机 salt/nonce) |
| 密文长度 | = 明文 + 60(32+12+16) |
| 错误口令解密 | 抛 `CryptoException` |
| 密文被篡改 1 字节 | 抛 `CryptoException`(GCM 认证) |
| 密文 <60B | 抛 `CryptoException("ciphertext too short")` |
| **Go 样本解密** | 用 Go CLI 加密的固定样本能解密(固定向量) |

### 2.3 oci(MockWebServer)

| 用例 | 场景 |
|------|------|
| push 完整流程 | POST upload → PUT blob → PUT manifest,断言请求路径/头/体 |
| 匿名 → 401 → token 流程 | 首次 401 + WWW-Authenticate → 带 Basic 取 token → 重试成功;token 缓存(第二次不再取) |
| 配置凭据直接 Basic | auths 提供凭据时,请求带 Basic(不经 token) |
| pull | GET manifest → GET blob,断言 annotation 解析 |
| isEncrypted | manifest 中 encrypted=true/false |
| list 分页 | 两页 tags,Link header 翻页,过滤无 version annotation 的 tag |
| list catalog 404 | 抛 `Unsupported`(友好降级) |
| delete | 先 resolve 再按 digest DELETE |
| updateAnnotations | 修改后 PUT 新 digest + PUT tag |
| 429 重试 | 指数退避最多 3 次后成功 |
| 5xx 重试 | 同上 |
| 断网 | 抛 `OciException.Network` |

### 2.4 config / cache

| 用例 | 断言 |
|------|------|
| load 无配置 | 空 AppConfig |
| save→load 往返 | 一致;password 为密文(SecretCodec fake 断言被调用) |
| shortcut 校验 | 含 `@` / 含 tag 后缀 → Result.failure |
| addShortcut 覆盖 | 同名覆盖 |
| ActivityStore(内存) | 倒序、limit、clear、100 条上限、stats |

### 2.5 SyncService(编排)

| 用例 | 断言 |
|------|------|
| push 成功 | MockWebServer 收到完整请求序列;活动记录 success=true |
| push 失败(网络) | 活动记录 success=false + error |
| pull 加密无口令 | 快速失败(未发 GET blob 请求,只发 manifest 请求) |
| pull 成功解密 | 输出文件内容正确 |
| label 操作记录活动 | type=LABEL |

## 3. 互操作测试(关键,验证格式兼容)

### 3.1 前提

- 一台有 Go 1.25+ 的机器(或 CI runner),`go install github.com/tiramission/oci-sync@latest`
- 一个测试 registry:本地 `docker run -d -p 5000:5000 registry:2`,或远程测试仓库(如 GHCR 私有测试 repo)
- 测试数据:`docs/interop/fixtures/`(固定内容目录 + 固定口令 `test-passphrase`)

### 3.2 用例矩阵

| # | 场景 | 步骤 | 期望 |
|---|------|------|------|
| 1 | Go push → Android pull(明文) | Go: `oci-sync push -l fixtures/data -r localhost:5000/interop/plain:latest`;Android 测试: pull 到临时目录 | 文件内容逐字节一致 |
| 2 | Go push → Android pull(加密) | Go 加 `--passphrase test-passphrase`;Android 端解密 | 内容一致 |
| 3 | Android push → Go pull | Android 测试 push → Go: `oci-sync pull -r localhost:5000/interop/from-android:latest -l out` | 内容一致 |
| 4 | Android push → Go list | Android push 后 `oci-sync list -r localhost:5000/interop` | 显示该 tag、version、labels |
| 5 | Go label set → Android list | Go `label set` 后 Android list | 显示新 label |
| 6 | 错误口令交叉验证 | Go 加密 → Android 错误口令 | 两端均报错 |

### 3.3 自动化形态

- 用 `gradle test` 的 JUnit tag 或独立脚本 `scripts/interop.sh` 实现
- CI(GitHub Actions):service 起 `registry:2`,runner 装 Go,跑完整矩阵
- 无 CI 环境时:人工按矩阵执行,结果记录到 `docs/interop/run-<date>.md`

## 4. 仪器测试(Robolectric / connected)

| 用例 | 层级 |
|------|------|
| KeystoreCrypto 加密→解密往返 | ~~Robolectric~~ → **真机/模拟器** |
| Room ActivityStore 增删查 + 100 上限 | Robolectric(in-memory Room) |
| DataStore 读写 | Robolectric |
| MainActivity 启动 + 三 Tab 切换 | connected(Compose UI test) |
| PushScreen 表单校验(空 ref 禁用按钮) | connected |
| ListScreen 空态/错误态显示 | connected |

> ⚠️ 2026-08-14 更新:Robolectric 无法模拟 AndroidKeyStore 的 binder 服务
> (`Could not connect to Keystore service`),`KeystoreCryptoTest` 已 `@Ignore`
> 并保留代码;该用例必须在真机/模拟器执行(`connectedDebugAndroidTest`),
> 加解密逻辑本身与 core CryptoEngine 同构,由 M1 单测 + 04 §6 真机验收覆盖。

## 5. 覆盖率目标

- core 模块:语句覆盖 ≥ 80%(archive/crypto/oci 是关键,目标 90%+)
- app 模块:核心 ViewModel 状态流转覆盖,UI 冒烟
- 用 `./gradlew :core:test jacocoTestReport` 生成报告

## 6. 性能与稳定性

| 项 | 测试 |
|----|------|
| 大文件(100MB)push | 内存峰值 <100MB(流式验证) |
| 大文件 pull+解包 | 同上 |
| 弱网(模拟限速) | 进度回调平滑、可取消 |
| 取消操作 | push/pull 取消后无残留临时文件(cacheDir 清理) |
