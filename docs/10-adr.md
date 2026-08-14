# 10 · 架构决策记录(ADR)

> 版本:0.2.0 | 更新时间:2026-08-14
> 状态说明:`✅ 已采纳`(实现必须遵守)/ `🔄 备选`(未采纳但可参考)

---

## ADR-001:纯 Kotlin 重写核心,而非 gomobile 复用 Go 代码

**状态:✅ 已采纳**

**背景**:上游 oci-sync 是 Go 实现(archive/crypto/oci 三个核心包)。

**方案对比**:
- A(gomobile bind 复用 Go):行为 100% 一致,但需要 Go 工具链 + gomobile + NDK,构建链复杂;gomobile 生态维护弱;AI 工具落地时工具链问题会消耗大量时间;`oras-go` 依赖通过 gomobile 桥接有网络栈兼容风险
- B(纯 Kotlin 重写):标准 Android 技术栈,AI 可落地性强;字节级格式由 docs/02-core-format.md 约束,配合互操作测试保证兼容

**结论**:选 B。兼容性靠"格式规范 + 互操作测试矩阵"(09-testing.md §3)双重保障,而非共享代码。

---

## ADR-002:自研轻量 OCI 客户端(OkHttp),不引入 oras 类库

**状态:✅ 已采纳**

**背景**:JVM 生态无成熟的 OCI artifact 客户端库(oras-go 是 Go 专属)。

**方案对比**:
- 自研:OCI Distribution Spec 的 push/pull/list/delete 流程简单(约 6 个端点),自研代码 ~800 行,可控性强,便于 MockWebServer 测试
- 引入重型库(如 containerd 的 java client、jib 相关库):均面向容器镜像而非自定义 artifact,定制成本高

**结论**:自研 `core/oci/OciClient`,严格按 docs/03-oci-protocol.md 实现。

---

## ADR-003:core 模块纯 JVM,不依赖 Android SDK

**状态:✅ 已采纳**

**理由**:单元测试无需设备/模拟器;未来可移植桌面端;AI 工具在 CI 中即可验证核心逻辑。所有 Android 能力(Keystore、SAF、Room)通过接口(app 侧实现)注入。

**代价**:SAF 目录选择需拷贝到 cacheDir(无法直接流式打包 content URI 树),多一次磁盘拷贝,可接受。

---

## ADR-004:凭据用 Android Keystore AES-GCM 密钥包装存储

**状态:✅ 已采纳**

**理由**:Keystore 密钥不可导出,即使 root 也拿不到明文;AES-GCM 有完整性保护。`setUserAuthenticationRequired(false)` 不绑定生物识别,保证后台任务(前台服务 push)不需要锁屏交互。

**备选**:EncryptedSharedPreferences(Google 已标记弃用)→ 不采用;直接明文 → 违反安全设计。

---

## ADR-005:活动历史用 Room,而非 JSON 文件

**状态:✅ 已采纳**

**背景**:Go CLI 用 `activity.json`(100 条上限)。

**理由**:Room 提供查询/统计/分页,更适合移动端;与 CLI 版无互操作需求(历史是本地的),无需格式兼容。

**差异**:Go 版只记录成功操作;Android 版同时记录失败(带 error 字段),便于用户排查。行为差异有文档化理由。

---

## ADR-006:不引入 Hilt,使用手动 DI 容器

**状态:✅ 已采纳**

**理由**:项目规模小(一个 AppContainer 即可);减少 KSP 处理与构建复杂度,降低 AI 工具出错概率;ViewModel 工厂手动注册清晰可控。

**备选**:Hilt(官方推荐,规模化友好)→ 若后续模块膨胀可迁移,成本可控。

---

## ADR-007:大文件任务用前台服务,不用 WorkManager

**状态:✅ 已采纳**

**理由**:push/pull 是用户主动交互任务(非后台周期性任务),需要实时进度、可取消;前台服务 + 通知最贴合。WorkManager 更适合"稍后执行"型任务。

**注意**:Android 14+ 需 `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC` 权限。

---

## ADR-008:push/pull 采用流式处理,单文件上限 512MB

**状态:✅ 已采纳**

**背景**:Go CLI 版将整个数据读入内存(单次调用,CLI 场景可接受);Android 内存受限。

**方案**:打包/上传/下载/解包全部流式(InputStream/OutputStream);blob 上传用 monolithic(单请求);超过 512MB 报 `TooLarge`(v1 限制,后续可加 chunked 上传)。

**代价**:与 Go 版字节格式仍兼容(流式不改变格式,只改变内存策略)。

---

## ADR-009:manifest annotations 存 labels,key 保留 `io.oci-sync.` 前缀

**状态:✅ 已采纳(继承上游设计)**

**约束**:`io.oci-sync.version` / `io.oci-sync.encrypted` 为系统保留;用户 label 不得使用此前缀(ConfigLoader 校验 + UI 提示)。list 过滤仅识别含 `io.oci-sync.version` 的镜像。

---

## ADR-010:docker.io 隐式补全规则与 Docker/oras 一致

**状态:✅ 已采纳(继承上游行为)**

**规则**:含 `.`/`:` 或 `localhost` 的 host 显式使用;单段名补 `docker.io` + `library/` 前缀。`ReferenceParser.parse("alpine")` → `registry-1.docker.io/library/alpine`(registry-1 为 docker.io 的规范推送主机)。

---

## ADR-011:工具链升级到 2026-08 最新稳定基线

**状态:✅ 已采纳**

**背景**:原版本基线(AGP 8.7.3 / Kotlin 2.1.0 / OkHttp 4.12 / targetSdk 35)已落后约一年半,存在安全与维护成本问题。Google Play 自 2026-08 起强制新应用 target API 36,原基线已不满足上架要求。

**升级内容(2026-08-14 向 Google Maven / Maven Central / GitHub 官方源逐一核实的最新稳定版):**

| 组件 | 旧 | 新 | 说明 |
|------|-----|-----|------|
| AGP | 8.7.3 | 9.3.1 | major 升级,需 Gradle 9 |
| Gradle(wrapper) | — | 9.7.0 | AGP 9.x 配套 |
| JDK | 17 | 21 LTS | AGP 9 要求 JDK 17+,21 最稳;25 LTS 待官方确认 |
| Kotlin | 2.1.0 | 2.4.10 | Compose 编译器随 Kotlin 发布 |
| KSP | 2.1.0-1.0.29 | 2.3.11 | KSP 已独立版本号,落地时验证与 Kotlin 2.4 兼容 |
| Compose BOM | 2024.12.01 | 2026.08.00 | 含 Material 3 1.4.0 |
| OkHttp / MockWebServer | 4.12.0 | 5.4.0 | Kotlin 重写,协程原生支持 |
| Room | 2.6.1 | 2.8.4 | 支持 KMP(未来 core 可迁移) |
| DataStore | 1.1.1 | 1.2.1 | |
| targetSdk / compileSdk | 35 | 36 / 37 | targetSdk 36(Play 要求);compileSdk 37 由最新 androidx(core-ktx 1.19.0 等)强制 |
| 其余(navigation 2.9.8、lifecycle 2.11.0、BC 1.85.2、commons-compress 1.28.0、kotlinx-serialization 1.11.0 等) | | | 完整清单见 08-implementation-plan.md 版本基线 |

**理由**:
- OkHttp 5.x:Kotlin 重写、`suspend` 协程 API、默认更强 TLS 配置,安全与开发效率双提升
- Kotlin 2.4:Compose 编译器内置(`org.jetbrains.kotlin.plugin.compose`),删除 `composeOptions` 配置,版本错配问题消失
- AGP 9.x:built-in Kotlin 支持、构建管线优化;Gradle 9 配置缓存更成熟
- targetSdk 36:满足 Play 2026-08 强制要求,避免上架被拒

**风险与缓解**:
- AGP 9 为 major 升级,存在 breaking changes:脚手架 M0 实际构建为最终验证;若遇阻可回退 AGP 8.13 + Gradle 8.14,仅损失新特性,不影响架构设计
- KSP 与 Kotlin 2.4.10 的兼容组合在 M0 验证,失败则按官方兼容矩阵调整
- JDK 25 LTS 暂不采用,待 AGP 官方支持矩阵确认后评估

**格式兼容影响**:无。工具链升级不改变字节级格式(02-core-format.md),与 Go CLI 的互操作测试矩阵(09-testing.md §3)继续有效。

---

## 待办决策(未来迭代)

| # | 议题 | 现状 |
|---|------|------|
| 1 | 生物识别门禁(查看/使用凭据) | v1 不做,Keystore 密钥未绑用户认证 |
| 2 | "保存口令"选项(passphrase 持久化) | v1 不提供 |
| 3 | chunked blob 上传(>512MB) | v1 限制为 monolithic |
| 4 | 断点续传 | v1 不做,失败重试 |
| 5 | 多文件选择 | CLI 对齐:单路径 |
