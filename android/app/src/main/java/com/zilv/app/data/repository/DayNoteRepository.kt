package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.DayNoteDao
import com.zilv.app.data.local.entity.DayNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate

/**
 * 某一天的留言。
 *
 * 领域模型只保留"这一天写了什么"，`epochDay` 与日期之间的换算由仓库负责，
 * 界面拿到的永远是 [LocalDate]，不需要自己碰 epochDay。
 */
data class DayNote(
    val date: LocalDate,
    val text: String,
    val updatedAtMillis: Long
)

/**
 * 按天留言的数据入口。
 *
 * 「一天一条」由 `day_notes` 的主键保证（见 [DayNoteEntity]），
 * 这里只负责日期换算与"空文本 = 清除"这条业务规则。
 *
 * 时间统一走注入的 [clock]，与 [HabitRepository] 口径一致 ——
 * 单测里可以把"今天"固定住。
 */
class DayNoteRepository(
    private val dayNoteDao: DayNoteDao,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    fun today(): LocalDate = LocalDate.now(clock)

    /** 观察某天的留言，没有则为 null（界面据此决定是否预填输入框）。 */
    fun observe(date: LocalDate): Flow<DayNote?> =
        dayNoteDao.observe(date.toEpochDay()).map { it?.toDomain() }

    suspend fun get(date: LocalDate): DayNote? = dayNoteDao.get(date.toEpochDay())?.toDomain()

    suspend fun getAll(): List<DayNote> = dayNoteDao.getAll().map { it.toDomain() }

    /** 有留言的全部日期 —— 月历标记只需要这个集合。 */
    fun observeNoteDates(): Flow<Set<LocalDate>> =
        dayNoteDao.observeEpochDays().map { days -> days.mapTo(HashSet()) { LocalDate.ofEpochDay(it) } }

    suspend fun noteDates(): Set<LocalDate> =
        dayNoteDao.epochDays().mapTo(HashSet()) { LocalDate.ofEpochDay(it) }

    /**
     * 保存某天的留言。
     *
     * **空文本（或纯空白）等同于清除那一天** —— 这样界面上"清除留言"和
     * "把内容删空后点保存"是同一个结果，不会留下一条 `text=""` 的幽灵记录
     * 让日历上一直挂着一个小圆点。
     *
     * @return true 表示保存了一条留言，false 表示清除（或本来就没有）
     */
    suspend fun save(date: LocalDate, text: String): Boolean {
        val trimmed = text.trim()
        val epochDay = date.toEpochDay()
        if (trimmed.isEmpty()) {
            dayNoteDao.delete(epochDay)
            return false
        }
        dayNoteDao.upsert(
            DayNoteEntity(
                epochDay = epochDay,
                text = trimmed,
                updatedAt = clock.millis()
            )
        )
        return true
    }

    suspend fun clear(date: LocalDate) {
        dayNoteDao.delete(date.toEpochDay())
    }
}

private fun DayNoteEntity.toDomain(): DayNote =
    DayNote(
        date = LocalDate.ofEpochDay(epochDay),
        text = text,
        updatedAtMillis = updatedAt
    )
