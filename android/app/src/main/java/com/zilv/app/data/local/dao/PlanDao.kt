package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zilv.app.data.local.entity.PlanEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「明日规划」的 DAO。
 *
 * 与其它 DAO 一样只做存取：**业务判断一律留在仓库层**
 * （"只能添加明天的规划"、"不能跨 0 点"、"进行中的规划不能改删"都在
 * [com.zilv.app.data.repository.PlanRepository]），这样纯 JVM 单测
 * 可以用内存 Fake 完整覆盖，而这里的 SQL 由 Room 注解处理器在编译期校验。
 *
 * 排序统一按"开始时间升序、id 升序"：同一天的规划在日历与锁机调度里
 * 都要按时间顺序出现，顺序稳定才不会让界面抖动。
 */
@Dao
interface PlanDao {

    /** 观察某一天的规划（按开始时间升序）。 */
    @Query(
        "SELECT * FROM plans WHERE epoch_day = :epochDay " +
            "ORDER BY start_minute ASC, id ASC"
    )
    fun observeForDay(epochDay: Long): Flow<List<PlanEntity>>

    @Query(
        "SELECT * FROM plans WHERE epoch_day = :epochDay " +
            "ORDER BY start_minute ASC, id ASC"
    )
    suspend fun getForDay(epochDay: Long): List<PlanEntity>

    @Query("SELECT * FROM plans ORDER BY epoch_day ASC, start_minute ASC, id ASC")
    fun observeAll(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans ORDER BY epoch_day ASC, start_minute ASC, id ASC")
    suspend fun getAll(): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getById(id: Long): PlanEntity?

    /**
     * 日历用：**有规划的日期集合**。
     *
     * 与 `day_notes` 的同名查询同理：月历一次要渲染 42 个格子，
     * 只要一个日期集合就够画标记，没必要把整天的规划正文拉进内存。
     */
    @Query("SELECT DISTINCT epoch_day FROM plans ORDER BY epoch_day ASC")
    fun observeEpochDays(): Flow<List<Long>>

    @Query("SELECT DISTINCT epoch_day FROM plans ORDER BY epoch_day ASC")
    suspend fun epochDays(): List<Long>

    @Insert
    suspend fun insert(plan: PlanEntity): Long

    @Update
    suspend fun update(plan: PlanEntity)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM plans")
    suspend fun count(): Int
}
