# 01 · 整体架构

> 版本:0.1.0 | 更新时间:2026-08-13

## 1. 项目概述

`oci-sync-android` 是 [tiramission/oci-sync](https://github.com/tiramission/oci-sync)(Go CLI)的 Android 移植版。核心目标:

1. **功能对等**:push / pull / list / delete / label / shortcuts / recent 全部可用
2. **格式兼容**:与 Go CLI 版字节级兼容(详见 02-core-format.md),PC 与手机两端互操作
3. **移动端体验**:SAF 文件选择、进度显示、后台任务、可离线配置

## 2. 技术选型总览

| 领域 | 选型 | 理由 |
|------|------|------|
| 语言 | Kotlin 2.0+ | Android 官方语言 |
| UI | Jetpack Compose + Material 3 | 现代声明式 UI |
| 架构 | MVVM + 单 Activity + Navigation Compose | 官方推荐模式,状态可测试 |
| 网络 | OkHttp 4.x | 最成熟的 JVM HTTP 客户端 |
| OCI 协议 | 自研轻量客户端(core/oci) | JVM 无现成 OCI artifact 客户端,协议简单 |
| 加密 | javax.crypto(AES/GCM)+ BouncyCastle(scrypt) | 与 Go `x/crypto/scrypt` 算法参数一致 |
| 打包 | commons-compress | tar + gzip 标准实现 |
| 存储 | DataStore Preferences(设置)+ Room(历史) | Android 官方存储方案 |
| 序列化 | kotlinx-serialization | Kotlin 原生 |
| 构建 | Gradle Kotlin DSL + AGP 8.x | 标准 |

**完整选型论证见 [10-adr.md](10-adr.md)**。

## 3. 模块划分

```
┌─────────────────────────────────────────────────────────────┐
│                         app 模块                             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────┐ │
│  │  UI 层    │ │ ViewModel│ │ 导航      │ │ 系统集成         │ │
│  │ Compose  │ │ 状态管理 │ │ NavGraph │ │ SAF/通知/Keystore│ │
│  └────┬─────┘ └────┬─────┘ └──────────┘ └─────────────────┘ │
│       │            │                                         │
│       └─────┬──────┘                                         │
└─────────────┼───────────────────────────────────────────────┘
              │ 调用
┌─────────────▼───────────────────────────────────────────────┐
│                       core 模块(纯 Kotlin/JVM)              │
│  ┌─────────────┐ ┌─────────────┐ ┌────────────────────────┐ │
│  │ archive     │ │ crypto      │ │ oci                    │ │
│  │ tar.gz      │ │ scrypt +    │ │ OCI Distribution Spec  │ │
│  │ 打包/解包   │ │ AES-256-GCM │ │ 客户端(OkHttp)          │ │
│  └─────────────┘ └─────────────┘ └───────────┬────────────┘ │
│  ┌─────────────┐ ┌─────────────┐             │              │
│  │ config      │ │ cache       │             │              │
│  │ shortcuts/  │ │ 活动历史     │             │              │
│  │ auths 解析  │ │ (Room)      │             │              │
│  └─────────────┘ └─────────────┘             │              │
│  ┌───────────────────────────────────────────▼────────────┐ │
│  │ model: ArtifactInfo / Activity / Shortcut / RegistryAuth│ │
│  └────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────┘
```

### 3.1 core 模块(纯 Kotlin/JVM,无 Android 依赖)

独立 Gradle module `:core`,可跑纯 JVM 单元测试(不依赖设备)。

| 包 | 职责 | 关键类 |
|----|------|--------|
| `core/archive` | tar.gz 打包/解包 | `ArchivePacker`、`ArchiveUnpacker` |
| `core/crypto` | 加密/解密 | `CryptoEngine` |
| `core/oci` | OCI HTTP 客户端 | `OciClient`、`RegistryAuthProvider`、`ManifestParser`、`ReferenceParser` |
| `core/config` | 配置解析 | `ConfigLoader`、`Shortcut`、`RegistryAuth` |
| `core/cache` | 活动历史 | `ActivityStore`(接口)、`RoomActivityStore`(Android 侧实现) |
| `core/model` | 领域模型 | `ArtifactInfo`、`Activity`、`PushRequest`、`PullResult` |

**设计原则**:core 不依赖 Android SDK,只依赖 OkHttp / BouncyCastle / commons-compress / kotlinx-serialization。这样:
- 单元测试在 JVM 上快速运行
- 未来可移植到其他平台(如 JVM 桌面版)
- AI 工具实现时无需模拟器即可验证核心逻辑

### 3.2 app 模块(Android 应用)

| 层 | 职责 | 关键类 |
|----|------|--------|
| UI | Compose 页面 | `MainActivity`、`PushScreen`、`PullScreen`、`ListScreen`、`HistoryScreen`、`SettingsScreen`、`ShortcutScreen` |
| ViewModel | 状态管理 | `PushViewModel`、`PullViewModel`、`ListViewModel`、`HistoryViewModel`、`SettingsViewModel` |
| 系统集成 | 文件选择/通知 | `FilePicker`(SAF)、`ProgressNotifier`、`KeystoreCrypto`(凭据加密) |

## 4. 数据流

### 4.1 push

```
SAF 选择文件/目录 → 拷贝到 app 缓存目录(cacheDir/push/xxx)
→ [ArchivePacker.pack] tar.gz bytes(流式)
→ [可选 CryptoEngine.encrypt] scrypt 派生密钥 + AES-256-GCM
→ [OciClient.push] POST blob → PUT manifest → 仓库
→ [ActivityStore.add] 记录 push 活动
```

### 4.2 pull

```
用户输入 remote ref(或选 shortcut + tag)
→ [OciClient.fetchManifest] 检查 io.oci-sync.encrypted(只读 manifest)
→ 若加密且无 passphrase → 快速失败(不下载)
→ [OciClient.fetchLayer] 流式下载 layer → [可选解密] → [ArchiveUnpacker.unpack] 到用户选择目录
→ [ActivityStore.add] 记录 pull 活动
```

### 4.3 list

```
输入 registry 或 repo ref
→ [OciClient.listTags] / [OciClient.listRepositories](registry 支持时)
→ 逐 tag fetch manifest → 过滤含 io.oci-sync.version annotation 的 artifact
→ 按 --label 等价条件筛选 → 返回 ArtifactInfo 列表 → UI 表格展示
```

### 4.4 delete / label

```
delete: resolve tag → digest → DELETE manifest(按 digest)
label set/unset: fetch manifest → 修改 annotations → PUT 新 manifest → 更新 tag 引用
```

## 5. 关键设计约束

1. **格式兼容优先**:02-core-format.md 中的字节布局是硬约束,任何实现不得偏离
2. **流式处理**:push/pull 全程流式,禁止将整个文件读入内存(单文件 >50MB 时必须走流式路径)
3. **凭据安全**:registry token/passphrase 只存在于内存与 Keystore 加密存储中
4. **网络错误可恢复**:OkHttp 超时、重试策略在 03-oci-protocol.md 定义
5. **单 Activity**:导航完全由 Navigation Compose 管理,不新增 Activity
