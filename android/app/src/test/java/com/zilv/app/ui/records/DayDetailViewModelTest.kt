package com.zilv.app.ui.records

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeDayNoteDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.FakeUsageDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.intercept.AppLabelSource
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
import java.time.ZoneId

/**
 * [DayDetailViewModel] 的单元测试。
 *
 * 用户要求："点击打卡日历可以观看某一天的记录，包括打卡记录和各 app 使用时长"。
 * 这里验证两件事：**打卡明细的口径正确**（尤其到点打卡的"未通过地点校验"要标出来），
 * 以及**使用时长按降序且带应用名**。
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayDetailViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedInstant = Instant.parse("2026-10-14T08:00:00Z")

    private lateinit var habitDao: FakeHabitDao
    private lateinit var checkInDao: FakeCheckInDao
    private lateinit var usageDao: FakeUsageDao
    private lateinit var dayNoteDao: FakeDayNoteDao
    private lateinit var habitRepo: HabitRepository
    private lateinit var usageRepo: UsageRepository
    private lateinit var dayNoteRepo: DayNoteRepository
    private lateinit var viewModel: DayDetailViewModel

    /** 简单的假标签源：把包名映射成固定的中文名。 */
    private class FakeLabels(private val map: Map<String, String> = emptyMap()) : AppLabelSource {
        override fun labelOf(packageName: String): String = map[packageName] ?: packageName
        override fun labelsOf(packageNames: Collection<String>): Map<String, String> =
            packageNames.associateWith { labelOf(it) }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = Clock.fixed(fixedInstant, zone)
        habitDao = FakeHabitDao()
        checkInDao = FakeCheckInDao()
        usageDao = FakeUsageDao()
        dayNoteDao = FakeDayNoteDao()
        habitRepo = HabitRepository(habitDao, checkInDao, clock)
        usageRepo = UsageRepository(usageDao, clock)
        dayNoteRepo = DayNoteRepository(dayNoteDao, clock)
        viewModel = DayDetailViewModel(
            habitRepository = habitRepo,
            usageRepository = usageRepo,
            dayNoteRepository = dayNoteRepo,
            labelResolver = FakeLabels(mapOf("com.tencent.mm" to "微信")),
            zone = zone
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun today(): LocalDate = LocalDate.of(2026, 10, 14)
    private fun todayEpochDay(): Long = today().toEpochDay()

    private fun at(hour: Int, minute: Int = 0): Long =
        today().atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------- 打卡

    @Test
    fun `未打卡的活跃习惯也会列出并标注未打卡`() = runTest {
        habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        val record = viewModel.uiState.value.records.single()
        assertThat(record.habitName).isEqualTo("早起")
        assertThat(record.checked).isFalse()
        assertThat(record.statusLabel).isEqualTo("未打卡")
    }

    @Test
    fun `地点打卡显示时刻与已打卡`() = runTest {
        val id = habitRepo.save(Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L))
        habitRepo.checkIn(
            habitId = id,
            checkedAtMillis = at(8, 30),
            status = CheckInStatus.ON_TIME,
            lat = 30.0, lng = 120.0, spotId = 7L
        )

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        val record = viewModel.uiState.value.records.single()
        assertThat(record.checked).isTrue()
        assertThat(record.times).containsExactly("08:30")
        assertThat(record.locationVerified).isTrue()
        assertThat(record.statusLabel).isEqualTo("已打卡")
    }

    @Test
    fun `异常解锁标注未通过地点校验且不计入天数`() = runTest {
        val id = habitRepo.save(Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L))
        habitRepo.checkIn(
            habitId = id,
            checkedAtMillis = at(9),
            status = CheckInStatus.ABNORMAL_UNLOCK,
            lat = 30.0, lng = 120.0, spotId = 7L
        )

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        val record = viewModel.uiState.value.records.single()
        assertThat(record.checked).isTrue()
        assertThat(record.locationVerified).isFalse()
        assertThat(record.statusLabel).contains("未通过地点校验")
    }

    @Test
    fun `手动勾选与地点打卡能区分开`() = runTest {
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        habitRepo.checkIn(habitId = id, checkedAtMillis = at(7), status = CheckInStatus.MANUAL)

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.records.single().statusLabel).isEqualTo("手动勾选")
    }

    @Test
    fun `同一天多次打卡都会列出来`() = runTest {
        val id = habitRepo.save(Habit(name = "喝水", type = HabitType.SIMPLE))
        habitRepo.checkIn(habitId = id, checkedAtMillis = at(9))
        habitRepo.checkIn(habitId = id, checkedAtMillis = at(14))

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        val record = viewModel.uiState.value.records.single()
        assertThat(record.count).isEqualTo(2)
        assertThat(record.times).containsExactly("09:00", "14:00").inOrder()
    }

    @Test
    fun `已归档习惯只在当天有打卡时出现`() = runTest {
        val archivedWithCheckIn = habitRepo.save(Habit(name = "旧习惯", type = HabitType.SIMPLE))
        habitRepo.checkIn(habitId = archivedWithCheckIn, checkedAtMillis = at(10))
        habitRepo.archive(archivedWithCheckIn)

        val archivedWithout = habitRepo.save(Habit(name = "弃用习惯", type = HabitType.SIMPLE))
        habitRepo.archive(archivedWithout)

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        val names = viewModel.uiState.value.records.map { it.habitName }
        assertThat(names).contains("旧习惯")
        assertThat(names).doesNotContain("弃用习惯")
        assertThat(viewModel.uiState.value.records.first { it.habitName == "旧习惯" }.archived).isTrue()
    }

    @Test
    fun `checkedCount 只数真正打过卡的`() = runTest {
        val a = habitRepo.save(Habit(name = "A", type = HabitType.SIMPLE))
        habitRepo.save(Habit(name = "B", type = HabitType.SIMPLE))
        habitRepo.checkIn(habitId = a, checkedAtMillis = at(9))

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.records).hasSize(2)
        assertThat(viewModel.uiState.value.checkedCount).isEqualTo(1)
    }

    // ------------------------------------------------------------ 使用时长

    @Test
    fun `使用时长按降序并解析出应用名`() = runTest {
        usageRepo.recordSegment("com.tencent.mm", at(9), at(10))   // 60 分钟
        usageRepo.recordSegment("com.android.chrome", at(11), at(11, 20)) // 20 分钟

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        val entries = viewModel.uiState.value.usageEntries
        assertThat(entries.map { it.packageName })
            .containsExactly("com.tencent.mm", "com.android.chrome").inOrder()
        assertThat(entries.first().label).isEqualTo("微信")
        // 未登记的包名回退为包名本身
        assertThat(entries[1].label).isEqualTo("com.android.chrome")
        assertThat(viewModel.uiState.value.totalUsageMillis).isEqualTo(80 * 60_000L)
    }

    @Test
    fun `没有使用记录时列表为空`() = runTest {
        habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.usageEntries).isEmpty()
        assertThat(viewModel.uiState.value.totalUsageMillis).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- 空态

    @Test
    fun `完全空的一天 hasAnything 为假`() = runTest {
        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasAnything).isFalse()
        assertThat(viewModel.uiState.value.records).isEmpty()
    }

    @Test
    fun `日期标签与查询日期一致`() = runTest {
        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.date).isEqualTo(today())
        assertThat(viewModel.uiState.value.dateLabel).isEqualTo("2026 年 10 月 14 日")
    }

    @Test
    fun `别的日期的打卡不会混进来`() = runTest {
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        val yesterday = today().minusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        habitRepo.checkIn(habitId = id, checkedAtMillis = yesterday)

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.records.single().checked).isFalse()
    }

    // ---------------------------------------------------------------- 留言
    //
    // 用户要求："点「所有任务已完成」可以留言，留言记录可以在日历上看见。"

    @Test
    fun `没有留言时 note 为 null`() = runTest {
        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.note).isNull()
        assertThat(viewModel.uiState.value.hasNote).isFalse()
    }

    @Test
    fun `那天的留言会被读出来`() = runTest {
        dayNoteRepo.save(today(), "今天全部做完了，很稳")

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.note).isEqualTo("今天全部做完了，很稳")
        assertThat(viewModel.uiState.value.hasNote).isTrue()
    }

    @Test
    fun `别的日期的留言不会混进来`() = runTest {
        dayNoteRepo.save(today().minusDays(1), "昨天的留言")

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.note).isNull()
    }

    @Test
    fun `只有留言的一天 hasAnything 为真`() = runTest {
        // 打卡记录为空、使用时长为空，但那天写了留言 —— 不该显示"这一天没有记录"
        dayNoteRepo.save(today(), "只写了留言")

        viewModel.load(todayEpochDay())
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.records).isEmpty()
        assertThat(viewModel.uiState.value.usageEntries).isEmpty()
        assertThat(viewModel.uiState.value.hasAnything).isTrue()
    }
}
