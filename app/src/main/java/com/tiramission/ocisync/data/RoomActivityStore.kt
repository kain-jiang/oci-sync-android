package com.tiramission.ocisync.data

import com.tiramission.ocisync.core.cache.Activity
import com.tiramission.ocisync.core.cache.ActivityStats
import com.tiramission.ocisync.core.cache.ActivityStore
import com.tiramission.ocisync.core.cache.ActivityType
import kotlinx.serialization.json.Json

/**
 * Room 实现的 [ActivityStore],见 docs/05-data-layer.md §3.1 与 docs/09-testing.md §4。
 * - 与 CLI 版差异(ADR-005):同时记录失败操作(带 error)
 * - 100 条上限:插入后删除最旧记录
 */
class RoomActivityStore(
    private val dao: ActivityDao,
    private val maxEntries: Int = 100,
) : ActivityStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun add(activity: Activity) {
        dao.insert(activity.toEntity())
        dao.deleteOldestBeyond(maxEntries)
    }

    override suspend fun recent(limit: Int): List<Activity> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun stats(limit: Int): ActivityStats {
        val all = dao.all().map { it.toDomain() }
        return ActivityStats(
            total = all.size,
            byType = all.groupBy { it.type.name }.mapValues { it.value.size },
            recent = all.take(limit),
        )
    }

    private fun Activity.toEntity() = ActivityEntity(
        type = type.name,
        timestamp = timestamp,
        remoteRef = remoteRef,
        localPath = localPath,
        labelsJson = labels.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) },
        success = success,
        error = error,
    )

    private fun ActivityEntity.toDomain() = Activity(
        type = ActivityType.valueOf(type),
        timestamp = timestamp,
        remoteRef = remoteRef,
        localPath = localPath,
        labels = labelsJson?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        } ?: emptyList(),
        success = success,
        error = error,
    )
}
