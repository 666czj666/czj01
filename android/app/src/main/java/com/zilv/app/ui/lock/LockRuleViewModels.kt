package com.zilv.app.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.lock.CheckInWindowRules
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.model.Spot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * 锁机配置编辑（**按习惯作用域**）。
 *
 * v2.0 起锁机不再有独立入口：用户要求在习惯编辑页里"详细设置"锁机。
 * 所以这里的主键是 `habitId` —— 一个习惯至多一份配置，
 * 由 [LockRuleRepository.saveForHabit] 保证反复保存不会堆出多条。
 */
data class LockRuleEditUiState(
    val id: Long = 0L,
    /** 归属的习惯；0 表示还没有习惯（理论上不会出现，界面会先让用户保存习惯） */
    val habitId: Long = 0L,
    val name: String = "",
    val enabled: Boolean = true,
    /** 生效日期区间，null 表示不限。界面上没有入口，但保存时**必须原样带回去**。 */
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val days: Set<DayOfWeek> = emptySet(),
    /**
     * 旧式**自选锁机窗口**（域对象里叫 `windows`）。
     *
     * v2.1 起界面上不再有这个入口（用户要求："不自选锁机时间删除，改为超过打卡时间之后锁机"）——
     * 它只用于兼容历史规则，以及 [LockRuleEditViewModel.addWindow]/[removeWindow]
     * 这条旧式路径。新式配置看 [lockDurationMinutes]。
     */
    val windows: List<TimeWindow> = emptyList(),
    /**
     * 锁机时长（分钟）：**从打卡截止开始锁多久**，上限 24 小时（1440）。
     *
     * `0` 表示还没设置 —— 界面上不允许用这种状态保存（见 [canSaveOnScreen]）。
     */
    val lockDurationMinutes: Int = 0,
    /** 「必须先打卡」的时间段。为空表示不强制打卡（兼容 v1.x 行为）。 */
    val checkInWindows: List<TimeWindow> = emptyList(),
    /**
     * **已废弃**：见 `UnlockMode` 的 KDoc。用户取消了"仅定时解锁 / 需地点打卡"
     * 这个选择（v2.2：打卡即解锁，或等锁机时间走完），界面上**不再有**这个选择器。
     *
     * 字段与 [setUnlockMode] 保留是为了不动数据库列、也为了不破坏既有回归测试；
     * 值会原样带着走，但**不影响任何行为**。
     */
    val unlockMode: UnlockMode = UnlockMode.TIME_ONLY,
    /**
     * **已废弃**：打卡点归习惯的打卡条件管。保留同样只是为了让旧数据原样往返。
     */
    val spotId: Long? = null,
    /** 可选打卡点（M5：从数据库读取真实打卡点，不再硬编码编号） */
    val availableSpots: List<Spot> = emptyList(),
    val earlyCheckInMinutes: Int = 0,
    val graceMinutes: Int = 0,
    val strictMode: Boolean = false,
    val message: String = "",
    val sortOrder: Int = 0,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null
) {
    /**
     * 当前配置本身是否自洽。
     *
     * 直接复用域对象的 [LockRule.isValid]：新式（打卡截止 + 时长）与旧式（自选窗口）
     * 两种形态都由它判定，界面层不另写一份 —— 否则调度层和界面迟早悄悄分叉。
     *
     * 这里**不判断名称**：名称由所属习惯决定，界面会自动带过来，
     * 让用户为"锁机"再起一个名字是多余的负担（[toRule] 会兜一个默认名）。
     */
    val isValid: Boolean get() = toRule().isValid

    val canSave: Boolean get() = isValid

    /**
     * **界面上的**保存门槛：v2.1 起锁机由"时长"定义，没设时长就不让保存
     * （用户要求："锁机的自选时间开始改为自选时长，最长为 24 小时"）。
     *
     * 与 [canSave] 的差别只在**旧式自选窗口**规则：老数据没有时长也仍然合法，
     * [LockRuleRepository] 与既有回归测试都还依赖那条路径，所以 [canSave] 保持宽松；
     * 而界面上已经没有自选窗口的入口，因此界面用这个更严格的门槛。
     */
    val canSaveOnScreen: Boolean get() = canSave && lockDurationMinutes > 0

    /** 时长文案（`45 分钟` / `2 小时 30 分` / `24 小时`）：复用 [LockRule.lockDurationLabel]。 */
    val lockDurationLabel: String get() = toRule().lockDurationLabel

    /**
     * 「锁机必须晚于打卡截止」这条约束是否满足（与域对象同一套口径）。
     *
     * 用 [toRule] 复用 [LockRule.lockStartsAfterCheckIn]，
     * 避免界面层另写一份判断而与调度层悄悄分叉。
     */
    val lockStartsAfterCheckIn: Boolean get() = toRule().lockStartsAfterCheckIn

    /** 不满足约束时给用户看的原因；满足时为 null。 */
    val lockWindowProblem: String? get() = toRule().lockWindowProblem

    /** 保存被拒绝时给用户的第一句人话。 */
    val validationMessage: String?
        get() = when {
            // 新式口径：没时长就没有锁机（旧式自选窗口非空时仍按老规矩放行）
            lockDurationMinutes <= 0 && windows.isEmpty() ->
                "请设置锁机时长，或至少添加一个锁机时间"
            lockWindowProblem != null -> lockWindowProblem
            // v2.2：解锁方式不再可配（打卡即解锁 或 等时间走完），
            // 打卡点也归习惯的打卡条件管 —— 这里因此不再要求 spotId
            else -> null
        }

    fun toRule(): LockRule = LockRule(
        id = id,
        habitId = habitId.takeIf { it != 0L },
        name = name.ifBlank { DEFAULT_RULE_NAME },
        enabled = enabled,
        startDate = startDate,
        endDate = endDate,
        daysOfWeek = days,
        checkInWindows = checkInWindows,
        windows = windows,
        lockDurationMinutes = lockDurationMinutes.coerceIn(0, LockRule.MAX_LOCK_DURATION_MINUTES),
        unlockMode = unlockMode,
        spotId = spotId,
        earlyCheckInMinutes = earlyCheckInMinutes,
        graceMinutes = graceMinutes,
        strictMode = strictMode,
        message = message.trim(),
        sortOrder = sortOrder
    )

    companion object {
        /** 习惯名缺失时的兜底规则名 —— `LockRule.isValid` 要求名称非空。 */
        const val DEFAULT_RULE_NAME = "锁机"
    }
}

/** 锁机配置编辑（按习惯，PRD T3.3 / §4.5）。 */
class LockRuleEditViewModel(
    private val repo: LockRuleRepository,
    private val spotRepo: SpotRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LockRuleEditUiState())
    val uiState: StateFlow<LockRuleEditUiState> = _uiState.asStateFlow()

    /** 上一次载入用的习惯 id，`save()` 需要它。 */
    private var habitId: Long = 0L

    /**
     * 载入某个习惯的锁机配置。
     *
     * 没配置过（[LockRuleRepository.getRuleForHabit] 返回 null）时给一份**默认值**，
     * 而不是报错 —— 用户第一次打开"详细设置"看到的应该是一个可以随手改的表单，
     * 不是一句"找不到规则"。
     */
    fun load(habitId: Long) {
        this.habitId = habitId
        viewModelScope.launch {
            // 打卡点始终加载，供"到点打卡"解锁条件选择
            val spots = spotRepo.getAllSpots()
            _uiState.value = _uiState.value.copy(availableSpots = spots)

            if (habitId == 0L) return@launch
            val rule = repo.getRuleForHabit(habitId)
            val next = if (rule == null) {
                defaultState(habitId, spots)
            } else {
                LockRuleEditUiState(
                    id = rule.id,
                    habitId = habitId,
                    name = rule.name,
                    enabled = rule.enabled,
                    // 界面上没有生效日期的入口，但**必须原样带回去** ——
                    // 否则用户在别的设置上点一次保存，就把已有规则的生效区间抹掉了
                    startDate = rule.startDate,
                    endDate = rule.endDate,
                    days = rule.daysOfWeek,
                    windows = rule.windows,
                    lockDurationMinutes = rule.lockDurationMinutes,
                    checkInWindows = rule.checkInWindows,
                    unlockMode = rule.unlockMode,
                    spotId = rule.spotId,
                    availableSpots = spots,
                    earlyCheckInMinutes = rule.earlyCheckInMinutes,
                    graceMinutes = rule.graceMinutes,
                    strictMode = rule.strictMode,
                    message = rule.message,
                    sortOrder = rule.sortOrder
                )
            }
            _uiState.value = next
        }
    }

    fun setName(v: String) {
        _uiState.value = _uiState.value.copy(name = v, error = null)
    }

    fun setEnabled(v: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = v)
    }

    /**
     * 界面上的「对这条习惯开启锁机」开关。
     *
     * 关掉只影响 `enabled`，**不会删掉配置** —— 删除是习惯编辑页那个开关（`deleteForHabit`）
     * 的职责。这样用户在详细设置里关掉再打开，时间窗口还在。
     */
    fun setLockEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled)
    }

    fun toggleDay(d: DayOfWeek) {
        val cur = _uiState.value.days
        _uiState.value = _uiState.value.copy(days = if (d in cur) cur - d else cur + d)
    }

    fun clearDays() {
        _uiState.value = _uiState.value.copy(days = emptySet())
    }

    // ------------------------------------------------------- 锁机时长（v2.1）
    //
    // 用户要求："不自选锁机时间删除，改为超过打卡时间之后锁机，
    // 锁机的自选时间开始改为自选时长，最长为 24 小时"。

    /**
     * 设置锁机时长（分钟）：从**打卡截止时刻**开始锁多久，上限 24 小时。
     *
     * `0` 表示没设置 —— 界面会拒绝保存并提示"请设置锁机时长"。
     *
     * 设置时长（含设成 0）意味着**改用"打卡截止 + 时长"这套口径**，
     * 因此顺手清掉旧式自选窗口：`LockRule.usesDurationMode` 为真时 `windows` 本来就会被
     * 推导值覆盖，留着只会让用户看到两条互相矛盾的锁机时间；
     * 而设成 0 时若还留着窗口，用户会以为"没设时长也能存"，那正是这次要删掉的用法。
     */
    fun setLockDurationMinutes(value: Int) {
        _uiState.value = _uiState.value.copy(
            lockDurationMinutes = value.coerceIn(0, LockRule.MAX_LOCK_DURATION_MINUTES),
            windows = emptyList(),
            error = null
        )
    }

    // -------------------------------------------- 旧式自选锁机窗口（v2.1 起
    //  界面上已没有入口，保留是为了兼容历史规则与既有回归测试）

    fun addWindow(start: LocalTime, end: LocalTime) {
        // ⚠️ 起止相同必须显式拦下：`TimeWindow(x, x)` 会被判成"跨零点"，
        // durationMinutes 是 **1440 而不是 0** —— 只看 durationMinutes 会静悄悄
        // 塞进一个"全天锁机"窗口（与 addCheckInWindow 同一个坑）
        if (start == end && end != LocalTime.MIDNIGHT) {
            _uiState.value = _uiState.value.copy(error = "时间窗口的结束时间不能与开始时间相同")
            return
        }
        val w = TimeWindow.of(start, end, endMeansMidnight = true)
        if (w.durationMinutes <= 0) {
            _uiState.value = _uiState.value.copy(error = "时间窗口的结束时间不能与开始时间相同")
            return
        }
        _uiState.value = _uiState.value.copy(
            windows = (_uiState.value.windows + w).distinct(),
            // 手动编辑自选窗口＝回到旧式模式：不清掉时长的话，
            // 刚加进来的窗口会被"打卡截止 + 时长"的推导覆盖掉
            lockDurationMinutes = 0,
            error = null
        )
    }

    fun removeWindow(index: Int) {
        val list = _uiState.value.windows.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _uiState.value = _uiState.value.copy(windows = list, lockDurationMinutes = 0)
    }

    // ------------------------------------------------------------ 打卡时间

    /**
     * 追加一个"必须打卡"的时间段。
     *
     * 参数是**当天第几分钟**（`[TimeWindow]` 的字段语义）。
     *
     * ⚠️ 校验口径必须与 [addWindow] 完全一致，这里踩过一次真实的坑：
     * `TimeWindow(540, 540)` 的 [TimeWindow.durationMinutes] 是 **1440，不是 0** ——
     * 起止相同被 [TimeWindow.crossesMidnight] 判成跨零点。若只用 `durationMinutes <= 0`
     * 当校验，`addCheckInWindow(9*60, 9*60)` 会静悄悄塞进一个"全天打卡窗口"。
     * 所以先显式排除起止相同，再走与锁机窗口同一套 [TimeWindow.of] 语义。
     *
     * 校验与转换统一走 [CheckInWindowRules]（习惯编辑页 / 新建习惯弹窗用的是
     * **同一个**纯函数），三处界面的口径因此不可能漂移。
     */
    fun addCheckInWindow(startMinute: Int, endMinute: Int) {
        CheckInWindowRules.problem(startMinute, endMinute)?.let { reason ->
            _uiState.value = _uiState.value.copy(error = reason)
            return
        }
        // 结束取 00:00 表示 24:00（与锁机窗口同一口径）。结束早于开始则是合法的跨零点窗口。
        val w = CheckInWindowRules.toWindow(startMinute, endMinute)
        if (w == null) {
            _uiState.value = _uiState.value.copy(error = "打卡时间不合法")
            return
        }
        _uiState.value = _uiState.value.copy(
            checkInWindows = (_uiState.value.checkInWindows + w).distinct(),
            error = null
        )
    }

    fun removeCheckInWindow(index: Int) {
        val list = _uiState.value.checkInWindows.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _uiState.value = _uiState.value.copy(checkInWindows = list)
    }

    // ------------------------------------------------------------ 解锁条件

    /**
     * **已废弃，界面上已无入口**（v2.2：打卡即解锁，不再由用户选解锁方式）。
     *
     * 保留这个方法是为了不破坏既有回归测试。它现在**只改内存里的值**，
     * 不再顺手清 `spotId` —— 打卡点已经归习惯管，规则页不该再动它。
     */
    fun setUnlockMode(mode: UnlockMode) {
        _uiState.value = _uiState.value.copy(unlockMode = mode, error = null)
    }

    /** **已废弃，界面上已无入口**：打卡点归习惯的打卡条件管。 */
    fun setSpotId(id: Long?) {
        _uiState.value = _uiState.value.copy(spotId = id, error = null)
    }

    fun setGraceMinutes(v: Int) {
        _uiState.value = _uiState.value.copy(graceMinutes = v.coerceIn(0, 120))
    }

    fun setEarlyCheckInMinutes(v: Int) {
        _uiState.value = _uiState.value.copy(earlyCheckInMinutes = v.coerceIn(0, 240))
    }

    fun setStrictMode(v: Boolean) {
        _uiState.value = _uiState.value.copy(strictMode = v)
    }

    fun setMessage(v: String) {
        _uiState.value = _uiState.value.copy(message = v)
    }

    // ---------------------------------------------------------------- 保存

    fun save() {
        val s = _uiState.value
        // habitId 先判：习惯没保存过时根本没有可挂的配置，
        // 此时报"请设置锁机时长"只会把用户引到错的方向
        if (s.habitId == 0L) {
            _uiState.value = s.copy(error = "先保存习惯，再设置锁机")
            return
        }
        val problem = s.validationMessage
        if (problem != null) {
            _uiState.value = s.copy(error = problem)
            return
        }
        viewModelScope.launch {
            _uiState.value = s.copy(saving = true, error = null)
            // 仓库层在锁机期间会抛 RuleLockedException —— runCatching 是安全网，
            // viewModelScope 里的未捕获异常会直接让 App 崩溃。
            runCatching { repo.saveForHabit(habitId, s.toRule()) }
                .onSuccess { _uiState.value = _uiState.value.copy(saving = false, saved = true) }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        saving = false,
                        error = it.message ?: "保存失败"
                    )
                }
        }
    }

    private companion object {
        /** 没配置过时的默认锁机时长：2 小时。 */
        const val DEFAULT_LOCK_DURATION_MINUTES = 120

        /**
         * 第一次打开"详细设置"时的默认配置。
         *
         * v2.1 起默认就是新式形态：**09:00–10:00 必须打卡、超过打卡截止后锁 2 小时** ——
         * 锁机起点由打卡截止推导，天然满足"锁机晚于打卡截止"这条硬约束，
         * 用户打开就能直接保存，不用先跟一句报错搏斗。
         *
         * `windows` 里仍然留一份**旧式自选窗口**：它是 v2.0 及以前的数据形态，
         * [LockRuleEditViewModel.addWindow]/[removeWindow] 这条旧式路径与既有回归测试
         * 还在用它（`LockRule` 只在没有时长时才采信它，所以并存不会互相干扰）。
         */
        fun defaultState(habitId: Long, spots: List<Spot>): LockRuleEditUiState {
            val checkIn = TimeWindow(9 * 60, 10 * 60)
            val lock = TimeWindow(21 * 60, 23 * 60)
            return LockRuleEditUiState(
                id = 0L,
                habitId = habitId,
                name = LockRuleEditUiState.DEFAULT_RULE_NAME,
                enabled = true,
                days = emptySet(),
                windows = listOf(lock),
                lockDurationMinutes = DEFAULT_LOCK_DURATION_MINUTES,
                checkInWindows = listOf(checkIn),
                unlockMode = UnlockMode.TIME_ONLY,
                availableSpots = spots
            )
        }
    }
}
