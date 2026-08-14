package com.tiramission.ocisync.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 活动历史 Room 实现(docs/05-data-layer.md §3.1)。
 * M0 仅落地实体/DAO 骨架以验证 Room + KSP 工具链,完整逻辑在 M5 实现。
 */
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
    @Query("SELECT * FROM activities ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityEntity>
}

@Database(entities = [ActivityEntity::class], version = 1, exportSchema = false)
abstract class OciSyncDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}
