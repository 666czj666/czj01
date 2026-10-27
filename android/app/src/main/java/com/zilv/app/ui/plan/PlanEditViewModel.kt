package com.zilv.app.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.domain.plan.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 「明日规划」新建 / 编辑页的状态。
 *
 * ## 时间用「当天第几分钟」而不是 `LocalTime`
 *
 * 与 `TimeWindow` / `Plan` 的口径一致（它们也用 minuteOfDay）：
 * "跨 0 点"这件事只有在分钟数上才看得清楚 —— `22:00` 到 `06:00`
 * 用 `LocalTime` 比较大小会得到"结束早于开始"，而这里可以直接说
 * `endMinute < startMinute` 就是跨 0 点。
 */
data class PlanEditUiState(
    val id: Long = 0L,
    val name: String = "",
    /** 规划生效的那一天。新建时恒为**明天**（仓库层会再校验一次）。 */
    val date: LocalDate = LocalDate.now().plusDays(1),
    /** 开始时间（当天第几分钟）；`null` 表示还没选。 */
    val startMinute: Int? = null,
    /** 结束时间（当天第几分钟）；`null` 表示还没选。 */
    val endMinute: Int? = null,
    /** 载入完成后为真 —— 界面的"预填名称"要等它。 */
    val loadedOnce: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** 是否正在等待删除确认（不可恢复的操作必须二次确认） */
    val pendingDelete: Boolean = false,
    val error: String? = null
) {

    val isNew: Boolean get() = id == 0L

    /** 时段文案：`09:00-11:00`；没选全时给出占位。 */
    val timeLabel: String
        get() {
            val s = startMinute
            val e = endMinute
            return when {
                s == null && e == null -> "未设置"
                s == null -> "--:-- 至 ${hhmm(e!!)}"
                e == null -> "${hhmm(s)} 至 --:--"
                else -> "${hhmm(s)}-${hhmm(e)}"
            }
        }

    /**
     * 当前输入的**本地**问题（还没提交给仓库就能看出来的）。
     *
     * 这里只做"填没填全 + 起止顺序"这类**纯输入**校验；
     * "只能添加明天"、"规划进行中不能改"这些需要看库的判断一律交给仓库层
     * （它的拒绝理由也是可读的中文，直接显示给用户）。
     *
     * ⚠️ `startMinute == endMinute` **必须单独拦下**：
     * `TimeWindow(x, x)` 的时长是 **1440 分钟（全天）**而不是 0 ——
     * 放过去就等于把"10:00 到 10:00"变成整天锁机（这个坑本项目踩过多次）。
     */
    val inputProblem: String?
        get() {
            val s = startMinute
            val e = endMinute
            return when {
                name.isBlank() -> "请填写规划名称"
                s == null || e == null -> "请设置开始时间和结束时间"
                s == e -> "开始时间和结束时间不能相同（会被当成全天锁机）"
                e < s -> "规划不能跨 0 点：结束时间必须晚于开始时间"
                else -> null
            }
        }

    val canSave: Boolean get() = inputProblem == null && !saving

    private fun hhmm(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

/**
 * 「明日规划」新建 / 编辑 ViewModel。
 *
 * ## 为什么所有仓库调用都包着 `runCatching`
 *
 * `PlanRepository` 的三个写入口都会抛 `PlanRejectedException`
 * （不是明天 / 跨 0 点 / 起止相同 / 名称空 / 正在进行中不能改删）。
 * `viewModelScope` 里的**未捕获异常会直接终止 App 进程** ——
 * 这个坑在本项目踩过两次，所以这里一处不漏。
 * 失败原因都是给人看的中文，直接显示到界面上。
 */
class PlanEditViewModel(
    private val planRepo: PlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanEditUiState())
    val uiState: StateFlow<PlanEditUiState> = _uiState.asStateFlow()

    /**
     * 载入。
     *
     * @param planId `0` 表示新建；否则是 `plans.id`
     */
    fun load(planId: Long) {
        viewModelScope.launch {
            // 先把 loadedOnce 置回 false：同一个 ViewModel 实例理论上只会 load 一次，
            // 但把状态显式归零能让"预填必须晚于 load"这条规则在任何调用顺序下都成立
            _uiState.value = PlanEditUiState(loadedOnce = false)

            if (planId == 0L) {
                // 新建：日期就是"明天"（由注入的时钟决定，测试可固定），
                // 时间留空让用户自己选 —— 编造一个默认时段反而会让用户
                // 以为"规划已经设好了"就点了保存
                _uiState.value = PlanEditUiState(
                    id = 0L,
                    date = planRepo.tomorrow(),
                    startMinute = null,
                    endMinute = null,
                    loadedOnce = true
                )
                return@launch
            }

            val plan = runCatching { planRepo.getById(planId) }.getOrNull()
            if (plan == null) {
                _uiState.value = PlanEditUiState(
                    id = planId,
                    loadedOnce = true,
                    error = "这条规划不存在（id=$planId）"
                )
                return@launch
            }

            _uiState.value = PlanEditUiState(
                id = plan.id,
                name = plan.name,
                date = plan.date,
                startMinute = plan.startMinute,
                endMinute = plan.endMinute,
                loadedOnce = true
            )
        }
    }

    fun setName(value: String) {
        _uiState.value = _uiState.value.copy(name = value, error = null)
    }

    /**
     * 预填名称 —— 供「添加」页的弹窗把用户输入的昵称带进来。
     *
     * 与 `HabitEditViewModel.prefillName` 同一条规则：**只在名称还空着时**生效，
     * 编辑已有规划时一律以库里的值为准（漏判一次就会覆盖用户的名字）。
     */
    fun prefillName(value: String) {
        if (value.isBlank()) return
        if (_uiState.value.name.isNotBlank()) return
        _uiState.value = _uiState.value.copy(name = value, error = null)
    }

    fun setStartMinute(minuteOfDay: Int) {
        _uiState.value = _uiState.value.copy(startMinute = minuteOfDay, error = null)
    }

    fun setEndMinute(minuteOfDay: Int) {
        _uiState.value = _uiState.value.copy(endMinute = minuteOfDay, error = null)
    }

    /**
     * 预填起止时间 —— 供「添加」页的时间窗把用户选好的时段带进来。
     *
     * 与 [prefillName] 同一条规则：
     *  - **只在新建时**生效（编辑已有规划时时间以库里的值为准）
     *  - 已经设过的那一端不覆盖（用户可能先自己改了一端）
     *
     * @param startMinute / [endMinute] 为 `null` 表示调用方没有要预填的值
     */
    fun prefillTimes(startMinute: Int?, endMinute: Int?) {
        val state = _uiState.value
        if (!state.isNew) return
        _uiState.value = state.copy(
            startMinute = startMinute ?: state.startMinute,
            endMinute = endMinute ?: state.endMinute,
            error = null
        )
    }

    /** 保存。仓库层的拒绝理由会原样显示出来（它们是给人看的中文）。 */
    fun save() {
        val state = _uiState.value
        val problem = state.inputProblem
        if (problem != null) {
            _uiState.value = state.copy(error = problem)
            return
        }
        val start = state.startMinute!!
        val end = state.endMinute!!

        viewModelScope.launch {
            _uiState.value = state.copy(saving = true, error = null)

            // 两个写入口分开 runCatching：新建与修改的失败原因不同，
            // 而且"改到过去的日期"这类拒绝只可能来自 update
            val result = runCatching {
                if (state.isNew) {
                    planRepo.addPlan(
                        name = state.name,
                        date = state.date,
                        startMinute = start,
                        endMinute = end
                    )
                } else {
                    planRepo.update(
                        Plan(
                            id = state.id,
                            name = state.name,
                            date = state.date,
                            startMinute = start,
                            endMinute = end
                        )
                    )
                }
            }

            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(saving = false, saved = true, error = null)
            } else {
                _uiState.value.copy(
                    saving = false,
                    saved = false,
                    error = result.exceptionOrNull()?.message ?: "保存失败"
                )
            }
        }
    }

    // ---------------------------------------------------------------- 删除

    /** 第一步：点删除 -> 只弹确认框，**不立即删**。 */
    fun requestDelete() {
        if (_uiState.value.id == 0L) return
        _uiState.value = _uiState.value.copy(pendingDelete = true)
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(pendingDelete = false)
    }

    /**
     * 第二步：确认删除。
     *
     * 正在进行中的规划会被仓库层拒绝（"解锁条件只有到点解锁"，
     * 允许删除就等于允许提前解锁），拒绝理由显示到界面上。
     */
    fun confirmDelete() {
        val id = _uiState.value.id
        if (id == 0L) {
            _uiState.value = _uiState.value.copy(pendingDelete = false)
            return
        }
        viewModelScope.launch {
            runCatching { planRepo.delete(id) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        pendingDelete = false,
                        saved = true,
                        error = null
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        pendingDelete = false,
                        error = it.message ?: "删除失败"
                    )
                }
        }
    }
}
