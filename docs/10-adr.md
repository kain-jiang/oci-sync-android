# 10 · 架构决策记录(ADR)

> 版本:0.1.0 | 更新时间:2026-08-13
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

## 待办决策(未来迭代)

| # | 议题 | 现状 |
|---|------|------|
| 1 | 生物识别门禁(查看/使用凭据) | v1 不做,Keystore 密钥未绑用户认证 |
| 2 | "保存口令"选项(passphrase 持久化) | v1 不提供 |
| 3 | chunked blob 上传(>512MB) | v1 限制为 monolithic |
| 4 | 断点续传 | v1 不做,失败重试 |
| 5 | 多文件选择 | CLI 对齐:单路径 |
