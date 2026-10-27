package com.zilv.app.ui.records

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeDayNoteDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.FakePlanDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * [RecordsViewModel] 的单元测试。
 *
 * 主要盯着 v2.3 新增的一件事：**有留言的那天在月历上被标出来**
 * （用户要求："留言记录可以在日历上看见"）。
 *
 * 时间固定在 2026-10-14 16:00 (+08:00)，所以"今天"和当前月份都是确定的。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private lateinit var habitDao: FakeHabitDao
    private lateinit var checkInDao: FakeCheckInDao
    private lateinit var dayNoteDao: FakeDayNoteDao
    private lateinit var planDao: FakePlanDao
    private lateinit var habitRepo: HabitRepository
    private lateinit var dayNoteRepo: DayNoteRepository
    private lateinit var planRepo: PlanRepository
    private lateinit var viewModel: RecordsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        habitDao = FakeHabitDao()
        checkInDao = FakeCheckInDao()
        dayNoteDao = FakeDayNoteDao()
        planDao = FakePlanDao()
        habitRepo = HabitRepository(habitDao, checkInDao, clock)
        dayNoteRepo = DayNoteRepository(dayNoteDao, clock)
        planRepo = PlanRepository(planDao, clock)
        viewModel = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun millisOf(date: LocalDate, hour: Int = 9): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- 基本

    @Test
    fun `默认展示当前月`() = runTest {
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertThat(state.today).isEqualTo(today)
        assertThat(state.month.yearMonth).isEqualTo(YearMonth.of(2026, 10))
        assertThat(state.month.days.filter { it.inMonth }).hasSize(31)
    }

    @Test
    fun `打卡记录会铺到月历上`() = runTest {
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        habitRepo.checkIn(id, millisOf(today))

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        val day = fresh.uiState.value.month.days.first { it.date == today }
        assertThat(day.hasCheckIn).isTrue()
        assertThat(fresh.uiState.value.month.checkedDayCount).isEqualTo(1)
    }

    // ---------------------------------------------------------------- 留言

    @Test
    fun `没有留言时月历上没有标记`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.month.notedDayCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.month.days.none { it.hasNote }).isTrue()
    }

    @Test
    fun `有留言的那天被标记出来`() = runTest {
        dayNoteRepo.save(today, "今天全部完成了")

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        val day = fresh.uiState.value.month.days.first { it.date == today }
        assertThat(day.hasNote).isTrue()
        assertThat(fresh.uiState.value.month.notedDayCount).isEqualTo(1)
    }

    @Test
    fun `写完留言后不用手动刷新，月历自己会更新`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.month.days.none { it.hasNote }).isTrue()

        // 模拟用户在"今天"界面写完留言
        dayNoteRepo.save(today, "刚写的")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.month.days.first { it.date == today }.hasNote).isTrue()
    }

    @Test
    fun `打卡与留言在同一天可以同时标记`() = runTest {
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        habitRepo.checkIn(id, millisOf(today))
        dayNoteRepo.save(today, "又打卡又留言")

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        val day = fresh.uiState.value.month.days.first { it.date == today }
        assertThat(day.hasCheckIn).isTrue()
        assertThat(day.hasNote).isTrue()
    }

    @Test
    fun `别的月份的留言不影响本月网格`() = runTest {
        dayNoteRepo.save(LocalDate.of(2026, 9, 20), "上个月的留言")

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        assertThat(fresh.uiState.value.month.notedDayCount).isEqualTo(0)
    }

    // ---------------------------------------------------------------- 翻月

    @Test
    fun `翻到上个月后留言标记跟着走`() = runTest {
        val lastMonth = LocalDate.of(2026, 9, 20)
        dayNoteRepo.save(lastMonth, "上个月的留言")

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()
        assertThat(fresh.uiState.value.month.notedDayCount).isEqualTo(0)

        fresh.previousMonth()
        advanceUntilIdle()

        assertThat(fresh.uiState.value.month.yearMonth).isEqualTo(YearMonth.of(2026, 9))
        assertThat(fresh.uiState.value.month.days.first { it.date == lastMonth }.hasNote).isTrue()
        assertThat(fresh.uiState.value.month.notedDayCount).isEqualTo(1)
    }

    @Test
    fun `回到本月`() = runTest {
        advanceUntilIdle()
        viewModel.previousMonth()
        viewModel.goToCurrentMonth()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.month.yearMonth).isEqualTo(YearMonth.of(2026, 10))
        assertThat(viewModel.uiState.value.atCurrentMonth).isTrue()
    }

    // ------------------------------------------------------------ 规划标记

    /**
     * 用户要求："日历上可以看见当天的规划"（v2.5 的 A3）。
     *
     * 标记与打卡绿底、留言圆点**必须能同时存在**，所以这里除了"有没有标记"
     * 还要断言三者互不干扰。
     */
    @Test
    fun `有规划的那天被标记出来`() = runTest {
        // 规划只能加明天的，所以标记出现在明天那一格
        planRepo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        val day = fresh.uiState.value.month.days.first { it.date == tomorrow }
        assertThat(day.hasPlan).isTrue()
        assertThat(day.planCount).isEqualTo(1)
        // 只有规划时不该误标成"打过卡"或"写过留言"
        assertThat(day.hasCheckIn).isFalse()
        assertThat(day.hasNote).isFalse()
        assertThat(fresh.uiState.value.month.plannedDayCount).isEqualTo(1)
    }

    @Test
    fun `没有规划时月历上没有规划标记`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.month.plannedDayCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.month.days.none { it.hasPlan }).isTrue()
    }

    @Test
    fun `写完规划后不用手动刷新，月历自己会更新`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.month.days.none { it.hasPlan }).isTrue()

        planRepo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.month.days.first { it.date == tomorrow }.hasPlan).isTrue()
    }

    @Test
    fun `打卡丶留言与规划在同一天可以同时标记`() = runTest {
        // 造一条"今天"的规划（走 DAO，绕开"只能加明天"的仓库校验）
        planDao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = "今天的规划",
                epochDay = today.toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 0L
            )
        )
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        habitRepo.checkIn(id, millisOf(today))
        dayNoteRepo.save(today, "又打卡又留言又有规划")

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        val day = fresh.uiState.value.month.days.first { it.date == today }
        assertThat(day.hasCheckIn).isTrue()
        assertThat(day.hasNote).isTrue()
        assertThat(day.hasPlan).isTrue()
    }

    @Test
    fun `别的月份的规划不影响本月网格`() = runTest {
        planDao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = "上个月的",
                epochDay = LocalDate.of(2026, 9, 20).toEpochDay(),
                startMinute = 9 * 60,
                endMinute = 11 * 60,
                createdAt = 0L
            )
        )

        val fresh = RecordsViewModel(habitRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        assertThat(fresh.uiState.value.month.plannedDayCount).isEqualTo(0)

        // 翻到上个月就能看见
        fresh.previousMonth()
        advanceUntilIdle()
        assertThat(fresh.uiState.value.month.plannedDayCount).isEqualTo(1)
    }
}
