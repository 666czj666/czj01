package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.CheckInDao
import com.zilv.app.data.local.dao.HabitDao
import com.zilv.app.data.mapper.toDomain
import com.zilv.app.data.mapper.toEntity
import com.zilv.app.domain.habit.StreakCalculator
import com.zilv.app.domain.habit.StreakStats
import com.zilv.app.domain.model.CheckIn
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.Habit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 首页「今日习惯」条目。 */
data class TodayHabit(
    val habit: Habit,
    val checkedToday: Boolean,
    val stats: StreakStats
)

/**
 * 习惯与打卡的数据入口。
 *
 * 所有跟"今天是哪天"相关的判定都通过注入的 [clock] 完成，
 * 这样单元测试里可以把时间固定住，结果是确定的。
 */
class HabitRepository(
    private val habitDao: HabitDao,
    private val checkInDao: CheckInDao,
    /**
     * 注入的时钟。**公开**只读：首页的「当前 / 接下来」聚焦卡片要按
     * "现在几点"挑习惯，而它必须和"今天是哪天"用同一个时钟
     * （否则单测固定了日期却挑不出确定的结果）。仍然只有仓库能改它。
     */
    val clock: Clock = Clock.systemDefaultZone()
) {

    private val zone: ZoneId get() = clock.zone

    fun today(): LocalDate = LocalDate.now(clock)

    // ------------------------------------------------------------------ 习惯

    fun observeActiveHabits(): Flow<List<Habit>> =
        habitDao.observeActive().map { list -> list.map { it.toDomain() } }

    suspend fun getActiveHabits(): List<Habit> = habitDao.getActive().map { it.toDomain() }

    suspend fun getHabit(id: Long): Habit? = habitDao.getById(id)?.toDomain()

    /** 新增或更新；返回习惯 id。 */
    suspend fun save(habit: Habit): Long {
        require(habit.isValid) { "习惯不合法：需要名称，且到点打卡类型必须选择打卡点" }
        return if (habit.id == 0L) {
            habitDao.insert(habit.toEntity())
        } else {
            habitDao.update(habit.toEntity())
            habit.id
        }
    }

    suspend fun archive(id: Long) = habitDao.archive(id)

    /** 取消归档，让习惯重新回到今日列表。 */
    suspend fun unarchive(id: Long) = habitDao.unarchive(id)

    /** 已归档的习惯（在管理页单独一组展示，可恢复或彻底删除）。 */
    suspend fun getArchivedHabits(): List<Habit> = habitDao.getArchived().map { it.toDomain() }

    /**
     * **彻底删除**习惯，连同它的全部打卡记录。**不可恢复。**
     *
     * 与 [archive] 的区别：
     *  - 归档：从今日列表隐藏，数据保留，可以 [unarchive] 恢复
     *  - 删除：习惯与历史记录一并抹掉
     *
     * `check_ins` 表上声明了 `ON DELETE CASCADE`，但这里**不依赖数据库外键**：
     * 外键约束可以在数据库层被关闭，而"删习惯必须删掉它的打卡"是业务规则。
     * 显式删除让这条规则成为可测试的代码路径，而不是一个隐式的数据库行为。
     */
    suspend fun deletePermanently(id: Long) {
        checkInDao.deleteAllForHabit(id)
        habitDao.delete(id)
    }

    // ------------------------------------------------------------------ 打卡

    /** 打卡（同一天可重复调用；由调用方决定是否先查重）。 */
    suspend fun checkIn(
        habitId: Long,
        checkedAtMillis: Long = clock.millis(),
        status: CheckInStatus = CheckInStatus.MANUAL,
        ruleId: Long? = null,
        sessionId: Long? = null,
        lat: Double? = null,
        lng: Double? = null,
        accuracyM: Float? = null,
        distanceM: Double? = null,
        spotId: Long? = null,
        photoPath: String? = null,
        note: String? = null
    ): Long {
        val epochDay = CheckIn.epochDayOf(checkedAtMillis, zone)
        return checkInDao.insert(
            CheckIn(
                habitId = habitId,
                ruleId = ruleId,
                sessionId = sessionId,
                checkedAtMillis = checkedAtMillis,
                epochDay = epochDay,
                status = status,
                lat = lat,
                lng = lng,
                accuracyM = accuracyM,
                distanceM = distanceM,
                spotId = spotId,
                photoPath = photoPath,
                note = note
            ).toEntity()
        )
    }

    suspend fun undoCheckIn(habitId: Long, date: LocalDate) =
        checkInDao.deleteForDay(habitId, date.toEpochDay())

    suspend fun isCheckedOn(habitId: Long, date: LocalDate): Boolean =
        checkInDao.countForDay(habitId, date.toEpochDay()) > 0

    /** 该习惯所有有打卡的日期（同一天多次只算一天）。 */
    suspend fun checkedDates(habitId: Long): Set<LocalDate> =
        checkInDao.epochDaysFor(habitId).mapTo(HashSet()) { LocalDate.ofEpochDay(it) }

    // ------------------------------------------------- 「到点打卡」的地点在证

    /**
     * 该习惯的连续天数是否**必须**有地点验证。
     *
     * v2.2 起判定依据是**打卡条件**（[Habit.checkInCondition]）而不是裸的
     * `type == LOCATION`：只有"指定地点"且真的选了打卡点，才要求地点在证。
     * `LOCATION` 但没选点、以及 `SIMPLE` / `TIMED`，都按"无条件打卡"处理 ——
     * 与 [Habit.checkInCondition] 的口径完全一致，不再有两套判断。
     *
     * PRD 语义：需要地点在证时，在首页点一下就算完成的话，地点校验就形同虚设了。
     */
    fun requiresLocationProof(habit: Habit): Boolean =
        habit.checkInCondition == CheckInCondition.LOCATION

    /**
     * 只统计**地点已验证**的打卡日期。
     *
     * 判定标记是 `spot_id`：只有从打卡点流程写入的记录才带 spotId，
     * 首页手动勾选永远是 null。
     */
    suspend fun verifiedCheckedDates(habitId: Long): Set<LocalDate> =
        checkInDao.verifiedEpochDaysFor(habitId).mapTo(HashSet()) { LocalDate.ofEpochDay(it) }

    /** 按习惯类型取"算数"的打卡日期集合。 */
    suspend fun effectiveCheckedDates(habit: Habit): Set<LocalDate> =
        if (requiresLocationProof(habit)) verifiedCheckedDates(habit.id) else checkedDates(habit.id)

    /** 今天是否已（按要求方式）打卡。 */
    suspend fun isCheckedToday(habit: Habit, date: LocalDate): Boolean =
        if (requiresLocationProof(habit)) {
            checkInDao.countVerifiedForDay(habit.id, date.toEpochDay()) > 0
        } else {
            isCheckedOn(habit.id, date)
        }

    suspend fun checkInsOn(habitId: Long, date: LocalDate): List<CheckIn> =
        checkInDao.forDay(habitId, date.toEpochDay()).map { it.toDomain() }

    /** 某习惯在某天的打卡条数（用于日历热力图的明细）。 */
    suspend fun checkInCountOn(habitId: Long, date: LocalDate): Int =
        checkInDao.countForDay(habitId, date.toEpochDay())

    // ------------------------------------------------------------------ 统计

    /**
     * 连续统计。
     *
     * ⚠️ 「到点打卡」习惯只统计**地点已验证**的打卡 —— 用户反馈：
     * 这类习惯原先只要在首页点一下就能加连续天数，等于绕过了地点校验。
     */
    suspend fun streakFor(habit: Habit, today: LocalDate = today()): StreakStats =
        StreakCalculator.stats(habit.frequency, effectiveCheckedDates(habit), today)

    /** 首页今日列表：习惯 + 今天是否已打卡 + 连续统计。 */
    suspend fun todayList(today: LocalDate = today()): List<TodayHabit> =
        toTodayHabits(getActiveHabits(), today)

    /**
     * 把一批习惯组装成今日条目。
     *
     * 单独抽出来是因为首页要**响应式**刷新：ViewModel 观察
     * [observeActiveHabits] 与 [observeDailyCheckInCounts] 两个 Flow，
     * 变化时拿最新的习惯列表调这里，而不是重新查一次库。
     */
    suspend fun toTodayHabits(habits: List<Habit>, today: LocalDate): List<TodayHabit> =
        habits.map { habit ->
            TodayHabit(
                habit = habit,
                checkedToday = isCheckedToday(habit, today),
                stats = streakFor(habit, today)
            )
        }

    /** 全部打卡的按天聚合，供热力图使用。 */
    fun observeDailyCheckInCounts(): Flow<Map<LocalDate, Int>> =
        checkInDao.observeDailyCounts()
            .map { rows -> rows.associate { LocalDate.ofEpochDay(it.epochDay) to it.count } }

    /** 同上，但只取当前快照一次（ViewModel 里的一次性刷新用）。 */
    suspend fun dailyCheckInCountsOnce(): Map<LocalDate, Int> =
        observeDailyCheckInCounts().first()
}
