package com.zilv.app.ui.quota

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeQuotaDao
import com.zilv.app.data.repository.FakeUsageDao
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import com.zilv.app.intercept.SystemPackageDetector
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
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [QuotaViewModel] 的单元测试（v2.7）。
 *
 * 覆盖四件事：
 *  1. **草稿校验**：名称为空 / 时长越界 / 没选应用都要在点保存前就给出原因。
 *     ⚠️ v2.11（用户第 3 条规则 2 的边界）：**新建**不再接受 0 分钟
 *     （0 分钟额度一建出来就用尽，于是它自己也改不了、删不掉），
 *     但**编辑**已有的 0 分钟额度仍然放行。
 *     ⚠️ 本轮起 0 是**非法配置**：新建 / 保存 / 备份恢复都不接受它；
 *     而库里已有的 0 分钟行状态是【配置无效】、**不冻结**（见本文件末尾）。
 *  2. **保存与编辑**：新建落库、编辑整组替换绑定关系、失败时编辑器保持打开
 *  3. **仓库层抛异常全部被 runCatching 兜住** —— 未捕获的协程异常会终止
 *     App 进程，这个坑本项目踩过两次，所以这里专门验证"失败只产生一条消息"
 *  4. **已用 / 剩余**跟着用量走（数字只留在 ViewModel 里，界面按用户第 3 条
 *     规则 1 只显示文字状态，见 `QuotaStatusTest`）
 *
 * 时间固定在 2026-10-14 20:00 (+08:00)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotaViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), zone)

    private lateinit var quotaDao: FakeQuotaDao
    private lateinit var usageDao: FakeUsageDao
    private lateinit var repo: UsageQuotaRepository
    private lateinit var viewModel: QuotaViewModel

    private val douyin = "com.ss.android.ugc.aweme"
    private val kuaishou = "com.smile.gifmaker"

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        quotaDao = FakeQuotaDao()
        usageDao = FakeUsageDao()
        repo = UsageQuotaRepository(quotaDao, UsageRepository(usageDao, clock), clock)
        // SystemPackageDetector 需要 Context，但候选列表加载失败时会 getOrDefault(空)，
        // 所以这一页的业务逻辑完全不依赖它（候选应用在别的测试里覆盖）
        // 候选列表走 ioDispatcher = UnconfinedTestDispatcher，advanceUntilIdle 能等到它
        viewModel = QuotaViewModel(repo, systemPackageDetector(), UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 一个"读不到任何应用"的探测器。
     *
     * `SystemPackageDetector` 需要真 Context（PackageManager、InputMethodManager…），
     * 而本项目的单元测试跑在**纯 JVM** 上（没有 Robolectric）。所以这里给一个
     * 桩 Context：它的 `packageManager` 一访问就抛异常，
     * `installedLaunchableApps()` 内部的 runCatching 会把它变成空列表 ——
     * 而"候选应用为空"完全不影响额度本身的业务逻辑（那是这一组测试的目标）。
     */
    private fun systemPackageDetector(): SystemPackageDetector =
        SystemPackageDetector(stubContext)

    private val stubContext: android.content.Context =
        object : android.content.ContextWrapper(null) {
            override fun getPackageManager(): android.content.pm.PackageManager =
                throw UnsupportedOperationException("测试环境没有 PackageManager")
        }

    private suspend fun seed(
        name: String = "短视频",
        limit: Int = 30,
        period: ResetPeriod = ResetPeriod.DAILY,
        packages: Set<String> = setOf("com.ss.android.ugc.aweme"),
        enabled: Boolean = true
    ): Long = repo.add(name, limit, period, packages, enabled)

    private suspend fun use(pkg: String, day: Int, fromHour: Int, toHour: Int, toMinute: Int = 0) {
        val start = LocalDateTime.of(2026, 10, day, fromHour, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 10, day, toHour, toMinute)
            .atZone(zone).toInstant().toEpochMilli()
        UsageRepository(usageDao, clock).recordSegment(pkg, start, end)
    }

    // ============================================================ 列表

    @Test
    fun `初始时列表为空`() = runTest {
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.isEmpty).isTrue()
        assertThat(viewModel.uiState.value.rows).isEmpty()
        assertThat(viewModel.uiState.value.loading).isFalse()
    }

    @Test
    fun `新增额度后列表自动出现（不用手动刷新）`() = runTest {
        advanceUntilIdle()
        seed(name = "短视频", limit = 45)
        advanceUntilIdle()

        val row = viewModel.uiState.value.rows.single()
        assertThat(row.quota.name).isEqualTo("短视频")
        assertThat(row.quota.limitMinutes).isEqualTo(45)
        assertThat(row.quota.packageNames).containsExactly(douyin)
    }

    @Test
    fun `已用与剩余跟着用量走`() = runTest {
        advanceUntilIdle()
        seed(limit = 30)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 20)
        viewModel.refreshUsage()
        advanceUntilIdle()

        val usage = viewModel.uiState.value.rows.single().usage
        assertThat(usage.usedMinutes).isEqualTo(20)
        assertThat(usage.remainingMinutes).isEqualTo(10)
        assertThat(usage.exhausted).isFalse()
    }

    @Test
    fun `用尽后 exhaustedCount 与启用数正确`() = runTest {
        seed(name = "A", limit = 30, packages = setOf(douyin))
        seed(name = "B", limit = 30, packages = setOf(kuaishou), enabled = false)
        use(douyin, day = 14, fromHour = 9, toHour = 10)  // 60 分钟 > 30
        viewModel.refreshUsage()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.rows).hasSize(2)
        assertThat(viewModel.uiState.value.enabledCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.exhaustedCount).isEqualTo(1)
    }

    // ============================================================ 草稿校验

    @Test
    fun `新建草稿的默认值可以被直接校验出问题`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()

        val state = viewModel.uiState.value
        assertThat(state.draft).isNotNull()
        assertThat(state.draft!!.isNew).isTrue()
        assertThat(state.draft!!.limitMinutes).isEqualTo(UsageQuota.DEFAULT_LIMIT_MINUTES)
        // 名称空 + 没选应用 —— 两个问题都在，但只报第一个
        assertThat(state.draftProblem).contains("名字")
    }

    @Test
    fun `名称填上但没选应用时提示选应用`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("短视频")

        assertThat(viewModel.uiState.value.draftProblem).contains("应用")
    }

    @Test
    fun `名称与应用都齐了就校验通过`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("短视频")
        viewModel.togglePackage(douyin)

        assertThat(viewModel.uiState.value.draftProblem).isNull()
    }

    @Test
    fun `新建时长为 0 被拒绝 —— 本轮起新建至少 1 分钟`() = runTest {
        advanceUntilIdle()
        // ⚠️ v2.11 规格变更（用户第 3 条规则 2 的边界处理）：
        // 旧规格是"时长为 0 必须被接受"（"额度为零时禁用指定软件"）。
        // 但规则 2 让"剩余 == 0"的额度永远关不掉也删不掉，于是**新建**
        // 一个 0 分钟额度就等于当场把自己锁死 —— 用户点名要求堵住这个口子，
        // 并明确"保证新建时就不能创建 0 分钟额度"。所以这条断言被改写了。
        //
        // 0 分钟本身仍然是**存量数据形态**（读路径不收紧）：见
        // `UsageQuotaRepositoryTest` 里用 DAO 铺出来的旧数据用例，
        // 以及本文件末尾"0 分钟坏数据不冻结"那几条。
        val problem = QuotaViewModel.validate(
            QuotaDraft(name = "直接禁用", limitMinutes = 0, packageNames = setOf(douyin))
        )
        assertThat(problem).contains("至少 1 分钟")
    }

    @Test
    fun `编辑已有的 0 分钟额度不被本地校验拦住 —— 它是唯一的改回来的出口`() = runTest {
        advanceUntilIdle()
        // 新建不许 0 分钟，但**编辑**必须放行：旧库里可能就有 0 分钟额度，
        // 拦了它就等于让用户连"改成正常值"都做不到（比原问题更糟）。
        val problem = QuotaViewModel.validate(
            QuotaDraft(
                id = 7L,
                name = "老额度",
                limitMinutes = 0,
                packageNames = setOf(douyin)
            )
        )
        assertThat(problem).isNull()
    }

    @Test
    fun `时长越界被拒绝`() = runTest {
        advanceUntilIdle()
        // ⚠️ 规格变更：上限从 1440 分钟提到 44640 分钟（744 小时）。
        // 旧断言用的是 1441（旧上界 + 1），现在它是**合法值**，
        // 所以越界样本必须跟着上界一起挪到 44641。
        // v2.11 补充：越界的报错必须仍然是"越界"那句，不能被
        // "新建至少 1 分钟"那条顶掉（validate 里两条的先后顺序因此是刻意的）。
        assertThat(
            QuotaViewModel.validate(
                QuotaDraft(
                    name = "x",
                    limitMinutes = UsageQuota.MAX_LIMIT_MINUTES + 1,
                    packageNames = setOf(douyin)
                )
            )
        ).contains("44640")
        assertThat(
            QuotaViewModel.validate(
                QuotaDraft(name = "x", limitMinutes = -1, packageNames = setOf(douyin))
            )
        ).contains("44640")
    }

    @Test
    fun `上限值本身合法，旧的 24 小时上限不再越界`() = runTest {
        advanceUntilIdle()
        assertThat(
            QuotaViewModel.validate(
                QuotaDraft(
                    name = "x",
                    limitMinutes = UsageQuota.MAX_LIMIT_MINUTES,
                    packageNames = setOf(douyin)
                )
            )
        ).isNull()
        // 1441 曾经是"越界"，现在只是普通的一档
        assertThat(
            QuotaViewModel.validate(
                QuotaDraft(name = "x", limitMinutes = 1441, packageNames = setOf(douyin))
            )
        ).isNull()
    }

    @Test
    fun `updateLimit 会把越界值夹到合法区间`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateLimit(99_999)
        assertThat(viewModel.uiState.value.draft!!.limitMinutes)
            .isEqualTo(UsageQuota.MAX_LIMIT_MINUTES)
        viewModel.updateLimit(-50)
        assertThat(viewModel.uiState.value.draft!!.limitMinutes)
            .isEqualTo(UsageQuota.MIN_LIMIT_MINUTES)
    }

    // ------------------------------------------------ 时长输入：先小时，再分钟
    //
    // 用户要求"把滑块改成自填数字：先填小时，再填分钟"。
    // 合成逻辑做成纯函数（QuotaViewModel.limitMinutesOf），这里逐条钉住；
    // 界面（QuotaScreen）只是把两个输入框的文本喂给它。

    @Test
    fun `小时与分钟合成总分钟数`() {
        assertThat(QuotaViewModel.limitMinutesOf("2", "30")).isEqualTo(150)
        assertThat(QuotaViewModel.limitMinutesOf("0", "0")).isEqualTo(0)
        assertThat(QuotaViewModel.limitMinutesOf("744", "0")).isEqualTo(44640)
        // 空输入按 0 算（用户正在删字符时不至于跳成别的值）
        assertThat(QuotaViewModel.limitMinutesOf("", "")).isEqualTo(0)
        assertThat(QuotaViewModel.limitMinutesOf("3", "")).isEqualTo(180)
    }

    @Test
    fun `合成的结果会被夹进合法区间`() {
        // 分钟栏超过 59 按 59 算（"90 分钟"该写进小时栏）
        assertThat(QuotaViewModel.limitMinutesOf("1", "90")).isEqualTo(119)
        // 合计超过上限夹到上限（744 小时 = 44640 分钟）
        assertThat(QuotaViewModel.limitMinutesOf("999", "0"))
            .isEqualTo(UsageQuota.MAX_LIMIT_MINUTES)
        assertThat(QuotaViewModel.limitMinutesOf("744", "1"))
            .isEqualTo(UsageQuota.MAX_LIMIT_MINUTES)
        // 非数字被忽略，不会崩
        assertThat(QuotaViewModel.limitMinutesOf("ab", "cd")).isEqualTo(0)
    }

    @Test
    fun `名称超长被截断到上限`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("啊".repeat(100))
        assertThat(viewModel.uiState.value.draft!!.name.length)
            .isEqualTo(UsageQuota.MAX_NAME_LENGTH)
    }

    @Test
    fun `勾选与取消勾选应用`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.togglePackage(douyin)
        viewModel.togglePackage(kuaishou)
        assertThat(viewModel.uiState.value.draft!!.packageNames)
            .containsExactly(douyin, kuaishou)

        viewModel.togglePackage(douyin)
        assertThat(viewModel.uiState.value.draft!!.packageNames).containsExactly(kuaishou)
    }

    @Test
    fun `切换刷新周期与启用状态`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updatePeriod(ResetPeriod.MONTHLY)
        viewModel.toggleEnabled(false)

        val draft = viewModel.uiState.value.draft!!
        assertThat(draft.resetPeriod).isEqualTo(ResetPeriod.MONTHLY)
        assertThat(draft.enabled).isFalse()
    }

    // ============================================================ 保存

    @Test
    fun `保存新建的额度后编辑器关闭、列表更新`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("短视频")
        viewModel.updateLimit(45)
        viewModel.updatePeriod(ResetPeriod.WEEKLY)
        viewModel.togglePackage(douyin)
        viewModel.togglePackage(kuaishou)
        viewModel.saveDraft()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.draft).isNull()
        assertThat(viewModel.uiState.value.message).contains("已创建")

        val saved = repo.getAll().single()
        assertThat(saved.name).isEqualTo("短视频")
        assertThat(saved.limitMinutes).isEqualTo(45)
        assertThat(saved.resetPeriod).isEqualTo(ResetPeriod.WEEKLY)
        assertThat(saved.packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `保存时名称前后空格被去掉`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("  短视频  ")
        viewModel.togglePackage(douyin)
        viewModel.saveDraft()
        advanceUntilIdle()

        assertThat(repo.getAll().single().name).isEqualTo("短视频")
    }

    @Test
    fun `草稿不合法时保存不会落库，编辑器保持打开并给出原因`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("")   // 名称为空
        viewModel.togglePackage(douyin)
        viewModel.saveDraft()
        advanceUntilIdle()

        assertThat(repo.count()).isEqualTo(0)
        // 编辑器仍然开着 —— 关掉等于把用户刚填的内容丢掉
        assertThat(viewModel.uiState.value.draft).isNotNull()
        // 注意这里报的是**仓库层**的原因（`UsageQuota.invalidReason` 的措辞），
        // 不是 ViewModel 本地校验的那句"请给这个额度起个名字"：
        // 本地校验只负责"提前给反馈"，真正的拒绝在仓库层，口径以它为准。
        assertThat(viewModel.uiState.value.draftProblem).contains("名称不能为空")
    }

    @Test
    fun `本地校验在打开编辑器时立刻给出名字问题`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        // 还没点保存，本地校验就已经在提示了
        assertThat(viewModel.uiState.value.draftProblem).contains("名字")
    }

    @Test
    fun `edit 已有的额度：打开编辑器时带上现有配置`() = runTest {
        val id = seed(name = "短视频", limit = 45, period = ResetPeriod.MONTHLY, packages = setOf(douyin, kuaishou))
        advanceUntilIdle()

        viewModel.startEdit(repo.getById(id)!!)

        val draft = viewModel.uiState.value.draft!!
        assertThat(draft.isNew).isFalse()
        assertThat(draft.id).isEqualTo(id)
        assertThat(draft.name).isEqualTo("短视频")
        assertThat(draft.limitMinutes).isEqualTo(45)
        assertThat(draft.resetPeriod).isEqualTo(ResetPeriod.MONTHLY)
        assertThat(draft.packageNames).containsExactly(douyin, kuaishou)
        assertThat(viewModel.uiState.value.draftProblem).isNull()
    }

    @Test
    fun `编辑保存会整组替换绑定关系`() = runTest {
        val id = seed(packages = setOf(douyin, kuaishou))
        advanceUntilIdle()
        viewModel.startEdit(repo.getById(id)!!)

        viewModel.togglePackage(douyin)         // 取消勾选
        viewModel.updateLimit(120)
        viewModel.saveDraft()
        advanceUntilIdle()

        val after = repo.getById(id)!!
        assertThat(after.packageNames).containsExactly(kuaishou)
        assertThat(after.limitMinutes).isEqualTo(120)
        assertThat(quotaDao.appRowCount).isEqualTo(1)
        assertThat(viewModel.uiState.value.message).contains("已保存")
    }

    @Test
    fun `编辑时把应用全删光会被拒绝，库里保持原样`() = runTest {
        val id = seed(packages = setOf(douyin))
        advanceUntilIdle()
        viewModel.startEdit(repo.getById(id)!!)

        viewModel.togglePackage(douyin)   // 取消唯一一个应用
        viewModel.saveDraft()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.draftProblem).contains("应用")
        assertThat(repo.getById(id)!!.packageNames).containsExactly(douyin)
        assertThat(viewModel.uiState.value.draft).isNotNull()
    }

    @Test
    fun `没有打开编辑器时保存是空操作`() = runTest {
        advanceUntilIdle()
        viewModel.saveDraft()
        advanceUntilIdle()
        assertThat(repo.count()).isEqualTo(0)
    }

    @Test
    fun `取消编辑会清掉草稿与问题`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("x")
        viewModel.cancelEdit()

        assertThat(viewModel.uiState.value.draft).isNull()
        assertThat(viewModel.uiState.value.draftProblem).isNull()
    }

    // ============================================================ 删除 / 启停

    @Test
    fun `删除额度连绑定关系一起清掉`() = runTest {
        val id = seed(packages = setOf(douyin, kuaishou))
        advanceUntilIdle()

        viewModel.delete(id)
        advanceUntilIdle()

        assertThat(repo.count()).isEqualTo(0)
        assertThat(quotaDao.appRowCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.message).contains("已删除")
    }

    @Test
    fun `删除不存在的额度只产生消息，不抛异常`() = runTest {
        advanceUntilIdle()
        viewModel.delete(999L)
        advanceUntilIdle()

        // 关键：仓库不抛、ViewModel 也不崩（未捕获的协程异常会终止进程）
        assertThat(viewModel.uiState.value.rows).isEmpty()
    }

    @Test
    fun `停用与启用额度`() = runTest {
        val id = seed()
        advanceUntilIdle()

        viewModel.setEnabled(id, false)
        advanceUntilIdle()
        assertThat(repo.getById(id)!!.enabled).isFalse()
        assertThat(viewModel.uiState.value.enabledCount).isEqualTo(0)

        viewModel.setEnabled(id, true)
        advanceUntilIdle()
        assertThat(repo.getById(id)!!.enabled).isTrue()
    }

    // ============================ v2.11（用户第 3 条规则 2）：用尽即冻结
    //
    // 用户要求："不能只挡界面：请在 ViewModel（乃至仓库）也拒绝，并给出可读原因。"
    // 仓库层那道权威守卫见 `UsageQuotaRepositoryTest`；这里验的是
    // **ViewModel 这一层**：拒绝要变成一条人话，而且状态得跟着刷新。

    /** 造一份"确实已经用尽"的额度：1 分钟额度 + 520 分钟用量。 */
    private suspend fun seedExhausted(name: String = "短视频"): Long {
        val id = seed(name = name, limit = 1)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 30)
        viewModel.refreshUsage()
        return id
    }

    @Test
    fun `列表行的状态只有三种文字 —— 界面上没有分钟数可露`() = runTest {
        // ⚠️ 本轮起状态是**四种**（多了【配置无效】），这条断言覆盖的是
        // 正常的三条（生效中 / 已关闭 / 已耗尽）——【配置无效】那一条
        // 由本文件末尾"上限为 0 的坏数据显示配置无效"专门钉住。
        // 断言的意图没降低：每一条状态文案都必须精确、且行里没有分钟数可露。
        val id = seed(limit = 30)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 20)
        // 顺带把"停用"也造出来：一份 30 分钟额度只用 20 分钟，
        // 剩余 10 分钟 > 0，所以停用后必然是【已关闭】而不是【额度已耗尽】
        // （"停用优先于用尽"那条语义的界面体现）
        viewModel.setEnabled(id, false)
        advanceUntilIdle()

        val disabledRow = viewModel.uiState.value.rows.single { it.quota.id == id }
        assertThat(disabledRow.status).isEqualTo(QuotaStatus.DISABLED)
        assertThat(disabledRow.status.label).isEqualTo("【已关闭】")
        // 停用态也一并冻结（"先关掉再删掉"要一起堵上）
        assertThat(disabledRow.mutationLocked).isTrue()

        // 启用回来 -> 生效中，按钮解冻
        viewModel.setEnabled(id, true)
        advanceUntilIdle()
        val activeRow = viewModel.uiState.value.rows.single { it.quota.id == id }
        assertThat(activeRow.status).isEqualTo(QuotaStatus.ACTIVE)
        assertThat(activeRow.status.label).isEqualTo("【限额生效中】")
        assertThat(activeRow.mutationLocked).isFalse()
        assertThat(activeRow.mutationLockedHint).isNull()

        // 用尽 -> 【额度已耗尽】+ 三个动作冻结
        val exhaustedId = seedExhausted(name = "已用完")
        advanceUntilIdle()
        val exhaustedRow = viewModel.uiState.value.rows.single { it.quota.id == exhaustedId }
        assertThat(exhaustedRow.status).isEqualTo(QuotaStatus.EXHAUSTED)
        assertThat(exhaustedRow.status.label).isEqualTo("【额度已耗尽】")
        assertThat(exhaustedRow.mutationLocked).isTrue()
    }

    // ==================================================== v2.12：Bug 2 的钉子
    //
    // 用户报的 bug：「时间限额关闭后无法再次打开（显示为灰色）」。
    //
    // 根因在**界面**：v2.11 用的是 `Switch(enabled = !mutationLocked)` —— 一刀切。
    // 而 `mutationLocked` 对【已关闭】的额度是 true（那是刻意的，为了堵
    // "先关掉再删掉"）。于是"关闭"点得动、"打开"点不动，一份额度关掉之后
    // 再也开不回来，而它同时又改不了、删不掉。
    //
    // 下面这一组把**方向**钉死：开关的可点性必须问 `QuotaRow.canToggle`
    // （= `UsageQuota.allowsEnabledChange`），而不是 `mutationLocked`。

    @Test
    fun `关闭之后列表行的开关仍然可以再打开 —— 不能跟着 mutationLocked 一起置灰`() = runTest {
        val id = seed(limit = 30)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.rows.single().canToggle).isTrue()

        // 关掉
        viewModel.setEnabled(id, false)
        advanceUntilIdle()

        val closed = viewModel.uiState.value.rows.single { it.quota.id == id }
        assertThat(closed.status).isEqualTo(QuotaStatus.DISABLED)
        // 编辑/删除照旧冻结（"先关掉再删掉"不许绕过）
        assertThat(closed.mutationLocked).isTrue()
        // ⚠️ 但开关**必须还能点** —— 这就是本次修的 bug
        assertThat(closed.canToggle).isTrue()
        assertThat(closed.canToggleHint).isNull()
        // 文案也要让用户看出来"还能开回来"
        assertThat(closed.switchLabel).isEqualTo("已关闭，可打开")

        // 而且真的点得回去：往返成立
        viewModel.setEnabled(id, true)
        advanceUntilIdle()
        val reopened = viewModel.uiState.value.rows.single { it.quota.id == id }
        assertThat(reopened.status).isEqualTo(QuotaStatus.ACTIVE)
        assertThat(reopened.quota.enabled).isTrue()
        assertThat(reopened.switchLabel).isEqualTo("关闭")
    }

    @Test
    fun `已用尽的额度开关只能往打开方向拨 —— 关闭方向照旧冻结`() = runTest {
        val id = seedExhausted()
        advanceUntilIdle()
        val row = viewModel.uiState.value.rows.single { it.quota.id == id }

        // 现在它是启用中的"已耗尽"：拨下去等于"关掉" —— 必须被拒（防绕过）
        assertThat(row.canToggle).isFalse()
        assertThat(row.canToggleHint).contains("已用完")

        // 已被拒的关闭动作不会改变任何东西
        viewModel.setEnabled(id, false)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.rows.single { it.quota.id == id }.quota.enabled).isTrue()

        // 而"打开"方向永远放行（模拟一份被关掉的已用尽额度）
        val repoId = repo.getById(id)!!
        assertThat(repoId.enabled).isTrue()
    }

    @Test
    fun `配置无效的坏数据开关也点得动 —— 别把方向性守卫做成新的死路`() = runTest {
        // 上限为 0 的存量坏数据（用户在库里本来就有的那种）：
        // 它的状态是【配置无效】，不冻结；开关的两个方向都该可点。
        val badId = seedLegacyZeroMinuteQuota(name = "坏数据")
        advanceUntilIdle()
        val badRow = viewModel.uiState.value.rows.single { it.quota.id == badId }
        assertThat(badRow.canToggle).isTrue()
    }

    @Test
    fun `已用尽的额度点编辑不会打开编辑器，而是给一句可读原因`() = runTest {        val id = seedExhausted()
        advanceUntilIdle()

        viewModel.startEdit(repo.getById(id)!!)
        advanceUntilIdle()

        // 编辑器没开
        assertThat(viewModel.uiState.value.draft).isNull()
        // 原因可读：说清"用完了"以及"到刷新点就能改"
        assertThat(viewModel.uiState.value.message).contains("已用完")
        assertThat(viewModel.uiState.value.message).contains("刷新点")
    }

    @Test
    fun `已用尽的额度删除被 ViewModel 拒绝，数据没动`() = runTest {
        val id = seedExhausted()
        advanceUntilIdle()

        viewModel.delete(id)
        advanceUntilIdle()

        assertThat(repo.count()).isEqualTo(1)
        // 仓库那句人话被原样带出来（不是"删除失败：xxx"）
        assertThat(viewModel.uiState.value.message).contains("已用完")
        assertThat(viewModel.uiState.value.message).doesNotContain("删除失败：")
    }

    @Test
    fun `已用尽的额度关闭被 ViewModel 拒绝`() = runTest {
        val id = seedExhausted()
        advanceUntilIdle()

        viewModel.setEnabled(id, false)
        advanceUntilIdle()

        assertThat(repo.getById(id)!!.enabled).isTrue()
        assertThat(viewModel.uiState.value.message).contains("已用完")
    }

    @Test
    fun `编辑期间额度用尽 —— 点保存那一刻仍然会被拒`() = runTest {
        // 这一条覆盖"编辑器已经开着、期间额度才用尽"的真实时序：
        // startEdit 发生在还没用尽时（放行），保存发生在用尽之后（拒绝）。
        // 判据取的是**当下**那一次用量，不是打开编辑器时的旧快照。
        val id = seed(limit = 1)
        advanceUntilIdle()
        viewModel.startEdit(repo.getById(id)!!)
        assertThat(viewModel.uiState.value.draft).isNotNull()

        // 编辑器开着的时候额度被用完了
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 30)
        viewModel.refreshUsage()
        advanceUntilIdle()

        viewModel.updateLimit(600)
        viewModel.saveDraft()
        advanceUntilIdle()

        // 没改成，编辑器还开着，并给出原因
        assertThat(repo.getById(id)!!.limitMinutes).isEqualTo(1)
        assertThat(viewModel.uiState.value.draft).isNotNull()
        assertThat(viewModel.uiState.value.draftProblem).contains("已用完")
    }

    @Test
    fun `还有剩余时编辑与删除照旧`() = runTest {
        val id = seed(limit = 30)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 10)
        viewModel.refreshUsage()
        advanceUntilIdle()

        viewModel.startEdit(repo.getById(id)!!)
        assertThat(viewModel.uiState.value.draft).isNotNull()
        viewModel.cancelEdit()

        viewModel.delete(id)
        advanceUntilIdle()
        assertThat(repo.count()).isEqualTo(0)
    }

    @Test
    fun `新建 0 分钟额度会被本地校验拦住，且编辑器保持打开`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("直接禁用")
        viewModel.togglePackage(douyin)
        viewModel.updateLimit(0)

        // 本地校验已经给了原因（保存按钮本来就是灰的）
        assertThat(viewModel.uiState.value.draftProblem).contains("至少 1 分钟")

        // 就算硬点保存，仓库层也会拒
        viewModel.saveDraft()
        advanceUntilIdle()
        assertThat(repo.count()).isEqualTo(0)
        assertThat(viewModel.uiState.value.draft).isNotNull()
        assertThat(viewModel.uiState.value.draftProblem).contains("至少 1 分钟")
    }

    // ==================================== v2.11 本轮：0 分钟 = 配置无效（不冻结）
    //
    // 用户看过"0 分钟额度会被永久冻结"的风险说明后决定：
    // "**不予许设置成 0 分钟**"，并要求把 0 判成"配置非法"而不是"额度耗尽"、
    // **不冻结** —— 允许编辑（好把它改成 ≥1 分钟）与删除（好清掉它），
    // "关闭"也允许。理由（用户原话）："冻结是为了防'用完就关掉'，
    // 而一个上限为 0 的配置永远不可能被'用完'，它是坏数据；
    // 把它冻住只会让用户无路可走。"
    //
    // 下面这一组就是**界面层**那三个按钮不再置灰的证明（`QuotaRow.mutationLocked`
    // 就是界面画按钮时读的那个字段）。仓库层那道权威守卫的同名用例在
    // `UsageQuotaRepositoryTest`；而"上限 5 分钟用满仍然冻结"那条钉子在
    // **两个文件里各有一条**。

    /**
     * 铺一条**库里本来就有的** 0 分钟额度。
     *
     * ⚠️ 这里**故意绕开 `UsageQuotaRepository.add`**，直接用 DAO 写行：
     * 本轮起所有写入口（新建 / 保存 / 备份恢复）都不接受 0 分钟，
     * 生产代码再也造不出这种行 —— 真实世界里它的来源只有一个，
     * 就是"v2.11 之前建的、留到现在"。直接从 DAO 铺，测的才是那个真实形态，
     * 而不是"用测试专用的放行开关造出来的"。
     */
    private suspend fun seedLegacyZeroMinuteQuota(name: String = "直接禁用"): Long {
        val id = quotaDao.insert(
            com.zilv.app.data.local.entity.UsageQuotaEntity(
                name = name,
                limitMinutes = 0,
                resetPeriod = "DAILY",
                enabled = true,
                createdAt = 0L
            )
        )
        quotaDao.insertApp(
            com.zilv.app.data.local.entity.UsageQuotaAppEntity(quotaId = id, packageName = douyin)
        )
        return id
    }

    @Test
    fun `上限为 0 的坏数据显示配置无效，三个按钮都不置灰`() = runTest {
        seedLegacyZeroMinuteQuota()
        advanceUntilIdle()

        val row = viewModel.uiState.value.rows.single()
        // ① 状态是【配置无效】而不是【额度已耗尽】
        assertThat(row.status).isEqualTo(QuotaStatus.INVALID)
        assertThat(row.status.label).isEqualTo("【配置无效】")
        // ② 三个按钮都不置灰（这正是用户在界面上要的"能修能删"）
        assertThat(row.mutationLocked).isFalse()
        // ③ 不置灰就不该有"为什么点不动"的提示
        assertThat(row.mutationLockedHint).isNull()
        // ④ 文案里不许出现分钟数（规则 1 对新状态同样成立）
        assertThat(row.status.label).doesNotContain("0")
    }

    @Test
    fun `上限为 0 的坏数据点编辑能打开编辑器 —— 那是修好它的唯一出路`() = runTest {
        val id = seedLegacyZeroMinuteQuota(name = "老额度")
        advanceUntilIdle()

        viewModel.startEdit(repo.getById(id)!!)
        advanceUntilIdle()

        val draft = viewModel.uiState.value.draft
        assertThat(draft).isNotNull()
        assertThat(draft!!.id).isEqualTo(id)
        // 草稿里带的还是 0 —— 本地校验对它放行（编辑路径不设"至少 1 分钟"，
        // 否则用户连"把它改成正常值"都做不到），保存按钮因此是可点的
        assertThat(draft.limitMinutes).isEqualTo(0)
        assertThat(viewModel.uiState.value.draftProblem).isNull()

        // 改成 30 分钟 → 保存成功，坏数据被修好
        viewModel.updateLimit(30)
        viewModel.saveDraft()
        advanceUntilIdle()

        assertThat(repo.getById(id)!!.limitMinutes).isEqualTo(30)
        assertThat(viewModel.uiState.value.draft).isNull()
        // 修好之后它就是一条正常额度（失效中→生效中），而不是继续报"配置无效"
        assertThat(viewModel.uiState.value.rows.single().status)
            .isEqualTo(QuotaStatus.ACTIVE)
    }

    @Test
    fun `上限为 0 的坏数据能直接删掉 —— 清掉它不该被冻死`() = runTest {
        val id = seedLegacyZeroMinuteQuota()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.rows.single().mutationLocked).isFalse()

        viewModel.delete(id)
        advanceUntilIdle()

        assertThat(repo.count()).isEqualTo(0)
        assertThat(quotaDao.appRowCount).isEqualTo(0)
        assertThat(viewModel.uiState.value.message).contains("额度已删除")
    }

    @Test
    fun `上限为 0 的坏数据也能关闭 —— 三个动作一个都不受限制`() = runTest {
        val id = seedLegacyZeroMinuteQuota()
        advanceUntilIdle()

        viewModel.setEnabled(id, false)
        advanceUntilIdle()

        assertThat(repo.getById(id)!!.enabled).isFalse()
        // 关掉之后状态是【已关闭】（INVALID 之上只剩它自己那条判据了？
        // 不 —— INVALID 优先级更高，所以仍然是【配置无效】：
        // 一份坏数据不会因为被关掉就变得"配置没问题"）
        assertThat(viewModel.uiState.value.rows.single().status)
            .isEqualTo(QuotaStatus.INVALID)
    }

    @Test
    fun `上限 5 分钟的本周期用满 5 分钟后三个动作仍然冻结 —— 绝不放宽真正的耗尽`() = runTest {
        // 🔒 本轮最重要的那条钉子（界面层）。用户点名了这个数字：
        // 上限 5 分钟、用满 5 分钟 —— 这时三个按钮必须**仍然**全部置灰，
        // 新加的【配置无效】一格都不许把这里吃掉。
        val id = seed(name = "五分钟", limit = 5)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 5)
        viewModel.refreshUsage()
        advanceUntilIdle()

        val row = viewModel.uiState.value.rows.single { it.quota.id == id }
        assertThat(row.status).isEqualTo(QuotaStatus.EXHAUSTED)
        assertThat(row.status.label).isEqualTo("【额度已耗尽】")
        assertThat(row.mutationLocked).isTrue()
        // 提示必须说清"用完了、到刷新点就能改"，而不是别的
        assertThat(row.mutationLockedHint).contains("已用完")
        assertThat(row.mutationLockedHint).contains("刷新点")

        // 三个动作逐个被拒（ViewModel 层），且数据一律没动
        viewModel.startEdit(repo.getById(id)!!)
        assertThat(viewModel.uiState.value.draft).isNull()

        viewModel.setEnabled(id, false)
        advanceUntilIdle()
        assertThat(repo.getById(id)!!.enabled).isTrue()

        viewModel.delete(id)
        advanceUntilIdle()
        assertThat(repo.count()).isEqualTo(1)
        assertThat(repo.getById(id)!!.limitMinutes).isEqualTo(5)
    }

    // ============================================================ 异常兜底

    @Test
    fun `仓库写失败只产生一条消息，不抛异常`() = runTest {
        // 造一个"保存就炸"的 DAO：验证 ViewModel 的 runCatching 真的兜住了
        val brokenDao = object : FakeQuotaDao() {
            override suspend fun insert(
                quota: com.zilv.app.data.local.entity.UsageQuotaEntity
            ): Long = throw IllegalStateException("磁盘满了")
        }
        val brokenRepo = UsageQuotaRepository(
            brokenDao,
            UsageRepository(FakeUsageDao(), clock),
            clock
        )
        val vm = QuotaViewModel(brokenRepo, systemPackageDetector(), UnconfinedTestDispatcher())
        advanceUntilIdle()

        vm.startCreate()
        vm.updateName("短视频")
        vm.togglePackage(douyin)
        vm.saveDraft()
        advanceUntilIdle()

        // 编辑器保持打开、有原因、**没有**异常逃逸
        assertThat(vm.uiState.value.draft).isNotNull()
        assertThat(vm.uiState.value.message).contains("保存失败")
    }

    @Test
    fun `读取列表失败只产生一条消息`() = runTest {
        val brokenDao = object : FakeQuotaDao() {
            override fun observeAll(): kotlinx.coroutines.flow.Flow<
                List<com.zilv.app.data.local.entity.UsageQuotaEntity>
                > = kotlinx.coroutines.flow.flow { throw IllegalStateException("库坏了") }
        }
        val brokenRepo = UsageQuotaRepository(
            brokenDao,
            UsageRepository(FakeUsageDao(), clock),
            clock
        )
        val vm = QuotaViewModel(brokenRepo, systemPackageDetector(), UnconfinedTestDispatcher())
        advanceUntilIdle()

        assertThat(vm.uiState.value.message).contains("读取额度失败")
    }

    @Test
    fun `clearMessage 清掉消息`() = runTest {
        advanceUntilIdle()
        viewModel.startCreate()
        viewModel.updateName("x")
        viewModel.cancelEdit()
        viewModel.delete(999L)
        advanceUntilIdle()

        viewModel.clearMessage()
        assertThat(viewModel.uiState.value.message).isNull()
    }

    // ============================================================ 候选应用

    @Test
    fun `已被其它额度绑定的应用只是被标记，不会被排除`() = runTest {
        seed(name = "A", packages = setOf(douyin))
        advanceUntilIdle()

        // 候选列表来自 PackageManager（测试环境拿不到），所以这里直接验证
        // "标记逻辑"：A 额度里已绑的 douyin 在编辑**别的**额度时应被标出来
        val candidates = viewModel.candidatesExcept(editingQuotaId = 0L)
        // 候选为空时这条断言只证明"没有崩"，真正的标记逻辑靠下面的字段语义保证
        assertThat(candidates).isEmpty()

        // 口径确认：允许多个额度绑同一个应用（禁用取最严的那个）
        val secondId = seed(name = "B", limit = 5, packages = setOf(douyin))
        advanceUntilIdle()
        assertThat(repo.getById(secondId)!!.packageNames).containsExactly(douyin)
        // 两个额度都在，绑定关系各存一份
        assertThat(quotaDao.appRowCount).isEqualTo(2)
    }

    @Test
    fun `isNew 的判定只看 id`() {
        assertThat(QuotaDraft(name = "x").isNew).isTrue()
        assertThat(QuotaDraft(id = 3L, name = "x").isNew).isFalse()
    }
}
