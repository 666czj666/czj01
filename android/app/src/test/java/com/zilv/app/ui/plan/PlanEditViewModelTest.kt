package com.zilv.app.ui.plan

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakePlanDao
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.domain.lock.TimeWindow
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
 * [PlanEditViewModel] 的单元测试（v2.5 的 D）。
 *
 * 用户要求：
 *  - "只能建明天的规划"（仓库层强制，界面要给出可读的失败提示）
 *  - "不能跨 0 点"（`end > start`）
 *  - "起止相同要显式拒绝"（`TimeWindow(x, x)` 是**全天 1440 分钟**，不是 0 分钟）
 *  - "编辑：可改名称与时间；删除：编辑里要能删除"
 *
 * 时间固定在 2026-10-14 16:00 (+08:00)：今天 = 10-14，明天 = 10-15。
 *
 * ## 这个文件也在钉"异常不会漏出去"
 *
 * `PlanRepository` 的三个写入口都会抛 `PlanRejectedException`，
 * 而 `viewModelScope` 里的未捕获异常会**直接终止 App 进程**（本项目踩过两次）。
 * 所以这里刻意构造所有会被拒绝的输入，断言它们都变成 `uiState.error`
 * 而不是把异常抛到 `runTest` 外面 —— 若哪天有人把 `runCatching` 删了，
 * 这些用例会立刻失败。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanEditViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)
    private val tomorrow: LocalDate = LocalDate.of(2026, 10, 15)

    private lateinit var dao: FakePlanDao
    private lateinit var repo: PlanRepository
    private lateinit var viewModel: PlanEditViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = FakePlanDao()
        repo = PlanRepository(dao, Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone))
        viewModel = PlanEditViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------ 新建

    @Test
    fun `新建时日期就是明天`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isNew).isTrue()
        assertThat(state.date).isEqualTo(tomorrow)
        // 时间**不预填**：编造一个默认时段会让用户以为"已经设好了"
        assertThat(state.startMinute).isNull()
        assertThat(state.endMinute).isNull()
        assertThat(state.loadedOnce).isTrue()
    }

    @Test
    fun `名称与时间都填好之后可以保存`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("读书")
        viewModel.setStartMinute(9 * 60)
        viewModel.setEndMinute(11 * 60)

        assertThat(viewModel.uiState.value.inputProblem).isNull()
        assertThat(viewModel.uiState.value.canSave).isTrue()

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(viewModel.uiState.value.error).isNull()
        val saved = repo.getForDay(tomorrow).single()
        assertThat(saved.name).isEqualTo("读书")
        assertThat(saved.window).isEqualTo(TimeWindow(9 * 60, 11 * 60))
    }

    @Test
    fun `名称为空不给出保存`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setStartMinute(9 * 60)
        viewModel.setEndMinute(11 * 60)

        assertThat(viewModel.uiState.value.canSave).isFalse()
        assertThat(viewModel.uiState.value.inputProblem).contains("名称")
    }

    @Test
    fun `没设时间不给出保存`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("读书")
        viewModel.setStartMinute(9 * 60)

        assertThat(viewModel.uiState.value.canSave).isFalse()
        assertThat(viewModel.uiState.value.inputProblem).contains("开始时间和结束时间")
    }

    // ------------------------------------------------- 起止相同 / 跨 0 点

    @Test
    fun `起止相同被显式拒绝（否则会被当成全天锁机）`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("会议")
        viewModel.setStartMinute(10 * 60)
        viewModel.setEndMinute(10 * 60)

        val problem = viewModel.uiState.value.inputProblem!!
        assertThat(problem).contains("不能相同")
        assertThat(problem).contains("全天")
        assertThat(viewModel.uiState.value.canSave).isFalse()

        // 即使绕过界面直接调 save，也必须被拦下且不写库
        viewModel.save()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.error).contains("不能相同")
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `跨 0 点被拒绝`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("通宵")
        viewModel.setStartMinute(22 * 60)
        viewModel.setEndMinute(6 * 60)

        assertThat(viewModel.uiState.value.inputProblem).contains("不能跨 0 点")
        assertThat(viewModel.uiState.value.canSave).isFalse()
    }

    @Test
    fun `23点到23点59分是允许的`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        viewModel.setName("睡前复盘")
        viewModel.setStartMinute(23 * 60)
        viewModel.setEndMinute(23 * 60 + 59)

        assertThat(viewModel.uiState.value.inputProblem).isNull()
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(repo.getForDay(tomorrow).single().durationMinutes).isEqualTo(59)
    }

    // -------------------------------------------------------- 仓库层拒绝

    @Test
    fun `名称全是空白会被仓库拒绝，界面拿到可读理由`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()
        // 界面这道校验先拦住（canSave = false），但即使绕过也必须被仓库拒绝
        viewModel.setName("   ")
        viewModel.setStartMinute(9 * 60)
        viewModel.setEndMinute(11 * 60)

        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.error).contains("名称")
        assertThat(dao.count()).isEqualTo(0)
    }

    /**
     * "不是明天"这条拒绝路径由 `PlanRepositoryTest` 逐条覆盖
     * （今天 / 后天 / 过去的日期 / 无法识别的 epochDay）。
     *
     * 这里**不重复**构造它，理由是：要在这个 ViewModel 上造出那个场景，
     * 只能把日期改成"不是明天"——而界面里根**不提供**修改日期的入口
     * （日期只读，由 `PlanRepository.tomorrow()` 决定），
     * 为了测试而加一个生产代码里用不到的 setter 是本末倒置。
     *
     * 上面那条"名称空白被拒绝"覆盖的正是这里真正想验证的东西：
     * **仓库抛出的 `PlanRejectedException` 被 `runCatching` 接住了**，
     * 变成 `uiState.error` 而不是把 App 打崩。
     */

    // ------------------------------------------------------------ 编辑

    @Test
    fun `编辑已有规划时读出库里的值`() = runTest {
        val id = repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)

        viewModel.load(id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isNew).isFalse()
        assertThat(state.name).isEqualTo("读书")
        assertThat(state.date).isEqualTo(tomorrow)
        assertThat(state.startMinute).isEqualTo(9 * 60)
        assertThat(state.endMinute).isEqualTo(11 * 60)
    }

    @Test
    fun `编辑可以改名称与时间`() = runTest {
        val id = repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.setName("看论文")
        viewModel.setStartMinute(10 * 60)
        viewModel.setEndMinute(12 * 60)
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        val updated = repo.getById(id)!!
        assertThat(updated.name).isEqualTo("看论文")
        assertThat(updated.window).isEqualTo(TimeWindow(10 * 60, 12 * 60))
    }

    @Test
    fun `编辑不存在的规划给出可读错误`() = runTest {
        viewModel.load(999L)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.error).contains("不存在")
        assertThat(viewModel.uiState.value.loadedOnce).isTrue()
    }

    // -------------------------------------- 进行中的规划不能改 / 删（仓库层）

    @Test
    fun `进行中的规划不能保存修改，理由显示在界面上`() = runTest {
        // 直接往 DAO 塞一条"今天 15:00-17:00"的规划，并把时钟固定在 16:00
        dao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = "读书",
                epochDay = today.toEpochDay(),
                startMinute = 15 * 60,
                endMinute = 17 * 60,
                createdAt = 0L
            )
        )
        viewModel.load(1L)
        advanceUntilIdle()
        viewModel.setName("偷偷改掉")
        viewModel.save()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isFalse()
        assertThat(viewModel.uiState.value.error).contains("正在进行中")
        assertThat(dao.getById(1L)!!.name).isEqualTo("读书")
    }

    @Test
    fun `进行中的规划不能删除，理由显示在界面上`() = runTest {
        dao.insert(
            com.zilv.app.data.local.entity.PlanEntity(
                name = "读书",
                epochDay = today.toEpochDay(),
                startMinute = 15 * 60,
                endMinute = 17 * 60,
                createdAt = 0L
            )
        )
        viewModel.load(1L)
        advanceUntilIdle()

        viewModel.requestDelete()
        assertThat(viewModel.uiState.value.pendingDelete).isTrue()

        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.pendingDelete).isFalse()
        assertThat(viewModel.uiState.value.error).contains("正在进行中")
        assertThat(dao.getById(1L)).isNotNull()
    }

    // ------------------------------------------------------------ 删除

    @Test
    fun `删除要二次确认，确认后真的删掉`() = runTest {
        val id = repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        viewModel.load(id)
        advanceUntilIdle()

        // 第一步只弹确认框
        viewModel.requestDelete()
        assertThat(viewModel.uiState.value.pendingDelete).isTrue()
        assertThat(dao.getById(id)).isNotNull()

        viewModel.confirmDelete()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.saved).isTrue()
        assertThat(dao.getById(id)).isNull()
    }

    @Test
    fun `取消删除不会删掉规划`() = runTest {
        val id = repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.requestDelete()
        viewModel.cancelDelete()

        assertThat(viewModel.uiState.value.pendingDelete).isFalse()
        assertThat(dao.getById(id)).isNotNull()
    }

    @Test
    fun `新建时请求删除是空操作`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.requestDelete()
        assertThat(viewModel.uiState.value.pendingDelete).isFalse()
    }

    // ------------------------------------------------------------ 预填

    @Test
    fun `预填名称与时间只对新建生效`() = runTest {
        viewModel.load(0L)
        advanceUntilIdle()

        viewModel.prefillName("晨读")
        viewModel.prefillTimes(7 * 60, 8 * 60)

        assertThat(viewModel.uiState.value.name).isEqualTo("晨读")
        assertThat(viewModel.uiState.value.startMinute).isEqualTo(7 * 60)
        assertThat(viewModel.uiState.value.endMinute).isEqualTo(8 * 60)
    }

    @Test
    fun `编辑已有规划时预填不会覆盖库里的值`() = runTest {
        val id = repo.addPlan("读书", tomorrow, 9 * 60, 11 * 60)
        viewModel.load(id)
        advanceUntilIdle()

        viewModel.prefillName("弹窗里随手写的")
        viewModel.prefillTimes(1, 2)

        val state = viewModel.uiState.value
        assertThat(state.name).isEqualTo("读书")
        assertThat(state.startMinute).isEqualTo(9 * 60)
        assertThat(state.endMinute).isEqualTo(11 * 60)
    }

    // ------------------------------------------------------ 时段文案

    @Test
    fun `时段文案在没设全时给出占位而不是骗人的时间`() {
        assertThat(PlanEditUiState().timeLabel).isEqualTo("未设置")
        assertThat(PlanEditUiState(startMinute = 9 * 60).timeLabel).isEqualTo("09:00 至 --:--")
        assertThat(PlanEditUiState(endMinute = 11 * 60).timeLabel).isEqualTo("--:-- 至 11:00")
        assertThat(PlanEditUiState(startMinute = 9 * 60, endMinute = 11 * 60).timeLabel)
            .isEqualTo("09:00-11:00")
    }
}
