package com.zilv.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilv.app.data.repository.DayNote
import com.zilv.app.data.repository.DayNoteRepository
import com.zilv.app.data.repository.HabitRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.habit.NowNextPicker
import com.zilv.app.domain.lock.InstantLock
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.today.AgendaItem
import com.zilv.app.domain.today.TodayAgenda
import com.zilv.app.domain.today.TodayProgress
import com.zilv.app.domain.today.TodayProgressRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** 首页状态卡片的四种形态（PRD §4.2）。 */
enum class LockCardState { IDLE, WARN, LOCKED, UNLOCKED }

data class TodayUiState(
    val today: LocalDate = LocalDate.now(),
    /**
     * 今天页的行：**今天需要打卡的习惯 + 今天生效的规划**，按时间从早到晚（v2.5）。
     *
     * 用户要求"该界面显示当天的习惯和昨天做好的规划……以时间从早到晚排序"。
     * 构建规则全在纯函数 [TodayAgenda.build] 里（那里有单测）。
     */
    val agenda: List<AgendaItem> = emptyList(),
    val cardState: LockCardState = LockCardState.IDLE,
    val cardTitle: String = "",
    val cardMessage: String = "",
    /** 锁机中且可打卡时，给出可直接跳转的打卡页参数 */
    val checkInRuleId: Long? = null,
    val checkInSpotId: Long? = null,
    /**
     * 这次打卡挂在哪条习惯上（v2.2）。
     *
     * 有了它，室内定位失败时打卡页才能给「改用无条件打卡」这个快捷入口
     * （要改的就是这条习惯的打卡条件）。`null` 表示这条规则没挂习惯。
     */
    val checkInHabitId: Long? = null,
    /** 一次性提示（例如"该习惯需要到打卡点打卡"），由界面消费后清空 */
    val notice: String? = null,
    /**
     * 今日列表上方的聚焦卡片：「现在该做 / 接下来」（v2.3）。
     *
     * 由纯函数 [NowNextPicker.pick] 选出，规则与边界都写在那里并有单测覆盖；
     * 这里只是把它的结果搬到状态里。`null` 表示**没有未打卡的习惯** ——
     * 界面这时显示「所有任务已完成」绿底横幅。
     *
     * v2.5 起候选就是 [agenda] 里的习惯那一份（与列表同一个来源），
     * 所以不会再出现"列表里有、卡片却挑不到"的口径错位。
     */
    val nowNext: NowNextPicker.NowNext? = null,
    /** 今天已经写下的留言（`null` 表示还没写）。 */
    val dayNote: DayNote? = null,
    /**
     * 上次算进展时"此刻是当天的第几分钟"（`hour * 60 + minute`）。
     *
     * 单独存成一个字段而不是在 [progress] 里现取时钟，是为了让状态**自洽**：
     * 「规划结束了吗」是时间的函数，而这个时间必须与 [agenda]（哪一天的列表）
     * 取自同一个时刻，否则跨过 0 点就会出现"列表是新的、分钟数是旧的"。
     * 界面每次回到首页（[TodayViewModel.refresh]）刷新它。
     */
    val nowMinuteOfDay: Int = 0,
    val loading: Boolean = true
) {
    /** 今天页上的**习惯**条目 —— 打卡相关的计数只看它们。 */
    val items: List<TodayHabit>
        get() = agenda.filterIsInstance<AgendaItem.HabitTask>().map { it.entry }

    /** 今天生效的规划（不可打卡，只展示）。 */
    val plans: List<com.zilv.app.domain.plan.Plan>
        get() = agenda.filterIsInstance<AgendaItem.PlanTask>().map { it.plan }

    /** 今天生效的规划条数（不可打卡，只展示）。 */
    val planCount: Int get() = agenda.count { it is AgendaItem.PlanTask }

    val checkedCount: Int get() = items.count { it.checkedToday }
    val totalCount: Int get() = items.size

    val canCheckIn: Boolean get() = checkInRuleId != null && checkInSpotId != null

    /**
     * 今天有没有进展 ——「有习惯完成 **或** 规划结束」（v2.12）。
     *
     * 判定本身是纯函数 [TodayProgressRules.evaluate]，这里只是把
     * 列表里的两份数据（[items] / [plans]）喂给它。
     */
    val progress: TodayProgress
        get() = TodayProgressRules.evaluate(
            habits = items,
            plans = plans,
            date = today,
            nowMinuteOfDay = nowMinuteOfDay
        )

    /**
     * 今天是不是"全部打卡完成"。
     *
     * ⚠️ **必须至少有一条习惯** —— 一条习惯都没有时 `all {}` 对空集合恒为 true，
     * 那会让一个刚装好、什么都没配的 App 首页挂着「所有任务已完成」。
     * 这条不变式现在由 [TodayProgress.allHabitsChecked] 持有（那里有单测）。
     *
     * ⚠️ 判定**只看习惯**：规划不可打卡（见 [AgendaItem.PlanTask]），
     * 把它算进来会让"全部完成"永远不成立。
     */
    val allDone: Boolean get() = progress.allHabitsChecked

    /**
     * 留言入口该不该在（v2.12，用户要求）。
     *
     * 用户原话：「留言条件改为『有习惯完成 或 规划结束』」——
     * 所以判据是 [TodayProgress.hasProgress]，**不再是** [allDone]。
     * 另外：**已经写过的留言**也要一直看得见（否则用户写完之后
     * 一旦归档掉一条习惯就再也点不进那个输入框了）。
     */
    val canLeaveNote: Boolean get() = progress.hasProgress || dayNote != null
}

/**
 * 首页 ViewModel。
 *
 * 状态卡片由 [LockRuleRepository.currentStatus] 驱动，后者内部走
 * [com.zilv.app.domain.lock.WindowScheduler]（已被 44 个单元测试覆盖）。
 *
 * v2.5：用户要求首页变成"只显示和打卡、不可编辑"的界面 ——
 * 列表内容 = 今天需要打卡的习惯 + 今天生效的规划，按时间从早到晚。
 * 那条规则全在纯函数 [TodayAgenda.build] 里（见 `TodayAgendaTest`）。
 */
class TodayViewModel(
    private val habitRepo: HabitRepository,
    private val lockRepo: LockRuleRepository,
    private val dayNoteRepo: DayNoteRepository,
    /**
     * 「明日规划」的数据入口（v2.5）。
     *
     * 首页要显示**今天生效**的规划（它们是昨天做好的），所以只读今天那一份。
     */
    private val planRepo: PlanRepository,
    /**
     * 锁机状态变化后的通知钩子（v2.12，即时锁机要用）。
     *
     * **为什么必需**：即时锁机是"现在就开始锁"，而且它落库之后**必须**让
     * 前台服务立刻重算并挂上「🔒 锁机中」的通知 —— 否则用户点了按钮、
     * 界面看着像没反应（通知没出现），而无障碍服务那边要等到下次切换应用
     * 才会重新问一次状态。
     *
     * 用回调而不是直接依赖 Service，与 `CheckInViewModel.onLockStateChanged`
     * 是同一个手法：让本 ViewModel 保持可单元测试（默认值什么都不做，
     * 既有 39 条测试因此一行都不用改）。
     */
    private val onLockStateChanged: () -> Unit = {}
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    /**
     * 供 [NowNextPicker] 使用的"现在几点"。
     *
     * 从仓库的时钟取而不是直接 `LocalTime.now()` —— 单测把时钟固定在
     * 2026-10-14 16:00，聚焦卡片选出来的习惯也因此是确定的。
     */
    private val now: LocalTime get() = LocalTime.now(habitRepo.clock)

    /**
     * 此刻是当天的第几分钟。
     *
     * 与 [now] 同一口时钟 —— 「规划结束了吗」也是时间的函数，
     * 用另一个时钟就会在单测里得到不确定的结果。
     */
    private fun nowMinuteOfDay(): Int = now.let { it.hour * 60 + it.minute }

    init {
        observeTodayList()
        observeDayNote()
        refreshLockStatus()
    }

    /**
     * 今日列表走 **Flow 观察**，而不是"进页面手动刷一次"。
     *
     * 这是我修掉的一个真实 bug：原先只在 `init` 里调一次 `refresh()`，
     * 而从「新建习惯」返回时 ViewModel 被导航栈保留、`init` 不再执行，
     * 结果新习惯永远不出现在首页。
     *
     * 改成观察之后，插库即刷新，这个 bug 类别**在结构上不可能再出现**。
     *
     * 同时观察三张表：
     *  - `habits`：新增/编辑/归档习惯
     *  - `check_ins`：勾选打卡会改变 `checkedToday`，也会改变"本月/本周额度"
     *  - `plans`：今天生效的规划（用户要求首页显示"昨天做好的规划"）
     *
     * v2.5 起"今天需不需要打卡"不再只是 `checkedToday`：
     * `HabitFrequency.needsCheckInOn` 还要看"今天是不是计划日"
     * （v2.14 删掉「每周 N 次」之后不再有"本周额度用完没有"这一支），
     * 所以要顺带取一次已打卡日期集合。
     */
    private fun observeTodayList() {
        viewModelScope.launch {
            combine(
                habitRepo.observeActiveHabits(),
                habitRepo.observeDailyCheckInCounts(),
                // observeAll 而不是 observeForDay(今天)：日期要在**收集时**算。
                // init 阶段算的话跨过 0 点就永远停在昨天了；这里在 collect 里
                // 按当时的 today 过滤，口径与列表同源。
                planRepo.observeAll()
            ) { habits, _, plans -> habits to plans }
                .collect { (habits, allPlans) ->
                    val today = habitRepo.today()
                    val entries = habitRepo.toTodayHabits(habits, today)

                    // "今天需不需要打卡"要看本周期额度有没有用完，
                    // 所以按习惯取一次已打卡日期集合（同一天多次只算一天）。
                    val checkedDatesByHabit = entries.associate { entry ->
                        entry.habit.id to habitRepo.effectiveCheckedDates(entry.habit)
                    }

                    val agenda = TodayAgenda.build(
                        habits = entries,
                        plans = allPlans.filter { it.date == today },
                        date = today,
                        checkedDatesByHabit = checkedDatesByHabit
                    )
                    val habitEntries = agenda.filterIsInstance<AgendaItem.HabitTask>()

                    _uiState.value = _uiState.value.copy(
                        today = today,
                        agenda = agenda,
                        // 「规划结束了吗」用的分钟数必须和列表是同一次取的时刻
                        nowMinuteOfDay = nowMinuteOfDay(),
                        // 聚焦卡片与列表**同一个来源**：候选就是 agenda 里的习惯那一份。
                        // 分开算的话早晚会出现"列表已经打上了勾、卡片还指着它"的错位
                        nowNext = NowNextPicker.pick(
                            items = habitEntries.map { it.entry },
                            checkedHabitIds = habitEntries
                                .filter { it.entry.checkedToday }
                                .mapTo(HashSet()) { it.entry.habit.id },
                            now = now
                        ),
                        loading = false
                    )
                }
        }
    }

    /**
     * 观察今天的留言。
     *
     * 用 Flow 而不是"进页面查一次"：用户写完留言横幅要立刻显示摘要，
     * 而写操作发生在**本页内部**，观察能省掉一次手动刷新。
     */
    private fun observeDayNote() {
        viewModelScope.launch {
            dayNoteRepo.observe(habitRepo.today()).collect { note ->
                _uiState.value = _uiState.value.copy(dayNote = note)
            }
        }
    }

    /** 界面消费完提示后清空，避免重复弹。 */
    fun consumeNotice() {
        if (_uiState.value.notice != null) _uiState.value = _uiState.value.copy(notice = null)
    }

    // ------------------------------------------------------------ 即时锁机（v2.12）

    /**
     * **立刻锁机**，持续 [minutes] 分钟（0 = 到下一个整分钟解除）。
     *
     * 用户要求：「添加即时锁机功能，锁机可选时间从 0 分到 12 小时。
     * 入口放在今天界面的最上方。」
     *
     * ## 它一个字节的拦截逻辑都没新写
     *
     * 只做两件事：
     *  1. 往 `lock_rules` 里插一条今天生效的规则
     *     （由 `LockRuleRepository.startInstantLock` 负责，规则构造在纯函数
     *     [com.zilv.app.domain.lock.InstantLock] 里，有单测）
     *  2. 通知前台服务重算 —— 于是「🔒 锁机中 · 即时锁机」的通知立刻挂上，
     *     无障碍服务下次问 `currentStatus()` 时就会开始拦
     *
     * 解锁靠**到点自动解除**（规则上 `allowCheckInUnlock = false`）：
     * 窗口结束的那一刻由 `AlarmScheduler` 与锁机期间的每分钟 ticker 一起收尾。
     *
     * ⚠️ 仓库层会抛（库坏了、规则不合法），所以必须 `runCatching` ——
     * 未捕获的协程异常会终止 App 进程，这个坑本项目踩过两次。
     */
    fun startInstantLock(minutes: Int) {
        viewModelScope.launch {
            val clamped = InstantLock.clampMinutes(minutes)
            runCatching { lockRepo.startInstantLock(clamped) }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        notice = "已开始即时锁机：${InstantLock.label(clamped)}"
                    )
                    // 立刻让前台服务重算并挂上锁机通知
                    onLockStateChanged()
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        notice = "即时锁机失败：${it.message ?: "未知原因"}"
                    )
                }
            // 无论成没成，都用库里的真相重算一次状态卡片
            refreshLockStatus()
        }
    }

    /**
     * 刷新首页的时间相关状态（列表由 Flow 自动维护，不在这里刷）。
     *
     * 「规划结束了吗」是时间的函数，Flow 观察不到"时间流逝" ——
     * 所以每次回到首页都要把 [TodayUiState.nowMinuteOfDay] 拨到当下，
     * 否则一条 21:00 结束的规划在 21:30 也不会给出留言入口。
     */
    fun refresh() {
        _uiState.value = _uiState.value.copy(nowMinuteOfDay = nowMinuteOfDay())
        refreshLockStatus()
    }

    private fun refreshLockStatus() {
        viewModelScope.launch {
            val status = lockRepo.currentStatus()

            val (state, title, message) = when (status.phase) {
                LockPhase.LOCKED -> Triple(
                    LockCardState.LOCKED,
                    "🔒 锁机中  ·  ${status.rule?.name.orEmpty()}",
                    buildString {
                        append("还有 ${status.minutesUntilChange} 分钟")
                        val msg = status.rule?.message.orEmpty()
                        if (msg.isNotBlank()) append("\n「$msg」")
                    }
                )

                LockPhase.WARN -> Triple(
                    LockCardState.WARN,
                    "⏳ 即将锁机  ·  ${status.rule?.name.orEmpty()}",
                    "还有 ${status.minutesUntilChange} 分钟开始"
                )

                // v2.0：打卡窗口内还没打卡 —— 此时**尚未锁机**，只是提醒该打了。
                // 这是"在打卡时间未能打卡才进行锁机"在界面上的体现。
                LockPhase.CHECKIN_DUE -> Triple(
                    LockCardState.WARN,
                    "📌 该打卡了  ·  ${status.rule?.name.orEmpty()}",
                    buildString {
                        append("打卡时间还剩 ${status.minutesUntilChange} 分钟")
                        append("\n没打上的话到点会锁机")
                    }
                )

                LockPhase.UNLOCKED -> Triple(
                    LockCardState.UNLOCKED,
                    "✅ 已解锁  ·  ${status.rule?.name.orEmpty()}",
                    "本窗口不再拦截"
                )

                LockPhase.IDLE -> {
                    val next = status.instance
                    if (next == null) {
                        // v2.8：入口改到「添加」页。文案同步更新 ——
                        // v2.0 时锁机配置在「习惯 → 锁机」里，v2.1 后叫「惩罚」，
                        // v2.6 起「添加」页才是统一的新建入口，旧文案会把人指错地方。
                        Triple(LockCardState.IDLE, "今日无锁机计划", "去「添加」里点击加号添加")
                    } else {
                        Triple(
                            LockCardState.IDLE,
                            "空闲",
                            "下一个：${next.rule.name}  ${next.start.monthValue}月${next.start.dayOfMonth}日 " +
                                "%02d:%02d".format(next.start.hour, next.start.minute)
                        )
                    }
                }
            }

            // 打卡入口在两种情况下提供：
            //   锁机中（打卡可解锁）
            //   打卡窗口内还没打卡（CHECKIN_DUE，趁没锁上赶紧打）
            //
            // v2.2：解锁方法统一为"打卡（任何打卡方法都可以）或等时间走完"，
            // 已废弃的 `unlockMode` 不再参与判断。锁机卡片上的按钮指向**打卡点打卡**
            // （锁机时最典型的做法）；打卡条件是「无」的习惯在今日列表里
            // 点一下就能打卡，走的是另一条路径。
            //
            // v2.12：**打卡对它无效的规则不给这个按钮**（`allowCheckInUnlock == false`）。
            // 目前有两类这样的规则：即时锁机（用户要求"仅到点解锁"）与「明日规划」
            // （同一条语义，见 `Plan.toLockRule`）。以前这里会给出一个按了没用的
            // 「立即打卡解锁」——那是在骗用户，现在按同一个开关收掉。
            val checkInCanUnlock = status.rule?.allowCheckInUnlock != false
            val needsCheckIn = checkInCanUnlock && (
                status.phase == LockPhase.LOCKED ||
                    status.phase == LockPhase.CHECKIN_DUE
                )

            // 挂在习惯上的规则要把 habitId 一起带过去 —— 室内定位失败时
            // 打卡页靠它给出「改用无条件打卡」的入口。
            val habitId = status.rule
                ?.takeIf { needsCheckIn }
                ?.habitId
                ?.takeIf { it > 0L }

            _uiState.value = _uiState.value.copy(
                cardState = state,
                cardTitle = title,
                cardMessage = message,
                checkInRuleId = status.rule?.takeIf { needsCheckIn }?.id,
                checkInSpotId = status.rule?.takeIf { needsCheckIn }?.spotId,
                checkInHabitId = habitId
            )
        }
    }

    /**
     * 打卡。**单向操作：只写入，不删除。**
     *
     * 用户明确要求："打卡完成后不能取消打卡" ——
     * 之前这里是 `toggleCheckIn`（切换）语义，再点一次会把打卡撤销掉，
     * 于是"今天做完了"这件事可以一不小心被自己抹掉，连续天数也跟着断。
     * 现在已打卡的习惯**直接返回**，既不写入也不删除，只给一句提示让用户
     * 知道点击被收到了（而不是怀疑界面卡了）。
     *
     * 另外两条既有规则不变：
     *  - 「指定地点」的习惯在首页点一下**无效** —— 必须去打卡页走地点校验
     *    （那是防止绕过地点验证的保险丝，界面也会把点击路由到打卡页）
     *  - 列表与聚焦卡片会因 `check_ins` 变化自动更新
     */
    fun checkIn(habitId: Long) {
        viewModelScope.launch {
            val habit = habitRepo.getHabit(habitId) ?: return@launch
            if (habitRepo.requiresLocationProof(habit)) {
                _uiState.value = _uiState.value.copy(notice = "「${habit.name}」需要到打卡点打卡")
                return@launch
            }

            val today = habitRepo.today()
            if (habitRepo.isCheckedOn(habitId, today)) {
                // 已打卡：不做任何取消。给一句提示而不是静默 ——
                // 静默会让用户以为是漏点了，反复戳同一个卡片
                _uiState.value = _uiState.value.copy(notice = "「${habit.name}」今天已经打过卡了")
                return@launch
            }

            habitRepo.checkIn(habitId, status = com.zilv.app.domain.model.CheckInStatus.MANUAL)
        }
    }

    /**
     * 旧名字，保留只是为了让既有调用点/测试继续编译。
     *
     * ⚠️ 语义已从"切换"改成"单向打卡"（见 [checkIn]）—— 名字留着是因为
     * 首页历史上一直这么调，重命名不改变任何行为。**不要**在这里恢复
     * "已打卡就撤销"的逻辑：那正是用户要求去掉的行为。
     */
    fun toggleCheckIn(habitId: Long) = checkIn(habitId)

    // ---------------------------------------------------------------- 留言

    /**
     * 保存今天的留言。
     *
     * 空文本等同于清除（由 [DayNoteRepository.save] 保证），
     * 所以界面上"清除留言"和"删空内容后保存"是同一个结果。
     */
    fun saveDayNote(text: String) {
        viewModelScope.launch {
            val saved = dayNoteRepo.save(habitRepo.today(), text)
            _uiState.value = _uiState.value.copy(
                notice = if (saved) "留言已保存" else "留言已清除"
            )
        }
    }
}
