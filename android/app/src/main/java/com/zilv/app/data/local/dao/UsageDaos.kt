package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zilv.app.data.local.entity.AppUsageEntity
import com.zilv.app.data.local.entity.EventEntity
import com.zilv.app.data.local.entity.LockSessionEntity
import com.zilv.app.data.local.entity.UsageSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Insert
    suspend fun insertSession(session: UsageSessionEntity): Long

    /** 按天累加（存在则相加）—— 用 UPSERT 语义需要先查再写，这里提供两步接口。 */
    @Query("SELECT * FROM app_usage WHERE epoch_day = :epochDay AND package_name = :packageName")
    suspend fun aggregateRow(epochDay: Long, packageName: String): AppUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAggregate(row: AppUsageEntity)

    @Query("SELECT * FROM app_usage WHERE epoch_day = :epochDay ORDER BY foreground_ms DESC")
    suspend fun totalsForDay(epochDay: Long): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage WHERE epoch_day = :epochDay ORDER BY foreground_ms DESC")
    fun observeTotalsForDay(epochDay: Long): Flow<List<AppUsageEntity>>

    @Query("SELECT package_name AS packageName, SUM(foreground_ms) AS total FROM app_usage GROUP BY package_name ORDER BY total DESC LIMIT :limit")
    suspend fun allTimeTotals(limit: Int): List<PackageTotal>

    /**
     * 窗口内的会话段。
     *
     * 判定条件用**区间相交**而不是包含：一段使用可能横跨窗口边界。
     */
    @Query(
        """
        SELECT * FROM usage_sessions
        WHERE ended_at > :windowStart AND started_at < :windowEnd
        ORDER BY started_at ASC
        """
    )
    suspend fun sessionsInWindow(windowStart: Long, windowEnd: Long): List<UsageSessionEntity>

    @Query("SELECT * FROM usage_sessions WHERE started_at >= :fromMillis ORDER BY started_at ASC")
    suspend fun sessionsSince(fromMillis: Long): List<UsageSessionEntity>

    /** 窗口内每次使用的**最长持续时间**（PRD 的「最长连续使用」指标）。 */
    @Query(
        """
        SELECT MAX(duration_ms) FROM usage_sessions
        WHERE ended_at > :windowStart AND started_at < :windowEnd
        """
    )
    suspend fun longestSessionInWindow(windowStart: Long, windowEnd: Long): Long?

    @Query("DELETE FROM usage_sessions WHERE started_at < :beforeMillis")
    suspend fun pruneSessionsBefore(beforeMillis: Long)
}

@Dao
interface LockSessionDao {

    @Insert
    suspend fun insert(session: LockSessionEntity): Long

    @Update
    suspend fun update(session: LockSessionEntity)

    @Query("SELECT * FROM lock_sessions WHERE id = :id")
    suspend fun getById(id: Long): LockSessionEntity?

    /** 某规则当前进行中的窗口（同规则同开始时刻只应有一条）。 */
    @Query("SELECT * FROM lock_sessions WHERE rule_id = :ruleId AND window_start = :windowStart LIMIT 1")
    suspend fun findByRuleAndStart(ruleId: Long, windowStart: Long): LockSessionEntity?

    @Query("SELECT * FROM lock_sessions ORDER BY window_start DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<LockSessionEntity>

    @Query("SELECT * FROM lock_sessions ORDER BY window_start DESC")
    fun observeAll(): Flow<List<LockSessionEntity>>

    /** 首页「上次回顾」卡片：最近一条未查看的已结束窗口。 */
    @Query(
        """
        SELECT * FROM lock_sessions
        WHERE report_seen = 0 AND window_end <= :nowMillis
        ORDER BY window_end DESC LIMIT 1
        """
    )
    suspend fun latestUnseenFinished(nowMillis: Long): LockSessionEntity?

    @Query("UPDATE lock_sessions SET report_seen = 1 WHERE id = :id")
    suspend fun markReportSeen(id: Long)

    @Query("SELECT COUNT(*) FROM lock_sessions")
    suspend fun count(): Int
}

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE session_id = :sessionId ORDER BY detected_at ASC")
    suspend fun forSession(sessionId: Long): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY detected_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE detected_at >= :fromMillis ORDER BY detected_at DESC")
    suspend fun since(fromMillis: Long): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
