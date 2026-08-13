# 08 · 实施计划(供 AI 工具按里程碑落地)

> 版本:0.1.0 | 更新时间:2026-08-13
> 目标:AI 工具(Cursor/Claude Code/OpenCode 等)按本计划顺序实现,每个里程碑有明确验收标准。

## 0. 项目脚手架(前置)

```
oci-sync-android/
├── settings.gradle.kts
├── build.gradle.kts              # 根:插件声明(AGP、Kotlin、KSP、serialization)
├── gradle/libs.versions.toml     # 版本目录(集中管理)
├── gradle.properties
├── core/build.gradle.kts         # Kotlin/JVM 模块(无 Android 插件)
├── app/build.gradle.kts          # Android 应用模块
├── README.md / AGENTS.md / docs/
└── .gitignore
```

**版本基线(2026-08,以能解析为准,可升级 minor):**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
navigationCompose = "2.8.5"
lifecycle = "2.8.7"
room = "2.6.1"
okhttp = "4.12.0"
bouncycastle = "1.78.1"
commonsCompress = "1.27.1"
kotlinxSerialization = "1.7.3"
datastore = "1.1.1"
ksp = "2.1.0-1.0.29"
junit = "4.13.2"
mockwebserver = "4.12.0"
coroutinesTest = "1.9.0"
robolectric = "4.14.1"
```

**core 模块要点**:
- `plugins { kotlin("jvm"); kotlin("plugin.serialization") }`
- 依赖:okhttp、bouncycastle、commons-compress、kotlinx-serialization-json、junit、mockwebserver(test)、coroutines-test(test)
- **core 不依赖任何 Android 类** → 纯 JVM 单测

**app 模块要点**:
- minSdk 26,targetSdk 35,compileSdk 35
- 依赖:compose BOM、material3、navigation-compose、lifecycle-viewmodel-compose、room(ksp)、datastore-preferences、core 模块
- `android:allowBackup="false"`、`foregroundServiceType="dataSync"`(Android 14+)

**验收(M0)**:`./gradlew :core:test` 与 `./gradlew assembleDebug` 通过,空 Compose 应用可安装启动。

---

## M1 · core:archive + crypto(纯 JVM)

任务:
- [ ] `ArchivePacker.pack`(流式 File→tar.gz,目录/文件分支,目录条目 `/` 结尾)
- [ ] `ArchiveUnpacker.unpack`(路径穿越防护,仅 DIR/普通文件)
- [ ] `CryptoEngine.encrypt/decrypt`(scrypt + AES-GCM,布局 salt|nonce|ct)
- [ ] 单元测试:往返、空文件、嵌套目录、非法路径、错误口令、密文过短
- [ ] **互操作测试准备**:生成 Go CLI 打包/加密样本(见 09-testing.md §3,若本机无 Go,先用固定字节断言:加密后长度 = 明文 + 60)

验收:
- [ ] `./gradlew :core:test` 全绿
- [ ] 用 Go CLI `oci-sync push`(本地起 registry)产物可在 JVM 测试中解包/解密(09 §3)
- [ ] 恶意 tar(`../evil.txt`)被拒绝并抛 `ArchiveException`

---

## M2 · core:oci 客户端(纯 JVM + MockWebServer)

任务:
- [ ] `ReferenceParser.parse`(隐式 docker.io 规则、tag/digest 解析)
- [ ] `OciClient.push`(blob monolithic upload → manifest PUT)
- [ ] `OciClient.pull` / `isEncrypted`(GET manifest + GET blob)
- [ ] `OciClient.list`(tags 分页 + annotation 过滤)
- [ ] `OciClient.delete`(resolve digest → DELETE)
- [ ] `OciClient.updateAnnotations`(label set/unset)
- [ ] 401 Bearer token 流程(AuthInterceptor + TokenCache)
- [ ] 错误映射(404→NotFound、403→AuthFailed 等)
- [ ] MockWebServer 测试:全端点 + token 挑战 + 分页 + 断网重试

验收:
- [ ] `./gradlew :core:test` 全绿(所有 OCI 测试走 MockWebServer,无真实网络)
- [ ] 对本地 `registry:2`(docker)完成真实 push/pull/list/delete 冒烟(可选,有 docker 时)

---

## M3 · core:config + cache + SyncService 编排

任务:
- [ ] `AppConfig`/`ConfigLoader`(KeyValueStore + SecretCodec 抽象,password 加密落盘)
- [ ] `ActivityStore` 接口 + `InMemoryActivityStore`(测试用)
- [ ] `SyncService`(push/pull/list/delete/label 完整编排 + 活动记录 + Stage 回调)
- [ ] 单元测试:配置往返、shortcut 校验、SyncService 端到端(MockWebServer)

验收:
- [ ] `:core:test` 全绿
- [ ] `SyncService.push` → MockWebServer 收到正确请求序列(POST upload → PUT blob → PUT manifest)
- [ ] 活动记录按 100 条上限截断(内存实现)

---

## M4 · app:数据层落地

任务:
- [ ] `DataStoreKeyValueStore`(实现 KeyValueStore)
- [ ] `KeystoreCrypto`(实现 SecretCodec,Android Keystore AES-GCM)
- [ ] Room:`ActivityEntity`/`ActivityDao`/`RoomActivityStore`
- [ ] 依赖注入:手动 DI 容器(`AppContainer`,不引入 Hilt,减少构建复杂度;ADR 记录)
- [ ] Robolectric 测试:KeystoreCrypto 往返、Room 增删查

验收:
- [ ] `./gradlew test`(app 模块 JVM 测试)全绿
- [ ] 真机/模拟器:`auths` 保存后文件内无明文(adb shell 查看 DataStore 文件)

---

## M5 · app:UI 骨架 + 导航

任务:
- [ ] `MainActivity` + NavHost + 底部导航(Home/Browse/History)
- [ ] HomeScreen(shortcuts 卡片 + 操作入口)
- [ ] SettingsScreen(凭据 + shortcuts 管理)
- [ ] 空状态、深色模式、i18n 字符串(中/英)

验收:
- [ ] 应用可安装,三 Tab 可切换
- [ ] 添加/删除 shortcut 与凭据落盘生效

---

## M6 · app:push/pull 流程

任务:
- [ ] SAF 文件/目录选择 + 目录递归拷贝到 cacheDir
- [ ] PushScreen(表单 + 进度 + 取消)
- [ ] PullScreen(ref 输入 + 目标目录 + 加密预检 + 进度)
- [ ] ListScreen(查询 + 表格 + 行操作:拉取/删除/标签弹窗)
- [ ] ShortcutDetailScreen(tag 列表 + 操作)
- [ ] HistoryScreen(列表 + 筛选 + 清空)
- [ ] 大文件前台服务 + 通知进度(≥20MB)

验收:
- [ ] 端到端:真机 push 文件到真实 registry(如 GHCR 测试仓库)→ PC Go CLI pull 成功
- [ ] PC Go CLI push → 真机 pull 成功
- [ ] 加密内容两端互通
- [ ] 旋转/退后台任务不中断

---

## M7 · 打磨与发布准备

任务:
- [ ] `./gradlew lint` 清零
- [ ] R8 混淆配置验证(serialization 字段保持)
- [ ] 应用图标、启动页、版本信息
- [ ] 互操作测试自动化脚本(09-testing.md §3)
- [ ] 补齐 README 截图与使用说明
- [ ] 可选:GitHub Actions CI(assemble + test + lint)

验收:
- [ ] lint/test/build 全绿
- [ ] 全部安全验收清单(04-crypto-security.md §6)通过
- [ ] 发布 debug APK 供内测

---

## 依赖关系图

```
M0 ──► M1 ──► M3 ──► M4 ──► M5 ──► M6 ──► M7
        └──► M2 ──► M3
```

M1/M2 可并行;M3 依赖 M1+M2;M4 依赖 M3;M5/M6 依赖 M4。

## 工作量估计(供排期参考)

| 里程碑 | 预计任务数 | 重点风险 |
|--------|-----------|----------|
| M1 | 4 | 格式兼容细节(tar 目录条目、gzip) |
| M2 | 8 | Bearer token 流程、分页、delete 语义 |
| M3 | 4 | SyncService 编排正确性 |
| M4 | 4 | Keystore 在 Robolectric 的兼容性 |
| M5 | 4 | 导航结构 |
| M6 | 7 | SAF 目录拷贝、前台服务 |
| M7 | 6 | 混淆、CI |
