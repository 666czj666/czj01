package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.PlanDao
import com.zilv.app.data.local.entity.PlanEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [PlanDao] 的内存实现（与 [FakeDayNoteDao] / [FakeHabitDao] 同源同风格）。
 *
 * 严格按 DAO 的语义实现：
 *  - 排序与 SQL 一致：`ORDER BY start_minute ASC, id ASC`（按天查询）
 *    与 `ORDER BY epoch_day ASC, start_minute ASC, id ASC`（全表）
 *  - `epochDays()` 去重后升序（对应 `SELECT DISTINCT epoch_day ... ORDER BY`）
 *  - `update` 对不存在的 id 是**静默无操作** —— 这正是 Room `@Update`
 *    的真实行为（影响 0 行，不抛异常），不是"为了测试好写"的宽松处理
 */
class FakePlanDao : PlanDao {

    private val items = mutableListOf<PlanEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value = revision.value + 1
    }

    private fun forDay(epochDay: Long): List<PlanEntity> =
        items.filter { it.epochDay == epochDay }
            .sortedWith(compareBy({ it.startMinute }, { it.id }))

    private fun all(): List<PlanEntity> =
        items.sortedWith(compareBy({ it.epochDay }, { it.startMinute }, { it.id }))

    override fun observeForDay(epochDay: Long): Flow<List<PlanEntity>> =
        revision.map { forDay(epochDay) }

    override suspend fun getForDay(epochDay: Long): List<PlanEntity> = forDay(epochDay)

    override fun observeAll(): Flow<List<PlanEntity>> = revision.map { all() }

    override suspend fun getAll(): List<PlanEntity> = all()

    override suspend fun getById(id: Long): PlanEntity? = items.find { it.id == id }

    override fun observeEpochDays(): Flow<List<Long>> =
        revision.map { items.map { it.epochDay }.distinct().sorted() }

    override suspend fun epochDays(): List<Long> =
        items.map { it.epochDay }.distinct().sorted()

    override suspend fun insert(plan: PlanEntity): Long {
        val id = nextId++
        items += plan.copy(id = id)
        bump()
        return id
    }

    override suspend fun update(plan: PlanEntity) {
        val idx = items.indexOfFirst { it.id == plan.id }
        // 与 Room @Update 一致：目标不存在时影响 0 行，不抛异常
        if (idx >= 0) {
            items[idx] = plan
            bump()
        }
    }

    override suspend fun delete(id: Long) {
        items.removeAll { it.id == id }
        bump()
    }

    override suspend fun count(): Int = items.size
}
