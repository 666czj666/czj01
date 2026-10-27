package com.zilv.app.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.model.Habit
import com.zilv.app.ui.components.AppHelp
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.components.TimeSettingRow
import com.zilv.app.ui.components.TitleWithHelp
import com.zilv.app.ui.components.WheelTimePickerDialog
import com.zilv.app.ui.habits.HabitCreateDialog
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate

/**
 * 「添加」页（v2.5，本轮按用户要求重排 + 改造加号弹窗）。
 *
 * ## 本轮的用户要求（逐条对上）
 *
 *  - "「添加」页面主体的区块显示顺序改为：明日规划、习惯、使用时间限制"
 *    → `LazyColumn` 里就是这三段（`SECTION` 注释标了 1/2/3）
 *  - "把加号弹窗里的名称输入删掉" → v2.6 那个「请输入昵称」整块删除，
 *    弹窗只剩三个入口按钮
 *  - "弹窗里显示顺序从上到下为：明日规划、习惯、时间限额"
 *    → [ChooserDialog] 里的按钮顺序，与主体区块顺序**刻意一致**
 *  - "把新建习惯界面改为弹窗" → 「习惯」按钮打开 [HabitCreateDialog]
 *    （不再跳整页 `HabitEditScreen`）
 *  - "把'使用时间限额'的添加入口改到「添加」界面的加号弹窗里"
 *    → 弹窗第三项「时间限额」，进入额度管理页；主体里的区块也保留
 *    （用户同时要求主体顺序里带上"使用时间限制"，两者是同一个入口的两种到达方式）
 *
 * ## 「明日规划」为什么还是"弹窗 2 + 整页"
 *
 * 用户原话（v2.5）："点击明日规划就再弹出来一个窗口，新窗口有开始时间和
 * 结束时间的设定" —— 这一步本轮没有改，只是把昵称输入去掉了。
 * 时间设定仍然只负责**预填**，真正的落库在 `PlanEditScreen` 点保存时。
 *
 * ## 这一页不写数据库
 *
 * 新建流程全程只在内存里攒参数，真正落库发生在目标页（或习惯弹窗）点「保存」
 * 的那一刻。否则用户在第二个弹窗里取消就会在库里留下一条空壳数据。
 *
 * ## 锁机期间为什么这一页还能用（本轮第 5 条要求）
 *
 * "锁机时间不能控制添加界面"：新建习惯（弹窗）、新建规划、新建额度都只是
 * **新增**，不会让任何正在生效的锁消失；而"编辑一条习惯"会走到惩罚配置的
 * 保存路径上，那里**只在这条习惯的锁此刻没有生效时**才放行 ——
 * 完整的分界线写在 `LockRuleRepository.ensureRuleEditable` 的 KDoc 上。
 *
 * @param onEditHabit 打开**已有**习惯的编辑页（整页）；新建走 [HabitCreateDialog]
 * @param onEditPlan 打开规划编辑页（`planId = 0` 表示新建），后三个参数是预填的名称与起止
 * @param onOpenQuotas 打开「使用时间限时」管理页
 * @param onManageSpots 弹窗里选了「指定地点」却没有打卡点时跳去创建
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(
    onEditHabit: (habitId: Long) -> Unit,
    onEditPlan: (planId: Long, name: String?, startMinute: Int?, endMinute: Int?) -> Unit,
    onOpenQuotas: () -> Unit = {},
    onManageSpots: () -> Unit = {},
    viewModel: AddViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从习惯/规划编辑页返回时列表由 Flow 自动更新，这一页的额度摘要也顺带
    // 重算一次 —— "生效中 / 已耗尽"是**时间的函数**，Flow 观察不到时间流逝
    // （与 QuotaScreen 的 refreshUsage 同一个坑、同一个处理）。
    LifecycleResumeEffect(Unit) {
        viewModel.refreshQuotaStatuses()
        onPauseOrDispose { }
    }

    // ---------------- 对话框状态 ----------------

    /** 「加号」弹窗是否打开（明日规划 / 习惯 / 时间限额 三项） */
    var chooserOpen by remember { mutableStateOf(false) }
    /** 第二个窗口：时间设定。null 表示没开 */
    var timeWindow by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** 时间窗里的临时起止（滚轮选择中） */
    var draftStart by remember { mutableIntStateOf(AddViewModel.DEFAULT_START_MINUTE) }
    var draftEnd by remember { mutableIntStateOf(AddViewModel.DEFAULT_END_MINUTE) }
    /** 正在用滚轮选哪一个：true = 开始，false = 结束 */
    var pickingStart by remember { mutableStateOf(true) }
    var pickingTime by remember { mutableStateOf(false) }
    /** 「新建习惯」弹窗是否打开 */
    var creatingHabit by remember { mutableStateOf(false) }

    /** 关闭整条新建流程并清空临时输入。 */
    fun resetFlow() {
        chooserOpen = false
        timeWindow = null
        pickingTime = false
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("添加") }) },
        floatingActionButton = {
            // 用户要求："点击加号后屏幕中上方出现弹窗"
            FloatingActionButton(onClick = { chooserOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (state.isEmpty && !state.loading) {
                item { HintCard(EMPTY_HINT, EMPTY_HINT_DETAIL) }
            }

            // =============================================== 区块 1：明日规划
            //
            // 用户要求："「添加」页面主体的区块显示顺序改为：明日规划、习惯、使用时间限制"。
            // 所以规划排在最上面，习惯退到第二，额度垫底。
            item {
                Spacer(Modifier.height(6.dp))
                SectionTitle(
                    "明日规划（${state.planCount}）　${formatDate(state.tomorrow)}",
                    help = PLAN_SECTION_HELP
                )
            }

            if (state.plans.isEmpty() && !state.loading) {
                item { HintCard(EMPTY_PLAN_HINT, EMPTY_PLAN_HINT_DETAIL) }
            }

            items(state.plans, key = { "plan-${it.id}" }) { plan ->
                EditRow(
                    title = plan.name,
                    subtitle = "${hhmm(plan.startMinute)}-${hhmm(plan.endMinute)} · 共 ${plan.durationMinutes} 分钟",
                    onClick = { onEditPlan(plan.id, null, null, null) }
                )
            }

            // =============================================== 区块 2：习惯
            if (state.habits.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(Modifier.height(6.dp))
                    SectionTitle("习惯（${state.habits.size}）　点击可编辑")
                }
                items(state.habits, key = { "habit-${it.id}" }) { habit ->
                    EditRow(
                        title = habit.name,
                        subtitle = habitSubtitle(habit),
                        onClick = { onEditHabit(habit.id) }
                    )
                }
            }

            if (state.archivedHabits.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    SectionTitle("已归档（${state.archivedHabits.size}）")
                }
                items(state.archivedHabits, key = { "archived-${it.id}" }) { habit ->
                    EditRow(
                        title = habit.name + "（已归档）",
                        subtitle = "点进去可以恢复",
                        onClick = { onEditHabit(habit.id) }
                    )
                }
            }

            // =============================================== 区块 3：使用时间限制
            //
            // 用户要求（v2.7）："在添加界面加入软件使用时间限时功能入口，要独立入口"。
            // 本轮又要求"把'使用时间限额'的添加入口改到加号弹窗里"以及
            // "主体区块顺序 …、使用时间限制" —— 于是它既是弹窗第三项，
            // 也仍然是主体里一个与习惯/规划并列的独立区块（同一个跳转目标）。
            item {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                // 用户要求（v2.8）：删掉标题里的「点击进入管理」；
                // 下面那句长说明收成标题旁的「?」，点开才显示（原文案完整保留在 AppHelp.QUOTA 里）
                SectionTitle("使用时间限制（${state.quotaCount}）", help = AppHelp.QUOTA)
            }

            item {
                EditRow(
                    title = if (state.quotaCount == 0) "还没有额度" else "已设 ${state.quotaCount} 个额度",
                    // v2.11（用户第 3 条规则 1）：这里以前写的是
                    // "其中 N 个已启用"，现在改成**只给文字状态**的汇总 ——
                    // 这一页整个区块都不许出现"还剩 / 已用多少分钟"。
                    subtitle = if (state.quotaCount == 0) {
                        "点这里新建第一个：选应用、定时长、选刷新间隔"
                    } else {
                        state.quotaStatusSummary + " · 点这里新建/编辑/删除"
                    },
                    onClick = onOpenQuotas,
                    // 与另外两个区块区分开：习惯/规划没有图标，额度给一个时钟图标
                    leadingIcon = Icons.Filled.Timer
                )
            }

            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    // ================================================== 弹窗 1：三个添加入口
    //
    // 顺序是用户点名的：**明日规划、习惯、时间限额**（从上到下）。
    // v2.6 那个「请输入昵称」输入框按用户要求整块删除 ——
    // 于是这里不再需要 nickname 状态，也不再往目标页传名称。
    if (chooserOpen) {
        ChooserDialog(
            onDismiss = { resetFlow() },
            onPlan = {
                // 用户要求："点击明日规划就再弹出来一个窗口，
                // 新窗口有开始时间和结束时间的设定。"
                draftStart = AddViewModel.DEFAULT_START_MINUTE
                draftEnd = AddViewModel.DEFAULT_END_MINUTE
                chooserOpen = false
                timeWindow = draftStart to draftEnd
            },
            onHabit = {
                chooserOpen = false
                creatingHabit = true
            },
            onQuota = {
                chooserOpen = false
                onOpenQuotas()
            }
        )
    }

    // ================================================== 弹窗 2：规划时间设定
    timeWindow?.let {
        val problem = viewModel.validateTimes(draftStart, draftEnd)
        TopAlignedDialog(onDismiss = { timeWindow = null }) {
            Text("明日规划的时间", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${AddViewModel.DEFAULT_PLAN_NAME}　${formatDate(state.tomorrow)}",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
            Spacer(Modifier.height(12.dp))

            TimeSettingRow(
                label = "开始时间",
                value = hhmm(draftStart),
                onClick = {
                    pickingStart = true
                    pickingTime = true
                }
            )
            Spacer(Modifier.height(8.dp))
            TimeSettingRow(
                label = "结束时间",
                value = hhmm(draftEnd),
                onClick = {
                    pickingStart = false
                    pickingTime = true
                }
            )

            Spacer(Modifier.height(10.dp))
            Text(
                text = "规划不能跨 0 点，起止也不能相同（相同会被当成全天锁机）。",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
            // 越界/跨 0 点在这里就给出可读提示，用户不用等到点保存才知道。
            // `problem` 是由当前起止时间**实时**算出来的，所以每改一次滚轮就自动更新
            if (problem != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { timeWindow = null }) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    // 起止相同 / 跨 0 点时按钮直接不可点（问题原因就在上面那行红字里）
                    enabled = problem == null,
                    onClick = {
                        val start = draftStart
                        val end = draftEnd
                        resetFlow()
                        // 名称不再由弹窗收集（用户要求删掉名称输入），
                        // 所以带一个可改的默认名过去，用户在规划页里改就行
                        onEditPlan(0L, AddViewModel.DEFAULT_PLAN_NAME, start, end)
                    }
                ) { Text("下一步") }
            }
        }
    }

    // ================================================== 弹窗 3：新建习惯
    if (creatingHabit) {
        HabitCreateDialog(
            onDismiss = { creatingHabit = false },
            onManageSpots = onManageSpots
        )
    }

    // ================================================== 滚轮时间选择
    if (pickingTime) {
        WheelTimePickerDialog(
            title = if (pickingStart) "选择开始时间" else "选择结束时间",
            initialMinuteOfDay = if (pickingStart) draftStart else draftEnd,
            onDismiss = { pickingTime = false },
            onConfirm = { minuteOfDay ->
                if (pickingStart) draftStart = minuteOfDay else draftEnd = minuteOfDay
                pickingTime = false
            }
        )
    }
}

/**
 * 加号弹窗：**明日规划 / 习惯 / 时间限额** 三个入口，从上到下就这个顺序
 * （用户点名要求）。
 *
 * 刻意做成一个独立的私有组件而不是写在 `AddScreen` 里：它只负责"点哪个"，
 * 具体动作全部由回调决定，于是弹窗的**顺序**这件事在代码里是一眼可数的三行。
 */
@Composable
private fun ChooserDialog(
    onDismiss: () -> Unit,
    onPlan: () -> Unit,
    onHabit: () -> Unit,
    onQuota: () -> Unit
) {
    TopAlignedDialog(onDismiss = onDismiss) {
        Text("添加", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        // 1) 明日规划：再弹一个窗口设起止时间
        OutlinedButton(onClick = onPlan, modifier = Modifier.fillMaxWidth()) {
            Text("明日规划")
        }

        Spacer(Modifier.height(8.dp))

        // 2) 习惯：弹窗里直接新建（不再跳整页）
        OutlinedButton(onClick = onHabit, modifier = Modifier.fillMaxWidth()) {
            Text("习惯")
        }

        Spacer(Modifier.height(8.dp))

        // 3) 时间限额：进额度管理页（那里有自己的加号）
        OutlinedButton(onClick = onQuota, modifier = Modifier.fillMaxWidth()) {
            Text("时间限额")
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    }
}

/**
 * 靠**屏幕中上方**的弹窗容器。
 *
 * 用户要求"点击加号后屏幕中上方出现弹窗" —— `AlertDialog` 的位置是固定居中的，
 * 改不动，所以这里用 [Dialog] 自己摆内容：
 *  - `usePlatformDefaultWidth = false` 才能自己控制宽度
 *  - 上边距用 `padding(top = 72.dp)`，大约在状态栏 + 标题栏下方，
 *    视觉上就是"屏幕中上方"
 */
@Composable
private fun TopAlignedDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 72.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp)) { content() }
            }
        }
    }
}

/** 一行可点击的"标题 + 副标题 + 箭头"，习惯与规划共用。 */
@Composable
private fun EditRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                Icon(leadingIcon, contentDescription = null, tint = StatusColors.Idle)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "编辑",
                tint = StatusColors.Idle
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, help: String? = null) {
    TitleWithHelp(text = text, help = help)
}

/**
 * 空状态提示卡：一句短的"接下来该干什么"，旁边一个小「?」。
 *
 * 卡片上的那句话按 v2.8 的口径属于**引导**（"不看到它用户就不知道该点哪"），
 * 所以保留可见；「?」里放的是它的**展开说明**（为什么、还有什么设定），
 * 两者刻意不重复 —— 点开看到一个和卡片上一模一样的句子是最糟的交互。
 */
@Composable
private fun HintCard(text: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text, color = StatusColors.Idle)
            HelpHint(text = detail)
        }
    }
}

// ==================================================================== 本页文案
//
// v2.8：这一页的成句说明统一收进「?」（点开才显示），但**原文案一字未删** ——
// 它们只是从"一直占屏幕"变成"点一下才出现"。
//
// 没有收起的（刻意保留可见）：
//   - 空状态卡上的引导句 —— 收掉它用户就不知道该点哪里了；
//     卡片旁边仍然给了一个「?」，但里面放的是它的**展开说明**，不与卡片重复
//   - "规划不能跨 0 点，起止也不能相同（相同会被当成全天锁机）。" —— 弹窗里
//     紧挨着滚轮，是改时间时**当下**要对照的约束，且越界后下面立刻出红字
//   - 习惯/规划行的副标题、区块计数、"点击可编辑"之类的短标题

/** 全空时的引导语（一个习惯、一条规划都没有）。 */
private const val EMPTY_HINT = "还没有习惯或规划。点右下角的加号开始添加。"

/** 上一句的展开说明（问号里）。与卡片上的短句**不重复**，是"接下来会发生什么"。 */
private const val EMPTY_HINT_DETAIL = "这一页只负责新建，不会改动任何正在生效的锁；" +
    "锁机期间也能正常添加。"

/** 「明日规划」区块为空时的引导语。 */
private const val EMPTY_PLAN_HINT = "明天还没有规划。点右下角加号 → 明日规划。"

/** 上一句的展开说明（问号里）。 */
private const val EMPTY_PLAN_HINT_DETAIL = "规划只能排在明天；到点会锁机，只能到点解锁。" +
    "点击已有规划可以编辑或删除。"

/** 「明日规划」区块标题旁的说明：把原来那行正文整句搬进来。 */
private const val PLAN_SECTION_HELP =
    "规划只能排在明天；到点会锁机，只能到点解锁。点击可编辑或删除。"

/** 习惯的副标题：频率 + 提醒时间 + 打卡条件。 */
private fun habitSubtitle(habit: Habit): String = buildString {
    append(
        when (val f = habit.frequency) {
            is com.zilv.app.domain.habit.HabitFrequency.Daily -> "每天"
            // v2.14：「每周 N 次」已按用户要求删除（旧数据解码后是「指定星期」）。
            // v2.13：「每月 N 次」→「指定每月几号」（与 HabitListScreen.describe 同一套文案）
            is com.zilv.app.domain.habit.HabitFrequency.SpecificMonthDays ->
                if (f.days.isEmpty()) {
                    "每月（未选日期）"
                } else {
                    "每月 " + f.sortedDays().joinToString("、") + " 号"
                }

            is com.zilv.app.domain.habit.HabitFrequency.SpecificDays ->
                "指定星期 " + f.days.sortedBy { it.value }.joinToString("") { dayLabel(it) }
        }
    )
    if (habit.remindTimes.isNotEmpty()) {
        append(" · ")
        append(habit.remindTimes.joinToString("、") { hhmm(it.hour * 60 + it.minute) })
    }
    append(" · ")
    append(habit.checkInConditionLabel)
}

private fun dayLabel(day: java.time.DayOfWeek): String = when (day) {
    java.time.DayOfWeek.MONDAY -> "一"
    java.time.DayOfWeek.TUESDAY -> "二"
    java.time.DayOfWeek.WEDNESDAY -> "三"
    java.time.DayOfWeek.THURSDAY -> "四"
    java.time.DayOfWeek.FRIDAY -> "五"
    java.time.DayOfWeek.SATURDAY -> "六"
    java.time.DayOfWeek.SUNDAY -> "日"
}

private fun hhmm(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private fun formatDate(date: LocalDate): String =
    "${date.monthValue}月${date.dayOfMonth}日"
