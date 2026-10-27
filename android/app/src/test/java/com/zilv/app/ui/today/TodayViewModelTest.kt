package com.zilv.app.ui.today

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.FakeAllowAppDao
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeDayNoteDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.FakeLockRuleDao
import com.zilv.app.data.repository.FakePlanDao
import com.zilv.app.data.repository.FakeRuleAllowAppDao
import com.zilv.app.data.repository.FakeRuleCheckInDao
import com.zilv.app.data.repository.FakeSpotDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.habit.NowNextPicker
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.domain.model.Spot
import com.zilv.app.domain.today.AgendaItem
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * [TodayViewModel] 的单元测试。
 *
 * **本文件的第一组用例是为了锁死一个真实 bug 而写的**：
 * 用户反馈「无法添加习惯，保存后今天界面无显示」。
 *
 * 根因是 `refresh()` 只在 `init` 里跑一次，而从「新建习惯」返回时
 * ViewModel 被导航栈保留、`init` 不再执行，列表永远停在旧状态。
 * 修复方式是改成 Flow 观察。因此这里的关键断言是：
 * **保存之后不调用任何 refresh，列表必须自己更新**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var habitDao: FakeHabitDao
    private lateinit var checkInDao: FakeCheckInDao
    private lateinit var dayNoteDao: FakeDayNoteDao
    private lateinit var planDao: FakePlanDao
    private lateinit var lockRuleDao: FakeLockRuleDao
    private lateinit var habitRepo: HabitRepository
    private lateinit var dayNoteRepo: DayNoteRepository
    private lateinit var lockRepo: LockRuleRepository
    private lateinit var planRepo: PlanRepository
    private lateinit var spotRepo: SpotRepository
    private lateinit var viewModel: TodayViewModel

    /** 即时锁机是否通知过前台服务重算（v2.12）。 */
    private var lockStateChangedCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        habitDao = FakeHabitDao()
        checkInDao = FakeCheckInDao()
        dayNoteDao = FakeDayNoteDao()
        planDao = FakePlanDao()
        lockRuleDao = FakeLockRuleDao()
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        habitRepo = HabitRepository(habitDao, checkInDao, clock)
        dayNoteRepo = DayNoteRepository(dayNoteDao, clock)
        planRepo = PlanRepository(planDao, clock)
        lockRepo = LockRuleRepository(
            lockRuleDao,
            FakeAllowAppDao(),
            FakeRuleAllowAppDao(),
            FakeRuleCheckInDao(),
            checkInDao,
            clock
        )
        spotRepo = SpotRepository(FakeSpotDao(), FakeRuleCheckInDao(), clock)
        lockStateChangedCount = 0
        viewModel = TodayViewModel(habitRepo, lockRepo, dayNoteRepo, planRepo) {
            lockStateChangedCount++
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun simpleHabit(name: String = "早起") = Habit(
        name = name,
        type = HabitType.SIMPLE,
        frequency = HabitFrequency.Daily
    )

    // -------------------------------------------------- 回归：保存后首页要显示

    @Test
    fun `初始时今日列表为空`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).isEmpty()
        assertThat(viewModel.uiState.value.loading).isFalse()
    }

    @Test
    fun `保存习惯后不调用 refresh 列表也要自动出现它`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).isEmpty()

        // 模拟「新建习惯」页保存
        habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()

        // 关键：这里**没有**调用 viewModel.refresh()
        assertThat(viewModel.uiState.value.items).hasSize(1)
        assertThat(viewModel.uiState.value.items.first().habit.name).isEqualTo("早起")
        assertThat(viewModel.uiState.value.totalCount).isEqualTo(1)
    }

    @Test
    fun `连续新增多个习惯都会自动出现`() = runTest {
        advanceUntilIdle()
        listOf("早起", "读书", "跑步").forEach { name ->
            habitRepo.save(simpleHabit(name))
            advanceUntilIdle()
        }
        assertThat(viewModel.uiState.value.items.map { it.habit.name })
            .containsExactly("早起", "读书", "跑步").inOrder()
    }

    @Test
    fun `归档习惯后自动从列表消失`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).hasSize(1)

        habitRepo.archive(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).isEmpty()
    }

    @Test
    fun `删除习惯后自动从列表消失`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()

        habitRepo.deletePermanently(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items).isEmpty()
    }

    @Test
    fun `今日日期由注入的时钟决定`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.today).isEqualTo(LocalDate.of(2026, 10, 14))
    }

    // ------------------------------------------------------- 打卡状态同步

    @Test
    fun `勾选打卡后 checkedToday 自动变为 true`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items.first().checkedToday).isFalse()

        viewModel.toggleCheckIn(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.first().checkedToday).isTrue()
        assertThat(viewModel.uiState.value.checkedCount).isEqualTo(1)
    }

    /**
     * ⚠️ **这条用例改写自旧规格，不是代码错了。**
     *
     * 旧行为（`toggleCheckIn`）：再点一次 = 取消打卡，`checkedToday` 变回 false。
     * 用户新要求："打卡完成后不能取消打卡" —— 两条规格正面冲突，
     * 因此这里按新规格断言：**再点一次什么都不变**。
     *
     * 原来那句 `assertThat(...checkedToday).isFalse()` 被替换（不是删除断言），
     * 换成"状态不变 + 条数不变 + 给出提示"三条更强的断言。
     */
    @Test
    fun `已打卡后再点一次不会取消打卡_新规格`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()

        viewModel.checkIn(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.items.first().checkedToday).isTrue()
        assertThat(checkInDao.countForHabit(id)).isEqualTo(1)

        // 再点一次：不写入、不删除
        viewModel.checkIn(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.first().checkedToday).isTrue()
        assertThat(viewModel.uiState.value.checkedCount).isEqualTo(1)
        assertThat(checkInDao.countForHabit(id)).isEqualTo(1)
        assertThat(checkInDao.countAll()).isEqualTo(1)
        assertThat(viewModel.uiState.value.notice).contains("已经打过卡")
        assertThat(habitRepo.checkedDates(id)).hasSize(1)
    }

    @Test
    fun `旧方法名 toggleCheckIn 也是单向的，不会撤销打卡`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()

        viewModel.toggleCheckIn(id)
        advanceUntilIdle()
        viewModel.toggleCheckIn(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.first().checkedToday).isTrue()
        assertThat(checkInDao.countAll()).isEqualTo(1)
    }

    @Test
    fun `打卡次数在反复点击后仍然只有一次`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(simpleHabit("喝水"))
        advanceUntilIdle()

        repeat(5) {
            viewModel.checkIn(id)
            advanceUntilIdle()
        }

        assertThat(checkInDao.countForHabit(id)).isEqualTo(1)
    }

    // ------------------------------------------------------------ 锁机卡片

    @Test
    fun `没有任何规则时卡片显示今日无锁机计划`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.cardState).isEqualTo(LockCardState.IDLE)
        assertThat(viewModel.uiState.value.cardTitle).isEqualTo("今日无锁机计划")
        // v2.8：引导文案必须指向「添加」页 ——
        // 旧文案说的是"去习惯里打开锁机"，而 v2.6 起统一入口已经是「添加」页的加号
        assertThat(viewModel.uiState.value.cardMessage).isEqualTo("去「添加」里点击加号添加")
    }

    @Test
    fun `没有需打卡的锁机规则时不显示打卡入口`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canCheckIn).isFalse()
    }

    @Test
    fun `锁机中且规则挂了习惯时，打卡入口带上 habitId`() = runTest {
        // v2.2：解锁方法＝打卡（任何打卡方法都可以），所以锁机中一律给打卡入口；
        // 且要把 habitId 带上 —— 室内定位失败时打卡页靠它给出「改用无条件打卡」
        advanceUntilIdle()
        val spotId = spotRepo.save(
            Spot(name = "图书馆", lat = 30.0, lng = 120.0, radiusM = 100, minAccuracyM = 50f)
        )
        val habitId = habitRepo.save(
            Habit(name = "早课", type = HabitType.LOCATION, spotId = spotId)
        )
        lockRepo.saveForHabit(
            habitId,
            LockRule(
                name = "早课",
                habitId = habitId,
                windows = listOf(TimeWindow(15 * 60, 18 * 60)),
                spotId = spotId
            )
        )
        // 时钟固定在 16:00 (+08:00) —— 正处于这条规则的锁机窗口内
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.cardState).isEqualTo(LockCardState.LOCKED)
        assertThat(state.canCheckIn).isTrue()
        assertThat(state.checkInRuleId).isNotNull()
        assertThat(state.checkInSpotId).isEqualTo(spotId)
        assertThat(state.checkInHabitId).isEqualTo(habitId)
    }

    // ---------------------------------------- 到点打卡习惯不许在首页点一下
    //
    // 用户要求："到点打卡习惯改为检测地点打卡后才能加连续天数"。
    // 界面会把点击路由到打卡页，这里是防止其它入口绕过的保险丝。

    @Test
    fun `到点打卡习惯在首页勾选无效并给出提示`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L)
        )
        advanceUntilIdle()

        viewModel.toggleCheckIn(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.notice).contains("到打卡点")
        assertThat(viewModel.uiState.value.items.first().checkedToday).isFalse()
        assertThat(habitRepo.checkedDates(id)).isEmpty()
    }

    @Test
    fun `纯打卡习惯照常可以勾选，且不产生提示`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        advanceUntilIdle()

        viewModel.toggleCheckIn(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.notice).isNull()
        assertThat(viewModel.uiState.value.items.first().checkedToday).isTrue()
    }

    @Test
    fun `提示被消费后清空，不会重复弹`() = runTest {
        advanceUntilIdle()
        val id = habitRepo.save(Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = 7L))
        advanceUntilIdle()

        viewModel.toggleCheckIn(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.notice).isNotNull()

        viewModel.consumeNotice()
        assertThat(viewModel.uiState.value.notice).isNull()
    }

    // ------------------------------------------------- 「当前 / 接下来」聚焦卡片
    //
    // 用户要求："今天界面保留今日习惯列表，在列表上方显示当前习惯或距离当前最近的习惯"。
    // 选择规则本身由 NowNextPickerTest 逐条钉死（那里有 20+ 个用例）；
    // 这里只验证 ViewModel 把它接到了状态上、并随打卡一起更新。

    @Test
    fun `固定时钟下选中 15 点 55 分的习惯为 NOW`() = runTest {
        // 时钟固定在 16:00 (+08:00)，所以"刚过 5 分钟"就是 15:55
        habitRepo.save(Habit(name = "背单词", remindTimes = listOf(LocalTime.of(15, 55))))
        habitRepo.save(Habit(name = "跑步", remindTimes = listOf(LocalTime.of(18, 0))))
        advanceUntilIdle()

        val nowNext = viewModel.uiState.value.nowNext!!
        assertThat(nowNext.habit.name).isEqualTo("背单词")
        assertThat(nowNext.mode).isEqualTo(NowNextPicker.Mode.NOW)
        assertThat(nowNext.minutesUntil).isEqualTo(-5)
    }

    @Test
    fun `没有已过提醒时选中将来最近的为 NEXT`() = runTest {
        habitRepo.save(Habit(name = "跑步", remindTimes = listOf(LocalTime.of(18, 0))))
        habitRepo.save(Habit(name = "写日记", remindTimes = listOf(LocalTime.of(21, 0))))
        advanceUntilIdle()

        val nowNext = viewModel.uiState.value.nowNext!!
        assertThat(nowNext.habit.name).isEqualTo("跑步")
        assertThat(nowNext.mode).isEqualTo(NowNextPicker.Mode.NEXT)
        assertThat(nowNext.distanceLabel).isEqualTo("还有 2 小时")
    }

    @Test
    fun `打卡后聚焦卡片换到下一条`() = runTest {
        val first = habitRepo.save(Habit(name = "跑步", remindTimes = listOf(LocalTime.of(18, 0))))
        habitRepo.save(Habit(name = "写日记", remindTimes = listOf(LocalTime.of(21, 0))))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.nowNext!!.habit.name).isEqualTo("跑步")

        viewModel.checkIn(first)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.nowNext!!.habit.name).isEqualTo("写日记")
    }

    @Test
    fun `全部打卡后聚焦卡片为空`() = runTest {
        val id = habitRepo.save(simpleHabit("早起"))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.nowNext).isNotNull()

        viewModel.checkIn(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.nowNext).isNull()
    }

    @Test
    fun `没有任何习惯时聚焦卡片为空`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.nowNext).isNull()
    }

    // -------------------------------------------------------- 全部完成与留言
    //
    // 用户要求："当天所有习惯都打卡完成后，今天界面上方显示绿底的「所有任务已完成」；
    // 点它可以留言；留言记录可以在日历上看见。"

    @Test
    fun `没有习惯时 allDone 为假`() = runTest {
        advanceUntilIdle()
        // 空集合上的 all{} 恒为 true —— 不显式拦下的话，
        // 一个刚装好、什么都没配的 App 首页会挂着"所有任务已完成"
        assertThat(viewModel.uiState.value.items).isEmpty()
        assertThat(viewModel.uiState.value.allDone).isFalse()
    }

    @Test
    fun `部分完成时 allDone 为假`() = runTest {
        val a = habitRepo.save(simpleHabit("早起"))
        habitRepo.save(simpleHabit("读书"))
        advanceUntilIdle()

        viewModel.checkIn(a)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.checkedCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.allDone).isFalse()
    }

    @Test
    fun `全部完成后 allDone 为真`() = runTest {
        val a = habitRepo.save(simpleHabit("早起"))
        val b = habitRepo.save(simpleHabit("读书"))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.allDone).isFalse()

        viewModel.checkIn(a)
        viewModel.checkIn(b)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.allDone).isTrue()
    }

    @Test
    fun `归档掉未打卡的习惯后 allDone 变真`() = runTest {
        val a = habitRepo.save(simpleHabit("早起"))
        val b = habitRepo.save(simpleHabit("读书"))
        advanceUntilIdle()

        viewModel.checkIn(a)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.allDone).isFalse()

        // 归档是"这条以后不做了"，今日列表里就没有它了 —— 剩下的都完成即算完成
        habitRepo.archive(b)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.allDone).isTrue()
    }

    @Test
    fun `初始时没有留言`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.dayNote).isNull()
    }

    // ------------------------------ v2.12：留言条件 =「有习惯完成 或 规划结束」
    //
    // 用户原话：「留言条件改为『有习惯完成 或 规划结束』」——
    // 旧规格是"当天所有习惯都打卡完成"，所以下面这几条里有两条是**新规格**，
    // 另外一条（`没有进展时留言入口不可用`）是旧规格的续命钉子。
    //
    // ⚠️ 下面这些用例**没有**削弱上面那组 `allDone` 的断言 ——
    // 「全完成」仍然是它自己的一个档（绿底横幅的观感靠它），
    // 变的只是"留言入口什么时候在"。

    @Test
    fun `部分完成时留言入口已经可用 —— 不必全部完成`() = runTest {
        val a = habitRepo.save(simpleHabit("早起"))
        habitRepo.save(simpleHabit("读书"))
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canLeaveNote).isFalse()

        viewModel.checkIn(a)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.checkedCount).isEqualTo(1)
        // 还没全完成 —— 旧规格这时是没有留言入口的
        assertThat(state.allDone).isFalse()
        // 但新规格下留言入口必须在
        assertThat(state.canLeaveNote).isTrue()
        assertThat(state.progress.hasProgress).isTrue()
        assertThat(state.progress.anyHabitChecked).isTrue()
    }

    @Test
    fun `规划结束后即使一条习惯都没打，留言入口也可用`() = runTest {
        // 时钟固定在 16:00，这条规划 09:00-11:00 已经结束
        planToday("读书", 9 * 60, 11 * 60)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.totalCount).isEqualTo(0)
        assertThat(state.allDone).isFalse()
        assertThat(state.progress.anyPlanEnded).isTrue()
        assertThat(state.canLeaveNote).isTrue()
    }

    @Test
    fun `规划还没结束时留言入口不出现`() = runTest {
        // 16:00 时一条 20:00-21:00 的规划还没结束
        planToday("夜跑", 20 * 60, 21 * 60)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.progress.anyPlanEnded).isFalse()
        assertThat(viewModel.uiState.value.canLeaveNote).isFalse()
    }

    @Test
    fun `没有习惯也没有规划时留言入口不可用`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canLeaveNote).isFalse()
    }

    @Test
    fun `已有留言时即使今天零进展也留着入口 —— 否则写了就改不回去`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.canLeaveNote).isFalse()

        viewModel.saveDayNote("今天还行")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.progress.hasProgress).isFalse()
        assertThat(viewModel.uiState.value.canLeaveNote).isTrue()
    }

    @Test
    fun `保存留言后状态里能读到`() = runTest {
        advanceUntilIdle()

        viewModel.saveDayNote("今天很稳")
        advanceUntilIdle()

        val note = viewModel.uiState.value.dayNote!!
        assertThat(note.text).isEqualTo("今天很稳")
        assertThat(note.date).isEqualTo(LocalDate.of(2026, 10, 14))
        assertThat(viewModel.uiState.value.notice).contains("已保存")
        // 写进了 Fake DAO（一天一条，主键是 epochDay）
        assertThat(dayNoteDao.count()).isEqualTo(1)
    }

    @Test
    fun `同一天再次保存留言是覆盖而不是新增`() = runTest {
        advanceUntilIdle()

        viewModel.saveDayNote("第一版")
        advanceUntilIdle()
        viewModel.saveDayNote("改一版")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dayNote!!.text).isEqualTo("改一版")
        assertThat(dayNoteDao.count()).isEqualTo(1)
    }

    @Test
    fun `保存空留言等于清除`() = runTest {
        advanceUntilIdle()
        viewModel.saveDayNote("先写一句")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.dayNote).isNotNull()

        viewModel.saveDayNote("")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dayNote).isNull()
        assertThat(dayNoteDao.count()).isEqualTo(0)
        assertThat(viewModel.uiState.value.notice).contains("已清除")
    }

    @Test
    fun `只有空白的留言也等于清除`() = runTest {
        advanceUntilIdle()
        viewModel.saveDayNote("   \n  ")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dayNote).isNull()
        assertThat(dayNoteDao.count()).isEqualTo(0)
    }

    @Test
    fun `留言首尾空白会被去掉`() = runTest {
        advanceUntilIdle()
        viewModel.saveDayNote("  今天很稳  ")
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.dayNote!!.text).isEqualTo("今天很稳")
    }

    @Test
    fun `初始化时已有留言会被读到`() = runTest {
        // 模拟"上次打开 App 时写的留言，这次进来还在"
        dayNoteRepo.save(LocalDate.of(2026, 10, 14), "昨天留下的")
        advanceUntilIdle()

        val fresh = TodayViewModel(habitRepo, lockRepo, dayNoteRepo, planRepo)
        advanceUntilIdle()

        assertThat(fresh.uiState.value.dayNote!!.text).isEqualTo("昨天留下的")
    }

    // ------------------------------------------------- 今日任务：习惯 + 规划
    //
    // 用户要求（v2.5）："该界面显示当天的习惯和昨天做好的规划，
    // 当天不需要打卡的习惯不显示，以时间从早到晚排序。"
    //
    // 排序与过滤的边界全部由 `TodayAgendaTest` 逐条钉死（那是纯函数）；
    // 这里只验证 ViewModel 把三张表（habits / check_ins / plans）接上了。

    /** 今天生效的规划（＝昨天做好的那些）。 */
    private suspend fun planToday(name: String, startMinute: Int, endMinute: Int) {
        planDao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = name,
                epochDay = LocalDate.of(2026, 10, 14).toEpochDay(),
                startMinute = startMinute,
                endMinute = endMinute,
                createdAt = 0L
            )
        )
    }

    private fun millisOf(date: LocalDate, hour: Int = 9): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun agendaNames(): List<String> = viewModel.uiState.value.agenda.map {
        when (it) {
            is AgendaItem.HabitTask -> it.entry.habit.name
            is AgendaItem.PlanTask -> it.plan.name
        }
    }

    @Test
    fun `今天生效的规划出现在今日任务里`() = runTest {
        advanceUntilIdle()
        planToday("读书", 9 * 60, 11 * 60)
        advanceUntilIdle()

        val agenda = viewModel.uiState.value.agenda
        assertThat(agenda).hasSize(1)
        assertThat(agenda.single()).isInstanceOf(AgendaItem.PlanTask::class.java)
        assertThat((agenda.single() as AgendaItem.PlanTask).plan.name).isEqualTo("读书")
        assertThat(viewModel.uiState.value.planCount).isEqualTo(1)
    }

    @Test
    fun `习惯与规划按时间从早到晚排在同一个列表里`() = runTest {
        habitRepo.save(Habit(name = "十点的习惯", remindTimes = listOf(LocalTime.of(10, 0))))
        planToday("九点的规划", 9 * 60, 10 * 60)
        advanceUntilIdle()

        assertThat(agendaNames()).containsExactly("九点的规划", "十点的习惯").inOrder()
    }

    @Test
    fun `规划不算进打卡计数，也不算进全部完成`() = runTest {
        val id = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        planToday("读书", 9 * 60, 11 * 60)
        advanceUntilIdle()

        // 规划不可打卡 -> 计数只数习惯
        assertThat(viewModel.uiState.value.totalCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.planCount).isEqualTo(1)

        viewModel.checkIn(id)
        advanceUntilIdle()

        // 习惯打完了就是"全部完成" —— 规划不影响这个判定（用户明确要求不要改）
        assertThat(viewModel.uiState.value.allDone).isTrue()
    }

    @Test
    fun `当天不需要打卡的习惯不出现在今日任务里`() = runTest {
        // 周三（2026-10-14）不在"只做周一"的指定星期里
        habitRepo.save(
            Habit(
                name = "周一才做",
                frequency = HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY))
            )
        )
        habitRepo.save(Habit(name = "每天做"))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items.map { it.habit.name }).containsExactly("每天做")
    }

    @Test
    fun `指定每月几号里不含今天的习惯不出现在今日任务里`() = runTest {
        // v2.13：「每月 N 次」已被「指定每月几号」取代。
        // 今天是 2026-10-14，只选了 1 号 -> 今天不需要打卡
        val id = habitRepo.save(
            Habit(name = "每月一号", frequency = HabitFrequency.SpecificMonthDays(setOf(1)))
        )
        habitRepo.checkIn(id, millisOf(LocalDate.of(2026, 10, 1)))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.items).isEmpty()
    }

    @Test
    fun `聚焦卡片的候选就是今日任务里的习惯那一份`() = runTest {
        // 一条今天不需要打卡的习惯（周一才做）不该被聚焦卡片挑中
        habitRepo.save(
            Habit(
                name = "周一才做",
                frequency = HabitFrequency.SpecificDays(setOf(DayOfWeek.MONDAY)),
                remindTimes = listOf(LocalTime.of(15, 55))
            )
        )
        habitRepo.save(Habit(name = "每天做", remindTimes = listOf(LocalTime.of(18, 0))))
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.nowNext!!.habit.name).isEqualTo("每天做")
    }

    @Test
    fun `新增规划后不用手动刷新，今日任务自己会更新`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.agenda).isEmpty()

        planToday("读书", 9 * 60, 11 * 60)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.planCount).isEqualTo(1)
    }

    // ------------------------------------------------------ 即时锁机（v2.12）
    //
    // 用户要求：「添加即时锁机功能，锁机可选时间从 0 分到 12 小时。
    // 入口放在今天界面的最上方。」
    //
    // 时序本身由 `InstantLockTest`（纯函数）与 `LockRuleRepositoryTest`（落库）
    // 逐条钉死；这里只验首页把它接上了：点一下真的进入锁机状态、
    // 通知钩子被调到、失败时有提示而不是崩掉。

    @Test
    fun `即时锁机会立刻把首页卡片变成锁机中`() = runTest {
        viewModel.refresh()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.cardState).isEqualTo(LockCardState.IDLE)

        viewModel.startInstantLock(30)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.cardState).isEqualTo(LockCardState.LOCKED)
        assertThat(state.cardTitle).contains("锁机中")
        assertThat(state.cardTitle).contains("即时锁机")
        assertThat(state.cardMessage).contains("30")
        // 回执要让用户知道点到了
        assertThat(state.notice).contains("即时锁机")
    }

    @Test
    fun `即时锁机会让前台服务重算 —— 否则通知栏不会出现锁机提示`() = runTest {
        viewModel.startInstantLock(30)
        advanceUntilIdle()

        assertThat(lockStateChangedCount).isEqualTo(1)
    }

    @Test
    fun `即时锁机不给打卡解锁入口 —— 它只能到点解锁`() = runTest {
        viewModel.startInstantLock(30)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.cardState).isEqualTo(LockCardState.LOCKED)
        // ⚠️ v2.12：allowCheckInUnlock = false 的规则不再给出那个按了没用的按钮
        assertThat(state.canCheckIn).isFalse()
        assertThat(state.checkInRuleId).isNull()
    }

    @Test
    fun `时长会被夹进 0 到 12 小时`() = runTest {
        viewModel.startInstantLock(99999)
        advanceUntilIdle()

        // 规则真的只有 12 小时（16:00 锁到次日 04:00），不是把 99999 原样存下去
        val status = lockRepo.instantLockStatus()
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.minutesUntilChange).isEqualTo(12 * 60L)
    }

    @Test
    fun `0 分也是合法时长 —— 立刻锁上，只锁到下一个整分钟`() = runTest {
        viewModel.startInstantLock(0)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.cardState).isEqualTo(LockCardState.LOCKED)
        assertThat(lockRuleDao.getAll()).hasSize(1)
        // 0 分不是"全天"（那是最危险的退化），而是最短的一次锁
        assertThat(lockRepo.instantLockStatus().minutesUntilChange).isEqualTo(1L)
    }

    @Test
    fun `仓库抛异常时只给一句提示，不把 App 带崩`() = runTest {
        // 未捕获的协程异常会终止 App 进程 —— 这个坑本项目踩过两次，
        // 所以每一条写路径都必须 runCatching。
        val failing = TodayViewModel(
            habitRepo,
            LockRuleRepository(
                ThrowingLockRuleDao(),
                FakeAllowAppDao(),
                FakeRuleAllowAppDao(),
                FakeRuleCheckInDao(),
                checkInDao,
                Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
            ),
            dayNoteRepo,
            planRepo
        )
        advanceUntilIdle()

        failing.startInstantLock(30)
        advanceUntilIdle()

        assertThat(failing.uiState.value.notice).contains("即时锁机失败")
        assertThat(failing.uiState.value.notice).contains("库坏了")
    }

    /** 一个读/写都会抛的锁机规则 DAO（模拟"库坏了"）。 */
    private class ThrowingLockRuleDao : com.zilv.app.data.local.dao.LockRuleDao {
        override fun observeAll() = kotlinx.coroutines.flow.flowOf(
            emptyList<com.zilv.app.data.local.entity.LockRuleEntity>()
        )

        override suspend fun getAll(): List<com.zilv.app.data.local.entity.LockRuleEntity> =
            throw IllegalStateException("库坏了")

        override suspend fun getEnabled(): List<com.zilv.app.data.local.entity.LockRuleEntity> =
            emptyList()

        override suspend fun getById(id: Long): com.zilv.app.data.local.entity.LockRuleEntity? = null

        override suspend fun getByHabit(habitId: Long):
            com.zilv.app.data.local.entity.LockRuleEntity? = null

        override suspend fun deleteByHabit(habitId: Long) = Unit

        override suspend fun insert(
            rule: com.zilv.app.data.local.entity.LockRuleEntity
        ): Long = throw IllegalStateException("库坏了")

        override suspend fun update(rule: com.zilv.app.data.local.entity.LockRuleEntity) =
            throw IllegalStateException("库坏了")

        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit

        override suspend fun delete(id: Long) = Unit

        override suspend fun count(): Int = 0
    }
}