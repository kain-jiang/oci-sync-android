# oci-sync-android AI 编码规范

AI 工具(Cursor、Copilot、OpenCode、Claude Code 等)协助开发此项目时,必须严格遵守以下规范。

## 🚫 Git 工作流(关键)

**⚠️ 禁止自动提交和推送(绝对规则)**

AI 绝对不能自动执行以下操作,除非用户明确、清晰地用中文或英文说"提交"、"push"、"commit"等明确的指令:

- ❌ `git commit`
- ❌ `git push`
- ❌ `git commit --amend`
- ❌ `git push --force`
- ❌ 任何其他强制操作

**工作流程:**
1. AI 进行代码修改后,必须等待用户明确指示才能提交
2. 提交前必须验证所有检查通过(见下文)
3. 如果不确定,必须先询问用户
4. 永远不要假设用户想要提交

**提交前验证(必须全部通过):**
```bash
./gradlew test                        # 单元测试
./gradlew assembleDebug               # 构建
./gradlew lint                        # Android Lint
```

## 📋 开发命令

```bash
./gradlew assembleDebug               # 构建 debug APK
./gradlew test                        # 运行单元测试(本地 JVM)
./gradlew connectedDebugAndroidTest   # 运行仪器测试(需设备/模拟器)
./gradlew lint                        # Android Lint 静态检查
./gradlew :core:test                  # 只测 core 模块
```

**临时文件和测试数据:**
- 单元测试产物自动进入 `build/` 目录(已 gitignore)
- 禁止在项目根目录创建临时文件

## 🔧 代码规范

**语言/日志:**
- 代码注释、类名、方法名、变量名:英文
- 用户可见 UI 文案:i18n 资源(res/values/strings.xml),默认中文
- 日志使用 `android.util.Log`(Tag 为类名),或 `timber`(若引入)

**安全(强制):**
- 所有文件解包必须做路径穿越防护(参考 `core/src/main/java/.../archive/ArchiveUnpacker.kt` 的 `filepath.Abs` 等价检查)
- 凭据(passphrase、registry token)禁止明文持久化,必须经 Android Keystore 加密(见 docs/04-crypto-security.md)
- 网络请求必须使用 OkHttp,禁止裸 `HttpURLConnection`(除非有充分理由)
- manifest 中的 `io.oci-sync.*` annotation 为系统保留命名空间,用户 label 不得使用此前缀

**格式兼容(核心约束):**
- 打包/加密/OCI artifact 的字节级格式必须与 Go CLI 版完全一致(见 docs/02-core-format.md)
- 任何格式变更必须更新 `docs/02-core-format.md` 并说明兼容性影响

**依赖(锁定):**
- UI:Jetpack Compose(BOM)、Material 3、Navigation Compose
- 网络:OkHttp(唯一 HTTP 客户端)
- 加密:BouncyCastle `bcprov-jdk18on`(scrypt)+ javax.crypto(AES-GCM)
- 打包:commons-compress(tar.gz)
- 存储:DataStore Preferences + Room
- 序列化:kotlinx-serialization(JSON)
- 禁止添加新依赖,除非有充分理由并在 docs/10-adr.md 记录

## 📚 文档更新

**架构或 API 变更**必须更新:
- `docs/01-architecture.md`(架构、模块)
- `docs/07-api-contract.md`(接口签名)

**格式或协议变更**必须更新:
- `docs/02-core-format.md`(字节级格式)
- `docs/03-oci-protocol.md`(HTTP 协议细节)

**功能添加或边界情况**应更新 `docs/08-implementation-plan.md` 与 README.md。

## 📁 项目结构

```
app/                  # Android 应用模块(UI、导航、ViewModel)
core/                 # 核心逻辑模块(纯 Kotlin/JVM,可独立单测)
  archive/            # tar.gz 打包/解包
  crypto/             # scrypt + AES-256-GCM 加密/解密
  oci/                # OCI Distribution Spec 客户端(OkHttp)
  model/              # 领域模型(ArtifactInfo、Activity 等)
  config/             # 配置加载(shortcuts、auths)
  cache/              # 活动历史(Room)
docs/                 # 全部设计文档(见 README 导航)
```

## 🎯 关键实现注意事项

- **无网络单测依赖**:OCI 客户端单元测试使用 OkHttp MockWebServer 模拟 registry,禁止依赖真实网络
- **格式验证**:新增互操作测试(Go CLI 产物 ↔ Android 端)前,必须先跑通 docs/09-testing.md 中的兼容性用例
- **加密状态检查**:pull 前必须先读 manifest 检查 `io.oci-sync.encrypted`,缺少 passphrase 时快速失败,不下载完整内容
- **大文件**:push/pull 必须流式处理(避免 OOM),禁止一次性读入超大文件到内存(上限由实现计划定义)
