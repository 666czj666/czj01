package com.zilv.app.ui.quota

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.QuotaRejectedException
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.QuotaUsage
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import com.zilv.app.intercept.SystemPackageDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑中的额度草稿（还没落库）。 */
data class QuotaDraft(
    val id: Long = 0L,
    val name: String = "",
    val limitMinutes: Int = UsageQuota.DEFAULT_LIMIT_MINUTES,
    val resetPeriod: ResetPeriod = ResetPeriod.DAILY,
    val enabled: Boolean = true,
    val packageNames: Set<String> = emptySet()
) {
    val isNew: Boolean get() = id == 0L

    fun toDomain(createdAt: Long = 0L) = UsageQuota(
        id = id,
        name = name,
        limitMinutes = limitMinutes,
        resetPeriod = resetPeriod,
        enabled = enabled,
        createdAt = createdAt,
        packageNames = packageNames
    )
}

/** 列表里的一行。 */
data class QuotaRow(
    val quota: UsageQuota,
    val usage: QuotaUsage
) {
    /**
     * 这一行**唯一**允许展示的状态（v2.11，用户第 3 条规则 1；本轮扩到四态）。
     *
     * 界面只画它的 [QuotaStatus.label]，不再画"还剩 N 分钟 / 已用 N 分钟"。
     * 上限也一并喂进去：上限 == 0 的存量坏数据要判成
     * [QuotaStatus.INVALID]（【配置无效】）而不是"已耗尽"。
     */
    val status: QuotaStatus get() = QuotaStatus.of(quota, usage)

    /**
     * 这一行的【编辑】【删除】是不是都该置灰（v2.11，用户规则 2）。
     *
     * 判据与仓库层的守卫**逐字相同**（都问 [UsageQuota.allowsMutation]）——
     * 界面只是把仓库的结论画出来，不自己另算一套。
     *
     * ⚠️ v2.12：它**不再**用来决定"那个开关能不能点"。见 [canToggle]。
     */
    val mutationLocked: Boolean get() = !quota.allowsMutation(status)

    /** 置灰时给用户看的一句话（放在按钮上方，否则他不知道为什么点不动）。 */
    val mutationLockedHint: String? get() = quota.mutationRejectionReason(status)

    /**
     * 启用开关**能不能拨**（v2.12 修 Bug 2）。
     *
     * ## 为什么必须按方向分开
     *
     * v2.11 的界面用的是 `Switch(enabled = !mutationLocked)` —— 一刀切。
     * 而 `mutationLocked` 对【已关闭】的额度是 **true**（那是刻意的：
     * "先关掉再删掉"要堵住）。结果就是：
     *  - 「关闭」点得动（那时额度还是 ACTIVE，`mutationLocked = false`）
     *  - 「打开」点不动（额度已经变成 DISABLED，`mutationLocked = true`）
     *
     * 于是**"改回来"的路被自己堵死** —— 一个额度一旦关掉就再也开不回来，
     * 而它同时又改不了、删不掉。用户报的正是这个。
     *
     * 现在这里问的是方向性判据 [UsageQuota.allowsEnabledChange]：
     * 拨向「打开」恒为允许，拨向「关闭」才受冻结约束。
     * 与仓库层 `UsageQuotaRepository.setEnabled` 是**同一个函数**，三层不可能分叉。
     */
    val canToggle: Boolean get() = quota.allowsEnabledChange(!quota.enabled, status)

    /** 开关拨不动时给用户的原因；拨得动时是 null。 */
    val canToggleHint: String?
        get() = quota.enabledChangeRejectionReason(!quota.enabled, status)

    /** 开关旁边那句话 —— 关闭态的额度必须让用户看出来"还能开回来"。 */
    val switchLabel: String
        get() = if (quota.enabled) "关闭" else "已关闭，可打开"
}

/** 可选的启动器应用（多选候选）。 */
data class AppOption(
    val packageName: String,
    val label: String,
    /** 已经绑在**别的**额度上的应用 —— 仍然可选，界面上给个提示。 */
    val usedElsewhere: Boolean = false
)

data class QuotaUiState(
    val rows: List<QuotaRow> = emptyList(),
    val candidates: List<AppOption> = emptyList(),
    val loading: Boolean = true,
    /** 编辑器正在编辑的草稿；null 表示编辑器关闭。 */
    val draft: QuotaDraft? = null,
    /** 草稿的实时校验问题；null 表示可以保存。 */
    val draftProblem: String? = null,
    val message: String? = null
) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** 当前禁用了多少个应用（列表顶部的汇总行）。 */
    val exhaustedCount: Int get() = rows.count { it.usage.exhausted }

    val enabledCount: Int get() = rows.count { it.quota.enabled }
}

/**
 * 「软件使用时间限时」管理页的 ViewModel（v2.7）。
 *
 * 用户要求："自定义一个使用时间额度，自定义一个或多个（共用）软件使用该时间额度。
 * 自定义时间额度刷新间隔（每天 0 点，每周一，每月 1 号）到点刷新（旧额度不可保留）。
 * 时间额度为零时禁用指定软件。"
 *
 * ## 两条数据来源，各管一半
 *
 *  - **额度定义**（名称/时长/周期/绑定）走 `UsageQuotaRepository.observeAll()`
 *    的 Flow：任何写入立刻反映到界面，不需要手动刷新
 *  - **已用 / 剩余**是时间的函数，Flow 观察不到"时间流逝"，所以每次列表变化后
 *    重算一次（`usageOfAll`），并在 [refreshUsage] 里给界面一个手动入口
 *
 * ## 仓库层抛异常的地方一律 runCatching
 *
 * `UsageQuotaRepository.add/update/delete` 在业务拒绝时抛
 * [QuotaRejectedException]，而**未捕获的协程异常会终止 App 进程** ——
 * 这个坑本项目踩过两次。所以这里每一处写操作都包在 `runCatching` 里，
 * 失败信息原样显示给用户（那本来就是一句人话）。
 */
class QuotaViewModel(
    private val quotaRepository: UsageQuotaRepository,
    private val systemPackageDetector: SystemPackageDetector,
    /**
     * 读已安装应用用的线程池。
     *
     * 单独成参数（默认 [Dispatchers.IO]）是为了让单元测试能换掉它：
     * `installedLaunchableApps()` 会真的去问 PackageManager，
     * 跑在真实的 IO 线程上时 `advanceUntilIdle()` 等不到它 ——
     * 那会让测试出现"有时过有时不过"的抖动。
     * 与仓库层注入 `Clock` 是同一个手法：把不确定的外部依赖收成参数。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuotaUiState())
    val uiState: StateFlow<QuotaUiState> = _uiState.asStateFlow()

    init {
        // 额度定义变化 → 立刻重新组装列表（含用量）
        viewModelScope.launch {
            runCatching {
                quotaRepository.observeAll().collect { quotas ->
                    val usage = runCatching { quotaRepository.usageOfAll() }
                        .getOrDefault(emptyMap())
                    _uiState.value = _uiState.value.copy(
                        rows = quotas.map { quota ->
                            QuotaRow(quota, usage[quota.id] ?: QuotaUsage.UNKNOWN)
                        },
                        loading = false
                    )
                }
            }.onFailure { fail("读取额度失败：${it.message}") }
        }
        loadCandidates()
    }

    // ---------------------------------------------------------------- 候选应用

    private fun loadCandidates() {
        viewModelScope.launch {
            // ⚠️ 两次 runCatching 是刻意的：外层的 getOrDefault 保证"探测器坏了"
            // 只是候选列表为空（用户仍能编辑已有额度），而不是让这一页崩掉。
            // `installedLaunchableApps()` 自己内部也有 runCatching，这里是兜底。
            val apps = withContext(ioDispatcher) {
                runCatching {
                    runCatching { systemPackageDetector.installedLaunchableApps() }
                        .getOrDefault(emptyList())
                        .map { AppOption(it.packageName, it.label) }
                }.getOrDefault(emptyList())
            }
            _uiState.value = _uiState.value.copy(candidates = apps)
        }
    }

    /**
     * 候选应用（带"已被别的额度绑过"的提示）。
     *
     * 允许一个应用被多个额度绑：口径是"禁用取最严的那个"（见
     * `QuotaCalculator.exhaustedPackages`），所以这里只提示、不禁止。
     */
    fun candidatesExcept(editingQuotaId: Long): List<AppOption> {
        val boundElsewhere = _uiState.value.rows
            .filter { it.quota.id != editingQuotaId }
            .flatMap { it.quota.packageNames }
            .toSet()
        return _uiState.value.candidates.map {
            it.copy(usedElsewhere = it.packageName in boundElsewhere)
        }
    }

    // ---------------------------------------------------------------- 用量

    /** 重算"已用 / 剩余"。界面在进入页面与下拉刷新时调用。 */
    fun refreshUsage() {
        viewModelScope.launch {
            runCatching {
                val usage = quotaRepository.usageOfAll()
                _uiState.value = _uiState.value.copy(
                    rows = _uiState.value.rows.map { row ->
                        row.copy(usage = usage[row.quota.id] ?: QuotaUsage.UNKNOWN)
                    }
                )
            }.onFailure { fail("重新计算用量失败：${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 编辑器

    /** 打开新建编辑器。 */
    fun startCreate() {
        updateDraft(QuotaDraft())
    }

    /**
     * 打开编辑已有额度。
     *
     * ## v2.11（用户第 3 条规则 2）：已用尽 / 已关闭时**不开编辑器**
     *
     * 界面上那个「编辑」按钮这时候是置灰的（点不到），这里是**第二道**：
     * 任何别的调用点（或将来新加的入口）走到这里也一律拒绝，
     * 并给出**可读原因** —— 用户至少知道为什么点不动。
     *
     * 判据来自仓库当前的行快照（[QuotaRow.status]），与仓库守卫同一个函数。
     */
    fun startEdit(quota: UsageQuota) {
        val row = _uiState.value.rows.firstOrNull { it.quota.id == quota.id }
        if (row != null && row.mutationLocked) {
            fail(row.mutationLockedHint ?: "这份额度现在改不了")
            return
        }
        updateDraft(
            QuotaDraft(
                id = quota.id,
                name = quota.name,
                limitMinutes = quota.limitMinutes,
                resetPeriod = quota.resetPeriod,
                enabled = quota.enabled,
                packageNames = quota.effectivePackages
            )
        )
    }

    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(draft = null, draftProblem = null)
    }

    fun updateName(name: String) {
        updateDraft(currentDraft().copy(name = name.take(UsageQuota.MAX_NAME_LENGTH)))
    }

    fun updateLimit(minutes: Int) {
        updateDraft(
            currentDraft().copy(
                limitMinutes = minutes.coerceIn(
                    UsageQuota.MIN_LIMIT_MINUTES,
                    UsageQuota.MAX_LIMIT_MINUTES
                )
            )
        )
    }

    fun updatePeriod(period: ResetPeriod) {
        updateDraft(currentDraft().copy(resetPeriod = period))
    }

    fun toggleEnabled(enabled: Boolean) {
        updateDraft(currentDraft().copy(enabled = enabled))
    }

    /** 勾选 / 取消勾选一个应用。 */
    fun togglePackage(packageName: String) {
        val draft = currentDraft()
        val next = if (packageName in draft.packageNames) {
            draft.packageNames - packageName
        } else {
            draft.packageNames + packageName
        }
        updateDraft(draft.copy(packageNames = next))
    }

    /**
     * 保存草稿。
     *
     * 成功后把 [QuotaUiState.draft] 置空 —— 界面据此关闭编辑器。
     * 失败时把原因写进 [QuotaUiState.draftProblem]，**编辑器保持打开**，
     * 用户改一下就能重试（关掉弹窗等于把刚填的内容丢掉）。
     */
    fun saveDraft() {
        val draft = _uiState.value.draft ?: return
        viewModelScope.launch {
            runCatching {
                if (draft.isNew) {
                    quotaRepository.add(
                        name = draft.name,
                        limitMinutes = draft.limitMinutes,
                        resetPeriod = draft.resetPeriod,
                        packageNames = draft.packageNames,
                        enabled = draft.enabled
                    )
                } else {
                    // v2.11（用户规则 2）：编辑已用尽的额度也要被拒 ——
                    // 用户可能在打开编辑器之后、点保存之前把额度用完（列表
                    // 停留几分钟就会发生）。这里按**当下**的行快照先拒一次，
                    // 仓库层还会再权威地拒一次。
                    val row = _uiState.value.rows.firstOrNull { it.quota.id == draft.id }
                    if (row != null && row.mutationLocked) {
                        throw QuotaRejectedException(
                            row.mutationLockedHint ?: "这份额度现在改不了"
                        )
                    }
                    // 编辑时 createdAt 保留原值（仓库只写 entity）
                    val existing = quotaRepository.getById(draft.id)
                    quotaRepository.update(
                        draft.toDomain(createdAt = existing?.createdAt ?: 0L)
                    )
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    draft = null,
                    draftProblem = null,
                    message = if (draft.isNew) "额度已创建" else "额度已保存"
                )
            }.onFailure { error ->
                // 业务拒绝（名称为空 / 时长越界 / 没选应用 / 已用尽改不了）
                // 与程序出错在这里走同一条显示路径 —— 它们对用户都是一句可读的原因
                val reason = error.message ?: "保存失败"
                _uiState.value = _uiState.value.copy(draftProblem = reason)
                if (error !is QuotaRejectedException) fail("保存失败：$reason")
            }
            // 保存会改变"用完没用完"，置灰状态必须立刻跟着变
            refreshUsage()
        }
    }

    /**
     * 删除一个额度（连带绑定关系，由仓库负责）。
     *
     * ## v2.11（用户规则 2）：ViewModel 这一层也要拒绝
     *
     * 用户明确要求"**不能只挡界面**：请在 ViewModel（乃至仓库）也拒绝，
     * 并给出可读原因"。仓库层会抛 [QuotaRejectedException]（真正权威），
     * 而**这种业务拒绝必须变成一句人话**而不是"删除失败：xxx"那种红字，
     * 所以这里单列一条分支。
     */
    fun delete(quotaId: Long) {
        viewModelScope.launch {
            runCatching { quotaRepository.delete(quotaId) }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "额度已删除") }
                .onFailure { error ->
                    if (error is QuotaRejectedException) {
                        // 业务拒绝：原样显示原因（本来就是一句人话）
                        fail(error.message ?: "这份额度现在删不掉")
                    } else {
                        fail("删除失败：${error.message}")
                    }
                }
            // 无论成没成，都用库里的真相重算一次 —— 置灰状态必须跟着变
            refreshUsage()
        }
    }

    /**
     * 启用 / 停用。
     *
     * ## v2.11（用户规则 2）：**关闭**也要被拒
     *
     * "额度用完就把额度关掉"是绕过限制的第一步，所以关掉必须和删除
     * 受同一道守卫。**打开**永远放行（往更严的方向走，而且是
     * "已关闭的额度怎么改回来"的唯一出口）。
     */
    fun setEnabled(quotaId: Long, enabled: Boolean) {
        viewModelScope.launch {
            runCatching { quotaRepository.setEnabled(quotaId, enabled) }
                .onFailure { error ->
                    if (error is QuotaRejectedException) {
                        fail(error.message ?: "这份额度现在关不掉")
                    } else {
                        fail("切换失败：${error.message}")
                    }
                }
            refreshUsage()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // ---------------------------------------------------------------- 内部

    private fun currentDraft(): QuotaDraft = _uiState.value.draft ?: QuotaDraft()

    /**
     * 写入草稿并**实时**重算校验问题。
     *
     * 实时校验而不是等点保存：这是界面上提前给反馈用的一道，
     * 真正的拒绝仍然发生在仓库层（那里也有单测）。
     */
    private fun updateDraft(draft: QuotaDraft) {
        _uiState.value = _uiState.value.copy(draft = draft, draftProblem = validate(draft))
    }

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(message = message)
    }

    companion object {

        /**
         * 把"小时 + 分钟"两个输入框的文本合成总分钟数。
         *
         * 用户要求："把滑块改成自填数字：**先填小时，再填分钟**"。
         * 上限放开到 744 小时之后滑块已经拖不准了（一像素 ≈ 31 分钟），
         * 所以时长改由这两个输入框决定，合成规则写在这里 ——
         * 它是纯函数，能被 JVM 单测逐条钉死，界面（[QuotaScreen]）只负责喂文本。
         *
         * 规则（每一条都对应一个"用户会打出来的怪输入"）：
         *  - 非数字一律忽略（用户正在删字符时输入框可能是空串）
         *  - 空串按 0 算，不会让整份草稿变成非法
         *  - 分钟栏超过 59 按 59 算（"90 分钟"该写进小时栏）
         *  - 合计超过上限**夹到上限**而不是报错：夹取的口径与
         *    [updateDraft] 的 `coerceIn` 完全一致，用户看到的值就是存下去的值
         *  - 全程用 `Long` 运算：用户真打了 10 位数进来也不会溢出成负数
         */
        fun limitMinutesOf(hours: String, minutes: String): Int {
            val h = hours.filter { it.isDigit() }.take(12).toLongOrNull() ?: 0L
            val m = (minutes.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0).coerceIn(0, 59)
            return (h * 60L + m).coerceIn(
                UsageQuota.MIN_LIMIT_MINUTES.toLong(),
                UsageQuota.MAX_LIMIT_MINUTES.toLong()
            ).toInt()
        }

        /**
         * 草稿校验：返回给用户看的原因；合法时返回 null。
         *
         * 与 `PlanRepository.ensureSameDayWindow` 同一口径：**起止为 0 这类
         * "看起来像空值"的输入必须显式拦下**。
         *
         * ## v2.11（用户第 3 条规则 2）：**新建**不再接受 0 分钟
         *
         * 以前这里对 0 分钟是放行的（"额度为零时禁用指定软件"是用户明确要求）。
         * 但"剩余 == 0 的三个按钮永久置灰"这条新规则一上，**新建一个 0 分钟
         * 额度就等于把自己锁死**（它立刻用尽，于是再也关不掉、改不了、删不掉）。
         * 用户点名要求处理这个边界，并且要求"保证新建时就不能创建 0 分钟额度"。
         *
         * 所以：
         *  - **新建**（[QuotaDraft.isNew]）→ 至少 [UsageQuota.MIN_CREATABLE_LIMIT_MINUTES] 分钟
         *  - **编辑**已有额度 → 不做这道限制。旧库里可能就有 0 分钟额度，
         *    那道限制会让用户连"把它改成正常值"都做不到 —— 比原问题更糟。
         *
         * 真正的拒绝仍然在仓库层（`UsageQuotaRepository.add`），这里只是
         * 提前给反馈，两处口径一致。
         */
        fun validate(draft: QuotaDraft): String? = when {
            draft.name.isBlank() -> "请给这个额度起个名字"

            // ⚠️ 顺序很重要：**越界**要排在"新建不许 0 分钟"前面。
            // 否则 -1（越界）会命中"至少 1 分钟"那句，用户看到的原因就不再是
            // "越界"了 —— 而越界本来有自己更准确的一条。
            draft.limitMinutes !in UsageQuota.MIN_LIMIT_MINUTES..UsageQuota.MAX_LIMIT_MINUTES ->
                "时长必须在 ${UsageQuota.MIN_LIMIT_MINUTES}..${UsageQuota.MAX_LIMIT_MINUTES} 分钟之间"

            draft.isNew && draft.limitMinutes < UsageQuota.MIN_CREATABLE_LIMIT_MINUTES ->
                "时长至少 ${UsageQuota.MIN_CREATABLE_LIMIT_MINUTES} 分钟。" +
                    "0 分钟的额度一建出来就用尽了，于是它自己也改不了、删不掉。" +
                    "想「一打开就拦」请设 1 分钟。"

            draft.packageNames.isEmpty() -> "至少要选一个应用（这些应用会共用这份额度）"
            else -> null
        }
    }
}
