package com.zilv.app.ui.lock

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeAllowAppDao
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeLockRuleDao
import com.zilv.app.data.repository.FakeRuleAllowAppDao
import com.zilv.app.data.repository.FakeRuleCheckInDao
import com.zilv.app.data.repository.FakeSpotDao
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
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
import java.time.LocalTime
import java.time.ZoneId

/**
 * [LockRuleEditViewModel] 的单元测试 —— **按习惯作用域**（v2.0）。
 *
 * 这个文件接替了被删除的 `LockRuleListViewModelTest`：
 * 独立的锁机列表页没了，但"锁机期间任何改动都不能让 App 崩"这条安全网
 * **必须原样保留** —— 仓库层会抛 `RuleLockedException`，而 `viewModelScope`
 * 里的未捕获异常会直接终止进程。
 *
 * 另一半重点是"打卡截止必须早于锁机开始"（[LockRule.lockStartsAfterCheckIn]）：
 * 用户要求"在打卡时间未能打卡才进行锁机"，那么先得给用户打卡的机会。
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockRuleEditViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var repo: LockRuleRepository
    private lateinit var lockDao: FakeLockRuleDao
    private lateinit var viewModel: LockRuleEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        lockDao = FakeLockRuleDao()
        repo = LockRuleRepository(
            lockDao,
            FakeAllowAppDao(),
            FakeRuleAllowAppDao(),
            FakeRuleCheckInDao(),
            FakeCheckInDao(),
            clock
        )
        viewModel = LockRuleEditViewModel(
            repo,
            SpotRepository(FakeSpotDao(), FakeRuleCheckInDao(), clock)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 16:00 时正在锁机中的配置。 */
    private val afternoonLock = LockRule(
        name = "下午自习",
        windows = listOf(TimeWindow(15 * 60, 18 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    // ------------------------------------------------------------ 载入

    @Test
    fun `习惯还没有锁机配置时给一份合法的默认值，而不是报错`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.habitId).isEqualTo(7L)
        assertThat(state.error).isNull()
        assertThat(state.windows).isNotEmpty()
        assertThat(state.checkInWindows).isNotEmpty()
        // 默认值必须能直接保存，否则用户一进来就撞一句报错
        assertThat(state.canSave).isTrue()
        assertThat(state.lockWindowProblem).isNull()
    }

    @Test
    fun `载入已有配置会带出打卡时间与锁机时间`() = runTest {
        val id = repo.saveForHabit(
            7L,
            LockRule(
                name = "上班",
                checkInWindows = listOf(TimeWindow(8 * 60, 9 * 60)),
                windows = listOf(TimeWindow(21 * 60, 23 * 60)),
                unlockMode = UnlockMode.TIME_ONLY
            )
        )

        viewModel.load(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.id).isEqualTo(id)
        assertThat(state.habitId).isEqualTo(7L)
        assertThat(state.name).isEqualTo("上班")
        assertThat(state.checkInWindows).containsExactly(TimeWindow(8 * 60, 9 * 60))
        assertThat(state.windows).containsExactly(TimeWindow(21 * 60, 23 * 60))
    }

    @Test
    fun `habitId 为 0 时不载入任何规则`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.habitId).isEqualTo(0L)
        assertThat(state.windows).isEmpty()
        assertThat(state.checkInWindows).isEmpty()
    }

    // -------------------------------------------------- 打卡时间增删

    @Test
    fun `添加打卡时间会进入列表并可再移除`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeCheckInWindow(0)

        viewModel.addCheckInWindow(6 * 60 + 30, 7 * 60 + 30)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.checkInWindows)
            .containsExactly(TimeWindow(6 * 60 + 30, 7 * 60 + 30))
        assertThat(viewModel.uiState.value.error).isNull()

        viewModel.removeCheckInWindow(0)
        assertThat(viewModel.uiState.value.checkInWindows).isEmpty()
    }

    @Test
    fun `打卡时间起止相同会被拒绝并给出提示`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeCheckInWindow(0)

        viewModel.addCheckInWindow(9 * 60, 9 * 60)

        assertThat(viewModel.uiState.value.checkInWindows).isEmpty()
        assertThat(viewModel.uiState.value.error).contains("结束时间")
    }

    @Test
    fun `打卡时间倒着填（结束早于开始）会被当成跨零点窗口接受`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeCheckInWindow(0)

        // 23:00–01:00：这是合法的跨零点窗口，不是输入错误
        viewModel.addCheckInWindow(23 * 60, 1 * 60)

        val windows = viewModel.uiState.value.checkInWindows
        assertThat(windows).containsExactly(TimeWindow(23 * 60, 1 * 60))
        assertThat(windows.single().crossesMidnight).isTrue()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `重复添加同一个打卡时间不会产生重复项`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeCheckInWindow(0)

        viewModel.addCheckInWindow(9 * 60, 10 * 60)
        viewModel.addCheckInWindow(9 * 60, 10 * 60)

        assertThat(viewModel.uiState.value.checkInWindows).hasSize(1)
    }

    // ------------------------------------------ 约束：锁机必须晚于打卡截止

    @Test
    fun `锁机开始早于打卡截止时不允许保存并把原因显示出来`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeCheckInWindow(0)
        viewModel.removeWindow(0)

        viewModel.addCheckInWindow(9 * 60, 18 * 60)
        // 锁机 15:00 开始，但打卡 18:00 才截止 —— 用户还没机会打卡就先被锁了
        viewModel.addWindow(LocalTime.of(15, 0), LocalTime.of(16, 0))
        val state = viewModel.uiState.value
        assertThat(state.lockWindowProblem).isNotNull()
        assertThat(state.lockWindowProblem).contains("晚于打卡截止")
        assertThat(state.canSave).isFalse()
        assertThat(state.isValid).isFalse()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).isEqualTo(state.lockWindowProblem)
        assertThat(lockDao.count()).isEqualTo(0)
    }

    @Test
    fun `锁机正好从打卡截止时刻开始时是合法的`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeCheckInWindow(0)
        viewModel.removeWindow(0)

        viewModel.addCheckInWindow(9 * 60, 10 * 60)
        viewModel.addWindow(LocalTime.of(10, 0), LocalTime.of(12, 0))

        val state = viewModel.uiState.value
        assertThat(state.lockWindowProblem).isNull()
        assertThat(state.canSave).isTrue()
    }

    @Test
    fun `删掉所有锁机时间时不允许保存`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeWindow(0)

        assertThat(viewModel.uiState.value.canSave).isFalse()
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).contains("锁机时间")
    }

    // ------------------------------------------------------------ 保存

    @Test
    fun `保存会按 habitId 落库且重复保存不会堆出第二条`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.setName("上班")
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        val saved = repo.getRuleForHabit(7L)
        assertThat(saved).isNotNull()
        assertThat(saved!!.name).isEqualTo("上班")
        assertThat(saved.habitId).isEqualTo(7L)

        // 再存一次：更新同一条
        viewModel.setName("上班（改）")
        viewModel.save()
        advanceUntilIdle()

        assertThat(lockDao.count()).isEqualTo(1)
        assertThat(repo.getRuleForHabit(7L)!!.name).isEqualTo("上班（改）")
    }

    @Test
    fun `保存会带上打卡时间`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()

        val saved = repo.getRuleForHabit(7L)!!
        assertThat(saved.checkInWindows).isNotEmpty()
        assertThat(saved.lockWindows).isNotEmpty()
        assertThat(saved.lockStartsAfterCheckIn).isTrue()
    }

    @Test
    fun `习惯没保存过时保存被拒绝`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.error).contains("先保存习惯")
        assertThat(lockDao.count()).isEqualTo(0)
    }

    // ------------------------------------ 锁机期间的改动不能让 App 崩溃

    @Test
    fun `锁机期间保存不崩溃，只把提示写进 error`() = runTest {
        repo.saveForHabit(7L, afternoonLock)
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.error).contains("锁机进行中")
        // 配置必须原样还在
        assertThat(repo.getRuleForHabit(7L)).isNotNull()
    }

    @Test
    fun `锁机期间保存不覆盖原有配置`() = runTest {
        repo.saveForHabit(7L, afternoonLock)
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.setName("被改掉的名字")
        viewModel.save()
        advanceUntilIdle()

        assertThat(repo.getRuleForHabit(7L)!!.name).isEqualTo("下午自习")
    }

    @Test
    fun `锁机期间仍可调整打卡时间，保存时才被拦下`() = runTest {
        repo.saveForHabit(7L, afternoonLock)
        viewModel.load(7L)
        advanceUntilIdle()
        // 这条配置本身没设打卡时间（checkInWindows 为空）
        assertThat(viewModel.uiState.value.checkInWindows).isEmpty()

        // 编辑本身的纯内存操作任何时候都不该失败
        viewModel.addCheckInWindow(6 * 60, 7 * 60)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.checkInWindows).hasSize(1)
        assertThat(viewModel.uiState.value.error).isNull()

        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).contains("锁机进行中")
    }

    // ---------------------------------------------- 开关（详细设置内）

    @Test
    fun `详细设置里的开关只切换 enabled，不删配置`() = runTest {
        repo.saveForHabit(7L, LockRule(name = "上班", windows = listOf(TimeWindow(7 * 60, 9 * 60))))
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.setLockEnabled(false)
        assertThat(viewModel.uiState.value.enabled).isFalse()

        viewModel.save()
        advanceUntilIdle()

        // 配置还在，只是停用了
        val saved = repo.getRuleForHabit(7L)
        assertThat(saved).isNotNull()
        assertThat(saved!!.enabled).isFalse()
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `formatWindow 对 24 点整给出 24 00 而不是 00 00`() {
        assertThat(formatWindow(TimeWindow(0, 1440))).isEqualTo("00:00-24:00")
        assertThat(formatWindow(TimeWindow(22 * 60 + 30, 7 * 60))).isEqualTo("22:30-07:00")
    }

    // ------------------------------------------- v2.1：锁机时长（不自选锁机时间）
    //
    // 用户要求："不自选锁机时间删除，改为超过打卡时间之后锁机，
    // 锁机的自选时间开始改为自选时长，最长为 24 小时"。
    // 于是这一页不再编辑"锁机时间"，只编辑**从打卡截止开始锁多久**。

    @Test
    fun `默认配置自带两小时时长，打开就能保存`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.lockDurationMinutes).isEqualTo(120)
        assertThat(state.lockDurationLabel).isEqualTo("2 小时")
        assertThat(state.canSaveOnScreen).isTrue()
        // 锁机起点＝打卡截止（默认打卡 09:00-10:00）
        assertThat(state.toRule().usesDurationMode).isTrue()
        assertThat(state.toRule().derivedLockWindows)
            .containsExactly(TimeWindow(10 * 60, 12 * 60))
    }

    @Test
    fun `载入旧式自选窗口规则时不带时长，界面要求先设时长`() = runTest {
        repo.saveForHabit(
            7L,
            LockRule(name = "旧规则", windows = listOf(TimeWindow(20 * 60, 22 * 60)))
        )
        viewModel.load(7L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.lockDurationMinutes).isEqualTo(0)
        // 老数据本身仍然合法（仓库层与调度层都还得认它）……
        assertThat(state.canSave).isTrue()
        // ……但界面上已经没有自选窗口的入口，所以要先把时长补上
        assertThat(state.canSaveOnScreen).isFalse()
    }

    @Test
    fun `设置时长会清掉旧式自选窗口`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.windows).isNotEmpty()

        viewModel.setLockDurationMinutes(180)

        val state = viewModel.uiState.value
        // 两种形态并存时 LockRule 只认时长，留着旧窗口只会显示出自相矛盾的锁机时间
        assertThat(state.windows).isEmpty()
        assertThat(state.toRule().usesDurationMode).isTrue()
        assertThat(state.toRule().derivedLockWindows)
            .containsExactly(TimeWindow(10 * 60, 13 * 60))
    }

    @Test
    fun `时长上限就是 24 小时，超出的会被夹住`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.setLockDurationMinutes(99999)

        assertThat(viewModel.uiState.value.lockDurationMinutes)
            .isEqualTo(LockRule.MAX_LOCK_DURATION_MINUTES)
        assertThat(viewModel.uiState.value.lockDurationLabel).isEqualTo("24 小时")
    }

    @Test
    fun `没设时长时不允许保存并提示请设置锁机时长`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.setLockDurationMinutes(0)

        assertThat(viewModel.uiState.value.canSaveOnScreen).isFalse()
        assertThat(viewModel.uiState.value.validationMessage).contains("请设置锁机时长")

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.error).contains("请设置锁机时长")
        assertThat(lockDao.count()).isEqualTo(0)
    }

    @Test
    fun `保存会把锁机时长写进规则`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.setLockDurationMinutes(240)
        viewModel.save()
        advanceUntilIdle()

        val saved = repo.getRuleForHabit(7L)!!
        assertThat(saved.lockDurationMinutes).isEqualTo(240)
        assertThat(saved.usesDurationMode).isTrue()
        assertThat(saved.effectiveLockWindows).containsExactly(TimeWindow(10 * 60, 14 * 60))
    }

    @Test
    fun `保存不会抹掉规则的生效日期`() = runTest {
        repo.saveForHabit(
            7L,
            LockRule(
                name = "上班",
                startDate = LocalDate.of(2026, 10, 1),
                endDate = LocalDate.of(2026, 12, 31),
                checkInWindows = listOf(TimeWindow(8 * 60, 9 * 60)),
                lockDurationMinutes = 60
            )
        )
        viewModel.load(7L)
        advanceUntilIdle()

        viewModel.setMessage("去自习")
        viewModel.save()
        advanceUntilIdle()

        val saved = repo.getRuleForHabit(7L)!!
        assertThat(saved.startDate).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(saved.endDate).isEqualTo(LocalDate.of(2026, 12, 31))
        assertThat(saved.message).isEqualTo("去自习")
    }

    @Test
    fun `addWindow 起止相同时不会塞进一个全天窗口`() = runTest {
        viewModel.load(7L)
        advanceUntilIdle()
        viewModel.removeWindow(0)

        // TimeWindow(x, x) 的 durationMinutes 是 1440（被当成跨零点）—— 必须显式拦下
        viewModel.addWindow(LocalTime.of(15, 0), LocalTime.of(15, 0))

        assertThat(viewModel.uiState.value.windows).isEmpty()
        assertThat(viewModel.uiState.value.error).contains("结束时间")
    }
}
