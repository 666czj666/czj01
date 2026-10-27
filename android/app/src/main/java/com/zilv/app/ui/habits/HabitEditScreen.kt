package com.zilv.app.ui.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.ui.components.CheckInWindowSection
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.components.TitleWithHelp
import com.zilv.app.ui.lock.LockDurationPicker
import com.zilv.app.ui.lock.describeUnlock
import com.zilv.app.ui.lock.formatWindows
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek

/**
 * 习惯新增 / 编辑（PRD §4.1 T2.6）。
 *
 * 验收要点：到点打卡类型未选打卡点时禁止保存 —— 由
 * [HabitEditUiState.canSave] 与 [HabitEditViewModel.save] 双重保证。
 *
 * v2.0：锁机并入习惯 —— 独立的锁机入口已删除。
 * v2.1：原先的「锁机」区块升级为**「惩罚」区块**（无 / 锁机）——
 * 用户要求"设置今日习惯时可以选择惩罚，分为无和锁机，选择锁机后出现选择锁机时长"。
 * 这里的改动**只在点「保存」时落库**（见 [HabitEditViewModel.save]）。
 *
 * ## 本次改造：新建走弹窗，编辑仍然走整页
 *
 * 用户要求"把新建习惯界面改为弹窗"。于是：
 *  - **新建**：[HabitCreateDialog]（「添加」页与「习惯管理」页的加号都打开它）
 *  - **编辑已有习惯**：仍然是这一页 —— 它要放的东西太多（频率、打卡条件、
 *    惩罚、详细设置入口、归档/删除），塞进弹窗会变成一个滚不到底的小表单
 *
 * 两者的**字段区块是同一份代码**（下面那几个 `Habit*Section`），
 * 所以"弹窗里能改的东西和整页不一致"这种分叉不会发生。
 *
 * @param onOpenLockSettings 打开这条习惯的锁机「详细设置」；参数是 habitId
 * @param initialName 「添加」页弹窗里输入的昵称（v2.5）。**只在新建、且名称还空着时**生效
 *        —— 编辑已有习惯时一律以库里的值为准（见 [HabitEditViewModel.prefillName]）
 * @param allowDuringLock 是不是从「添加」页进来的。传 `true` 时锁机期间也允许
 *        改这条习惯的惩罚配置，**但只要这条习惯的锁此刻正在生效就仍然会被拒绝**
 *        （详见 `LockRuleRepository.ensureRuleEditable`）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitEditScreen(
    habitId: Long,
    onBack: () -> Unit,
    onManageSpots: () -> Unit,
    onOpenLockSettings: (Long) -> Unit = {},
    initialName: String? = null,
    allowDuringLock: Boolean = false,
    viewModel: HabitEditViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(habitId, allowDuringLock) { viewModel.load(habitId, allowDuringLock) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    // 昵称预填放在 load 之后：load 会把整份状态换成库里的值，
    // 先填的话会被它冲掉。prefillName 自身也只对"名称为空"生效，
    // 所以它对编辑已有习惯是空操作（详见 HabitEditViewModel.prefillName）。
    //
    // ⚠️ 这个昵称在导航层是**读一次就删**的（见 ZilvNavHost 里 KEY_INITIAL_NAME 的用法），
    // 所以用户下次从「习惯管理」进同一页时不会再被上一次的昵称覆盖。
    LaunchedEffect(initialName, state.loadedOnce) {
        initialName?.let { viewModel.prefillName(it) }
    }

    // 从打卡点管理页返回时刷新列表，否则新建完打卡点回来还是看不到。
    // 同时刷新锁机摘要 —— 用户可能刚从"详细设置"返回，配置已经变了。
    LifecycleResumeEffect(Unit) {
        viewModel.reloadSpots()
        viewModel.refreshLock()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (habitId == 0L) "新建习惯" else "编辑习惯") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            HabitNameField(state = state, viewModel = viewModel)

            HabitCheckInConditionSection(
                state = state,
                viewModel = viewModel,
                onManageSpots = onManageSpots
            )

            HabitFrequencySection(state = state, viewModel = viewModel)

            HabitPunishmentSection(
                state = state,
                viewModel = viewModel,
                onOpenLockSettings = onOpenLockSettings
            )

            HabitSaveSection(state = state, viewModel = viewModel)

            // ---- 归档 / 删除（仅编辑已有习惯时显示）----
            //
            // 用户要求："在今日习惯的编辑界面里添加删除和归档功能"。
            // 两者语义不同，界面上必须让人一眼看出：
            //   归档 -> 从今日列表隐藏，可恢复
            //   删除 -> 连打卡记录一起清掉，不可恢复
            if (habitId != 0L) {
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(14.dp))

                OutlinedButton(
                    onClick = viewModel::archive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.Inventory2,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("归档（从今日列表隐藏，可恢复）")
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = viewModel::requestDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("删除这个习惯", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // ---- 删除二次确认 ----
    if (state.pendingDelete) {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除「${state.name}」？") },
            text = {
                Text(
                    "这个习惯和它的全部打卡记录都会被清掉，无法恢复。\n\n" +
                        "如果只是想让它从今天页面消失，用「归档」就好 —— 归档可以恢复。"
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text("取消") }
            }
        )
    }
}

// ==================================================================== 字段区块
//
// 下面这几个 `Habit*Section` 是**整页与新建弹窗共用**的字段区块。
// 之所以显式接收 [HabitEditViewModel] 而不是十来个回调：
// 这些区块全部是"改一下内存里的草稿"的纯 UI（落库只发生在 [HabitEditViewModel.save]），
// 传 ViewModel 既少一半样板，也保证两个入口改的一定是同一份状态。
// 它们只是文件内的复用单元，不对外暴露别的语义。

@Composable
internal fun HabitNameField(state: HabitEditUiState, viewModel: HabitEditViewModel) {
    OutlinedTextField(
        value = state.name,
        onValueChange = viewModel::setName,
        label = { Text("习惯名称") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 打卡条件（无 / 指定地点）与打卡点选择。
 *
 * 用户要求（v2.2）：打卡条件只有「指定地点」和「无」两个取值，
 * 而且"解锁方法就是打卡方法" —— 所以这里既决定怎么打卡，
 * 也决定锁机时怎么解锁：点是打卡、不点是等锁机时间走完。
 */
@Composable
internal fun HabitCheckInConditionSection(
    state: HabitEditUiState,
    viewModel: HabitEditViewModel,
    onManageSpots: () -> Unit
) {
    SectionTitle(
        "打卡条件",
        help = "选「指定地点」时，必须走到打卡点附近才能打卡，锁机期间也一样 —— " +
            "解锁方法就是打卡方法。选「无」则什么时候都能打卡，室内也能用。"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.checkInCondition == CheckInCondition.NONE,
            onClick = { viewModel.setCheckInCondition(CheckInCondition.NONE) },
            label = { Text("无") }
        )
        FilterChip(
            selected = state.checkInCondition == CheckInCondition.LOCATION,
            onClick = { viewModel.setCheckInCondition(CheckInCondition.LOCATION) },
            label = { Text("指定地点") }
        )
    }
    Text(
        // v2.8：完整解释（"解锁方法就是打卡方法"那一层）在标题旁的「?」里；
        // 这里只留"当下选的是哪个"这一句结果 —— 它是状态，不是说明
        text = when (state.checkInCondition) {
            CheckInCondition.NONE -> "什么时候都能打卡（室内也能用）"
            CheckInCondition.LOCATION -> "必须到打卡点才能打卡（室内可能定位不到）"
        },
        style = MaterialTheme.typography.bodySmall,
        color = StatusColors.Idle
    )

    if (state.checkInCondition == CheckInCondition.LOCATION) {
        when {
            state.needsSpotSetup -> {
                Text(
                    "还没有打卡点。指定地点打卡需要先创建一个地点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = onManageSpots,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("去创建打卡点")
                }
            }

            else -> {
                Text(
                    text = state.selectedSpotName?.let { "已选：$it" } ?: "请选择打卡点",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.spotId == null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        StatusColors.Unlocked
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.availableSpots.forEach { spot ->
                        FilterChip(
                            selected = state.spotId == spot.id,
                            onClick = { viewModel.setSpotId(spot.id) },
                            label = { Text(spot.name) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 频率：每天 / 指定每月几号 / 指定星期。
 *
 * ## v2.13：整块改用 [FlowRow]（自动换行），修的是**真机上看不到「周日」**的 bug
 *
 * 用户原话：「打卡频率里指定星期加上周日选项。」
 *
 * 查证结果：`SUNDAY to "日"` **一直都在**那份清单里（v2.4 起就没少过），
 * 领域层 `SpecificDays` / 编解码 / 备份 / 连续天数也都支持 `DayOfWeek.SUNDAY`。
 * 真正的根因是**布局溢出**：原先星期芯片放在一个普通 [Row] 里，7 个
 * [FilterChip] 每个的**最小可交互宽度是 48dp**（Material 3 的
 * `minimumInteractiveComponentSize`，与标签只有一个汉字无关），
 * 7 × 48dp + 6 × 6dp 间距 ≈ 372dp，比主流手机的内容区（360dp 屏 − 32dp 边距 = 328dp）
 * 还宽 —— 于是最后那个「日」被挤出屏幕右侧，用户既点不到、也看不见。
 *
 * 同理，上面那行频率芯片（v2.13 时有 4 个）加起来也超过一行宽度，
 * 最后那个同样会被裁掉。[FlowRow] 按**实际测量宽度**换行，与屏幕宽度、字号、
 * 语言都无关，所以"某个选项被挤出屏幕"这类问题从此不会复发。这也是选择它、
 * 而不是 `chunked(4)` 手工分行或 `horizontalScroll` 的原因：
 *  - 手工分行要猜每行放得下几个，换个窄屏或大字号还是会溢出或浪费空间
 *  - 横向滚动会把选项藏起来（用户不知道右边还有东西）——
 *    那正是这个 bug 的表现形式，等于换个方式再犯一次
 *
 * ## v2.14：删掉「每周 N 次」
 *
 * 用户原话：「打卡频率里删除每周 N 次。」于是这里只剩 3 个芯片，
 * 下面的 1..7 次数选择器一并删除（那个选择器只服务这一个频率）。
 * 领域层与编解码的迁移见 `HabitFrequency` / `HabitFrequencyCodec` 的 KDoc。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HabitFrequencySection(state: HabitEditUiState, viewModel: HabitEditViewModel) {
    SectionTitle("频率", help = FREQUENCY_HELP)

    // 三个频率选项同样用 FlowRow：窄屏上会换行，而不是把「指定星期」裁掉
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.frequency is HabitFrequency.Daily,
            onClick = { viewModel.setFrequency(HabitFrequency.Daily) },
            label = { Text("每天") }
        )
        // v2.13：用户要求"删除每月打卡多少次的选项，改为指定每月1-31号打卡"。
        // 选中后下面出现 1..31 的日期多选（可多选；当月没有的日期自然跳过）。
        FilterChip(
            selected = state.frequency is HabitFrequency.SpecificMonthDays,
            onClick = {
                viewModel.setFrequency(HabitFrequency.SpecificMonthDays(emptySet()))
            },
            label = { Text("指定每月几号") }
        )
        FilterChip(
            selected = state.frequency is HabitFrequency.SpecificDays,
            onClick = {
                viewModel.setFrequency(HabitFrequency.SpecificDays(emptySet()))
            },
            label = { Text("指定星期") }
        )
    }

    // ------------------------------------------------ 指定每月几号（v2.13）
    //
    // 31 个日期芯片，可多选。上限**必须**读
    // HabitFrequency.SpecificMonthDays.MAX_DAY（= 31），不能写死：
    // 领域层哪天调整了上限，界面也要跟着动，而不是先崩在构造函数上。
    //
    // 「当月没有这个日期」在界面上**不做任何特判** —— 语义完全由
    // HabitFrequency.SpecificMonthDays.isScheduledOn 表达：
    // 4 月任何一天的 dayOfMonth 最大是 30，选了 31 号就等于"4 月整月没有计划日"，
    // 自然跳过，直接接 5 月 31 日。
    if (state.frequency is HabitFrequency.SpecificMonthDays) {
        val selectedDays = (state.frequency as HabitFrequency.SpecificMonthDays).days
        // v2.8 的约定：成句说明进标题旁的「?」，这里只留"选了几号"这个状态
        SectionTitle("每月日期", help = MONTH_DAYS_HELP)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (HabitFrequency.SpecificMonthDays.MIN_DAY..HabitFrequency.SpecificMonthDays.MAX_DAY)
                .forEach { day ->
                    FilterChip(
                        selected = day in selectedDays,
                        onClick = { viewModel.toggleMonthDay(day) },
                        label = { Text("$day") }
                    )
                }
        }
    }

    if (state.frequency is HabitFrequency.SpecificDays) {
        val selectedDays = (state.frequency as HabitFrequency.SpecificDays).days
        // ⚠️ 周一到周日**七个都要在**，尤其别漏 SUNDAY。
        // v2.13 之前漏的其实不是这一行清单，而是**布局**（见本函数 KDoc）：
        // 普通 Row 放不下 7 个 48dp 的芯片，「日」被挤出了屏幕。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                DayOfWeek.MONDAY to "一",
                DayOfWeek.TUESDAY to "二",
                DayOfWeek.WEDNESDAY to "三",
                DayOfWeek.THURSDAY to "四",
                DayOfWeek.FRIDAY to "五",
                DayOfWeek.SATURDAY to "六",
                DayOfWeek.SUNDAY to "日"
            ).forEach { (day, label) ->
                FilterChip(
                    selected = day in selectedDays,
                    onClick = { viewModel.toggleSpecificDay(day) },
                    label = { Text(label) }
                )
            }
        }
    }
}

/**
 * 惩罚（无 / 锁机）与它的两个参数：锁机时长、打卡时间。
 *
 * 用户要求（v2.1）："把设置今日习惯时可以选择惩罚，分为无和锁机，
 * 选择锁机后出现选择锁机时长"。
 *
 * ⚠️ 这一整块**只改内存**，点下面的「保存」时才一并落库 ——
 * 每改一下就写库的话，锁机守卫很容易把中间态拦下（RuleLockedException）。
 */
@Composable
internal fun HabitPunishmentSection(
    state: HabitEditUiState,
    viewModel: HabitEditViewModel,
    onOpenLockSettings: (Long) -> Unit
) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    Spacer(Modifier.height(14.dp))

    SectionTitle("惩罚", help = PUNISHMENT_HELP)
    Text(
        "没在打卡时间内打卡的后果",
        style = MaterialTheme.typography.bodySmall,
        color = StatusColors.Idle
    )

    if (!state.canOpenLockSettings) {
        // 新建习惯还没有 habitId，锁机配置挂不上去
        Text(
            "先保存习惯，再设置惩罚",
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.Idle
        )
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.punishment == HabitPunishment.NONE,
            onClick = { viewModel.setPunishment(HabitPunishment.NONE) },
            label = { Text("无") }
        )
        FilterChip(
            selected = state.punishment == HabitPunishment.LOCK,
            onClick = { viewModel.setPunishment(HabitPunishment.LOCK) },
            label = { Text("锁机") }
        )
    }

    if (state.punishment == HabitPunishment.LOCK) {
        // 1) 锁机时长：不超过 24 小时
        Spacer(Modifier.height(4.dp))
        SectionTitle("锁机时长", help = LOCK_DURATION_HELP)
        LockDurationPicker(
            minutes = state.lockDurationMinutes,
            label = if (state.lockDurationMinutes > 0) {
                state.lockDurationLabel
            } else {
                "未设置"
            },
            checkInWindows = state.checkInWindows,
            onSelect = viewModel::setLockDurationMinutes,
            modifier = Modifier.fillMaxWidth()
        )
        if (state.lockDurationMinutes <= 0) {
            Text(
                "请设置锁机时长",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // 2) 打卡时间：超过它就会开始锁机。
        //
        // 用户要求："把打卡时间选择改成和明日规划那样（开始时间 + 结束时间两个
        // 滚轮选择器）"。这里整块交给 [CheckInWindowSection] ——
        // 锁机「详细设置」页用的是同一个组件，两处手感完全一致。
        Spacer(Modifier.height(4.dp))
        SectionTitle("打卡时间", help = CHECK_IN_WINDOW_HELP)
        CheckInWindowSection(
            windows = state.checkInWindows,
            onAdd = viewModel::addCheckInWindow,
            onRemove = viewModel::removeCheckInWindow,
            modifier = Modifier.fillMaxWidth()
        )

        state.lockRule?.let { rule ->
            Text(
                "解锁方式：" + describeUnlock(rule),
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
        }
        // 解锁方法 = 打卡方法（用户要求 v2.2）——
        // 打卡条件是"无"的习惯同样允许开锁机：锁机时点一下打卡就解锁。
        // 这是刻意接受的弱约束（本项目只做表面上的锁机）。
        //
        // v2.8：完整段落收进「?」，这里只留"这条习惯眼下"的结论（状态，不是说明）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (state.checkInCondition) {
                    CheckInCondition.NONE -> "打卡条件是「无」：锁机时点一下打卡即解锁"
                    CheckInCondition.LOCATION -> "打卡条件是「指定地点」：要到打卡点打卡才能解锁"
                },
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
            Spacer(Modifier.width(6.dp))
            HelpHint(
                text = "解锁方法 = 打卡方法：锁机时在习惯页点一下打卡就能解锁，" +
                    "或者等这次锁机时长走完自动解除。\n\n" +
                    "打卡条件是「无」的习惯也能开锁机 —— 锁机时点一下打卡即解锁；" +
                    "选了「指定地点」则必须到打卡点打卡。" +
                    "本项目只做表面上的锁机，这是刻意接受的弱约束。"
            )
        }
        if (state.lockRuleUsesLegacyWindows) {
            Text(
                "这条配置原本用的是「自选锁机时间」，保存后会改成「超过打卡时间后锁机」。",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Warn
            )
        }
        if (state.lockRuleDisabled) {
            Text(
                "锁机配置当前是停用状态，到「详细设置」里可以重新开启。",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Warn
            )
        }
    } else {
        Text(
            "不锁机：只会按上面的频率记录打卡。",
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.Idle
        )
        // 惩罚选了「无」但库里还留着配置时，把现状说清楚 ——
        // 保存之后它才会被删掉
        state.lockRule?.let { rule ->
            Text(
                "当前已保存的配置（保存后会删除）：打卡 " +
                    formatWindows(rule.checkInWindows, emptyText = "不强制") +
                    "；锁机 " +
                    formatWindows(rule.effectiveLockWindows, emptyText = "未设置"),
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
        }
    }

    // 「详细设置」的显示条件刻意是"已经有一份配置"，而不是"惩罚选了锁机"：
    // 配置挂在库里，只有存在时进去才有东西可改。
    if (state.lockRule != null) {
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = { onOpenLockSettings(state.id) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("详细设置")
        }
    }
}

/** 保存按钮与"为什么还存不了"的说明。 */
@Composable
internal fun HabitSaveSection(state: HabitEditUiState, viewModel: HabitEditViewModel) {
    if (state.error != null) {
        Text(state.error!!, color = MaterialTheme.colorScheme.error)
    }

    // 保存按钮放在最后：惩罚区块的改动也要靠它一起落库
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = viewModel::save,
        enabled = state.canSave && !state.saving,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (state.saving) "保存中…" else "保存")
    }

    if (!state.canSave) {
        Text(
            text = when {
                state.name.isBlank() -> "请先填写名称"
                state.needsSpotSetup -> "请先去创建打卡点"
                state.punishmentProblem != null -> state.punishmentProblem!!
                else -> "打卡条件选了「指定地点」，请选择打卡点"
            },
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.Idle
        )
    }
}

/**
 * 本页（及新建弹窗）各区块的说明文案。
 *
 * v2.8：这些句子原来一直显示在标题下面，现在收进标题旁的「?」——
 * **一字未删**，只是从"一直占屏幕"变成"点一下才出现"。
 *
 * 刻意**没有**收起的（它们是状态/校验，不是说明）：
 *   - "先保存习惯，再设置惩罚"（新建时惩罚挂不上 habitId，这是当时的操作前提）
 *   - "请设置锁机时长"、"请先填写名称"（校验，红字）
 *   - "这条配置原本用的是「自选锁机时间」…"、"锁机配置当前是停用状态…"（警告，状态）
 *   - "当前已保存的配置（保存后会删除）：…"（数据摘要）
 *   - "不锁机：只会按上面的频率记录打卡。"（选「无」时的结果，8 个字）
 */
private const val FREQUENCY_HELP =
    "每天 / 指定每月几号 / 指定星期，三种频率任选一种。" +
        "它决定这条习惯哪天出现在「今天」页、以及怎么算连续天数。"

/**
 * 「指定每月几号」的说明（v2.13，取代 v2.4 的「每月 N 次」）。
 *
 * 用户在 v2.4 的原话是"添加每月N次，最多28次"，v2.13 改成
 * "删除每月打卡多少次的选项，改为指定每月1-31号打卡，
 * 如果当月没有31号就跳过没有的号数，直接开始下一个月一号"。
 *
 * 说明里必须把"当月没有这个日期就跳过"讲清楚：它是这个频率最反直觉的一点
 * （选了 31 号的人会以为 4 月要打 31 次或者要补卡）。
 */
private const val MONTH_DAYS_HELP =
    "选出每月要打卡的几号（可多选）。只有选中的日期需要打卡，其余日子不影响连续天数。" +
        "当月没有这个日期就跳过 —— 例如只选 31 号时，4 月没有 31 号，" +
        "4 月整月都不需要打卡，下一次落在 5 月 31 日。"

private const val PUNISHMENT_HELP =
    "惩罚是「没在打卡时间内打卡」的后果：选「无」只记录打卡；" +
        "选「锁机」则超过打卡截止时刻后开始锁机（锁多久、什么时候锁在下面设）。"

private const val LOCK_DURATION_HELP =
    "超过打卡截止时刻后锁这么久，最长 24 小时（1440 分钟）。" +
        "选好时长后，下面会实时预览「打卡截止 → 锁机区间」。"

private const val CHECK_IN_WINDOW_HELP =
    "超过截止时刻还没打卡就会开始锁机；锁机期间补打卡也能解锁。" +
        "结束时间填 00:00 表示当天最后一刻（24:00），不是 0 分钟。"

@Composable
internal fun SectionTitle(text: String, help: String? = null) {
    TitleWithHelp(text = text, help = help)
}
