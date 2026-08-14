package com.tiramission.ocisync.core.cache

import kotlinx.serialization.Serializable
import java.util.Collections

enum class ActivityType { PUSH, PULL, DELETE, LABEL }

/** 活动历史模型,见 docs/05-data-layer.md §3 与 docs/07-api-contract.md §6。 */
@Serializable
data class Activity(
    val type: ActivityType,
    val timestamp: Long,               // epoch millis
    val remoteRef: String,
    val localPath: String? = null,
    val labels: List<String> = emptyList(),
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class ActivityStats(
    val total: Int,
    val byType: Map<String, Int>,
    val recent: List<Activity>,
)

/** 活动历史存储(app 模块用 Room 实现;core 测试用内存实现)。 */
interface ActivityStore {
    suspend fun add(activity: Activity)
    suspend fun recent(limit: Int = 20): List<Activity>   // 时间倒序
    suspend fun clear()
    suspend fun stats(limit: Int = 20): ActivityStats
}

/**
 * 内存实现(测试/无 Room 环境用),按 [maxEntries] 截断最旧记录。
 * 与 Room 实现(05 §3.1)保持相同语义:recent 时间倒序。
 */
class InMemoryActivityStore(private val maxEntries: Int = 100) : ActivityStore {

    private val list = Collections.synchronizedList(mutableListOf<Activity>())

    override suspend fun add(activity: Activity) {
        list.add(0, activity) // 头部最新
        while (list.size > maxEntries) {
            list.removeAt(list.size - 1)
        }
    }

    override suspend fun recent(limit: Int): List<Activity> =
        list.take(limit)

    override suspend fun clear() {
        list.clear()
    }

    override suspend fun stats(limit: Int): ActivityStats =
        ActivityStats(
            total = list.size,
            byType = list.groupBy { it.type.name }.mapValues { it.value.size },
            recent = list.take(limit),
        )
}
