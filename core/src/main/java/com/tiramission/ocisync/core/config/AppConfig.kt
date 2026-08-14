package com.tiramission.ocisync.core.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** registry 凭据(内存明文;save 时经 SecretCodec 加密落盘)。 */
@Serializable
data class RegistryAuth(
    val username: String = "",
    val password: String = "",
)

@Serializable
data class Shortcut(
    val repo: String = "",
)

/** 应用配置,字段名与 Go CLI YAML 一致(docs/02-core-format.md §5.1)。 */
@Serializable
data class AppConfig(
    val auths: Map<String, RegistryAuth> = emptyMap(),
    val shortcuts: Map<String, Shortcut> = emptyMap(),
)

/** 键值存储抽象(app 模块用 DataStore 实现;测试用内存实现)。 */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** 凭据加解密抽象(app 模块用 Android Keystore 实现;测试注入 fake)。 */
interface SecretCodec {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * 配置加载/保存,见 docs/05-data-layer.md §2 与 docs/02-core-format.md §5.2。
 * - password 落盘前经 [SecretCodec] 加密;读取时解密为明文
 * - shortcut repo 校验:不得含 '@'(digest);':' 只能在最后一个 '/' 之前(不得含 tag)
 */
class ConfigLoader(
    private val store: KeyValueStore,
    private val secretCodec: SecretCodec,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = "app_config"

    /** 无配置时返回空 AppConfig。 */
    fun load(): AppConfig {
        val raw = store.get(key) ?: return AppConfig()
        return try {
            json.decodeFromString(AppConfig.serializer(), raw)
        } catch (e: Exception) {
            AppConfig()
        }
    }

    /** 整体写回;所有 auths.password 加密后落盘。 */
    fun save(config: AppConfig) {
        val encrypted = config.copy(
            auths = config.auths.mapValues { (_, auth) ->
                auth.copy(password = secretCodec.encrypt(auth.password))
            },
        )
        store.put(key, json.encodeToString(AppConfig.serializer(), encrypted))
    }

    /** 返回指定 registry 的明文凭据(内存态)。 */
    fun getRegistryAuth(host: String): RegistryAuth? {
        val auth = load().auths[host] ?: return null
        if (auth.password.isEmpty()) return auth
        return auth.copy(password = secretCodec.decrypt(auth.password))
    }

    /** shortcut repo 校验:无 '@'、无 tag。 */
    fun getShortcutRepo(name: String): Result<String> {
        val repo = load().shortcuts[name]?.repo
            ?: return Result.failure(IllegalArgumentException("shortcut not found: $name"))
        return validateRepo(repo).map { repo }
    }

    fun getAllShortcuts(): List<Pair<String, Shortcut>> = load().shortcuts.toList()

    fun addShortcut(name: String, repo: String): Result<Unit> {
        validateRepo(repo).getOrElse { return Result.failure(it) }
        val config = load()
        save(config.copy(shortcuts = config.shortcuts + (name to Shortcut(repo))))
        return Result.success(Unit)
    }

    fun removeShortcut(name: String) {
        val config = load()
        save(config.copy(shortcuts = config.shortcuts - name))
    }

    fun addAuth(host: String, auth: RegistryAuth) {
        val config = load()
        save(config.copy(auths = config.auths + (host to auth)))
    }

    fun removeAuth(host: String) {
        val config = load()
        save(config.copy(auths = config.auths - host))
    }

    private fun validateRepo(repo: String): Result<Unit> {
        if (repo.contains('@')) {
            return Result.failure(IllegalArgumentException("shortcut repo must not contain '@'"))
        }
        val lastSlash = repo.lastIndexOf('/')
        val lastColon = repo.lastIndexOf(':')
        if (lastColon > lastSlash) {
            return Result.failure(IllegalArgumentException("shortcut repo must not contain tag"))
        }
        return Result.success(Unit)
    }
}
