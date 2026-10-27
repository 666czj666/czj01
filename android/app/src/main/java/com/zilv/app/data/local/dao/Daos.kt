package com.zilv.app.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zilv.app.data.local.entity.CheckInEntity
import com.zilv.app.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow

/** 每日打卡条数（用于日历热力图）。 */
data class DailyCount(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "cnt") val count: Int
)

@Dao
interface HabitDao {

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY sort_order ASC, id ASC")
    fun observeActive(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY sort_order ASC, id ASC")
    suspend fun getActive(): List<HabitEntity>

    @Query("SELECT * FROM habits ORDER BY archived ASC, sort_order ASC, id ASC")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: Long): HabitEntity?

    @Insert
    suspend fun insert(habit: HabitEntity): Long

    @Update
    suspend fun update(habit: HabitEntity)

    @Query("UPDATE habits SET archived = 1 WHERE id = :id")
    suspend fun archive(id: Long)

    /**
     * 取消归档。
     *
     * 有它才让「归档」名副其实 —— 否则归档后的习惯从列表消失且**再也看不到**，
     * 用户会以为数据丢了，而它其实还占着库。
     */
    @Query("UPDATE habits SET archived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)

    @Query("SELECT * FROM habits WHERE archived = 1 ORDER BY sort_order ASC, id ASC")
    suspend fun getArchived(): List<HabitEntity>

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM habits")
    suspend fun count(): Int
}

@Dao
interface CheckInDao {

    @Query("SELECT epoch_day FROM check_ins WHERE habit_id = :habitId")
    suspend fun epochDaysFor(habitId: Long): List<Long>

    @Query("SELECT DISTINCT epoch_day FROM check_ins WHERE habit_id = :habitId ORDER BY epoch_day DESC")
    fun observeEpochDays(habitId: Long): Flow<List<Long>>

    @Query("SELECT * FROM check_ins WHERE habit_id = :habitId AND epoch_day = :epochDay ORDER BY checked_at DESC")
    suspend fun forDay(habitId: Long, epochDay: Long): List<CheckInEntity>

    @Query("SELECT COUNT(*) FROM check_ins WHERE habit_id = :habitId AND epoch_day = :epochDay")
    suspend fun countForDay(habitId: Long, epochDay: Long): Int

    @Query("SELECT COUNT(*) FROM check_ins WHERE habit_id = :habitId")
    suspend fun countForHabit(habitId: Long): Int

    /**
     * 某习惯**最近一次打卡**（整个 `check_ins` 表里 checked_at 最大的那条）。
     *
     * 用于 v2.2 的「习惯打卡也能解锁锁机」：`LockRuleRepository.currentStatus()`
     * 拿它和 `rule_check_ins` 里的记录比早晚，取较晚的一个当作解锁时刻。
     * 放在 SQL 里做而不是拉全表回内存排序 —— 打卡记录会随时间无限增长。
     */
    @Query("SELECT * FROM check_ins WHERE habit_id = :habitId ORDER BY checked_at DESC LIMIT 1")
    suspend fun latestForHabit(habitId: Long): CheckInEntity?

    /**
     * 只统计**地点已验证**的打卡（同一天多次只算一天）。
     *
     * 「到点打卡」类型的习惯必须走这条路径统计连续天数 —— 否则用户
     * 在首页点一下就算完成，地点校验形同虚设。
     *
     * 判定标记是 `spot_id`：只有从打卡点流程写入的记录才会带 spotId，
     * 手动勾选永远是 null。
     */
    @Query("SELECT DISTINCT epoch_day FROM check_ins WHERE habit_id = :habitId AND spot_id IS NOT NULL")
    suspend fun verifiedEpochDaysFor(habitId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM check_ins WHERE habit_id = :habitId AND epoch_day = :epochDay AND spot_id IS NOT NULL")
    suspend fun countVerifiedForDay(habitId: Long, epochDay: Long): Int

    @Query("SELECT COUNT(*) FROM check_ins")
    suspend fun countAll(): Int

    @Insert
    suspend fun insert(checkIn: CheckInEntity): Long

    @Query("DELETE FROM check_ins WHERE habit_id = :habitId AND epoch_day = :epochDay")
    suspend fun deleteForDay(habitId: Long, epochDay: Long)

    @Query("DELETE FROM check_ins WHERE habit_id = :habitId")
    suspend fun deleteAllForHabit(habitId: Long)

    /** 热力图数据：按天聚合的打卡条数。 */
    @Query("SELECT epoch_day, COUNT(*) AS cnt FROM check_ins GROUP BY epoch_day ORDER BY epoch_day ASC")
    fun observeDailyCounts(): Flow<List<DailyCount>>
}