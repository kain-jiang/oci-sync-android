# 05 · 数据层设计(配置、shortcuts、活动历史)

> 版本:0.1.0 | 更新时间:2026-08-13

## 1. 存储选型

| 数据 | 存储 | 理由 |
|------|------|------|
| 应用设置(含 auths 密文) | DataStore Preferences | 小数据、键值、官方推荐 |
| shortcuts | DataStore Preferences(JSON 字符串) | 结构简单,与 auths 同处 |
| 活动历史 | Room 数据库 | 列表型、需查询/分页/统计 |
| 临时文件(push 缓存) | `cacheDir/push/<uuid>/` | 系统管理,可清理 |

## 2. 配置模型(core/config)

```kotlin
// core 模块:纯数据类 + JSON 序列化(字段名与 CLI YAML 一致)
@Serializable
data class RegistryAuth(
    val username: String = "",
    val password: String = "",      // 落盘为 Keystore 密文,内存为明文
)

@Serializable
data class Shortcut(
    val repo: String = "",
)

@Serializable
data class AppConfig(
    val auths: Map<String, RegistryAuth> = emptyMap(),
    val shortcuts: Map<String, Shortcut> = emptyMap(),
)
```

```kotlin
class ConfigLoader(
    private val keyValueStore: KeyValueStore,   // 抽象 DataStore,便于测试
    private val secretCodec: SecretCodec,       // KeystoreCrypto,测试可注入 fake
) {
    fun load(): AppConfig
    fun save(config: AppConfig)                 // password 字段加密后写入
    fun getRegistryAuth(host: String): RegistryAuth?
    fun getShortcutRepo(name: String): Result<String>   // 校验规则见 02-core-format.md §5.2
    fun getAllShortcuts(): List<Pair<String, Shortcut>>
}
```

**接口抽象(为 JVM 单测)**:

```kotlin
interface KeyValueStore { fun get(key: String): String?; fun put(key: String, value: String) }
interface SecretCodec { fun encrypt(plaintext: String): String; fun decrypt(ciphertext: String): String }
```

- app 模块提供 DataStore 与 KeystoreCrypto 的真实实现
- 单元测试用 in-memory fake

### 2.1 配置读写时机

- `load()`:App 启动时 + 设置页保存时(单一数据源,内存持有 `AppConfig` 可变状态,或 StateFlow)
- 保存:设置页/alias 操作后整体写回(JSON 序列化)

## 3. 活动历史(core/cache)

```kotlin
// 领域模型
enum class ActivityType { PUSH, PULL, DELETE, LABEL }

@Serializable
data class Activity(
    val type: ActivityType,
    val timestamp: Instant,       // ISO-8601
    val remoteRef: String,
    val localPath: String? = null,
    val labels: List<String> = emptyList(),
    val success: Boolean,
    val error: String? = null,
)

interface ActivityStore {
    suspend fun add(activity: Activity)
    suspend fun recent(limit: Int): List<Activity>   // 按时间倒序,默认 20
    suspend fun clear()
    suspend fun stats(): ActivityStats
}

@Serializable
data class ActivityStats(
    val total: Int,
    val byType: Map<String, Int>,
    val recent: List<Activity>,
)
```

### 3.1 Room 实现(app 模块)

```kotlin
@Entity(tableName = "activities")
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val timestamp: Long,          // epoch millis
    val remoteRef: String,
    val localPath: String?,
    val labelsJson: String?,      // JSON 数组
    val success: Boolean,
    val error: String?,
)

@Dao
interface ActivityDao {
    @Insert suspend fun insert(a: ActivityEntity)
    @Query("SELECT * FROM activities ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityEntity>
    @Query("DELETE FROM activities") suspend fun clear()
    @Query("SELECT COUNT(*) FROM activities") suspend fun count(): Int
    @Query("SELECT type, COUNT(*) as c FROM activities GROUP BY type")
    suspend fun countsByType(): List<TypeCount>
}
```

- 与 Go 版一致:**最多保留 100 条**(插入后 `DELETE FROM activities WHERE id NOT IN (SELECT id ORDER BY timestamp DESC LIMIT 100)`)
- `RoomActivityStore` 实现 `ActivityStore`,DAO 映射到领域模型

### 3.2 记录时机

与 Go CLI 一致:push/pull/delete/label 操作**成功后**记录(含 Success=true);失败操作不记录(Go 行为)——但 Android 端建议额外记录失败(Success=false,error 字段),便于用户排查。此差异记录于 10-adr.md。

## 4. Shortcuts 与 alias 操作

UI 对应 Go 的 `alias list/add/remove` 与动态命令:

| 操作 | 实现 |
|------|------|
| 添加 shortcut | 设置页表单:name + repo → `ConfigLoader.save`(合并写回) |
| 删除 shortcut | 列表项删除(需确认) |
| 使用 shortcut push/pull | 主屏点击 shortcut → push/pull 页面预填 `remote = <repo>:<tag>` |

校验规则复用 02-core-format.md §5.2:repo 无 `@`、无 tag 后缀。

## 5. 序列化约定

- JSON:kotlinx-serialization,字段名 snake_case(与 Go JSON tag 一致:如 `remote_ref`、`local_path`)
- 时间:Room 存 epoch millis;API 边界(如未来导出)用 ISO-8601

## 6. 数据层测试点(对应 09-testing.md)

- ConfigLoader:load/save 往返、password 加密落盘(密文中无明文)、shortcut 校验错误
- RoomActivityStore:插入/倒序/limit/clear/100 条上限
- 全部 JVM 可测(Room 用 Robolectric 或 in-memory Room)
