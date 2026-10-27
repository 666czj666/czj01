package com.zilv.app.ui.checkin

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.mapper.RuleCheckInStatus
import com.zilv.app.data.repository.FakeAllowAppDao
import com.zilv.app.data.repository.FakeLockRuleDao
import com.zilv.app.data.repository.FakeRuleAllowAppDao
import com.zilv.app.data.repository.FakeRuleCheckInDao
import com.zilv.app.data.repository.FakeSpotDao
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.domain.model.Spot
import com.zilv.app.location.LocationSource
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
import java.time.ZoneId
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType

/**
 * [CheckInViewModel] 的单元测试。
 *
 * **核心目的是锁死"打卡成功后通知必须收起"** —— 用户明确要求：
 * 非锁机时间不显示通知，打卡成功后也不显示。
 *
 * 原先打卡只写库，没有任何东西通知前台服务重算，结果解锁了通知栏
 * 还挂着「锁机中」。现在通过 `onLockStateChanged` 回调触发重算，
 * 这里验证该回调**只在真正解锁成功时**被调用。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var spotDao: FakeSpotDao
    private lateinit var checkInDao: FakeRuleCheckInDao
    private lateinit var spotRepo: SpotRepository
    private lateinit var lockRepo: LockRuleRepository
    private lateinit var habitRepo: HabitRepository
    private lateinit var clock: Clock

    /** 可控的定位来源。 */
    private class FakeLocationSource(
        var permission: Boolean = true,
        var location: GeoPoint? = null
    ) : LocationSource {
        override fun hasPermission(): Boolean = permission
        override suspend fun currentLocation(timeoutMs: Long): GeoPoint? = location
    }

    private lateinit var location: FakeLocationSource

    /** 记录回调被调用的次数。 */
    private var lockStateChangedCount = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)

        spotDao = FakeSpotDao()
        checkInDao = FakeRuleCheckInDao()
        spotRepo = SpotRepository(spotDao, checkInDao, clock)
        lockRepo = LockRuleRepository(
            FakeLockRuleDao(),
            FakeAllowAppDao(),
            FakeRuleAllowAppDao(),
            FakeRuleCheckInDao(),
            FakeCheckInDao(),
            clock
        )
        habitRepo = HabitRepository(FakeHabitDao(), FakeCheckInDao(), clock)
        location = FakeLocationSource()
        lockStateChangedCount = 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = CheckInViewModel(
        spotRepository = spotRepo,
        lockRuleRepository = lockRepo,
        habitRepository = habitRepo,
        locationProvider = location,
        clock = clock
    ) { lockStateChangedCount++ }

    private suspend fun seedSpot(): Long = spotRepo.save(
        Spot(name = "三教一楼", lat = 30.0, lng = 120.0, radiusM = 100, minAccuracyM = 50f)
    )

    private suspend fun seedRule(spotId: Long): Long = lockRepo.save(
        LockRule(
            name = "早课",
            enabled = true,
            windows = listOf(TimeWindow(7 * 60, 9 * 60)),
            unlockMode = UnlockMode.SPOT_CHECKIN,
            spotId = spotId
        )
    )

    private fun pointAtSpot(accuracy: Float = 10f) =
        GeoPoint(lat = 30.0, lng = 120.0, accuracyM = accuracy, atMillis = 0L)

    // ------------------------------------ 核心：打卡成功后必须通知收起通知

    @Test
    fun `打卡成功后触发锁机状态回调`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = pointAtSpot()

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Ready)
        assertThat(lockStateChangedCount).isEqualTo(0)

        vm.checkIn()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Success)
        assertThat(vm.uiState.value.saved).isTrue()
        // 关键断言：回调必须被触发，否则通知会一直挂着
        assertThat(lockStateChangedCount).isEqualTo(1)
    }

    @Test
    fun `异常解锁也触发锁机状态回调`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = pointAtSpot(accuracy = 200f) // 精度不达标

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        vm.abnormalUnlock()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Success)
        assertThat(lockStateChangedCount).isEqualTo(1)
    }

    @Test
    fun `精度不达标时未打卡不触发回调`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = pointAtSpot(accuracy = 200f)

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.AccuracyTooLow)
        vm.checkIn() // 未就绪，应当无效
        advanceUntilIdle()

        assertThat(lockStateChangedCount).isEqualTo(0)
        assertThat(vm.uiState.value.saved).isFalse()
    }

    @Test
    fun `距离过远时未打卡不触发回调`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        // 距打卡点约 1.1 公里
        location.location = GeoPoint(lat = 30.01, lng = 120.0, accuracyM = 10f, atMillis = 0L)

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.TooFar)
        vm.checkIn()
        advanceUntilIdle()

        assertThat(lockStateChangedCount).isEqualTo(0)
    }

    // ---------------------------------------------------------------- 落库

    @Test
    fun `打卡成功后记录落库且状态为按时`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = pointAtSpot()

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        vm.checkIn()
        advanceUntilIdle()

        val record = spotRepo.latestCheckIn(ruleId)!!
        assertThat(record.spotId).isEqualTo(spotId)
        assertThat(record.status).isEqualTo(RuleCheckInStatus.ON_TIME)
        assertThat(record.accuracyM).isEqualTo(10f)
    }

    @Test
    fun `没有定位权限时进入需要权限状态`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.permission = false

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.NeedPermission)
        assertThat(lockStateChangedCount).isEqualTo(0)
    }

    @Test
    fun `定位失败时进入错误状态`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)
    }

    @Test
    fun `打卡点不存在时给出明确错误`() = runTest {
        val ruleId = seedRule(spotId = 999L)
        val vm = newViewModel()
        vm.start(ruleId, 999L)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)
        assertThat(vm.uiState.value.message).contains("找不到打卡点")
    }

    // ------------------------------------ 地点打卡给绑定的到点打卡习惯记账
    //
    // 用户要求："到点打卡习惯改为检测地点打卡后才能加连续天数"。
    // 地点打卡写的是 rule_check_ins，原先**从不**写进习惯的 check_ins，
    // 所以这类习惯的连续天数完全来自手动点击 —— 这里验证闭环已通。

    @Test
    fun `地点打卡会给绑定该点的到点打卡习惯记上完成`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = pointAtSpot()

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        vm.checkIn()
        advanceUntilIdle()

        val habit = habitRepo.getHabit(habitId)!!
        assertThat(habitRepo.isCheckedToday(habit, habitRepo.today())).isTrue()
        assertThat(habitRepo.streakFor(habit, habitRepo.today()).current).isEqualTo(1)
    }

    @Test
    fun `纯打卡习惯不会被地点打卡记账`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        val habitId = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        location.location = pointAtSpot()

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        vm.checkIn()
        advanceUntilIdle()

        val habit = habitRepo.getHabit(habitId)!!
        assertThat(habitRepo.isCheckedToday(habit, habitRepo.today())).isFalse()
    }

    @Test
    fun `异常解锁不给习惯记账`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = pointAtSpot(accuracy = 200f) // 精度不达标

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        vm.abnormalUnlock()
        advanceUntilIdle()

        // 异常解锁没有真正通过地点校验，不计入连续天数
        val habit = habitRepo.getHabit(habitId)!!
        assertThat(habitRepo.isCheckedToday(habit, habitRepo.today())).isFalse()
    }

    @Test
    fun `有多个习惯绑定同一点时都会记账`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        val a = habitRepo.save(Habit(name = "A", type = HabitType.LOCATION, spotId = spotId))
        val b = habitRepo.save(Habit(name = "B", type = HabitType.LOCATION, spotId = spotId))
        location.location = pointAtSpot()

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        vm.checkIn()
        advanceUntilIdle()

        assertThat(habitRepo.isCheckedToday(habitRepo.getHabit(a)!!, habitRepo.today())).isTrue()
        assertThat(habitRepo.isCheckedToday(habitRepo.getHabit(b)!!, habitRepo.today())).isTrue()
    }

    @Test
    fun `ruleId 为 0 时不写入 rule_check_ins`() = runTest {
        // 从习惯入口进来的打卡没有规则，不该往 rule_check_ins 塞 ruleId=0 的垃圾行
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = pointAtSpot()

        val vm = newViewModel()
        vm.start(0L, spotId)
        advanceUntilIdle()
        vm.checkIn()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Success)
        assertThat(spotRepo.latestCheckIn(0L)).isNull()
        assertThat(habitRepo.isCheckedToday(habitRepo.getHabit(habitId)!!, habitRepo.today())).isTrue()
    }

    // ------------------------------ 定位失败 / 超时的降级通道
    //
    // 真实缺陷：原先只有"精度不达标"才累加失败计数，
    // "定位超时"直接进 Error —— 室内用户永远看不到降级通道，只能卡在"出错了"。

    @Test
    fun `有规则时定位连续失败三次会开放降级通道`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null // 定位失败

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)
        assertThat(vm.uiState.value.consecutiveAccuracyFailures).isEqualTo(1)
        assertThat(vm.uiState.value.offerFallback).isFalse()

        vm.refreshLocation(); advanceUntilIdle()
        vm.refreshLocation(); advanceUntilIdle()

        assertThat(vm.uiState.value.consecutiveAccuracyFailures).isEqualTo(3)
        assertThat(vm.uiState.value.offerFallback).isTrue()
    }

    @Test
    fun `习惯打卡路径定位失败不开放降级通道`() = runTest {
        // 习惯没有"锁机"可解，降级解锁对它没有意义；
        // 开放了反而会让用户以为能绕过地点校验
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId)
        repeat(5) { advanceUntilIdle(); vm.refreshLocation() }
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)
        assertThat(vm.uiState.value.offerFallback).isFalse()
        assertThat(habitId).isGreaterThan(0L)
    }

    @Test
    fun `定位失败的提示是可操作的而不是只说一句出错了`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()

        val msg = vm.uiState.value.message
        assertThat(msg).contains("重试")
        // ⚠️ v2.11 规格变更：文案**不再**在第 1 次失败时就提"临时解锁" ——
        // 那条通道现在要连续失败 3 次才出现（见下面 `从锁机规则进入时…` 那条）。
        // 而这条用例是从**锁机规则**进来的（没有关联习惯），所以也不会提
        // "改用无条件打卡"。第 1 次失败时它该说的是"可以重试"。
        assertThat(msg).contains("室内定位失败")
        assertThat(msg).doesNotContain("临时解锁")
        assertThat(msg).doesNotContain("无条件")
    }

    @Test
    fun `无规则时异常解锁不写入 rule_check_ins`() = runTest {
        val spotId = seedSpot()
        habitRepo.save(Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId))
        location.location = pointAtSpot(accuracy = 200f)

        val vm = newViewModel()
        vm.start(0L, spotId)
        advanceUntilIdle()
        vm.abnormalUnlock()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Success)
        assertThat(spotRepo.latestCheckIn(0L)).isNull()
    }

    // -------------------------- 室内定位失败 + 改用无条件打卡（v2.2）
    //
    // 用户要求：室内无法检测地点时，明说「室内定位失败」，
    // 并给一个「改用无条件打卡」的快捷入口（把该习惯的打卡条件改成「无」）。

    @Test
    fun `室内定位失败时明说室内定位失败并给出改用无条件打卡`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)
        assertThat(vm.uiState.value.message).contains("室内定位失败")
        assertThat(vm.uiState.value.message).contains("地下")
        // ⚠️ v2.11 规格变更（用户第 2 条）：不再是"失败一次就出现"，
        // 而是连续失败 3 次之后才出现。第 1 次失败时对话框还不该弹。
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
        assertThat(vm.uiState.value.consecutiveAccuracyFailures).isEqualTo(1)
        // v2.12：只有"拿不到定位"才累加无条件打卡的门槛，这里正是那条路径
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(1)
        assertThat(vm.uiState.value.failuresUntilUnconditional).isEqualTo(2)
        assertThat(vm.uiState.value.unconditionalPending).isTrue()
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()
    }

    // ============== v2.11（用户第 2 条）：两条 3 次门槛各走各的路径
    //
    // 用户要求："「改用无条件打卡」原来一失败就出现，要连续失败 3 次之后才出现"，
    // 同时"注意与既有的「拍照存证 + 临时解锁」门槛区分：后者只在 ruleId > 0
    // （从锁机规则进来）时才有；「无条件打卡」只在从「指定地点」的习惯进来时才有。
    // 两者门槛都是 3 次，但适用路径不同，别混在一起"。
    //
    // 下面这一组就是那条分界线的回归：**同一个 VM、同样连续失败 3 次**，
    // 从规则进来的看得见"临时解锁"，从习惯进来的看得见"无条件打卡"。

    @Test
    fun `连续失败三次之后无条件打卡入口才出现 —— 一二三次逐一钉住`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()

        // 第 1 次
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(1)
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
        assertThat(vm.uiState.value.failuresUntilUnconditional).isEqualTo(2)
        // ⚠️ **改写自旧规格**：v2.11 时这里断言文案里写着"再失败 2 次可以改用无条件打卡"。
        // v2.12 用户要求"把无条件打卡的提示隐藏"，所以那句文案被删掉了 ——
        // 是**旧断言与用户新要求正面冲突**，不是代码错了。
        // 断言强度不回退：从"文案里必须出现某个数字"换成"文案里一个字都不许提"。
        assertThat(vm.uiState.value.message).doesNotContain("再失败")
        assertThat(vm.uiState.value.message).doesNotContain("无条件")
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()

        // 第 2 次
        vm.refreshLocation(); advanceUntilIdle()
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(2)
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
        assertThat(vm.uiState.value.failuresUntilUnconditional).isEqualTo(1)
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()

        // 第 3 次 —— 到门槛，**对话框该弹了**（用户要求"弹出对话框"）
        vm.refreshLocation(); advanceUntilIdle()
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(3)
        assertThat(vm.uiState.value.offerUnconditional).isTrue()
        assertThat(vm.uiState.value.failuresUntilUnconditional).isEqualTo(0)
        assertThat(vm.uiState.value.unconditionalPending).isFalse()
        // ⚠️ 同样是**改写自旧规格**：旧断言要求文案里出现「改用无条件打卡」。
        // 现在入口的载体是对话框，判据是 `showUnconditionalDialog`。
        assertThat(vm.uiState.value.showUnconditionalDialog).isTrue()
        assertThat(vm.uiState.value.message).doesNotContain("再失败")

        // 而且点下去真的有效（不是"看得到点不动"）
        vm.useUnconditionalCheckIn(); advanceUntilIdle()
        assertThat(vm.uiState.value.switchedToUnconditional).isTrue()
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()
        assertThat(habitRepo.getHabit(habitId)!!.type).isEqualTo(HabitType.SIMPLE)
    }

    // ---------------- v2.12（用户第 4 条 / Bug 1）：门槛只认"拿不到定位"
    //
    // 用户原话：「只有『定位失败』（拿不到定位）才累加这个门槛。
    // 『定位成功但不在打卡范围内』不算失败，不能触发无条件打卡。」
    //
    // 下面这一组把"什么算一次失败"钉死。它与上面那组
    // 「拍照存证 + 临时解锁」的门槛**各数各的**，互不影响。

    @Test
    fun `定位成功但不在打卡范围之内不算失败 —— 门槛一点都不攒`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        // 拿到定位了，只是在 3 公里外（精度 10 米，够好）
        location.location = GeoPoint(lat = 30.03, lng = 120.0, accuracyM = 10f, atMillis = 0L)

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.TooFar)
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(0)
        assertThat(vm.uiState.value.offerUnconditional).isFalse()

        // 反复重试也不会攒出来
        repeat(5) { vm.refreshLocation(); advanceUntilIdle() }
        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.TooFar)
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(0)
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()
    }

    /**
     * ⚠️ **这条用例改写自旧规格。**
     *
     * 旧规格（v2.11）：`精度不达标这条失败路径同样要连失败三次才出现入口` ——
     * 断言"拿不到定位"与"精度不达标"共用同一个计数，连失败 3 次就开门。
     *
     * 新规格（v2.12 用户第 4 条）：门槛**只认"拿不到定位"**。
     * 精度不达标是"拿到了定位（只是不够准）"，因此**不累加**、并且把计数清零。
     * 两条规格正面冲突，所以这里必须按新规格改 —— 是**旧断言过时**，不是代码错。
     *
     * 断言强度不回退：仍然逐一钉住 1/2/3 次，只是期望值从"该出现"改成"永远不出现"，
     * 并且额外钉住"精度失败照样能开另一条门槛（拍照存证）"—— 那条口径一个字没改。
     */
    @Test
    fun `精度不达标不算定位失败 —— 不触发无条件打卡（旧规格已作废）`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        // 精度 200 米 > 门槛 50 米 -> AccuracyTooLow。注意**拿到了一个点**。
        location.location = pointAtSpot(accuracy = 200f)

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.AccuracyTooLow)
        // 「拍照存证」那条门槛照旧累加（口径不变）
        assertThat(vm.uiState.value.consecutiveAccuracyFailures).isEqualTo(1)
        // 「无条件打卡」那条门槛一点都不动，而且文案里也不再提它
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(0)
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
        assertThat(vm.uiState.value.message).doesNotContain("再失败")
        assertThat(vm.uiState.value.message).doesNotContain("无条件")

        repeat(4) { vm.refreshLocation(); advanceUntilIdle() }
        assertThat(vm.uiState.value.consecutiveAccuracyFailures).isEqualTo(5)
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(0)
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()
    }

    @Test
    fun `精度不达标三次仍然会开拍照存证那条降级通道 —— 别顺手弄坏它`() = runTest {
        // 用户点名要求：「注意区分另一个已有门槛：『拍照存证 + 临时解锁』……
        // 也不要因为本次改动把它弄坏」。
        // 这条用例就是那条门槛的回归：它只在从**锁机规则**进来时才有意义。
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = pointAtSpot(accuracy = 200f)

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        assertThat(vm.uiState.value.offerFallback).isFalse()

        vm.refreshLocation(); advanceUntilIdle()
        vm.refreshLocation(); advanceUntilIdle()

        assertThat(vm.uiState.value.consecutiveAccuracyFailures).isEqualTo(3)
        assertThat(vm.uiState.value.offerFallback).isTrue()
        // 它跟"无条件打卡"没关系：从规则进来时那个入口本来就不该有
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
    }

    @Test
    fun `拿不到定位三次弹出对话框，选继续重试后不再重复弹`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()
        vm.refreshLocation(); advanceUntilIdle()
        vm.refreshLocation(); advanceUntilIdle()

        // 到门槛：该弹
        assertThat(vm.uiState.value.showUnconditionalDialog).isTrue()

        // 用户选「继续重试」→ 收起对话框，但入口本身还在（不是"功能没了"）
        vm.dismissUnconditionalOffer()
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()
        assertThat(vm.uiState.value.offerUnconditional).isTrue()

        // 再失败一次也不该反复弹（计数在涨，但询问只做一次）
        vm.refreshLocation(); advanceUntilIdle()
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(4)
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()
        // 这时用户仍然能自己点「改用无条件打卡」（按钮在对话框里，
        // 所以真正的出口是"重新进这一页"或习惯编辑页）——状态本身没被锁死
        vm.useUnconditionalCheckIn(); advanceUntilIdle()
        assertThat(vm.uiState.value.switchedToUnconditional).isTrue()
    }

    @Test
    fun `定位失败中途成功过一次，计数清零、对话框重新可弹`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()
        vm.refreshLocation(); advanceUntilIdle()
        vm.refreshLocation(); advanceUntilIdle()
        assertThat(vm.uiState.value.showUnconditionalDialog).isTrue()

        // 拿到定位（哪怕只是"太远"）→ 链条断了，计数清零
        location.location = GeoPoint(lat = 30.03, lng = 120.0, accuracyM = 10f, atMillis = 0L)
        vm.refreshLocation(); advanceUntilIdle()
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(0)
        assertThat(vm.uiState.value.showUnconditionalDialog).isFalse()

        // 再连续失败三次，对话框该重新弹（不是"问过一次就永远不问了"）
        location.location = null
        repeat(3) { vm.refreshLocation(); advanceUntilIdle() }
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(3)
        assertThat(vm.uiState.value.showUnconditionalDialog).isTrue()
    }


    @Test
    fun `从锁机规则进来时只开临时解锁 —— 无条件打卡永远不出现`() = runTest {
        // 即使连续失败到门槛，从**规则**进来的这条路也不该冒出「改用无条件打卡」：
        // 没有关联习惯，没有可改的东西（用户点名要求分清这两条路径）。
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        repeat(4) { vm.refreshLocation(); advanceUntilIdle() }

        val state = vm.uiState.value
        assertThat(state.consecutiveAccuracyFailures).isEqualTo(5)
        assertThat(state.offerFallback).isTrue()
        assertThat(state.offerUnconditional).isFalse()
        assertThat(state.unconditionalPending).isFalse()
    }

    @Test
    fun `拿不到定位后重试成功会把还差几次的提示收回去`() = runTest {
        // "失败链条断了"这条语义必须一致：拿到定位之后不该还挂着
        // "再失败 N 次可以改用无条件打卡"（否则用户会以为还得先失败几次）。
        //
        // ⚠️ **这条用例改写自旧规格**：v2.11 时这里断言的是
        // `message).contains("再失败 2 次")` —— 即"没到门槛时把还差几次写在文案里"。
        // v2.12 用户要求「把无条件打卡的提示隐藏」，那句话已经被删掉了，
        // 所以旧断言不可能再成立。这里改成断言"**从头到尾都不出现**"：
        // 强度不回退（从"必须出现某个数字"换成"必须一个字都不出现"），
        // 而"拿到定位就把链条收回去"这条真正要保护的语义仍然由
        // `consecutiveLocationFailures` 归零 + `unconditionalPending` 变假来体现。
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()
        assertThat(vm.uiState.value.unconditionalPending).isTrue()
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(1)
        assertThat(vm.uiState.value.message).doesNotContain("再失败")
        assertThat(vm.uiState.value.message).doesNotContain("无条件")

        location.location = pointAtSpot(accuracy = 15f)
        vm.refreshLocation(); advanceUntilIdle()

        val state = vm.uiState.value
        // 拿到定位就不再是"室内定位失败"那一套了（具体相位取决于距离，
        // 但一定不是 Error / AccuracyTooLow —— 那两个才是"定位失败"）
        assertThat(state.phase).isNotEqualTo(CheckInPhase.Error)
        assertThat(state.phase).isNotEqualTo(CheckInPhase.AccuracyTooLow)
        // 连续失败计数被重置 -> 提示里不再有"再失败 N 次"
        assertThat(state.consecutiveAccuracyFailures).isEqualTo(0)
        // v2.12：无条件打卡那条门槛的计数也跟着清零
        assertThat(state.consecutiveLocationFailures).isEqualTo(0)
        assertThat(state.message).doesNotContain("再失败")
        assertThat(state.unconditionalPending).isTrue()
    }

    @Test
    fun `改用无条件打卡会把习惯改成无条件并给出回执`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()
        vm.useUnconditionalCheckIn()
        advanceUntilIdle()

        val habit = habitRepo.getHabit(habitId)!!
        assertThat(habit.type).isEqualTo(HabitType.SIMPLE)
        assertThat(habit.spotId).isNull()
        assertThat(habit.checkInCondition).isEqualTo(CheckInCondition.NONE)
        // 回执要说清楚"点一下就能打卡"，用户才知道接下来干嘛
        assertThat(vm.uiState.value.message).contains("已改为无条件打卡")
        assertThat(vm.uiState.value.switchedToUnconditional).isTrue()
        // 改完就不该再劝他改成无条件了
        assertThat(vm.uiState.value.offerUnconditional).isFalse()
    }

    @Test
    fun `改完无条件后这条习惯在首页点一下就能打卡`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()
        vm.useUnconditionalCheckIn()
        advanceUntilIdle()

        // 「无」的打卡条件 = 点一下就算（不再要求地点在证）
        val habit = habitRepo.getHabit(habitId)!!
        assertThat(habitRepo.requiresLocationProof(habit)).isFalse()
        habitRepo.checkIn(habitId)
        assertThat(habitRepo.isCheckedToday(habit, habitRepo.today())).isTrue()
    }

    @Test
    fun `打卡条件本来就是无的习惯不出现改用无条件入口`() = runTest {
        val spotId = seedSpot()
        val habitId = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()

        assertThat(vm.uiState.value.offerUnconditional).isFalse()
    }

    @Test
    fun `从锁机规则进入时不出现改用无条件入口`() = runTest {
        // 从遮挡层 / 首页锁机卡片进来的打卡没有关联习惯，没有可改的东西。
        // ⚠️ v2.11：文案不再在第 1 次失败时提"临时解锁"（要连失败 3 次才出现），
        // 所以这里改成**失败到门槛**再验那条通道确实出现了。
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        repeat(2) { vm.refreshLocation(); advanceUntilIdle() }

        val state = vm.uiState.value
        assertThat(state.consecutiveAccuracyFailures).isEqualTo(3)
        assertThat(state.offerUnconditional).isFalse()
        // 但"拍照存证 + 临时解锁"这条降级通道仍然照旧（到门槛后出现）
        assertThat(state.offerFallback).isTrue()
        assertThat(state.message).contains("临时解锁")
    }

    @Test
    fun `没有关联习惯时改用无条件打卡是空操作`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        vm.useUnconditionalCheckIn()
        advanceUntilIdle()

        // 不该崩、也不该把状态改得莫名其妙
        assertThat(vm.uiState.value.switchedToUnconditional).isFalse()
        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)
    }

    // ================== v2.10：室内确实拿不到定位时的降级路径
    //
    // 用户要求（第 4 条 bug）：室内**确实**拿不到位置时（飞行模式 / 无 Wi-Fi
    // 无基站）必须给出**可操作的失败提示**，且「改用无条件打卡」入口仍然可达。
    //
    // [LocationProvider] 现在会并发问 5 条链路（融合定位的 Wi-Fi/基站档、
    // 融合缓存、平台网络缓存、平台 GPS 缓存、平台实时请求），全部失败才返回 null。
    // 返回 null 之后用户看到什么、还能做什么 —— 就是下面这两条钉死的东西，
    // 也是我在没有真机的情况下唯一能验证"降级路径没被我的改动切断"的手段。

    @Test
    fun `室内确实拿不到定位时给出可操作提示且无条件入口仍可达`() = runTest {
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        // 从锁机规则进来（ruleId > 0）时，"拍照存证 + 临时解锁"这条降级通道才存在；
        // 同时挂上一条「指定地点」的习惯，两条降级路径会一起出现。
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null // 五条链路全部失败

        val vm = newViewModel()
        vm.start(ruleId, spotId, habitId)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertThat(state.phase).isEqualTo(CheckInPhase.Error)
        // ① 说明白"是室内定位失败"，并给可操作的动作（重试）。
        //    「重新定位」按钮一直在界面上（CheckInScreen 里无条件渲染），
        //    这里的文案就是它的解释。
        assertThat(state.message).contains("室内定位失败")
        assertThat(state.message).contains("可以重试")
        // ② ⚠️ **改写自旧规格**：v2.11 时这里断言文案里写着
        //    "再失败 2 次可以改用无条件打卡"。v2.12 用户要求
        //    「把无条件打卡的提示隐藏」，那句话已被删除 ——
        //    是**旧断言与用户新要求冲突**。这里改成断言"一个字都不提"，
        //    强度不回退（从"必须出现"换成"必须不出现"）。
        assertThat(state.message).doesNotContain("再失败")
        assertThat(state.message).doesNotContain("无条件")
        // ③ 连续失败 3 次之后，**对话框**必须弹出来（用户要求）
        assertThat(state.showUnconditionalDialog).isFalse()
        repeat(2) { vm.refreshLocation(); advanceUntilIdle() }
        assertThat(vm.uiState.value.offerUnconditional).isTrue()
        assertThat(vm.uiState.value.showUnconditionalDialog).isTrue()
        assertThat(vm.uiState.value.consecutiveLocationFailures).isEqualTo(3)
        // ④ 这个入口不是摆设：点下去真的把习惯改成无条件
        vm.useUnconditionalCheckIn()
        advanceUntilIdle()
        assertThat(vm.uiState.value.switchedToUnconditional).isTrue()
    }

    @Test
    fun `从习惯进入且拿不到定位时也仍然给得出无条件入口`() = runTest {
        // ruleId = 0（从习惯进来）时"临时解锁"通道不适用，
        // 但「改用无条件打卡」必须照旧可达 —— 那正是室内用户最需要的一条路。
        // v2.11 起它要连续失败 3 次才出现，这里就失败满 3 次。
        val spotId = seedSpot()
        val habitId = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        location.location = null

        val vm = newViewModel()
        vm.start(0L, spotId, habitId)
        advanceUntilIdle()
        repeat(2) { vm.refreshLocation(); advanceUntilIdle() }

        assertThat(vm.uiState.value.message).contains("室内定位失败")
        // ⚠️ **改写自旧规格**：旧断言要求文案里出现「改用无条件打卡」。
        // v2.12 起入口的载体是**对话框**，文案里不再提它（用户要求隐藏提示），
        // 所以判据换成 showUnconditionalDialog —— 同样是"这条入口可达"。
        assertThat(vm.uiState.value.message).doesNotContain("无条件")
        assertThat(vm.uiState.value.offerUnconditional).isTrue()
        assertThat(vm.uiState.value.showUnconditionalDialog).isTrue()
    }

    @Test
    fun `室内定位失败后重试成功仍然可以正常打卡`() = runTest {
        // 防止"失败后把状态卡死"这类回归：失败 → 重试 → 拿到位置 → 可打卡
        val spotId = seedSpot()
        val ruleId = seedRule(spotId)
        location.location = null

        val vm = newViewModel()
        vm.start(ruleId, spotId)
        advanceUntilIdle()
        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Error)

        location.location = pointAtSpot(accuracy = 15f)
        vm.refreshLocation()
        advanceUntilIdle()

        assertThat(vm.uiState.value.phase).isEqualTo(CheckInPhase.Ready)
        assertThat(vm.uiState.value.canCheckIn).isTrue()
    }
}