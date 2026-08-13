# 06 · UI/UX 设计(Jetpack Compose)

> 版本:0.1.0 | 更新时间:2026-08-13

## 1. 设计原则

- **功能对等 CLI**:push/pull/list/delete/label/shortcuts/recent 全部有对应 UI
- **移动优先**:SAF 文件选择、大文件后台任务 + 前台通知进度、横竖屏适配
- **Material 3**:动态取色(Material You,API 31+ 自动)
- **单 Activity + Navigation Compose**,深色模式跟随系统

## 2. 页面地图

```
MainActivity
└── NavHost
    ├── "home"            HomeScreen(首页:shortcuts + 最近活动入口)
    ├── "push"            PushScreen(选择文件/目录 → 配置 → 执行)
    ├── "pull"            PullScreen(输入 ref → 选择目标 → 执行)
    ├── "list"            ListScreen(浏览 repo/registry artifacts)
    ├── "history"         HistoryScreen(recent 活动)
    ├── "settings"        SettingsScreen(凭据管理、shortcuts 管理)
    └── "shortcut"        ShortcutDetailScreen(单个 shortcut 的操作台)
```

### 底部导航(3 个主 Tab)

```
[首页 Home] [仓库 Browse] [历史 History]
```

- 首页:shortcuts 卡片列表 + 快捷操作入口(Push/Pull/设置)
- 仓库:输入 registry/repo → artifacts 表格
- 历史:活动记录列表

## 3. 各页面详细设计

### 3.1 HomeScreen(首页)

- 顶部:App 名 + 设置入口(齿轮图标)
- **Shortcuts 区**:卡片列表(名称 + repo),点击进入 ShortcutDetailScreen;右上 "+" 添加
- **操作区**:两个大按钮「推送文件」「拉取文件」→ 分别导航 push/pull 页
- 空状态:无 shortcut 时显示引导文案 + "去添加"

### 3.2 PushScreen(推送)

```
[选择文件/目录]  ──(SAF OpenDocument / OpenDocumentTree)──> 显示所选路径 + 大小
[远程仓库 ref]   ── 文本输入 "registry.example.com/myteam/files:latest"
                  (或从 shortcuts 下拉选择,自动拼 tag 输入框)
[加密口令]       ── 可选,Password 输入;勾选"显示"
[标签]           ── 可折叠区,key=value 列表(添加/删除行)
[推送按钮]       ── 大按钮,执行中显示进度条 + 取消
```

- 执行中:LinearProgressIndicator(blob 上传进度)+ 阶段文案(打包中/加密中/上传中)
- 成功后:Snackbar「推送成功」+ 自动跳转历史页入口;失败:错误卡片展示可读错误

### 3.3 PullScreen(拉取)

```
[远程仓库 ref]   ── 输入或 shortcut 选择
[目标目录]       ── SAF OpenDocumentTree 选择,显示路径
[解密口令]       ── 可选;若 artifact 检测为加密且为空 → 校验失败提示
[拉取按钮]
```

- 流程:解析 ref → 检查加密状态(快速) → 若加密要求口令 → 下载(进度)→ 解包
- 解包完成后用系统文件管理器定位(Intent ACTION_VIEW 或分享)

### 3.4 ListScreen(仓库浏览)

```
[输入 registry 或 repo ref]  [查询]
[结果表格]
REPO | TAG | 加密🔒 | 大小 | 版本 | 标签 | 操作
─────────────────────────────────────────────
x    | v1  |  是   | 1.2MB| 0.1.0| app=my | [拉取][删除][标签]
```

- 行操作:拉取(跳 pull 预填)、删除(确认弹窗)、标签管理(弹窗 set/unset)
- 加密状态用锁图标,无权限 tag 提示
- 支持按 label 筛选(筛选栏 `key=value` 或 `key`)

### 3.5 ShortcutDetailScreen(shortcut 操作台)

```
顶部:shortcut 名称 + repo
[TAG 列表]:该 repo 下所有 tags(对应 Go `oci-sync x list`)
行操作:拉取 / 删除 / 标签
底部按钮:[推送新版本](push 页预填 repo)
```

### 3.6 HistoryScreen(历史)

- 列表:时间 | 类型徽标(push/pull/delete/label)| ref | 结果(✓/✗)
- 右上:清空(确认弹窗)
- 类型筛选(All/Push/Pull/Delete/Label)

### 3.7 SettingsScreen(设置)

```
[凭据管理]
  registry 列表:host | username | password(掩码) | [编辑][删除]
  + 添加 registry:host、username、password
[Shortcuts 管理]
  同列表 + 添加/删除
[关于]
  版本号、上游 CLI 链接、开源许可
```

## 4. 状态管理(MVVM)

```kotlin
// 每个页面一个 ViewModel,暴露 StateFlow + 事件
class PushViewModel(
    private val pusher: ArtifactPusher,     // core 门面
    private val configLoader: ConfigLoader,
) : ViewModel() {
    data class UiState(
        val selectedPath: String? = null,
        val remoteRef: String = "",
        val passphrase: String = "",
        val labels: List<Pair<String, String>> = emptyList(),
        val isRunning: Boolean = false,
        val progress: Float = 0f,           // 0..1
        val stage: Stage = Stage.IDLE,      // PACKING/ENCRYPTING/UPLOADING/UNPACKING/DONE
        val error: String? = null,
    )
    val uiState: StateFlow<UiState>
    fun onPathSelected(path: String)
    fun onRemoteRefChange(v: String)
    fun startPush()                         // 调 core,后台线程,更新 progress
    fun cancel()
}
```

- 长任务在 `Dispatchers.IO`(或 WorkManager,见 §6)
- 进度经 `MutableStateFlow` 更新 UI

## 5. 文件选择(SAF)

| 场景 | API |
|------|-----|
| 选择单个文件 | `ActivityResultContracts.OpenDocument` |
| 选择目录(push 目录 / pull 目标) | `ActivityResultContracts.OpenDocumentTree` |
| 多文件 | v1 不支持(与 CLI 对齐:CLI 只能选一个路径) |

- SAF 返回 `content://` URI;读取前先解析:单个文件可直接流式读取;**目录必须整体拷贝到 `cacheDir/push/<uuid>/`**(SAF 无法递归遍历所有子文件内容流,需用 `DocumentFile.listFiles()` 递归复制)
- 拷贝进度计入总进度

## 6. 后台任务策略

| 场景 | 方案 |
|------|------|
| 小文件(<20MB) | ViewModel + coroutine(IO 线程),UI 内进度 |
| 大文件(≥20MB) | **前台服务 + 通知**(FOREGROUND_SERVICE_DATA_SYNC 类型),进度/取消/结果均经通知;Activity 销毁不中断 |
| 应用被杀后恢复 | v1 不做断点续传;通知显示失败原因,用户重试 |

**进度通知**:Android 13+ 需要 `POST_NOTIFICATIONS` 权限;前台服务在 Android 14+ 需声明 `foregroundServiceType="dataSync"`。

## 7. 字符串资源

所有 UI 文案进 `res/values/strings.xml`(默认中文),错误消息同时提供英文(与 CLI 对齐:CLI 错误为英文,Android UI 文案中文,错误详情保留英文原文)。

## 8. UI 验收清单

- [ ] 全部页面可在无网络下打开(空状态友好)
- [ ] 深色模式正常
- [ ] 旋转屏幕状态不丢失(ViewModel 持有状态)
- [ ] 大文件 push 时退后台,通知持续显示进度
- [ ] 加密输入框默认掩码
- [ ] 删除操作均有确认弹窗
- [ ] 错误信息可读(非堆栈)
