package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.CheckInDao
import com.zilv.app.data.local.dao.DailyCount
import com.zilv.app.data.local.dao.HabitDao
import com.zilv.app.data.local.entity.CheckInEntity
import com.zilv.app.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * DAO 的内存实现，用于在**纯 JVM** 上测试 [HabitRepository] 的业务逻辑。
 *
 * 为什么不用真实 Room 数据库跑单元测试：
 * 本机沙箱下 Robolectric 的 SQLite 后端无法工作 —— LEGACY 模式需要把 sqlite4java
 * 原生 DLL 解压到临时目录（被拦截），NATIVE 模式会挂起。
 *
 * 因此采用分层验证：
 *  - **仓库业务逻辑**（本文件支撑）：纯 JVM，确定性，快速，每阶段都会跑。
 *  - **Room 的 SQL 与约束**：由 Room 注解处理器在**编译期**校验（表名/列名/SQL 语法
 *    错误会直接让构建失败），运行期行为由 androidTest 里的 instrumented 测试覆盖。
 *
 * 这些 Fake 严格按 DAO 接口的语义实现（含排序、按天去重、级联清理），
 * 不做任何"为了让测试通过"的宽松处理。
 */

private val habitOrder = compareBy<HabitEntity>({ it.archived }, { it.sortOrder }, { it.id })

class FakeHabitDao : HabitDao {

    private val items = mutableListOf<HabitEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value = revision.value + 1
    }

    private fun snapshot(): List<HabitEntity> = items.sortedWith(habitOrder)

    override fun observeActive(): Flow<List<HabitEntity>> =
        revision.map { snapshot().filter { h -> !h.archived } }

    override suspend fun getActive(): List<HabitEntity> = snapshot().filter { !it.archived }

    override suspend fun getAll(): List<HabitEntity> = snapshot()

    override suspend fun getById(id: Long): HabitEntity? = items.find { it.id == id }

    override suspend fun insert(habit: HabitEntity): Long {
        val id = nextId++
        items += habit.copy(id = id)
        bump()
        return id
    }

    override suspend fun update(habit: HabitEntity) {
        val idx = items.indexOfFirst { it.id == habit.id }
        require(idx >= 0) { "update 的目标不存在: id=${habit.id}" }
        items[idx] = habit
        bump()
    }

    override suspend fun archive(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(archived = true)
            bump()
        }
    }

    override suspend fun unarchive(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(archived = false)
            bump()
        }
    }

    override suspend fun getArchived(): List<HabitEntity> =
        snapshot().filter { it.archived }

    override suspend fun delete(id: Long) {
        items.removeAll { it.id == id }
        bump()
    }

    override suspend fun count(): Int = items.size
}

class FakeCheckInDao : CheckInDao {

    private val items = mutableListOf<CheckInEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value = revision.value + 1
    }

    override suspend fun epochDaysFor(habitId: Long): List<Long> =
        items.filter { it.habitId == habitId }.map { it.epochDay }

    override fun observeEpochDays(habitId: Long): Flow<List<Long>> =
        revision.map { r ->
            items.filter { it.habitId == habitId }.map { it.epochDay }.distinct().sortedDescending()
        }

    override suspend fun forDay(habitId: Long, epochDay: Long): List<CheckInEntity> =
        items.filter { it.habitId == habitId && it.epochDay == epochDay }
            .sortedByDescending { it.checkedAtMillis }

    override suspend fun countForDay(habitId: Long, epochDay: Long): Int =
        items.count { it.habitId == habitId && it.epochDay == epochDay }

    override suspend fun countForHabit(habitId: Long): Int = items.count { it.habitId == habitId }

    /** 最近一次打卡：`checked_at` 最大的那条（与 Room 的 ORDER BY checked_at DESC LIMIT 1 同义）。 */
    override suspend fun latestForHabit(habitId: Long): CheckInEntity? =
        items.filter { it.habitId == habitId }.maxByOrNull { it.checkedAtMillis }

    override suspend fun verifiedEpochDaysFor(habitId: Long): List<Long> =
        items.filter { it.habitId == habitId && it.spotId != null }
            .map { it.epochDay }
            .distinct()
            .sortedDescending()

    override suspend fun countVerifiedForDay(habitId: Long, epochDay: Long): Int =
        items.count { it.habitId == habitId && it.epochDay == epochDay && it.spotId != null }

    override suspend fun countAll(): Int = items.size

    override suspend fun insert(checkIn: CheckInEntity): Long {
        val id = nextId++
        items += checkIn.copy(id = id)
        bump()
        return id
    }

    override suspend fun deleteForDay(habitId: Long, epochDay: Long) {
        items.removeAll { it.habitId == habitId && it.epochDay == epochDay }
        bump()
    }

    override suspend fun deleteAllForHabit(habitId: Long) {
        items.removeAll { it.habitId == habitId }
        bump()
    }

    override fun observeDailyCounts(): Flow<List<DailyCount>> =
        revision.map {
            items.groupingBy { c -> c.epochDay }
                .eachCount()
                .map { (day, cnt) -> DailyCount(day, cnt) }
                .sortedBy { it.epochDay }
        }
}