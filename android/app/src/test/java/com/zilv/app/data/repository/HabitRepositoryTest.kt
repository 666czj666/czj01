package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.habit.HabitFrequencyCodec
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [HabitRepository] 的单元测试（纯 JVM，无需设备，无需 Robolectric）。
 *
 * 时间被固定在 2026-10-14 16:00 (+08:00)，因此"今天"始终是 2026-10-14。
 * DAO 用 [FakeHabitDao] / [FakeCheckInDao] 的内存实现；真实 Room 的运行期行为
 * 由 `androidTest` 下的 instrumented 测试覆盖（见 HabitDaoTest）。
 */
class HabitRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)

    private lateinit var habitDao: FakeHabitDao
    private lateinit var checkInDao: FakeCheckInDao
    private lateinit var repo: HabitRepository

    @Before
    fun setUp() {
        habitDao = FakeHabitDao()
        checkInDao = FakeCheckInDao()
        // 2026-10-14T08:00:00Z == 2026-10-14 16:00 +08:00
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        repo = HabitRepository(habitDao = habitDao, checkInDao = checkInDao, clock = clock)
    }

    private fun millisOf(date: LocalDate, hour: Int = 9): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun newDailyHabit(name: String = "早起"): Habit {
        val id = repo.save(Habit(name = name, type = HabitType.SIMPLE))
        return repo.getHabit(id)!!
    }

    // ---------------------------------------------------------------- 基础 CRUD

    @Test
    fun `保存并读回习惯`() = runTest {
        val id = repo.save(
            Habit(
                name = "到图书馆",
                type = HabitType.LOCATION,
                spotId = 1L,
                remindTimes = listOf(LocalTime.of(7, 30))
            )
        )
        val loaded = repo.getHabit(id)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.name).isEqualTo("到图书馆")
        assertThat(loaded.type).isEqualTo(HabitType.LOCATION)
        assertThat(loaded.spotId).isEqualTo(1L)
        assertThat(loaded.remindTimes).containsExactly(LocalTime.of(7, 30))
    }

    @Test
    fun `更新习惯`() = runTest {
        val habit = newDailyHabit()
        repo.save(habit.copy(name = "早起（改）", sortOrder = 5))
        val loaded = repo.getHabit(habit.id)!!
        assertThat(loaded.name).isEqualTo("早起（改）")
        assertThat(loaded.sortOrder).isEqualTo(5)
        assertThat(repo.getActiveHabits()).hasSize(1)
    }

    @Test
    fun `归档后不再出现在活跃列表`() = runTest {
        val habit = newDailyHabit()
        repo.archive(habit.id)
        assertThat(repo.getActiveHabits()).isEmpty()
        // 但记录仍在库里
        assertThat(repo.getHabit(habit.id)).isNotNull()
    }

    @Test
    fun `删除习惯会同时删除其打卡记录`() = runTest {
        val habit = newDailyHabit()
        repo.checkIn(habit.id, millisOf(today))
        assertThat(repo.checkInCountOn(habit.id, today)).isEqualTo(1)

        repo.deletePermanently(habit.id)

        assertThat(repo.getHabit(habit.id)).isNull()
        assertThat(checkInDao.countAll()).isEqualTo(0)
    }

    @Test
    fun `删除习惯不会影响其它习惯的打卡记录`() = runTest {
        val a = newDailyHabit("A")
        val b = newDailyHabit("B")
        repo.checkIn(a.id, millisOf(today))
        repo.checkIn(b.id, millisOf(today))

        repo.deletePermanently(a.id)

        assertThat(checkInDao.countAll()).isEqualTo(1)
        assertThat(repo.isCheckedOn(b.id, today)).isTrue()
    }

    @Test
    fun `保存不合法的习惯会抛异常`() = runTest {
        val bad = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = null)
        assertThat(bad.isValid).isFalse()
        var threw = false
        try {
            repo.save(bad)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test
    fun `空名称不合法`() {
        assertThat(Habit(name = "   ").isValid).isFalse()
        assertThat(Habit(name = "早起").isValid).isTrue()
    }

    // ------------------------------------------------------------------ 打卡

    @Test
    fun `打卡后当天标记为已打卡`() = runTest {
        val habit = newDailyHabit()
        assertThat(repo.isCheckedOn(habit.id, today)).isFalse()

        repo.checkIn(habit.id, millisOf(today))

        assertThat(repo.isCheckedOn(habit.id, today)).isTrue()
        assertThat(repo.checkInCountOn(habit.id, today)).isEqualTo(1)
    }

    @Test
    fun `同一天多次打卡 - 条数增加但日期集合只算一天`() = runTest {
        val habit = newDailyHabit()
        repo.checkIn(habit.id, millisOf(today, 9))
        repo.checkIn(habit.id, millisOf(today, 20), status = CheckInStatus.LATE)

        assertThat(repo.checkInCountOn(habit.id, today)).isEqualTo(2)
        assertThat(repo.checkedDates(habit.id)).hasSize(1)

        val records = repo.checkInsOn(habit.id, today)
        assertThat(records).hasSize(2)
        // 按 checked_at 倒序
        assertThat(records.first().status).isEqualTo(CheckInStatus.LATE)
    }

    @Test
    fun `撤回打卡`() = runTest {
        val habit = newDailyHabit()
        repo.checkIn(habit.id, millisOf(today))
        repo.checkIn(habit.id, millisOf(today.minusDays(1)))
        assertThat(repo.checkedDates(habit.id)).hasSize(2)

        repo.undoCheckIn(habit.id, today)

        assertThat(repo.isCheckedOn(habit.id, today)).isFalse()
        assertThat(repo.checkedDates(habit.id)).containsExactly(today.minusDays(1))
    }

    @Test
    fun `打卡记录按注入时钟的时区归属日期`() = runTest {
        val habit = newDailyHabit()
        // 2026-10-14 23:30 +08:00，仍是 10-14
        repo.checkIn(habit.id, today.atTime(23, 30).atZone(zone).toInstant().toEpochMilli())
        assertThat(repo.checkedDates(habit.id)).containsExactly(today)
    }

    @Test
    fun `打卡记录带地理信息时完整保存`() = runTest {
        val habit = newDailyHabit()
        repo.checkIn(
            habit.id,
            millisOf(today),
            status = CheckInStatus.ON_TIME,
            lat = 30.1234,
            lng = 120.5678,
            accuracyM = 12.5f,
            distanceM = 34.0,
            spotId = 42L,
            note = "到得早"
        )
        val record = repo.checkInsOn(habit.id, today).single()
        assertThat(record.lat).isEqualTo(30.1234)
        assertThat(record.accuracyM).isEqualTo(12.5f)
        assertThat(record.distanceM).isEqualTo(34.0)
        assertThat(record.spotId).isEqualTo(42L)
        assertThat(record.note).isEqualTo("到得早")
    }

    // ------------------------------------------------------------------ 统计

    @Test
    fun `连续三天打卡 - 当前连续为3`() = runTest {
        val habit = newDailyHabit()
        listOf(2L, 1L, 0L).forEach { back ->
            repo.checkIn(habit.id, millisOf(today.minusDays(back)))
        }
        val stats = repo.streakFor(habit, today)
        assertThat(stats.current).isEqualTo(3)
        assertThat(stats.longest).isEqualTo(3)
        assertThat(stats.total).isEqualTo(3)
    }

    @Test
    fun `今天未打卡不打断连续`() = runTest {
        val habit = newDailyHabit()
        listOf(2L, 1L).forEach { back -> repo.checkIn(habit.id, millisOf(today.minusDays(back))) }
        assertThat(repo.streakFor(habit, today).current).isEqualTo(2)
    }

    @Test
    fun `首页今日列表包含打卡状态与连续数`() = runTest {
        val a = newDailyHabit("早起")
        val b = repo.getHabit(repo.save(Habit(name = "读书")))!!
        repo.checkIn(a.id, millisOf(today))

        val list = repo.todayList(today)
        assertThat(list).hasSize(2)

        val first = list.first { it.habit.id == a.id }
        assertThat(first.checkedToday).isTrue()
        assertThat(first.stats.current).isEqualTo(1)

        val second = list.first { it.habit.id == b.id }
        assertThat(second.checkedToday).isFalse()
        assertThat(second.stats.current).isEqualTo(0)
    }

    @Test
    fun `旧每周N次数据迁移成指定星期后，连续数按计划日算`() = runTest {
        // v2.14 改写：原来这条是 `每周N次习惯的连续数走周维度`，
        // 断言 `WeeklyTimes(3)` 的连续数按"达标周数"算（=2）。
        // 用户删除该频率后那套算法一并删除，归类为**测试错（旧规格）**。
        // 换成**旧数据真实路径**：`WEEKLY:3` 解码成「指定星期 一/二/三」，
        // 连续数按"计划日"算 —— 这也证明了旧用户数据不会失去意义。
        val habit = repo.getHabit(
            repo.save(
                Habit(
                    name = "健身",
                    frequency = HabitFrequencyCodec.legacyWeeklyTimesToSpecificDays("3")
                )
            )
        )!!

        // 10-12(一) 10-13(二) 10-14(三) 是计划日
        listOf(12, 13, 14).forEach { day ->
            repo.checkIn(habit.id, millisOf(LocalDate.of(2026, 10, day)))
        }
        // 10-05(一) 10-06(二) 10-07(三) 也是计划日
        listOf(5, 6, 7).forEach { day ->
            repo.checkIn(habit.id, millisOf(LocalDate.of(2026, 10, day)))
        }

        // 6 个连续计划日；再往前 09-30(三) 是计划日但没打卡 -> 断
        assertThat(repo.streakFor(habit, today).current).isEqualTo(6)
    }

    @Test
    fun `指定星期习惯 - 只在计划日打卡也能保持连续`() = runTest {
        val habit = repo.getHabit(
            repo.save(
                Habit(
                    name = "周一三五跑步",
                    frequency = HabitFrequency.SpecificDays(
                        setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)
                    )
                )
            )
        )!!
        // 10-14 是周三；10-12 周一、10-09 周五
        listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14))
            .forEach { repo.checkIn(habit.id, millisOf(it)) }

        assertThat(repo.streakFor(habit, today).current).isEqualTo(3)
    }

    @Test
    fun `热力图数据按天聚合`() = runTest {
        val a = newDailyHabit("早起")
        val b = repo.getHabit(repo.save(Habit(name = "读书")))!!
        repo.checkIn(a.id, millisOf(today))
        repo.checkIn(b.id, millisOf(today))
        repo.checkIn(a.id, millisOf(today.minusDays(1)))

        val counts = repo.observeDailyCheckInCounts().first()
        assertThat(counts[today]).isEqualTo(2)
        assertThat(counts[today.minusDays(1)]).isEqualTo(1)
    }

    @Test
    fun `活跃习惯列表按 sortOrder 排序`() = runTest {
        repo.save(Habit(name = "第三", sortOrder = 3))
        repo.save(Habit(name = "第一", sortOrder = 1))
        repo.save(Habit(name = "第二", sortOrder = 2))

        val names = repo.getActiveHabits().map { it.name }
        assertThat(names).containsExactly("第一", "第二", "第三").inOrder()
    }

    @Test
    fun `归档的习惯不影响今日列表`() = runTest {
        val a = newDailyHabit("保留")
        val b = newDailyHabit("归档掉")
        repo.archive(b.id)

        val list = repo.todayList(today)
        assertThat(list.map { it.habit.id }).containsExactly(a.id)
    }

    // ---------------------------------------------------------- 归档与删除

    @Test
    fun `归档后从今日列表消失并进入已归档组`() = runTest {
        val id = repo.save(Habit(name = "早起"))
        repo.archive(id)

        assertThat(repo.getActiveHabits()).isEmpty()
        assertThat(repo.getArchivedHabits().map { it.name }).containsExactly("早起")
    }

    @Test
    fun `取消归档后重新回到今日列表`() = runTest {
        val id = repo.save(Habit(name = "早起"))
        repo.archive(id)
        repo.unarchive(id)

        assertThat(repo.getActiveHabits().map { it.name }).containsExactly("早起")
        assertThat(repo.getArchivedHabits()).isEmpty()
    }

    @Test
    fun `彻底删除会连打卡记录一起清掉`() = runTest {
        val id = repo.save(Habit(name = "早起"))
        repo.checkIn(id)
        assertThat(repo.isCheckedOn(id, repo.today())).isTrue()

        repo.deletePermanently(id)

        assertThat(repo.getActiveHabits()).isEmpty()
        assertThat(repo.getArchivedHabits()).isEmpty()
        assertThat(repo.checkedDates(id)).isEmpty()
    }

    @Test
    fun `删除不存在的习惯不会抛异常`() = runTest {
        repo.deletePermanently(9999L)
        assertThat(repo.getActiveHabits()).isEmpty()
    }

    @Test
    fun `删除一个习惯不影响其它习惯的打卡记录`() = runTest {
        val a = repo.save(Habit(name = "A"))
        val b = repo.save(Habit(name = "B"))
        repo.checkIn(a)
        repo.checkIn(b)

        repo.deletePermanently(a)

        assertThat(repo.checkedDates(a)).isEmpty()
        assertThat(repo.checkedDates(b)).isNotEmpty()
        assertThat(repo.getActiveHabits().map { it.name }).containsExactly("B")
    }

    // -------------------------------------- 「到点打卡」必须地点验证才加天数
    //
    // 用户要求："习惯里的到点打卡习惯改为检测地点打卡后才能加连续天数"。
    // 原先这类习惯在首页点一下就能加连续天数，地点校验形同虚设。

    @Test
    fun `requiresLocationProof 只对打卡条件为指定地点的习惯为真`() {
        // v2.2：判定依据从裸的 `type == LOCATION` 改成**打卡条件**
        // （`type == LOCATION && spotId != null`）。差别就在"LOCATION 但没选点"
        // 这一种：它没有可校验的地点，因此按"无条件打卡"处理 ——
        // 否则用户会有一条既打不了卡、又点不了勾的习惯。
        assertThat(
            repo.requiresLocationProof(Habit(name = "x", type = HabitType.LOCATION, spotId = 7L))
        ).isTrue()
        assertThat(
            repo.requiresLocationProof(Habit(name = "x", type = HabitType.LOCATION, spotId = null))
        ).isFalse()
        assertThat(repo.requiresLocationProof(Habit(name = "x", type = HabitType.SIMPLE))).isFalse()
        assertThat(repo.requiresLocationProof(Habit(name = "x", type = HabitType.TIMED))).isFalse()
    }

    @Test
    fun `打卡条件为无的习惯在首页点一下就算打卡`() = runTest {
        // 「无」= 什么时候都能打卡（室内也能用）
        val habit = Habit(name = "早起", type = HabitType.SIMPLE)
        val id = repo.save(habit)

        repo.checkIn(id, status = CheckInStatus.MANUAL)

        val saved = repo.getHabit(id)!!
        assertThat(saved.checkInCondition).isEqualTo(CheckInCondition.NONE)
        assertThat(repo.isCheckedToday(saved, repo.today())).isTrue()
        assertThat(repo.streakFor(saved, repo.today()).current).isEqualTo(1)
    }

    @Test
    fun `打卡条件为指定地点时普通打卡不计入连续天数`() = runTest {
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        val id = repo.save(habit)

        repo.checkIn(id, status = CheckInStatus.MANUAL)

        val saved = repo.getHabit(id)!!
        assertThat(saved.checkInCondition).isEqualTo(CheckInCondition.LOCATION)
        assertThat(repo.isCheckedToday(saved, repo.today())).isFalse()
    }

    @Test
    fun `到点打卡习惯的手动勾选不计入连续天数`() = runTest {
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        val id = repo.save(habit)

        repo.checkIn(id, status = CheckInStatus.MANUAL)

        val saved = repo.getHabit(id)!!
        assertThat(repo.streakFor(saved, repo.today()).current).isEqualTo(0)
        assertThat(repo.effectiveCheckedDates(saved)).isEmpty()
        assertThat(repo.isCheckedToday(saved, repo.today())).isFalse()
    }

    @Test
    fun `到点打卡习惯的地点打卡计入连续天数`() = runTest {
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        val id = repo.save(habit)

        repo.checkIn(
            habitId = id,
            status = CheckInStatus.ON_TIME,
            lat = 30.0,
            lng = 120.0,
            accuracyM = 10f,
            distanceM = 5.0,
            spotId = 7L
        )

        val saved = repo.getHabit(id)!!
        assertThat(repo.streakFor(saved, repo.today()).current).isEqualTo(1)
        assertThat(repo.isCheckedToday(saved, repo.today())).isTrue()
    }

    @Test
    fun `到点打卡习惯_手动与地点混着算也只认地点那次`() = runTest {
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        val id = repo.save(habit)

        repo.checkIn(id, status = CheckInStatus.MANUAL)
        repo.checkIn(id, status = CheckInStatus.ON_TIME, lat = 30.0, lng = 120.0, spotId = 7L)

        val saved = repo.getHabit(id)!!
        // 同一天只算一天，且这一天是"地点已验证"
        assertThat(repo.effectiveCheckedDates(saved)).hasSize(1)
        assertThat(repo.streakFor(saved, repo.today()).current).isEqualTo(1)
    }

    @Test
    fun `纯打卡习惯的手动勾选照常计入`() = runTest {
        val habit = Habit(name = "早起", type = HabitType.SIMPLE)
        val id = repo.save(habit)

        repo.checkIn(id, status = CheckInStatus.MANUAL)

        val saved = repo.getHabit(id)!!
        assertThat(repo.streakFor(saved, repo.today()).current).isEqualTo(1)
        assertThat(repo.isCheckedToday(saved, repo.today())).isTrue()
    }

    @Test
    fun `软删除的标记是 spotId 而不是坐标`() = runTest {
        // 只要带了 spotId 就算地点已验证 —— 这是流程写入的标记，
        // 手动勾选永远不带 spotId
        val habit = Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        val id = repo.save(habit)

        repo.checkIn(id, status = CheckInStatus.ON_TIME, spotId = 7L)
        assertThat(repo.isCheckedToday(repo.getHabit(id)!!, repo.today())).isTrue()
    }
}