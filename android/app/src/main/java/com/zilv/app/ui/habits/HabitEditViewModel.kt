package com.zilv.app.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.SpotRepository
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.lock.CheckInWindowRules
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.domain.model.Spot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 「惩罚」：没在打卡时间内打卡的后果。
 *
 * v2.1 用户要求："把设置今日习惯时可以选择惩罚，分为无和锁机，选择锁机后出现选择锁机时长"。
 * 习惯编辑页原先那个"锁机开关"因此升级成显式的二选一。
 */
enum class HabitPunishment {
    /** 无：不锁机，只是按习惯频率记录打卡。 */
    NONE,

    /** 锁机：超过打卡时间还没打卡，就从打卡截止时刻开始锁机。 */
    LOCK
}

data class HabitEditUiState(
    val id: Long = 0L,
    val name: String = "",
    val type: HabitType = HabitType.SIMPLE,
    val frequency: HabitFrequency = HabitFrequency.Daily,
    val remindTime: LocalTime? = null,
    val spotId: Long? = null,
    /**
     * **打卡条件**：指定地点 / 无。
     *
     * 与 [type] 是同一件事的两种说法（v2.2 用户亲自纠正过术语：
     * 「打卡条件」是习惯级的两个取值）。这里刻意保留两份：
     *  - [type] 是**落库口径**（`type` + `spotId` 两列，不新增数据库列）
     *  - 本字段是**界面意图**，勾成「指定地点」但还没选点时不会自己弹回「无」，
     *    界面因此能稳定地停在"请选择打卡点"上而不是来回跳
     *
     * 载入已有习惯时由 `habit.checkInCondition` 回显，保存时按 [type]/[spotId] 写库。
     */
    val checkInCondition: CheckInCondition = CheckInCondition.NONE,
    /** 可选打卡点（真实数据，供"指定地点"选择） */
    val availableSpots: List<Spot> = emptyList(),
    val spotsLoaded: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),

    // ------------------------------------------------------------ 惩罚（v2.1）
    //
    // 这一组是**编辑中的意图**：改动只留在内存里，点「保存」才一并落库
    // （见 [HabitEditViewModel.save]）。每改一下就写库的话，锁机守卫
    // 很容易把中间态拦下（RuleLockedException），用户会一头雾水。
    /** 用户选中的惩罚方式。 */
    val punishment: HabitPunishment = HabitPunishment.NONE,
    /** 锁机时长（分钟）：从打卡截止开始锁多久，上限 24 小时。`0` 表示还没设置。 */
    val lockDurationMinutes: Int = DEFAULT_LOCK_DURATION_MINUTES,
    /** 「必须先打卡」的时间段 —— 超过它还没打卡就会开始锁机。 */
    val checkInWindows: List<TimeWindow> = emptyList(),
    /**
     * 惩罚区块是否被改过且还没落库。
     *
     * 从「详细设置」返回时 [HabitEditViewModel.refreshLock] 会重读库里的配置，
     * 但**不能**把这一页上还没保存的改动冲掉 —— 所以改过就只刷新摘要。
     */
    val punishmentDirty: Boolean = false,

    /** 库里是否已经有这条习惯的锁机配置（v2.0"锁机开关"的语义）。 */
    val lockEnabled: Boolean = false,
    /** 已落库的锁机配置原文，界面据此渲染摘要；未配置时为 null */
    val lockRule: LockRule? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** 是否正在等待删除确认（不可恢复的操作必须二次确认） */
    val pendingDelete: Boolean = false,
    /**
     * 是否已经跑过一次 [HabitEditViewModel.load]（v2.5）。
     *
     * 「添加」页弹窗输入的昵称要预填进名称框，而预填**必须晚于** load ——
     * load 会用库里的值整份替换状态（编辑已有习惯时 `name` 就是库里的名字），
     * 先填的话会被它冲掉。界面因此用这个标志当预填的触发条件
     * （见 [HabitEditScreen] 里的 `LaunchedEffect`）。
     */
    val loadedOnce: Boolean = false,
    /**
     * 这次编辑是不是从「添加」页进来的（默认不是）。
     *
     * 用户要求"锁机时间不能控制添加界面"：锁机期间「添加」页必须照常能用。
     * 这个标志会被原样传给 `LockRuleRepository.saveForHabit` /
     * `deleteForHabit` 的 `allowDuringLock` 参数。
     *
     * ⚠️ **它不解除那条底线**：只要这条习惯的锁**此刻正在生效**，
     * 改/删它的惩罚配置仍然会被仓库层拒绝（否则在锁机时删掉这个习惯
     * 就等于把当前那把锁关掉了 —— 那是上一轮专门修过的洞）。
     * 完整的分界线见 `LockRuleRepository.ensureRuleEditable` 的 KDoc。
     */
    val allowDuringLock: Boolean = false,
    val error: String? = null
) {
    /**
     * 是否允许保存。PRD 验收标准：到点打卡类型未选打卡点时禁止保存。
     *
     * v2.2：这个门槛改挂在**打卡条件**上 —— 选了「指定地点」就必须选点；
     * 选「无」则随时可存（室内也能用）。
     *
     * v2.1 追加：惩罚选「锁机」时，锁机配置本身必须完整（见 [punishmentProblem]）——
     * 否则会存下一条"看着开了锁机、其实永远不会锁"的规则。
     */
    val canSave: Boolean
        get() = name.isNotBlank() &&
            (checkInCondition != CheckInCondition.LOCATION || spotId != null) &&
            punishmentProblem == null

    /** 打卡条件选了「指定地点」但一个打卡点都没有 —— 需要引导用户先去创建。 */
    val needsSpotSetup: Boolean
        get() = checkInCondition == CheckInCondition.LOCATION &&
            spotsLoaded &&
            availableSpots.isEmpty()

    val selectedSpotName: String?
        get() = availableSpots.firstOrNull { it.id == spotId }?.name

    /**
     * 锁机区块的提示文案。
     *
     * 新建习惯（[id] 为 0）时不允许打开锁机设置 —— 锁机配置挂在 habitId 上，
     * 习惯还没落库就没有 id 可用。这里把规则说清楚，而不是给一个点了没反应的按钮。
     */
    val lockHint: String
        get() = if (id == 0L) "先保存习惯，再设置锁机" else ""

    val canOpenLockSettings: Boolean get() = id != 0L

    /**
     * 惩罚选「锁机」时配置是否完整；不完整给出原因，完整或惩罚为「无」时返回 null。
     *
     * 锁机起点就是**打卡截止时刻**（[LockRule.derivedLockWindows]），
     * 所以没有打卡时间就没有锁机起点 —— 那种规则会存下来却永远不生效，
     * 必须在保存前拦下。
     *
     * 例外是**旧式自选窗口**规则（v2.0 老数据）：它没有时长也能正常工作，
     * 不能因为用户只是改个习惯名就把他拦在门外（保存时会原样保留旧配置）。
     */
    val punishmentProblem: String?
        get() {
            if (punishment != HabitPunishment.LOCK) return null
            val legacy = lockRuleUsesLegacyWindows
            if (checkInWindows.isEmpty() && !legacy) {
                return "请先添加打卡时间 —— 超过它才会开始锁机"
            }
            if (lockDurationMinutes <= 0 && !legacy) return "请设置锁机时长"
            val rule = toRule()
            return if (rule.isValid) {
                null
            } else {
                rule.lockWindowProblem ?: "锁机配置不完整，请到「详细设置」里补全"
            }
        }

    /** 时长文案：复用 [LockRule.lockDurationLabel]，界面与域对象同一套口径。 */
    val lockDurationLabel: String get() = toRule().lockDurationLabel

    /**
     * 已落库的规则还是旧式"自选锁机时间"（没有时长）。
     *
     * 这种情况下保存会把它改成"超过打卡时间后锁机"，所以界面上要提前说清。
     */
    val lockRuleUsesLegacyWindows: Boolean
        get() = lockRule?.let { it.windows.isNotEmpty() && !it.usesDurationMode } == true

    /** 配置在，但在「详细设置」里被停用了。 */
    val lockRuleDisabled: Boolean get() = lockRule?.enabled == false

    /**
     * 组装要落库的 [Habit]。
     *
     * 打卡条件 → `type`/`spotId` 的映射就在这里，而且**两列必须一致**：
     *  - 指定地点 → `LOCATION` + spotId
     *  - 无 → `SIMPLE` + `spotId = null`
     *
     * 否则库里会出现 `LOCATION` 但没选点的习惯 —— 它的
     * [Habit.checkInCondition] 是 NONE，界面显示与实际行为会对不上。
     */
    fun toHabit(): Habit {
        val location = checkInCondition == CheckInCondition.LOCATION
        return Habit(
            id = id,
            name = name.trim(),
            type = if (location) HabitType.LOCATION else HabitType.SIMPLE,
            frequency = frequency,
            remindTimes = remindTime?.let { listOf(it) } ?: emptyList(),
            spotId = spotId.takeIf { location },
            archived = archived,
            createdAt = createdAt
        )
    }

    /**
     * 把惩罚区块整理成一条 [LockRule]。
     *
     * 以**已落库的规则为底稿**，只覆盖这一页能改的东西（打卡时间、锁机时长）——
     * 解锁方式 / 宽限期 / 遮挡文案 / 严格模式都是「详细设置」的职责，
     * 不能在习惯页被悄悄重置成默认值。
     *
     * @param habitId 归属的习惯；新建习惯必须传 `HabitRepository.save` 返回的 id
     */
    fun toRule(habitId: Long = id): LockRule {
        val base = lockRule
        return LockRule(
            id = base?.id ?: 0L,
            habitId = habitId.takeIf { it != 0L },
            name = base?.name?.takeIf { it.isNotBlank() }
                ?: name.trim().ifBlank { DEFAULT_RULE_NAME },
            // 没配过就是新开的惩罚，默认启用；已配过的保留用户在「详细设置」里的启停选择
            enabled = base?.enabled ?: true,
            startDate = base?.startDate,
            endDate = base?.endDate,
            daysOfWeek = base?.daysOfWeek ?: emptySet(),
            checkInWindows = checkInWindows,
            // 旧式字段原样带着走：不迁数据、也不抹掉老规则
            windows = base?.windows ?: emptyList(),
            lockDurationMinutes = lockDurationMinutes
                .coerceIn(0, LockRule.MAX_LOCK_DURATION_MINUTES),
            unlockMode = base?.unlockMode ?: UnlockMode.TIME_ONLY,
            spotId = base?.spotId,
            earlyCheckInMinutes = base?.earlyCheckInMinutes ?: 0,
            graceMinutes = base?.graceMinutes ?: 0,
            strictMode = base?.strictMode ?: false,
            strictLeaveMinutes = base?.strictLeaveMinutes ?: 10,
            message = base?.message ?: "",
            sortOrder = base?.sortOrder ?: 0
        )
    }

    companion object {
        /** 习惯名缺失时的兜底规则名 —— `LockRule.isValid` 要求名称非空。 */
        const val DEFAULT_RULE_NAME = "锁机"

        /**
         * 默认锁机时长：2 小时。
         *
         * 与「详细设置」的默认值保持一致，用户一进页面就有个能直接用的配置。
         */
        const val DEFAULT_LOCK_DURATION_MINUTES = 120

        /**
         * 「新建习惯」弹窗每次打开时的**空白**状态。
         *
         * 做成 `HabitEditUiState` 上的纯函数（而不是写在 [HabitEditViewModel.startCreate]
         * 的协程里）有两个原因：
         *  1. 重置必须**同步**发生 —— 上一次保存留下的 `saved = true` 如果不在
         *     同一帧里清掉，弹窗下一次打开会立刻把自己关掉
         *     （见 [HabitEditViewModel.startCreate]）
         *  2. 于是它能被纯 JVM 单测直接钉死，不依赖协程调度器的行为
         *
         * 保留的只有"打卡点已经读过没有"这两项（避免弹窗一开就闪一下空列表）。
         */
        fun freshCreateState(
            previous: HabitEditUiState,
            allowDuringLock: Boolean
        ): HabitEditUiState = HabitEditUiState(
            availableSpots = previous.availableSpots,
            spotsLoaded = previous.spotsLoaded,
            loadedOnce = true,
            allowDuringLock = allowDuringLock
        )
    }
}

/** 习惯新增 / 编辑 ViewModel（PRD §4.1 T2.6）。 */
class HabitEditViewModel(
    private val repo: HabitRepository,
    private val spotRepo: SpotRepository,
    /**
     * 锁机配置按习惯作用域读写。
     *
     * v2.0 用户要求"把锁机功能和习惯功能结合，删除单独锁机" ——
     * v2.1 起"惩罚 = 锁机"的配置也落在这一页，但**只在点保存时**落库。
     */
    private val lockRepo: LockRuleRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HabitEditUiState())
    val uiState: StateFlow<HabitEditUiState> = _uiState.asStateFlow()

    /**
     * 载入打卡点列表（新建时也要载，否则无法选）与这条习惯的锁机配置。
     *
     * @param allowDuringLock 见 [HabitEditUiState.allowDuringLock]：
     *        「添加」页那条路径传 `true`，锁机规则「详细设置」/「习惯管理」不传。
     */
    fun load(habitId: Long, allowDuringLock: Boolean = false) {
        viewModelScope.launch {
            val spots = spotRepo.getAllSpots()
            _uiState.value = _uiState.value.copy(
                availableSpots = spots,
                spotsLoaded = true,
                // 新建（habitId == 0）时下面那个 return 会跳过整份替换，
                // 所以这个标志必须在这里就置上，否则预填永远等不到触发条件
                loadedOnce = true,
                allowDuringLock = allowDuringLock
            )

            if (habitId == 0L) return@launch
            val habit = repo.getHabit(habitId) ?: return@launch
            val rule = runCatching { lockRepo.getRuleForHabit(habitId) }.getOrNull()
            _uiState.value = HabitEditUiState(
                id = habit.id,
                name = habit.name,
                type = habit.type,
                frequency = habit.frequency,
                remindTime = habit.remindTimes.firstOrNull(),
                spotId = habit.spotId,
                // 按习惯的打卡条件回显（派生属性：LOCATION 且选了点才算"指定地点"）
                checkInCondition = habit.checkInCondition,
                availableSpots = spots,
                spotsLoaded = true,
                loadedOnce = true,
                archived = habit.archived,
                createdAt = habit.createdAt,
                punishment = if (rule != null) HabitPunishment.LOCK else HabitPunishment.NONE,
                // 老规则可能没时长（0）—— 原样带出来，让界面提示用户补一个
                lockDurationMinutes = rule?.lockDurationMinutes
                    ?: HabitEditUiState.DEFAULT_LOCK_DURATION_MINUTES,
                checkInWindows = rule?.checkInWindows ?: emptyList(),
                punishmentDirty = false,
                lockEnabled = rule != null,
                lockRule = rule,
                allowDuringLock = allowDuringLock
            )
        }
    }

    /**
     * 把这一页重置成一张**白纸**，供「新建习惯」弹窗每次打开时调用。
     *
     * 与 [load]`(0L)` 的区别：`load(0L)` 只补打卡点、保留其余字段 ——
     * 那是"从打卡点管理页转一圈回来"的语义。而弹窗是**反复开关**的同一个
     * ViewModel 实例（它挂在宿主页面的返回栈条目上），不显式重置的话
     * 用户上次填了一半的名字、频率会原样留着。
     *
     * ⚠️ **重置是同步的**（先 [freshCreateState] 再启动协程补打卡点），
     * 这一点不能省：上一次保存成功会留下 `saved = true`，而弹窗关闭后
     * 它一直留在 ViewModel 里；如果重置挪进协程，弹窗**下一次打开**的第一次
     * 组合就会读到 `saved = true` 并立刻把自己关掉。测试
     * `新的弹窗草稿必须立刻清掉上一次的 saved` 钉住了这条。
     *
     * 默认 `allowDuringLock = true`：这个弹窗只从「添加」页/「习惯管理」页打开，
     * 而用户明确要求"锁机时间不能控制添加界面"（新建习惯在锁机期间完全允许）。
     */
    fun startCreate(allowDuringLock: Boolean = true) {
        _uiState.value = HabitEditUiState.freshCreateState(_uiState.value, allowDuringLock)
        viewModelScope.launch {
            val spots = runCatching { spotRepo.getAllSpots() }
                .getOrDefault(_uiState.value.availableSpots)
            // 用 copy 而不是整份替换：打卡点是异步读回来的，这期间用户可能
            // 已经敲了名字，不能被冲掉
            _uiState.value = _uiState.value.copy(availableSpots = spots, spotsLoaded = true)
        }
    }

    /** 供从打卡点管理页返回时刷新。 */
    fun reloadSpots() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(availableSpots = spotRepo.getAllSpots())
        }
    }

    /** 供从锁机"详细设置"返回时刷新摘要 —— 否则摘要会停在旧配置。 */
    fun refreshLock() {
        val id = _uiState.value.id
        if (id == 0L) return
        viewModelScope.launch { reloadLock(id) }
    }

    /**
     * 重读这条习惯的锁机配置。
     *
     * 惩罚区块有未保存的改动时（[HabitEditUiState.punishmentDirty]）**只刷新摘要** ——
     * 用户可能刚在习惯页改完时长就跑去"打卡点"页转了一圈，
     * 回来把他改的东西冲掉比显示一份旧摘要糟糕得多。
     */
    private suspend fun reloadLock(habitId: Long) {
        val rule = runCatching { lockRepo.getRuleForHabit(habitId) }.getOrNull()
        val current = _uiState.value
        _uiState.value = if (current.punishmentDirty) {
            current.copy(lockEnabled = rule != null, lockRule = rule)
        } else {
            current.copy(
                lockEnabled = rule != null,
                lockRule = rule,
                punishment = if (rule != null) HabitPunishment.LOCK else HabitPunishment.NONE,
                lockDurationMinutes = rule?.lockDurationMinutes ?: current.lockDurationMinutes,
                checkInWindows = rule?.checkInWindows ?: current.checkInWindows
            )
        }
    }

    fun setName(value: String) {
        _uiState.value = _uiState.value.copy(name = value, error = null)
    }

    /**
     * 预填名称 —— 供「添加」页的弹窗把用户输入的昵称带进来。
     *
     * ## 为什么不是构造参数 / 导航参数
     *
     * 这一页同时承担"新建"和"编辑"两种用途，而且**编辑时必须以库里的值为准**。
     * 如果把昵称做成构造参数，就必须在每个调用点判断"这次到底该不该用它"，
     * 漏判一次就会出现"点开已有习惯，名字被弹窗里输的昵称覆盖"这种数据事故。
     *
     * 所以做成**只在名称为空时才生效**的预填：
     *  - 新建习惯（[HabitEditUiState.id] 为 0 且名称为空）→ 用昵称填上
     *  - 编辑已有习惯 → 名称为非空，这个调用是空操作，库里的值原样保留
     *
     * 名称被用户自己清空后再调它是不会发生的（那是用户主动清空，
     * 界面不会重复触发预填），所以这条规则在实践中足够安全。
     */
    fun prefillName(value: String) {
        if (value.isBlank()) return
        if (_uiState.value.name.isNotBlank()) return
        _uiState.value = _uiState.value.copy(name = value, error = null)
    }

    /**
     * 设置习惯类型。
     *
     * ⚠️ 界面已经不再直接调它（v2.2 改由「打卡条件」二选一驱动，
     * 见 [setCheckInCondition]）—— 但这条入口**保留**：
     * 它被既有回归测试锁定，而且"从 LOCATION 切走必须清掉 spotId"
     * 这条不变量仍然成立。这里同步把 [HabitEditUiState.checkInCondition]
     * 一起改掉，避免两份状态说法不一致。
     */
    fun setType(value: HabitType) {
        val condition = if (value == HabitType.LOCATION) {
            CheckInCondition.LOCATION
        } else {
            CheckInCondition.NONE
        }
        // 从"到点打卡"切走时清掉打卡点，避免残留不一致状态
        val next = _uiState.value.copy(type = value, checkInCondition = condition, error = null)
        _uiState.value = if (value == HabitType.LOCATION) next else next.copy(spotId = null)
    }

    /**
     * 选择**打卡条件**（无 / 指定地点）。
     *
     * 用户要求（v2.2）：打卡条件只有「指定地点」和「无」两个取值，
     * 而且"解锁方法就是打卡方法" —— 所以这个选择同时决定了习惯落库的
     * `type` 与 `spotId`：
     *  - 无 → `SIMPLE` + `spotId = null`：点一下就算，室内也能用
     *  - 指定地点 → `LOCATION` + 用户选的打卡点：必须到点才能打卡
     *
     * 切到「无」时**顺手清掉 spotId**，否则库里会留下一条
     * "类型是简单打卡、却挂着一个打卡点"的怪习惯。
     */
    fun setCheckInCondition(value: CheckInCondition) {
        val state = _uiState.value
        _uiState.value = when (value) {
            CheckInCondition.NONE -> state.copy(
                checkInCondition = CheckInCondition.NONE,
                type = HabitType.SIMPLE,
                spotId = null,
                error = null
            )

            // 保留已经选好的打卡点（用户可能只是在两个选项之间来回看）
            CheckInCondition.LOCATION -> state.copy(
                checkInCondition = CheckInCondition.LOCATION,
                type = HabitType.LOCATION,
                error = null
            )
        }
    }

    fun setFrequency(value: HabitFrequency) {
        _uiState.value = _uiState.value.copy(frequency = value, error = null)
    }

    /**
     * 切换「指定每月几号」里的一个日期（v2.13）。
     *
     * 与 [toggleSpecificDay] **逐行同构** —— 两者都只是"计划日集合"的一次增删，
     * 差别只在元素的类型（`DayOfWeek` / `Int`）。刻意保持同样的写法，
     * 以后改其中一处时另一处一眼就能对照上。
     *
     * 越界的 `day`（≤0 或 ≥32）直接忽略：界面上的日期芯片只提供 1..31，
     * 越界只可能是代码传错。这里挡一道，是因为领域层的
     * [HabitFrequency.SpecificMonthDays] 构造时会 `require` ——
     * 一次误传会让整个编辑页崩掉，而"忽略一次点击"用户根本察觉不到。
     */
    fun toggleMonthDay(day: Int) {
        if (day !in HabitFrequency.SpecificMonthDays.MIN_DAY..HabitFrequency.SpecificMonthDays.MAX_DAY) {
            return
        }
        val current = (_uiState.value.frequency as? HabitFrequency.SpecificMonthDays)
            ?.days
            ?: emptySet()
        val next = if (day in current) current - day else current + day
        _uiState.value = _uiState.value.copy(
            frequency = HabitFrequency.SpecificMonthDays(next),
            error = null
        )
    }

    fun toggleSpecificDay(day: DayOfWeek) {
        val current = (_uiState.value.frequency as? HabitFrequency.SpecificDays)?.days ?: emptySet()
        val next = if (day in current) current - day else current + day
        _uiState.value = _uiState.value.copy(frequency = HabitFrequency.SpecificDays(next), error = null)
    }

    fun setRemindTime(value: LocalTime?) {
        _uiState.value = _uiState.value.copy(remindTime = value)
    }

    fun setSpotId(value: Long?) {
        _uiState.value = _uiState.value.copy(spotId = value, error = null)
    }

    // ------------------------------------------------------------ 惩罚（v2.1）
    //
    // 这一组全部是**纯内存操作**：用户要求"点保存时一并落库，
    // 不要每改一下就写库，避免锁机守卫把中间态拦下"。
    // 落库在 [save] 里统一做，并且**必须 runCatching**（仓库层会抛 RuleLockedException）。

    /**
     * 选择惩罚方式（无 / 锁机）。
     *
     * 新建习惯（[HabitEditUiState.id] 为 0）时选择"锁机"会被忽略 ——
     * 配置挂不到 habitId 上，界面本来也没给控件，这里再兜一层。
     */
    fun setPunishment(value: HabitPunishment) {
        val state = _uiState.value
        if (value == HabitPunishment.LOCK && state.id == 0L) return
        _uiState.value = state.copy(
            punishment = value,
            punishmentDirty = true,
            error = null
        )
    }

    /** 设置锁机时长（分钟），上限 24 小时。只改内存，保存时落库。 */
    fun setLockDurationMinutes(minutes: Int) {
        _uiState.value = _uiState.value.copy(
            lockDurationMinutes = minutes.coerceIn(0, LockRule.MAX_LOCK_DURATION_MINUTES),
            punishmentDirty = true,
            error = null
        )
    }

    /**
     * 追加一个"必须打卡"的时间段。
     *
     * 参数是**当天第几分钟**（[TimeWindow] 的字段语义）。
     *
     * ⚠️ 起止相同必须显式拦下：`TimeWindow(540, 540)` 的 [TimeWindow.durationMinutes]
     * 是 **1440 而不是 0**（起止相同被判成跨零点＝全天），
     * 只看 `durationMinutes <= 0` 会让"09:00–09:00"静悄悄变成"全天都得打卡"。
     *
     * 校验与转换都走 [CheckInWindowRules] —— 界面上那两行"开始时间 / 结束时间"
     * 用的是**同一个**纯函数，所以"界面上说不行、这里却存进去了"不可能发生。
     */
    fun addCheckInWindow(startMinute: Int, endMinute: Int) {
        CheckInWindowRules.problem(startMinute, endMinute)?.let { reason ->
            _uiState.value = _uiState.value.copy(error = reason)
            return
        }
        // 结束取 00:00 表示 24:00；结束早于开始是合法的跨零点窗口
        val window = CheckInWindowRules.toWindow(startMinute, endMinute)
        if (window == null) {
            _uiState.value = _uiState.value.copy(error = "打卡时间不合法")
            return
        }
        _uiState.value = _uiState.value.copy(
            checkInWindows = (_uiState.value.checkInWindows + window).distinct(),
            punishmentDirty = true,
            error = null
        )
    }

    fun removeCheckInWindow(index: Int) {
        val list = _uiState.value.checkInWindows.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _uiState.value = _uiState.value.copy(
            checkInWindows = list,
            punishmentDirty = true,
            error = null
        )
    }

    // -------------------------------------------------- 旧版"开关即落库"入口
    //
    // v2.0 的行为：切一下开关就立刻写库/删库。v2.1 界面已经改用 [setPunishment]
    // （改动留到保存时），但这个方法**保留原语义**：它被既有回归测试锁定，
    // 而"开关打开必须留下一条挂在 habitId 上的配置"这条不变量仍然成立。

    /**
     * 打开/关闭这条习惯的锁机，**立即落库**。
     *
     * ⚠️ 仓库层在锁机期间会抛 RuleLockedException（"锁机时关掉锁机＝直接解锁"，
     * 产品上的硬约束）。viewModelScope 里的未捕获异常会直接让 App 崩溃，
     * 而"用户锁机时点了一下开关"完全可预期 —— 所以这里**必须 runCatching**。
     */
    fun setLockEnabled(enabled: Boolean) {
        val id = _uiState.value.id
        if (id == 0L) {
            // 习惯还没保存，没有 habitId 可挂 —— 与界面上的提示一致，静默忽略
            return
        }
        viewModelScope.launch {
            runCatching {
                if (enabled) {
                    if (lockRepo.getRuleForHabit(id) == null) {
                        lockRepo.saveForHabit(id, defaultLockRule(_uiState.value.name))
                    }
                } else {
                    lockRepo.deleteForHabit(id)
                }
            }.onSuccess {
                reloadLock(id)
                _uiState.value = _uiState.value.copy(error = null)
            }.onFailure {
                // 失败时把界面拉回真实状态，避免开关显示成用户以为的样子
                reloadLock(id)
                _uiState.value = _uiState.value.copy(error = it.message ?: "锁机设置失败")
            }
        }
    }

    // ------------------------------------------------------- 归档与删除
    //
    // 放在编辑页是因为用户反馈："在今日习惯的编辑界面里添加删除和归档功能" ——
    // 原先要删一个习惯得先回「设置 → 习惯管理」，路径太绕。

    /** 归档：从今日列表隐藏，数据保留，之后可在习惯管理页恢复。 */
    fun archive() {
        val id = _uiState.value.id
        if (id == 0L) return
        viewModelScope.launch {
            runCatching { repo.archive(id) }
                .onSuccess { _uiState.value = _uiState.value.copy(saved = true, error = null) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "归档失败") }
        }
    }

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
     * 不可恢复，会连同打卡记录一起清掉。
     * 删习惯之前**先删它的锁机配置**，否则会留下一条挂在不存在的 habitId 上的
     * 孤儿规则 —— 它仍然会被调度器当成启用规则去拦截用户。
     */
    fun confirmDelete() {
        val id = _uiState.value.id
        if (id == 0L) return
        // 「添加」页进来的编辑才放行锁机守卫 —— 但仍然只在"这条习惯的锁
        // 此刻没有在生效"时才真的放行（见 LockRuleRepository.ensureRuleEditable）
        val allowDuringLock = _uiState.value.allowDuringLock
        viewModelScope.launch {
            // 同样是 runCatching：锁机期间 deleteForHabit 会抛 RuleLockedException
            val lockResult = runCatching {
                lockRepo.deleteForHabit(id, allowDuringLock = allowDuringLock)
            }
            if (lockResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    pendingDelete = false,
                    error = lockResult.exceptionOrNull()?.message ?: "删除锁机配置失败"
                )
                return@launch
            }
            runCatching { repo.deletePermanently(id) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        pendingDelete = false,
                        saved = true,
                        punishment = HabitPunishment.NONE,
                        lockEnabled = false,
                        lockRule = null
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

    /**
     * 保存习惯，并把「惩罚」区块的改动**一并落库**。
     *
     * 顺序是有讲究的（用户要求"先保存习惯拿到 id，再存锁机配置"）：
     *
     *  1. **先存习惯**。[HabitRepository.save] 在新建时返回自增 id，
     *     而锁机配置必须挂在 habitId 上 —— 没有 id 就没有地方挂。
     *  2. **再落惩罚**。仓库层在锁机期间会抛 `RuleLockedException`，
     *     而 viewModelScope 里的未捕获异常会直接终止进程 —— **必须 runCatching**。
     *     失败时习惯其实已经存好了：提示必须把这个区别讲清楚，
     *     并且把 id 记进 state，用户再点一次保存是"更新习惯"而不是又建一条。
     */
    fun save() {
        val state = _uiState.value
        val problem = when {
            state.name.isBlank() -> "请填写习惯名称"
            state.needsSpotSetup -> "还没有打卡点，请先去「设置 → 打卡点」创建"
            state.checkInCondition == CheckInCondition.LOCATION && state.spotId == null ->
                "打卡条件选了「指定地点」，请选择打卡点"
            else -> state.punishmentProblem
        }
        if (problem != null) {
            _uiState.value = state.copy(error = problem)
            return
        }
        viewModelScope.launch {
            _uiState.value = state.copy(saving = true, error = null)

            // ---- 1) 习惯本身 ----
            val habitId = runCatching { repo.save(state.toHabit()) }.getOrElse { failure ->
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    error = failure.message ?: "保存失败"
                )
                return@launch
            }

            // ---- 2) 惩罚配置 ----
            //
            // allowDuringLock：只有从「添加」页进来的编辑才放行锁机守卫，
            // 而且放行的前提是"这条习惯的锁此刻没在生效"（仓库层判定）。
            // 用户要求"锁机时间不能控制添加界面"，同时**不能**允许
            // 锁机时把正在生效的那条规则改掉/删掉。
            val allowDuringLock = state.allowDuringLock
            val punishmentResult = runCatching {
                when {
                    state.punishment == HabitPunishment.LOCK ->
                        lockRepo.saveForHabit(
                            habitId,
                            state.toRule(habitId),
                            allowDuringLock = allowDuringLock
                        )

                    // 惩罚=无：删掉配置。**没有配置时不必调用** ——
                    // 仓库层会先走锁机守卫，无谓的调用只会在锁机期间制造一条假报错
                    state.lockRule != null ->
                        lockRepo.deleteForHabit(habitId, allowDuringLock = allowDuringLock)

                    else -> Unit
                }
            }
            val savedRule = runCatching { lockRepo.getRuleForHabit(habitId) }.getOrNull()

            if (punishmentResult.isFailure) {
                _uiState.value = _uiState.value.copy(
                    id = habitId,
                    saving = false,
                    saved = false,
                    lockEnabled = savedRule != null,
                    lockRule = savedRule,
                    punishmentDirty = false,
                    error = "习惯已保存，但惩罚设置没改成功：" +
                        (punishmentResult.exceptionOrNull()?.message ?: "未知原因")
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                id = habitId,
                saving = false,
                saved = true,
                lockEnabled = savedRule != null,
                lockRule = savedRule,
                punishment = if (savedRule != null) {
                    HabitPunishment.LOCK
                } else {
                    HabitPunishment.NONE
                },
                lockDurationMinutes = savedRule?.lockDurationMinutes
                    ?: _uiState.value.lockDurationMinutes,
                checkInWindows = savedRule?.checkInWindows ?: _uiState.value.checkInWindows,
                punishmentDirty = false,
                error = null
            )
        }
    }

    companion object {
        /**
         * 旧式"开关即落库"路径落下的默认锁机配置。
         *
         * v2.1 起默认形态就是新式的 **09:00–10:00 必须打卡、超过打卡时间后锁 2 小时**：
         * 锁机起点由打卡截止推导，天然满足"锁机晚于打卡截止"这条硬约束，
         * 用户打开详细设置就能直接保存，不用先跟一句报错搏斗。
         */
        fun defaultLockRule(habitName: String): LockRule = LockRule(
            name = habitName.trim().ifBlank { HabitEditUiState.DEFAULT_RULE_NAME },
            enabled = true,
            checkInWindows = listOf(TimeWindow(9 * 60, 10 * 60)),
            lockDurationMinutes = HabitEditUiState.DEFAULT_LOCK_DURATION_MINUTES,
            unlockMode = UnlockMode.TIME_ONLY
        )
    }
}
