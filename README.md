# oci-sync-android

Android 客户端,将本地文件/目录同步到 OCI 兼容镜像仓库。这是 [tiramission/oci-sync](https://github.com/tiramission/oci-sync)(Go CLI)的 Android 移植版,核心数据格式与 CLI 版**字节级兼容**,两端可互操作(push 在 PC、pull 在手机,反之亦然)。

## 功能

- **push**:选择本地文件/目录(SAF 文件选择器)→ tar.gz 打包 → 可选 AES-256-GCM 加密 → 推送为 OCI artifact
- **pull**:从 OCI 仓库拉取 → 自动检测加密状态 → 可选解密 → 解包到本地目录
- **list**:列出仓库/注册表下所有由本工具上传的 artifact,支持标签筛选
- **delete**:删除远程 artifact
- **label**:管理 manifest annotations 上的标签(set/unset)
- **shortcuts**:快捷仓库配置,一键 push/pull/list/delete
- **recent**:本地活动历史记录
- **认证**:为每个 registry 配置独立凭据(存储时经 Android Keystore 加密)

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + 单 Activity + Navigation Compose |
| 网络 | OkHttp(自研轻量 OCI Distribution Spec 客户端) |
| 加密 | javax.crypto AES/GCM + BouncyCastle scrypt |
| 打包 | commons-compress(tar.gz) |
| 本地存储 | DataStore(设置)+ Room(活动历史) |
| 构建 | Gradle Kotlin DSL + AGP 9.x(Gradle 9.7 + JDK 21)|
| minSdk / targetSdk | 26 / 36 |

## 文档导航(按实施顺序阅读)

| 文档 | 内容 |
|------|------|
| [docs/01-architecture.md](docs/01-architecture.md) | 整体架构、分层、模块图、数据流 |
| [docs/02-core-format.md](docs/02-core-format.md) | 核心数据格式规范(与 CLI 兼容的字节级规格) |
| [docs/03-oci-protocol.md](docs/03-oci-protocol.md) | OCI Distribution Spec 交互设计(HTTP 细节) |
| [docs/04-crypto-security.md](docs/04-crypto-security.md) | 加密与安全设计(含 Android Keystore) |
| [docs/05-data-layer.md](docs/05-data-layer.md) | 数据层设计(配置、shortcuts、活动历史) |
| [docs/06-ui-design.md](docs/06-ui-design.md) | UI/UX 设计(Compose 页面与交互) |
| [docs/07-api-contract.md](docs/07-api-contract.md) | 应用层 API 契约(类与方法签名) |
| [docs/08-implementation-plan.md](docs/08-implementation-plan.md) | 实施计划(里程碑、任务清单、验收标准) |
| [docs/09-testing.md](docs/09-testing.md) | 测试策略(单测/仪器/互操作/e2e) |
| [docs/10-adr.md](docs/10-adr.md) | 架构决策记录(ADR) |

## 构建

```bash
# 需要 JDK 21+、Android SDK(platform 36)
./gradlew assembleDebug          # 构建 debug APK
./gradlew test                   # 运行单元测试
./gradlew connectedDebugAndroidTest  # 运行仪器测试(需设备/模拟器)
```

## 仓库

- 上游(Go CLI):https://github.com/tiramission/oci-sync
- 本仓库:https://github.com/kain-jiang/oci-sync-android

## 许可

[MIT](LICENSE)
