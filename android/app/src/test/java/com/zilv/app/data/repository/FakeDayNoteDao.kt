package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.DayNoteDao
import com.zilv.app.data.local.entity.DayNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [DayNoteDao] 的内存实现（与 [FakeHabitDao] 等同源同风格）。
 *
 * 严格按 DAO 的语义实现：
 *  - 主键是 `epoch_day`，所以 `upsert` 是**替换**而不是追加（一天最多一条）
 *  - `observeEpochDays()` / `epochDays()` 按 epochDay 升序，与 SQL 的
 *    `ORDER BY epoch_day ASC` 一致
 */
class FakeDayNoteDao : DayNoteDao {

    private val items = mutableMapOf<Long, DayNoteEntity>()
    private val revision = MutableStateFlow(0)

    private fun bump() {
        revision.value = revision.value + 1
    }

    override fun observe(epochDay: Long): Flow<DayNoteEntity?> =
        revision.map { items[epochDay] }

    override suspend fun get(epochDay: Long): DayNoteEntity? = items[epochDay]

    override suspend fun getAll(): List<DayNoteEntity> = items.values.sortedBy { it.epochDay }

    override fun observeEpochDays(): Flow<List<Long>> =
        revision.map { items.keys.sorted() }

    override suspend fun epochDays(): List<Long> = items.keys.sorted()

    override suspend fun upsert(note: DayNoteEntity) {
        items[note.epochDay] = note
        bump()
    }

    override suspend fun delete(epochDay: Long) {
        items.remove(epochDay)
        bump()
    }

    override suspend fun count(): Int = items.size
}
