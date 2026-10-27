package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zilv.app.data.local.entity.BlockedAttemptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedAttemptDao {

    @Insert
    suspend fun insert(entity: BlockedAttemptEntity): Long

    /** 该包名最近一条记录（用于判断是新建还是并入）。 */
    @Query("SELECT * FROM blocked_attempts WHERE package_name = :packageName ORDER BY last_at DESC LIMIT 1")
    suspend fun latestFor(packageName: String): BlockedAttemptEntity?

    @Query("UPDATE blocked_attempts SET count = :count, last_at = :lastAt WHERE id = :id")
    suspend fun updateCount(id: Long, count: Int, lastAt: Long)

    @Query("SELECT * FROM blocked_attempts ORDER BY last_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BlockedAttemptEntity>>

    @Query("SELECT * FROM blocked_attempts ORDER BY last_at DESC")
    suspend fun getAll(): List<BlockedAttemptEntity>

    /** 按包名汇总次数，降序（报告里的「被拦住的」排行）。 */
    @Query(
        """
        SELECT package_name AS packageName, SUM(count) AS total
        FROM blocked_attempts
        GROUP BY package_name
        ORDER BY total DESC
        """
    )
    suspend fun totalsByPackage(): List<PackageTotal>

    /**
     * 指定时间窗口内的按包名汇总。
     *
     * 归属判定用 `last_at`：一次被合并的连续尝试算在它最后一次发生的时间点，
     * 这样跨窗口边界的"连点"不会被同时算进两个窗口。
     */
    @Query(
        """
        SELECT package_name AS packageName, SUM(count) AS total
        FROM blocked_attempts
        WHERE last_at >= :windowStart AND last_at < :windowEnd
        GROUP BY package_name
        ORDER BY total DESC
        """
    )
    suspend fun totalsByPackageBetween(windowStart: Long, windowEnd: Long): List<PackageTotal>

    @Query("SELECT SUM(count) FROM blocked_attempts")
    suspend fun totalCount(): Int?

    @Query("SELECT COUNT(*) FROM blocked_attempts")
    suspend fun rowCount(): Int

    @Query("DELETE FROM blocked_attempts")
    suspend fun clear()
}

/** 按包名汇总的拦截次数。 */
data class PackageTotal(
    val packageName: String,
    val total: Int
)
