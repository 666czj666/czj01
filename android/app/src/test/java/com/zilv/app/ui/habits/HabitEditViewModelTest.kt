package com.zilv.app.ui.habits

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeAllowAppDao
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeHabitDao
import com.zilv.app.data.repository.FakeLockRuleDao
import com.zilv.app.data.repository.FakeRuleAllowAppDao
import com.zilv.app.data.repository.FakeSpotDao
import com.zilv.app.data.repository.FakeRuleCheckInDao
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.domain.model.Spot
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
import java.time.ZoneId

/**
 * [HabitEditViewModel] 的单元测试。
 *
 * **本文件为了锁死第二个真实 bug 而写**：
 * 用户反馈「创建好打卡点后在新建习惯界面无显示」。
 *
 * 根因是 `HabitEditViewModel` 根本没有注入 `SpotRepository`、
 * 界面上还留着「M5 接入选择器」的占位文字 —— 打卡点选择器**从未实现**。
 * 所以这里最关键的一条是：**新建习惯时打卡点列表必须被载入**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitEditViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var habitDao: FakeHabitDao
    private lateinit var spotDao: FakeSpotDao
    private lateinit var habitRepo: HabitRepository
    private lateinit var spotRepo: SpotRepository
    private lateinit var lockRepo: LockRuleRepository
    private lateinit var lockDao: FakeLockRuleDao
    private lateinit var viewModel: HabitEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        habitDao = FakeHabitDao()
        spotDao = FakeSpotDao()
        habitRepo = HabitRepository(habitDao, FakeCheckInDao(), clock)
        spotRepo = SpotRepository(spotDao, FakeRuleCheckInDao(), clock)
        lockDao = FakeLockRuleDao()
        lockRepo = LockRuleRepository(
            lockDao,
            FakeAllowAppDao(),
            FakeRuleAllowAppDao(),
            FakeRuleCheckInDao(),
            FakeCheckInDao(),
            clock
        )
        viewModel = HabitEditViewModel(habitRepo, spotRepo, lockRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newSpot(name: String = "三教一楼") = Spot(
        name = name,
        lat = 30.0,
        lng = 120.0,
        radiusM = 100,
        minAccuracyM = 50f
    )

    // ------------------------------------------ 回归：新建习惯要能看到打卡点

    @Test
    fun `新建习惯时载入已有打卡点`() = runTest {
        spotRepo.save(newSpot("三教一楼"))
        spotRepo.save(newSpot("图书馆"))

        viewModel.load(0L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.spotsLoaded).isTrue()
        assertThat(state.availableSpots.map { it.name })
            .containsExactly("三教一楼", "图书馆").inOrder()
    }

    @Test
    fun `先创建打卡点再回到新建习惯界面能看到它`() = runTest {
        // 用户路径：进新建习惯（此时没有打卡点）-> 去创建 -> 回来
        viewModel.load(0L)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.availableSpots).isEmpty()
        assertThat(viewModel.uiState.value.needsSpotSetup).isFalse() // 还没切到 LOCATION

        // 用户去「打卡点」页创建了一个
        spotRepo.save(newSpot("三教一楼"))

        // 返回时界面会调 reloadSpots()
        viewModel.reloadSpots()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.availableSpots).hasSize(1)
        assertThat(viewModel.uiState.value.availableSpots.first().name).isEqualTo("三教一楼")
    }

    @Test
    fun `没有打卡点时选到点打卡会提示先去创建`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setType(HabitType.LOCATION)
        viewModel.setName("到图书馆")

        val state = viewModel.uiState.value
        assertThat(state.needsSpotSetup).isTrue()
        assertThat(state.canSave).isFalse()
    }

    // ------------------------------------------------ v2.2：打卡条件（无 / 指定地点）
    //
    // 用户亲自纠正过术语：「打卡条件」是习惯级的两个取值 ——
    // 「指定地点」或「无」。而"解锁方法就是打卡方法"，所以这个选择
    // 同时决定了锁机怎么解锁。

    @Test
    fun `默认打卡条件是无，室内也能打卡`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("早起")

        val state = viewModel.uiState.value
        assertThat(state.checkInCondition).isEqualTo(CheckInCondition.NONE)
        assertThat(state.type).isEqualTo(HabitType.SIMPLE)
        assertThat(state.spotId).isNull()
        assertThat(state.canSave).isTrue()

        viewModel.save()
        advanceUntilIdle()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.checkInCondition).isEqualTo(CheckInCondition.NONE)
        assertThat(saved.checkInConditionLabel).isEqualTo("无条件")
    }

    @Test
    fun `选指定地点后必须选点，落库为 LOCATION 加 spotId`() = runTest {
        val spotId = spotRepo.save(newSpot())

        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("到图书馆")
        viewModel.setCheckInCondition(CheckInCondition.LOCATION)

        // 还没选点：不允许保存
        assertThat(viewModel.uiState.value.canSave).isFalse()
        assertThat(viewModel.uiState.value.checkInCondition).isEqualTo(CheckInCondition.LOCATION)

        viewModel.setSpotId(spotId)
        assertThat(viewModel.uiState.value.canSave).isTrue()
        viewModel.save()
        advanceUntilIdle()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.type).isEqualTo(HabitType.LOCATION)
        assertThat(saved.spotId).isEqualTo(spotId)
        assertThat(saved.checkInCondition).isEqualTo(CheckInCondition.LOCATION)
        assertThat(saved.checkInConditionLabel).isEqualTo("指定地点")
    }

    @Test
    fun `从指定地点切回无会清掉打卡点，而不是留下悬空引用`() = runTest {
        val spotId = spotRepo.save(newSpot())

        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("到图书馆")
        viewModel.setCheckInCondition(CheckInCondition.LOCATION)
        viewModel.setSpotId(spotId)

        viewModel.setCheckInCondition(CheckInCondition.NONE)

        val state = viewModel.uiState.value
        assertThat(state.spotId).isNull()
        assertThat(state.type).isEqualTo(HabitType.SIMPLE)

        viewModel.save()
        advanceUntilIdle()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.checkInCondition).isEqualTo(CheckInCondition.NONE)
        assertThat(saved.spotId).isNull()
    }

    @Test
    fun `载入已有习惯时按它的打卡条件回显`() = runTest {
        val spotId = spotRepo.save(newSpot())
        val locationHabit = habitRepo.save(
            Habit(name = "到图书馆", type = HabitType.LOCATION, spotId = spotId)
        )
        val simpleHabit = habitRepo.save(Habit(name = "早起", type = HabitType.SIMPLE))

        viewModel.load(locationHabit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.checkInCondition).isEqualTo(CheckInCondition.LOCATION)

        viewModel.load(simpleHabit)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.checkInCondition).isEqualTo(CheckInCondition.NONE)
    }

    @Test
    fun `没有打卡点时选指定地点会提示先去创建`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setCheckInCondition(CheckInCondition.LOCATION)
        viewModel.setName("到图书馆")

        val state = viewModel.uiState.value
        assertThat(state.needsSpotSetup).isTrue()
        assertThat(state.canSave).isFalse()

        // 门禁在 ViewModel 里也有一层：即使绕过界面点保存也不能落库
        viewModel.save()
        advanceUntilIdle()
        assertThat(habitRepo.getActiveHabits()).isEmpty()
    }

    // ------------------------------------------------------------ 选择与保存

    @Test
    fun `选中打卡点后可以保存且落库带 spotId`() = runTest {
        val spotId = spotRepo.save(newSpot())

        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("到图书馆")
        viewModel.setType(HabitType.LOCATION)
        viewModel.setSpotId(spotId)

        assertThat(viewModel.uiState.value.canSave).isTrue()
        assertThat(viewModel.uiState.value.selectedSpotName).isEqualTo("三教一楼")

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.name).isEqualTo("到图书馆")
        assertThat(saved.type).isEqualTo(HabitType.LOCATION)
        assertThat(saved.spotId).isEqualTo(spotId)
    }

    @Test
    fun `到点打卡未选打卡点时保存被拒绝且不落库`() = runTest {
        spotRepo.save(newSpot())
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("到图书馆")
        viewModel.setType(HabitType.LOCATION)

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.error).isNotNull()
        assertThat(habitRepo.getActiveHabits()).isEmpty()
    }

    @Test
    fun `纯打卡类型不需要打卡点即可保存`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("早起")
        viewModel.setType(HabitType.SIMPLE)

        assertThat(viewModel.uiState.value.canSave).isTrue()
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(habitRepo.getActiveHabits().single().name).isEqualTo("早起")
    }

    @Test
    fun `打卡条件选无时可以随时打卡（室内可用）`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("早起")
        viewModel.setCheckInCondition(CheckInCondition.NONE)

        viewModel.save()
        advanceUntilIdle()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.checkInCondition).isEqualTo(CheckInCondition.NONE)
        // 「无」的含义就是"点一下就算" —— 不再需要地点在证
        assertThat(habitRepo.requiresLocationProof(saved)).isFalse()
    }

    @Test
    fun `指定地点的习惯需要地点在证`() = runTest {
        val spotId = spotRepo.save(newSpot())

        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("到图书馆")
        viewModel.setCheckInCondition(CheckInCondition.LOCATION)
        viewModel.setSpotId(spotId)
        viewModel.save()
        advanceUntilIdle()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(habitRepo.requiresLocationProof(saved)).isTrue()
    }

    @Test
    fun `名称为空时保存被拒绝`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("   ")
        assertThat(viewModel.uiState.value.canSave).isFalse()

        viewModel.save()
        advanceUntilIdle()
        assertThat(habitRepo.getActiveHabits()).isEmpty()
        assertThat(viewModel.uiState.value.error).contains("名称")
    }

    @Test
    fun `从到点打卡切回纯打卡会清掉已选打卡点`() = runTest {
        val spotId = spotRepo.save(newSpot())
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setType(HabitType.LOCATION)
        viewModel.setSpotId(spotId)
        assertThat(viewModel.uiState.value.spotId).isEqualTo(spotId)

        viewModel.setType(HabitType.SIMPLE)
        assertThat(viewModel.uiState.value.spotId).isNull()
    }

    // ---------------------------------------------------------------- 编辑

    @Test
    fun `编辑已有习惯会带出它的打卡点与类型`() = runTest {
        val spotId = spotRepo.save(newSpot())
        val habitId = habitRepo.save(
            Habit(
                name = "到图书馆",
                type = HabitType.LOCATION,
                frequency = HabitFrequency.Daily,
                spotId = spotId
            )
        )

        viewModel.load(habitId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.id).isEqualTo(habitId)
        assertThat(state.name).isEqualTo("到图书馆")
        assertThat(state.type).isEqualTo(HabitType.LOCATION)
        assertThat(state.spotId).isEqualTo(spotId)
        assertThat(state.selectedSpotName).isEqualTo("三教一楼")
    }

    @Test
    fun `编辑习惯时同样能载入打卡点列表`() = runTest {
        val spotId = spotRepo.save(newSpot("图书馆"))
        val habitId = habitRepo.save(
            Habit(name = "读书", type = HabitType.SIMPLE, spotId = null)
        )
        spotRepo.save(newSpot("三教一楼"))

        viewModel.load(habitId)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.availableSpots).hasSize(2)
        assertThat(spotId).isNotEqualTo(0L)
    }

    @Test
    fun `编辑保存是更新而不是新增`() = runTest {
        val habitId = habitRepo.save(Habit(name = "早起"))
        viewModel.load(habitId)
        advanceUntilIdle()
        viewModel.setName("早起喝水")
        viewModel.save()
        advanceUntilIdle()

        val all = habitRepo.getActiveHabits()
        assertThat(all).hasSize(1)
        assertThat(all.single().name).isEqualTo("早起喝水")
        assertThat(all.single().id).isEqualTo(habitId)
    }

    // -------------------------------------------- 编辑页内的归档与删除
    //
    // 用户要求："在今日习惯的编辑界面里添加删除和归档功能"。

    @Test
    fun `编辑页归档会把习惯移出活跃列表`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.archive()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(habitRepo.getActiveHabits()).isEmpty()
        assertThat(habitRepo.getArchivedHabits().map { it.name }).containsExactly("早起")
    }

    @Test
    fun `编辑页请求删除只弹确认框，数据不能先没了`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.requestDelete()

        assertThat(viewModel.uiState.value.pendingDelete).isTrue()
        // 关键：数据必须还在
        assertThat(habitRepo.getHabit(id)).isNotNull()
    }

    @Test
    fun `编辑页取消删除后数据完好`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.requestDelete()
        viewModel.cancelDelete()

        assertThat(viewModel.uiState.value.pendingDelete).isFalse()
        assertThat(habitRepo.getHabit(id)).isNotNull()
    }

    @Test
    fun `编辑页确认删除后数据与打卡记录一并消失`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        habitRepo.checkIn(id)
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.requestDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(habitRepo.getHabit(id)).isNull()
        assertThat(habitRepo.checkedDates(id)).isEmpty()
        assertThat(viewModel.uiState.value.saved).isTrue()
    }

    @Test
    fun `新建习惯时归档与删除都是空操作`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.archive()
        viewModel.requestDelete()
        advanceUntilIdle()

        // id == 0 表示还没保存过，不该有任何效果
        assertThat(viewModel.uiState.value.pendingDelete).isFalse()
        assertThat(viewModel.uiState.value.saved).isFalse()
    }

    // ------------------------------------------------ v2.0：锁机并入习惯
    //
    // 用户要求"把锁机功能和习惯功能结合，删除单独锁机"。
    // 这里的重点是两件事：
    //   1. 开关打开时**必须**留下一条挂在 habitId 上的配置，否则界面上开了却没配置可编辑
    //   2. 仓库层在锁机期间会抛 RuleLockedException，未捕获会直接崩 —— 必须 runCatching

    @Test
    fun `新建习惯时锁机开关不可用并给出先保存的提示`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.lockEnabled).isFalse()
        assertThat(state.canOpenLockSettings).isFalse()
        assertThat(state.lockHint).isEqualTo("先保存习惯，再设置锁机")

        // 即使硬点一下也不能落库（没有 habitId 可挂）
        viewModel.setLockEnabled(true)
        advanceUntilIdle()
        assertThat(lockDao.count()).isEqualTo(0)
        assertThat(viewModel.uiState.value.lockEnabled).isFalse()
    }

    @Test
    fun `载入已有习惯时带出它的锁机开关状态`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        lockRepo.saveForHabit(id, HabitEditViewModel.defaultLockRule("早起"))

        viewModel.load(id)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lockEnabled).isTrue()
        assertThat(viewModel.uiState.value.lockRule).isNotNull()
        assertThat(viewModel.uiState.value.lockRule!!.habitId).isEqualTo(id)
    }

    @Test
    fun `打开锁机开关会落一份默认配置并挂到这个习惯上`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setLockEnabled(true)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lockEnabled).isTrue()
        val rule = lockRepo.getRuleForHabit(id)
        assertThat(rule).isNotNull()
        assertThat(rule!!.habitId).isEqualTo(id)
        // 默认配置本身必须合法，否则用户点进"详细设置"先看到一句报错
        assertThat(rule.isValid).isTrue()
        assertThat(rule.lockWindowProblem).isNull()
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `关闭锁机开关会删掉配置`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        lockRepo.saveForHabit(id, HabitEditViewModel.defaultLockRule("早起"))
        viewModel.load(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.lockEnabled).isTrue()

        viewModel.setLockEnabled(false)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lockEnabled).isFalse()
        assertThat(lockRepo.getRuleForHabit(id)).isNull()
    }

    @Test
    fun `已有配置时再打开开关不会堆出第二条配置`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        lockRepo.saveForHabit(id, HabitEditViewModel.defaultLockRule("早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setLockEnabled(true)
        advanceUntilIdle()

        assertThat(lockDao.count()).isEqualTo(1)
    }

    @Test
    fun `锁机期间关闭锁机开关不崩溃，只把提示写进 error`() = runTest {
        // 时间固定在 2026-10-14 16:00 (+08:00)，下面这条窗口正好正在锁机
        val id = habitRepo.save(Habit(name = "下午自习"))
        lockRepo.saveForHabit(
            id,
            LockRule(
                name = "下午自习",
                windows = listOf(TimeWindow(15 * 60, 18 * 60)),
                unlockMode = UnlockMode.TIME_ONLY
            )
        )
        viewModel.load(id)
        advanceUntilIdle()

        // 修复前这一步会让 App 直接崩掉
        viewModel.setLockEnabled(false)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).contains("锁机进行中")
        // 配置必须还在，没被偷偷删掉
        assertThat(lockRepo.getRuleForHabit(id)).isNotNull()
    }

    @Test
    fun `锁机期间删除习惯会被拦下，习惯与配置都还在`() = runTest {
        val id = habitRepo.save(Habit(name = "下午自习"))
        lockRepo.saveForHabit(
            id,
            LockRule(
                name = "下午自习",
                windows = listOf(TimeWindow(15 * 60, 18 * 60)),
                unlockMode = UnlockMode.TIME_ONLY
            )
        )
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.requestDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).contains("锁机进行中")
        assertThat(habitRepo.getHabit(id)).isNotNull()
        assertThat(lockRepo.getRuleForHabit(id)).isNotNull()
    }

    @Test
    fun `删除习惯时会连带删掉它的锁机配置`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        lockRepo.saveForHabit(id, HabitEditViewModel.defaultLockRule("早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.requestDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(habitRepo.getHabit(id)).isNull()
        // 关键：不能留下挂在已删除 habitId 上的孤儿规则 ——
        // 它仍会被调度器当成启用规则去拦截用户
        assertThat(lockRepo.getRuleForHabit(id)).isNull()
        assertThat(viewModel.uiState.value.lockEnabled).isFalse()
        assertThat(lockDao.count()).isEqualTo(0)
    }

    @Test
    fun `从详细设置返回后摘要能刷新到新配置`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.lockEnabled).isFalse()

        // 用户切进"详细设置"，打开并保存了一份配置
        lockRepo.saveForHabit(id, HabitEditViewModel.defaultLockRule("早起"))

        // 返回时界面会调 refreshLock()
        viewModel.refreshLock()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lockEnabled).isTrue()
        assertThat(viewModel.uiState.value.lockRule).isNotNull()
    }

    @Test
    fun `新建习惯时 refreshLock 是空操作`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.refreshLock()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.lockEnabled).isFalse()
        assertThat(viewModel.uiState.value.lockRule).isNull()
    }

    @Test
    fun `配置被停用时习惯上仍然显示为已配置锁机`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        // 用户在"详细设置"里把启用关掉了 —— 配置还在，只是不生效
        lockRepo.saveForHabit(
            id,
            HabitEditViewModel.defaultLockRule("早起").copy(enabled = false)
        )

        viewModel.load(id)
        advanceUntilIdle()

        // 开关反映"有没有配置"，摘要因此仍可读、仍能进"详细设置"改回来
        assertThat(viewModel.uiState.value.lockEnabled).isTrue()
        assertThat(viewModel.uiState.value.lockRule!!.enabled).isFalse()
    }

    // ----------------------------------------------- v2.1：惩罚（无 / 锁机）
    //
    // 用户要求："把设置今日习惯时可以选择惩罚，分为无和锁机，选择锁机后出现选择锁机时长"。
    //
    // 这一组的关键在**落库时机**：改动只留在内存里，点保存时才一并写库 ——
    // 每改一下就写库的话，锁机守卫很容易把中间态拦下（抛 RuleLockedException）。

    @Test
    fun `新建习惯时惩罚区只有先保存的提示，选锁机不会落库`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.punishment).isEqualTo(HabitPunishment.NONE)
        assertThat(state.canOpenLockSettings).isFalse()

        // 即使硬点一下也不能落库：锁机配置挂不到 habitId 上
        viewModel.setPunishment(HabitPunishment.LOCK)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.punishment).isEqualTo(HabitPunishment.NONE)
        assertThat(lockDao.count()).isEqualTo(0)
    }

    @Test
    fun `改惩罚不落库，点保存才写入锁机配置`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setPunishment(HabitPunishment.LOCK)
        viewModel.setLockDurationMinutes(180)
        viewModel.addCheckInWindow(9 * 60, 10 * 60)
        advanceUntilIdle()

        // 只改内存：库里还不该有任何配置
        assertThat(lockDao.count()).isEqualTo(0)
        assertThat(viewModel.uiState.value.punishment).isEqualTo(HabitPunishment.LOCK)
        assertThat(viewModel.uiState.value.canSave).isTrue()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        val rule = lockRepo.getRuleForHabit(id)!!
        assertThat(rule.habitId).isEqualTo(id)
        assertThat(rule.lockDurationMinutes).isEqualTo(180)
        assertThat(rule.checkInWindows).containsExactly(TimeWindow(9 * 60, 10 * 60))
        // 锁机从打卡截止开始推导：10:00 + 3 小时
        assertThat(rule.derivedLockWindows).containsExactly(TimeWindow(10 * 60, 13 * 60))
    }

    @Test
    fun `惩罚选无时保存会把已有配置删掉`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        lockRepo.saveForHabit(id, HabitEditViewModel.defaultLockRule("早起"))
        viewModel.load(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.punishment).isEqualTo(HabitPunishment.LOCK)

        viewModel.setPunishment(HabitPunishment.NONE)
        viewModel.save()
        advanceUntilIdle()

        assertThat(lockRepo.getRuleForHabit(id)).isNull()
        assertThat(lockDao.count()).isEqualTo(0)
        assertThat(viewModel.uiState.value.lockEnabled).isFalse()
    }

    @Test
    fun `惩罚选锁机但没有打卡时间时不允许保存`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setPunishment(HabitPunishment.LOCK)

        // 锁机起点就是打卡截止，没有打卡时间就没有锁机 —— 不能存下一条永远不生效的规则
        assertThat(viewModel.uiState.value.canSave).isFalse()
        assertThat(viewModel.uiState.value.punishmentProblem).contains("打卡时间")

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(lockDao.count()).isEqualTo(0)
    }

    @Test
    fun `打卡时间起止相同时不会被塞进一个全天窗口`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        // TimeWindow(540, 540) 的 durationMinutes 是 1440 而不是 0 —— 必须显式拦下
        viewModel.addCheckInWindow(9 * 60, 9 * 60)

        assertThat(viewModel.uiState.value.checkInWindows).isEmpty()
        assertThat(viewModel.uiState.value.error).contains("结束时间")
    }

    @Test
    fun `锁机时长上限是 24 小时且文案可读`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setLockDurationMinutes(2000)
        assertThat(viewModel.uiState.value.lockDurationMinutes)
            .isEqualTo(LockRule.MAX_LOCK_DURATION_MINUTES)
        assertThat(viewModel.uiState.value.lockDurationLabel).isEqualTo("24 小时")

        viewModel.setLockDurationMinutes(150)
        assertThat(viewModel.uiState.value.lockDurationLabel).isEqualTo("2 小时 30 分")
    }

    @Test
    fun `从详细设置返回会带出新的时长与打卡时间`() = runTest {
        val id = habitRepo.save(Habit(name = "早课"))
        viewModel.load(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.punishment).isEqualTo(HabitPunishment.NONE)

        // 用户在"详细设置"里存了一份新式配置
        lockRepo.saveForHabit(
            id,
            LockRule(
                name = "早课",
                checkInWindows = listOf(TimeWindow(7 * 60, 8 * 60)),
                lockDurationMinutes = 240
            )
        )

        // 返回时界面会调 refreshLock()
        viewModel.refreshLock()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.punishment).isEqualTo(HabitPunishment.LOCK)
        assertThat(state.lockDurationMinutes).isEqualTo(240)
        assertThat(state.checkInWindows).containsExactly(TimeWindow(7 * 60, 8 * 60))
    }

    @Test
    fun `从详细设置返回不会冲掉还没保存的惩罚改动`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setPunishment(HabitPunishment.LOCK)
        viewModel.setLockDurationMinutes(60)
        viewModel.addCheckInWindow(6 * 60, 7 * 60)

        // 用户跑去别的页面转了一圈又回来（界面会调 refreshLock）
        viewModel.refreshLock()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.punishment).isEqualTo(HabitPunishment.LOCK)
        assertThat(state.lockDurationMinutes).isEqualTo(60)
        assertThat(state.checkInWindows).containsExactly(TimeWindow(6 * 60, 7 * 60))
    }

    @Test
    fun `锁机期间保存惩罚改动不崩溃，并说明习惯已经存好了`() = runTest {
        // 时间固定在 16:00 (+08:00)：打卡 15:00-15:30 + 时长 3 小时 → 15:30-18:30 正在锁机
        val id = habitRepo.save(Habit(name = "下午自习"))
        lockRepo.saveForHabit(
            id,
            LockRule(
                name = "下午自习",
                checkInWindows = listOf(TimeWindow(15 * 60, 15 * 60 + 30)),
                lockDurationMinutes = 180
            )
        )
        viewModel.load(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.lockDurationMinutes).isEqualTo(180)

        viewModel.setName("下午自习（改）")
        viewModel.setLockDurationMinutes(240)
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.saved).isFalse()
        // 修复前这里要么崩掉，要么只说一句"保存失败"，用户不知道习惯其实存好了
        assertThat(state.error).contains("习惯已保存")
        assertThat(state.error).contains("锁机进行中")
        assertThat(habitRepo.getHabit(id)!!.name).isEqualTo("下午自习（改）")
        // 锁机配置必须原样不动
        assertThat(lockRepo.getRuleForHabit(id)!!.lockDurationMinutes).isEqualTo(180)
    }

    @Test
    fun `新建习惯保存后再进来设惩罚，配置能挂到新 id 上`() = runTest {
        // 用户路径：新建 -> 保存（此时还没有惩罚控件）-> 再进来设惩罚
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("早起")
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.saved).isTrue()

        val id = habitRepo.getActiveHabits().single().id
        viewModel.load(id)
        advanceUntilIdle()
        viewModel.setPunishment(HabitPunishment.LOCK)
        viewModel.setLockDurationMinutes(60)
        viewModel.addCheckInWindow(7 * 60, 8 * 60)
        viewModel.save()
        advanceUntilIdle()

        val rule = lockRepo.getRuleForHabit(id)!!
        assertThat(rule.lockDurationMinutes).isEqualTo(60)
        assertThat(rule.derivedLockWindows).containsExactly(TimeWindow(8 * 60, 9 * 60))
        // 不能因此多出一条习惯
        assertThat(habitRepo.getActiveHabits()).hasSize(1)
    }

    // ------------------------------------------ 指定每月几号（v2.13，取代每月 N 次）

    /**
     * 用户原话：「删除每月打卡多少次的选项，改为指定每月1-31号打卡。」
     *
     * 这一组覆盖 **界面层 → 领域层 → 数据库 → 回显** 的整条链路：
     * 能选、能存、能读回来。`HabitFrequencyTest` / `StreakCalculatorTest`
     * 负责语义，这里负责"点了之后状态对不对、存下去的还是不是它"。
     */

    @Test
    fun `指定每月几号 - 点一下加入选中集合`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.setFrequency(HabitFrequency.SpecificMonthDays(emptySet()))
        viewModel.toggleMonthDay(15)

        val frequency = viewModel.uiState.value.frequency
        assertThat(frequency).isInstanceOf(HabitFrequency.SpecificMonthDays::class.java)
        assertThat((frequency as HabitFrequency.SpecificMonthDays).days).containsExactly(15)
    }

    @Test
    fun `指定每月几号 - 再点一下取消选中`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.setFrequency(HabitFrequency.SpecificMonthDays(emptySet()))
        viewModel.toggleMonthDay(15)
        viewModel.toggleMonthDay(1)
        assertThat((viewModel.uiState.value.frequency as HabitFrequency.SpecificMonthDays).days)
            .containsExactly(1, 15)

        viewModel.toggleMonthDay(15)
        assertThat((viewModel.uiState.value.frequency as HabitFrequency.SpecificMonthDays).days)
            .containsExactly(1)
    }

    @Test
    fun `指定每月几号 - 边界 1 与 31 都能选`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.setFrequency(HabitFrequency.SpecificMonthDays(emptySet()))
        viewModel.toggleMonthDay(HabitFrequency.SpecificMonthDays.MIN_DAY)
        viewModel.toggleMonthDay(HabitFrequency.SpecificMonthDays.MAX_DAY)

        val days = (viewModel.uiState.value.frequency as HabitFrequency.SpecificMonthDays).days
        assertThat(days).containsExactly(1, 31)
    }

    @Test
    fun `指定每月几号 - 越界日期被忽略而不是让页面崩掉`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.setFrequency(HabitFrequency.SpecificMonthDays(setOf(10)))
        // 0 / 32 / 负数都只可能来自"代码传错"：忽略一次点击，用户察觉不到；
        // 若直接构造 SpecificMonthDays(0) 会在 require 上抛异常、整页崩掉
        viewModel.toggleMonthDay(0)
        viewModel.toggleMonthDay(32)
        viewModel.toggleMonthDay(-1)

        assertThat((viewModel.uiState.value.frequency as HabitFrequency.SpecificMonthDays).days)
            .containsExactly(10)
    }

    @Test
    fun `指定每月几号能落库并能读回来`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("每月一号十五号")
        viewModel.setFrequency(HabitFrequency.SpecificMonthDays(emptySet()))
        viewModel.toggleMonthDay(1)
        viewModel.toggleMonthDay(15)
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.saved).isTrue()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.frequency)
            .isEqualTo(HabitFrequency.SpecificMonthDays(setOf(1, 15)))
    }

    @Test
    fun `编辑已有习惯时指定每月几号会回显`() = runTest {
        val id = habitRepo.save(
            Habit(
                name = "只做 31 号",
                frequency = HabitFrequency.SpecificMonthDays(setOf(31))
            )
        )
        viewModel.load(id)
        advanceUntilIdle()

        val days = (viewModel.uiState.value.frequency as HabitFrequency.SpecificMonthDays).days
        assertThat(days).containsExactly(31)
    }

    // ------------------------------------------ 指定星期含周日（v2.13，功能 1）

    /**
     * 用户原话：「打卡频率里指定星期加上周日选项。」
     *
     * 根因是**布局溢出**（7 个 48dp 芯片放不进一行，最后的「日」被挤出屏幕），
     * 不是这里少了一条数据。以下几条把"周日在数据这一侧本来就能选、能存、能回显"
     * 钉死 —— 万一以后界面又出问题，这些测试能立刻区分"是界面还是数据"。
     */

    @Test
    fun `指定星期 - 周日在七天内可选中`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.setFrequency(HabitFrequency.SpecificDays(emptySet()))
        // 七个都点一遍（界面上的「一」..「日」就是这七个）
        val allDays = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        )
        allDays.forEach { viewModel.toggleSpecificDay(it) }

        val days = (viewModel.uiState.value.frequency as HabitFrequency.SpecificDays).days
        assertThat(days).containsExactlyElementsIn(allDays)
        assertThat(days).contains(DayOfWeek.SUNDAY)
    }

    @Test
    fun `指定星期 - 只选周日能落库并能读回来`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("只在周日做")
        viewModel.setFrequency(HabitFrequency.SpecificDays(emptySet()))
        viewModel.toggleSpecificDay(DayOfWeek.SUNDAY)
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.saved).isTrue()

        val saved = habitRepo.getActiveHabits().single()
        assertThat(saved.frequency).isEqualTo(HabitFrequency.SpecificDays(setOf(DayOfWeek.SUNDAY)))
    }

    @Test
    fun `编辑已有习惯时指定星期含周日会回显`() = runTest {
        val id = habitRepo.save(
            Habit(
                name = "周日",
                frequency = HabitFrequency.SpecificDays(
                    setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                )
            )
        )
        viewModel.load(id)
        advanceUntilIdle()

        val days = (viewModel.uiState.value.frequency as HabitFrequency.SpecificDays).days
        assertThat(days).containsExactly(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    }

    // --------------------------------------------------- 预填名称（v2.5 的 C）

    @Test
    fun `从添加页带过来的昵称会预填进新建习惯的名称`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.prefillName("晨读")

        assertThat(viewModel.uiState.value.name).isEqualTo("晨读")
    }

    @Test
    fun `编辑已有习惯时预填昵称不会覆盖库里的名字`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.prefillName("弹窗里随手写的")

        assertThat(viewModel.uiState.value.name).isEqualTo("早起")
    }

    @Test
    fun `空白昵称不会预填`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.prefillName("   ")

        assertThat(viewModel.uiState.value.name).isEmpty()
    }

    @Test
    fun `load 之后 loadedOnce 为真，界面据此决定何时预填`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.loadedOnce).isTrue()

        val id = habitRepo.save(Habit(name = "早起"))
        val fresh = HabitEditViewModel(habitRepo, spotRepo, lockRepo)
        fresh.load(id)
        advanceUntilIdle()
        assertThat(fresh.uiState.value.loadedOnce).isTrue()
    }

    // ------------------------------------------- 新建习惯弹窗（本轮改造）
    //
    // 用户要求"把新建习惯界面改为弹窗"：弹窗每次打开都必须是一张白纸，
    // 而且它只从「添加」页打开 —— 也就是锁机期间也要能建（见 allowDuringLock）。

    @Test
    fun `startCreate 会把上一次残留的草稿清成一张白纸`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("填了一半")
        // v2.14 改写：这里原来是 `setWeeklyTimes(5)`（该方法已随「每周 N 次」删除）。
        // 归类：**测试错（旧规格）**。改成同样"改成一个非默认频率"的调用 ——
        // `startCreate` 必须把它重置回每天，断言强度不变。
        viewModel.setFrequency(HabitFrequency.SpecificMonthDays(setOf(3, 18)))
        viewModel.setCheckInCondition(CheckInCondition.NONE)

        // 弹窗反复开关用的是同一个 ViewModel 实例，所以必须显式重置
        viewModel.startCreate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.name).isEmpty()
        assertThat(state.frequency).isEqualTo(HabitFrequency.Daily)
        assertThat(state.checkInCondition).isEqualTo(CheckInCondition.NONE)
        assertThat(state.saved).isFalse()
        assertThat(state.loadedOnce).isTrue()
    }

    @Test
    fun `startCreate 默认带上添加页的放行标记`() = runTest {
        viewModel.startCreate()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.allowDuringLock).isTrue()
    }

    @Test
    fun `新的弹窗草稿必须立刻清掉上一次的 saved`() {
        // 这是实现时真实撞到的一个 bug：弹窗关闭后 ViewModel 还活着，
        // 上一次保存成功的 `saved = true` 一直留着 —— 弹窗下一次打开的
        // 第一次组合读到它就会**立刻把自己关掉**。
        //
        // 所以重置必须是**同步**的纯函数（不依赖协程调度器）：
        // 这里刻意不用 runTest 的协程环境，直接断言函数返回值。
        val afterSave = HabitEditUiState(
            id = 7L,
            name = "刚建好的",
            saved = true,
            lockEnabled = true,
            allowDuringLock = false,
            availableSpots = emptyList(),
            spotsLoaded = true
        )

        val fresh = HabitEditUiState.freshCreateState(afterSave, allowDuringLock = true)

        assertThat(fresh.saved).isFalse()
        assertThat(fresh.name).isEmpty()
        assertThat(fresh.id).isEqualTo(0L)
        assertThat(fresh.lockRule).isNull()
        assertThat(fresh.lockEnabled).isFalse()
        assertThat(fresh.allowDuringLock).isTrue()
        // 打卡点列表保留下来，弹窗不会闪一下空列表
        assertThat(fresh.spotsLoaded).isTrue()
    }

    @Test
    fun `load 不带参数时默认不放行（习惯管理页那条路径）`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.allowDuringLock).isFalse()
    }

    // ------------------------------------------- 锁机期间的新建/编辑（本轮第 5 条要求）
    //
    // 时钏固定在 2026-10-14 16:00 (+08:00)，所以 15:00-18:00 的规则此刻正在锁机。

    private fun lockedNow(habitId: Long) = LockRule(
        habitId = habitId,
        name = "自习",
        windows = listOf(TimeWindow(15 * 60, 18 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    @Test
    fun `锁机期间从添加页新建习惯是允许的`() = runTest {
        // 别的习惯正在锁机（用一条挂在它身上的规则造出 LOCKED）
        val other = habitRepo.save(Habit(name = "别的"))
        lockRepo.saveForHabit(other, lockedNow(other))
        assertThat(lockRepo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        viewModel.startCreate()          // 「添加」页打开新建习惯弹窗
        advanceUntilIdle()
        viewModel.setName("锁机时新建的")
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(habitRepo.getActiveHabits().map { it.name }).contains("锁机时新建的")
    }

    @Test
    fun `锁机期间从添加页改一条没在锁机的习惯也能存下惩罚`() = runTest {
        // ⚠️ 顺序有讲究：B 的规则必须**先**建，那时还没人锁着；
        // 建完 A 的规则之后全局才是 LOCKED（否则 B 自己也会被守卫拦下）
        val b = habitRepo.save(Habit(name = "B"))
        lockRepo.saveForHabit(
            b,
            LockRule(
                name = "B",
                checkInWindows = listOf(TimeWindow(6 * 60, 7 * 60)),
                lockDurationMinutes = 60,
                unlockMode = UnlockMode.TIME_ONLY
            )
        )
        // A 的规则 15:00-18:00 此刻（16:00）正在锁机
        val a = habitRepo.save(Habit(name = "A"))
        lockRepo.saveForHabit(a, lockedNow(a))
        assertThat(lockRepo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        viewModel.load(b, allowDuringLock = true)
        advanceUntilIdle()
        viewModel.setPunishment(HabitPunishment.LOCK)
        viewModel.setLockDurationMinutes(120)
        viewModel.addCheckInWindow(6 * 60, 7 * 60)
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.saved).isTrue()
        assertThat(state.error).isNull()
        assertThat(lockRepo.getRuleForHabit(b)!!.lockDurationMinutes).isEqualTo(120)
    }

    @Test
    fun `锁机期间从添加页改正在锁机的那条仍然被拒绝并给出可读提示`() = runTest {
        val a = habitRepo.save(Habit(name = "A"))
        lockRepo.saveForHabit(a, lockedNow(a))

        viewModel.load(a, allowDuringLock = true)
        advanceUntilIdle()
        // 用户想把惩罚改成"无"—— 那等于把当前这把锁关掉
        viewModel.setPunishment(HabitPunishment.NONE)
        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.saved).isFalse()
        assertThat(state.error).contains("锁机进行中")
        // 锁还在，没被偷偷关掉
        assertThat(lockRepo.getRuleForHabit(a)).isNotNull()
    }

    @Test
    fun `锁机期间从添加页删掉正在锁机的那条习惯仍然被拒绝`() = runTest {
        val a = habitRepo.save(Habit(name = "A"))
        lockRepo.saveForHabit(a, lockedNow(a))

        viewModel.load(a, allowDuringLock = true)
        advanceUntilIdle()
        viewModel.requestDelete()
        viewModel.confirmDelete()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.pendingDelete).isFalse()
        assertThat(state.error).contains("锁机进行中")
        // 配置与习惯都还在
        assertThat(lockRepo.getRuleForHabit(a)).isNotNull()
        assertThat(habitRepo.getHabit(a)).isNotNull()
    }

    // ------------------------------------------- 打卡时间：新增窗口（本轮交互改造）

    @Test
    fun `跨零点的打卡时间是合法的（23 点到 1 点）`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.addCheckInWindow(23 * 60, 1 * 60)

        assertThat(viewModel.uiState.value.checkInWindows)
            .containsExactly(TimeWindow(23 * 60, 1 * 60))
        assertThat(viewModel.uiState.value.error).isNull()
    }

    @Test
    fun `结束时间取 00 00 时读作 24 点而不是空窗口`() = runTest {
        val id = habitRepo.save(Habit(name = "早起"))
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.addCheckInWindow(8 * 60, 0)

        assertThat(viewModel.uiState.value.checkInWindows)
            .containsExactly(TimeWindow(8 * 60, 1440))
    }
}