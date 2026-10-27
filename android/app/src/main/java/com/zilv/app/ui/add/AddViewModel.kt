package com.zilv.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.plan.Plan
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.QuotaUsage
import com.zilv.app.domain.quota.UsageQuota
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 「添加」页的状态（v2.5，v2.7 增加额度入口）。
 *
 * 用户要求："新建名词为 添加 的界面，界面显示已有的习惯和明日规划，
 * 点击可进行编辑。"
 *
 * v2.7 追加："在添加界面加入软件使用时间限时功能入口，要独立入口。"
 */
data class AddUiState(
    /** 还没归档的习惯。 */
    val habits: List<Habit> = emptyList(),
    /** 已归档的习惯，单独一组显示，避免和"还在做"的混在一起。 */
    val archivedHabits: List<Habit> = emptyList(),
    /** 明日规划（按开始时间升序，与 `PlanDao` 的排序一致）。 */
    val plans: List<Plan> = emptyList(),
    /** 有几个「使用时间限时」额度 —— 只用于入口上的计数。 */
    val quotaCount: Int = 0,
    /** 其中已启用的个数，让入口一眼看出"限时有没有在生效"。 */
    val enabledQuotaCount: Int = 0,
    /**
     * 这些额度**只说文字状态**的汇总（v2.11，用户第 3 条规则 1）。
     *
     * 用户要求这一页的额度区块也照同一条规则走：**不展示还剩多少分钟、
     * 也不展示已用多少分钟**，只给状态。所以这里存的是**已经算好的状态列表**，
     * 界面只负责数个数、拼一句话，不会再碰到任何分钟数。
     */
    val quotaStatuses: List<QuotaStatus> = emptyList(),
    /** 明天是哪一天 —— 界面上要说清"这些是给哪天排的"。 */
    val tomorrow: LocalDate = LocalDate.now().plusDays(1),
    val loading: Boolean = true
) {
    val isEmpty: Boolean get() = habits.isEmpty() && archivedHabits.isEmpty() && plans.isEmpty()

    val planCount: Int get() = plans.size

    /**
     * 额度区块那一行摘要（v2.11，用户第 3 条规则 1；本轮扩到四态）。
     *
     * ⚠️ **只能出现状态文字，不能出现任何分钟数** ——
     * "还剩 N 分钟"和"已用 N 分钟"是用户明令不许展示的（互为反推）。
     * 所以这里的每个分支都只数个数、不碰数字。
     *
     * 四种状态各数一遍，再把为 0 的省掉，于是句子按实际配置长短自适应：
     *  - 全部生效 → "其中 3 个【限额生效中】"
     *  - 混着来   → "其中 1 个【限额生效中】、1 个【额度已耗尽】、1 个【已关闭】"
     *  - 有坏数据 → "其中 1 个【配置无效】、1 个【限额生效中】"
     *
     * 顺序与 `QuotaStatus` 的优先级一致（配置无效 → 已关闭 → 已耗尽 → 生效中），
     * 于是摘要读起来就是"从最该处理的那条开始"。
     */
    val quotaStatusSummary: String
        get() {
            if (quotaStatuses.isEmpty()) return ""
            val parts = mutableListOf<String>()
            listOf(
                QuotaStatus.INVALID,
                QuotaStatus.DISABLED,
                QuotaStatus.EXHAUSTED,
                QuotaStatus.ACTIVE
            ).forEach { status ->
                val count = quotaStatuses.count { it == status }
                if (count > 0) parts += "$count 个${status.label}"
            }
            return "其中 " + parts.joinToString("、")
        }
}

/**
 * 「添加」页 ViewModel。
 *
 * ## 这一页为什么必须走 Flow 观察
 *
 * 它同时是"新建入口"和"编辑入口"：用户从这里点进习惯编辑页改完名字回到这一页时，
 * ViewModel 被导航栈保留、`init` 不会重跑（本项目在首页踩过同一个坑 ——
 * "保存习惯后今天界面无显示"）。用 Flow 观察 `habits` / `plans` 两张表，
 * 插库即刷新，这个 bug 类别在结构上不可能再出现。
 *
 * ## 「明日规划」为什么只显示明天那一份
 *
 * 用户要求规划**只能添加明天的**（仓库层强制）。这一页是编辑入口，
 * 显示"能改的那些"才不会点进去就被"已经过去的日期"拒绝。
 *
 * ## 「使用时间限时」（v2.7）为什么只做入口、不在这里建
 *
 * 用户要求在「添加」界面加**独立入口**，本轮又要求把它也放进加号弹窗。
 * 两处都只是**跳转**到 `QuotaScreen`（那里有自己的加号与编辑器）：
 * 额度必须在同一屏里同时决定名称、时长、刷新周期和一组应用（多选要搜索 +
 * 长列表），是这一页放不下的表单。
 *
 * ## 这一页**不写数据库**
 *
 * 新建流程（弹窗里选类型 → 设时间 / 填名称）全程只在内存里攒参数，
 * 真正落库发生在 `PlanEditScreen` / `HabitCreateDialog` 点「保存」的那一刻。
 * 这样做的好处很实在：
 *  - 用户在第二个弹窗里取消时，不会在库里留下一条空壳规划
 *  - "只能添加明天的规划 / 不能跨 0 点 / 起止不能相同"这几条拒绝理由
 *    由仓库层**一处**给出，界面原样显示，不需要在两处重复实现校验
 */
class AddViewModel(
    private val habitRepo: HabitRepository,
    private val planRepo: PlanRepository,
    /**
     * v2.7：「使用时间限时」的入口计数。
     *
     * 只为显示"额度（N）"这个数字而注入 —— 这一页不新增/不修改额度，
     * 真正的管理在 [com.zilv.app.ui.quota.QuotaViewModel]。
     */
    private val quotaRepo: UsageQuotaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddUiState())
    val uiState: StateFlow<AddUiState> = _uiState.asStateFlow()

    init {
        // 三条流合成一次 collect：
        //  - 活跃习惯与归档习惯来自 habits 表的同一个快照（`HabitDao.observeActive`
        //    与 `getArchived`），但接口上分成了"流"和"一次性挂起调用"两种形态，
        //    所以这里让**活跃习惯的变化**顺带触发一次归档列表的重读。
        //    归档/恢复/删除都会同时改变这两组，重读不会漏。
        //  - 规划同理：用户在这一页新建完规划回来必须立刻看见。
        //  - 额度数同理：从管理页删/建一个额度回来，入口上的数字要跟着变。
        viewModelScope.launch {
            combine(
                habitRepo.observeActiveHabits(),
                planRepo.observeAll(),
                quotaRepo.observeAll()
            ) { habits, plans, quotas -> Triple(habits, plans, quotas) }
                .collect { (habits, plans, quotas) ->
                    val tomorrow = planRepo.tomorrow()
                    val archived = runCatching { habitRepo.getArchivedHabits() }
                        .getOrDefault(emptyList())
                    _uiState.value = _uiState.value.copy(
                        habits = habits,
                        archivedHabits = archived,
                        // 只显示**明天**的规划：它们是"能编辑的那些"
                        plans = plans.filter { it.date == tomorrow },
                        quotaCount = quotas.size,
                        enabledQuotaCount = quotas.count { it.enabled },
                        // v2.11：额度区块的摘要按"只给文字状态"的规则算（见下）
                        quotaStatuses = quotaStatusesOf(quotas),
                        tomorrow = tomorrow,
                        loading = false
                    )
                }
        }
    }

    /**
     * 重算额度区块的文字状态（v2.11，用户第 3 条规则 1）。
     *
     * ## 为什么额度变化之外还要单独调一次
     *
     * "生效中 / 已耗尽"是**剩余分钟**的函数，也就是**时间的函数** ——
     * Flow 只观察得到"额度定义变了"，观察不到时间流逝。
     * 用户在这一页停留几分钟、额度正好用完时，摘要会停在旧状态上，
     * 所以界面回到前台时（`LifecycleResumeEffect`）要再算一次。
     * 这与 `QuotaViewModel.refreshUsage` 是同一个坑、同一个处理。
     */
    fun refreshQuotaStatuses() {
        viewModelScope.launch {
            val quotas = runCatching { quotaRepo.getAll() }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(quotaStatuses = quotaStatusesOf(quotas))
        }
    }

    /**
     * 把一批额度算成"文字状态"列表。
     *
     * ## 失败方向
     *
     * 算不出来（库忙 / 读失败）时退化成空列表：这一页少一句摘要无所谓，
     * 绝不能让首页因为"额度算不出来"而崩掉（未捕获异常会终止进程）。
     *
     * ## 为什么这里只碰 [QuotaStatus]，不碰分钟数
     *
     * 用户第 3 条规则 1 点名"这一页也不许展示还剩 / 已用多少分钟"。
     * 让这个函数只返回状态、把 `usedMinutes` / `remainingMinutes` 留在
     * 计算过程里，界面就**没有机会**把它们画出来。
     *
     * ⚠️ 上限必须一起喂进去：上限 == 0 的存量坏数据是
     * [QuotaStatus.INVALID]（【配置无效】）而不是"已耗尽" ——
     * 判据只有一个（`QuotaStatus.of`），两页不可能分叉。
     */
    private suspend fun quotaStatusesOf(quotas: List<UsageQuota>): List<QuotaStatus> =
        runCatching {
            val usage = quotaRepo.usageOfAll()
            quotas.map { quota ->
                QuotaStatus.of(
                    enabled = quota.enabled,
                    remainingMinutes = (usage[quota.id] ?: QuotaUsage.UNKNOWN).remainingMinutes,
                    limitMinutes = quota.limitMinutes
                )
            }
        }.getOrDefault(emptyList())

    /**
     * 校验用户在弹出的时间窗里选的起止时间。
     *
     * @return 可以给用户看的错误原因；合法时返回 `null`
     *
     * ⚠️ `startMinute == endMinute` **必须显式拒绝**：
     * `TimeWindow(x, x)` 的时长是 **1440 分钟（全天）**而不是 0 ——
     * 放过去就等于把"10:00 到 10:00"变成整天锁机（这个坑本项目踩过多次）。
     *
     * 这是**界面上提前给反馈**用的一道校验，不是业务校验的替代：
     * 真正的拒绝仍然发生在 `PlanRepository.addPlan` / `update`（那里也有单测）。
     */
    fun validateTimes(startMinute: Int, endMinute: Int): String? {
        if (startMinute !in Plan.MINUTE_MIN..Plan.MINUTE_MAX ||
            endMinute !in Plan.MINUTE_MIN..Plan.MINUTE_MAX
        ) {
            return "时间必须在 00:00..23:59 之间"
        }
        if (startMinute == endMinute) {
            return "开始时间和结束时间不能相同（会被当成全天锁机）"
        }
        if (endMinute < startMinute) {
            return "规划不能跨 0 点：结束时间必须晚于开始时间"
        }
        return null
    }

    companion object {
        /**
         * 新建规划时预填的默认时段：09:00–10:00。
         *
         * **必须是一个合法的、不跨 0 点的区间** —— 不能拿 `TimeWindow(x, x)`
         * 当"空值"，那个语义是**全天锁机**。
         */
        const val DEFAULT_START_MINUTE = 9 * 60
        const val DEFAULT_END_MINUTE = 10 * 60

        /**
         * 新建规划时预填的默认**名称**。
         *
         * 本轮用户要求"把加号弹窗里的名称输入删掉"，于是弹窗不再收集名称；
         * 但规划名不能为空（仓库层会拒绝），所以带一个**可改的**默认名过去，
         * 用户在规划编辑页里改就行。
         */
        const val DEFAULT_PLAN_NAME = "新规划"
    }
}
