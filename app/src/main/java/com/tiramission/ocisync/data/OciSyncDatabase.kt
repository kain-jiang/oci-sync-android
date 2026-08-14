package com.tiramission.ocisync.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * 活动历史 Room 实现(docs/05-data-layer.md §3.1)。
 * M0 落地骨架,M4 补齐完整 CRUD 与 100 条上限截断。
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
    @Insert
    suspend fun insert(entity: ActivityEntity)

    @Query("SELECT * FROM activities ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActivityEntity>

    @Query("SELECT * FROM activities ORDER BY timestamp DESC, id DESC")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Query("DELETE FROM activities WHERE id NOT IN (SELECT id FROM activities ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun deleteOldestBeyond(keep: Int)

    @Query("DELETE FROM activities")
    suspend fun clear()
}

@Database(entities = [ActivityEntity::class], version = 1, exportSchema = false)
abstract class OciSyncDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
}
