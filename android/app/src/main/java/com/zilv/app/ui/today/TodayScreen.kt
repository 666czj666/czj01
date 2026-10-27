package com.zilv.app.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.habit.NowNextPicker
import com.zilv.app.domain.lock.InstantLock
import com.zilv.app.domain.model.CheckInCondition
import com.zilv.app.domain.today.AgendaItem
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.LocalTime
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.scale
import com.zilv.app.ui.theme.Motion
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * 首页（PRD §4.2）。
 *
 * ## v2.5：这里变成"只显示和打卡、不可编辑"的界面
 *
 * 用户要求：
 *  - "该界面显示当天的习惯和昨天做好的规划"
 *  - "当天不需要打卡的习惯不显示"
 *  - "以时间从早到晚排序"
 *  - "把习惯和明日规划的编辑功能隐藏，把加号移到（添加）的新界面"
 *  - "把今天界面变成一个只显示和打卡，不可编辑的界面"
 *
 * 所以这一页：
 *  - **没有加号**（FAB 搬到「添加」页）
 *  - **没有编辑入口**（点击习惯 = 打卡，点击规划 = 什么都不做）
 *  - 列表内容与排序全由纯函数 [com.zilv.app.domain.today.TodayAgenda] 决定
 *
 * 编辑习惯请到「添加」页点它；新建习惯/规划也在「添加」页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    /**
     * @param habitId 这条打卡挂在哪条习惯上；`null` 表示该规则没挂习惯
     *        （v2.2：室内定位失败时打卡页要用它给出「改用无条件打卡」）
     */
    onCheckIn: (ruleId: Long, spotId: Long, habitId: Long?) -> Unit,
    viewModel: TodayViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    // 例如"该习惯需要到打卡点打卡"——一次性提示，弹完就清
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    // 打卡前确认（用户要求："点习惯时弹一个确认对话框，确认后才写入"）
    var pendingCheckIn by remember { mutableStateOf<TodayHabit?>(null) }
    // 留言输入框
    var noteDialogOpen by remember { mutableStateOf(false) }
    // 即时锁机的时长选择（v2.12）
    var instantLockDialogOpen by remember { mutableStateOf(false) }

    // 回到首页时刷新锁机状态卡片（今日列表由 Flow 自动维护，无需手动刷）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // ---------------- 打卡确认对话框 ----------------
    //
    // 只在"点一下就能打卡"的路径上出现。打卡条件是「指定地点」的习惯
    // 走打卡页，那里已经有明确的「立即打卡」按钮，本身就是一次确认，
    // 再叠一层对话框只会让人多点两下。
    pendingCheckIn?.let { pending ->
        val name = pending.habit.name
        AlertDialog(
            onDismissRequest = { pendingCheckIn = null },
            title = { Text("确定打卡吗？") },
            text = {
                Text(
                    buildString {
                        append("「$name」")
                        append("\n")
                        append("打卡后今天不能取消。")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingCheckIn = null
                    viewModel.checkIn(pending.habit.id)
                }) { Text("确定打卡") }
            },
            dismissButton = {
                TextButton(onClick = { pendingCheckIn = null }) { Text("取消") }
            }
        )
    }

    // ---------------- 当日留言对话框 ----------------
    if (noteDialogOpen) {
        DayNoteDialog(
            initialText = state.dayNote?.text.orEmpty(),
            onDismiss = { noteDialogOpen = false },
            onSave = { text ->
                noteDialogOpen = false
                viewModel.saveDayNote(text)
            },
            onClear = {
                noteDialogOpen = false
                viewModel.saveDayNote("")
            }
        )
    }

    // ---------------- 即时锁机：时长选择对话框（v2.12） ----------------
    if (instantLockDialogOpen) {
        InstantLockDialog(
            onDismiss = { instantLockDialogOpen = false },
            onConfirm = { minutes ->
                instantLockDialogOpen = false
                viewModel.startInstantLock(minutes)
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // ⚠️ 这里**刻意没有 floatingActionButton**：
        // 用户要求"把加号移到（添加）的新界面"。加号在「添加」页。
        topBar = { TopAppBar(title = { Text("今天  ·  ${formatDate(state.today)}") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------- 即时锁机（最上方，用户要求 5 / v2.12） ----------------
            //
            // 用户原话：「添加入口放在今天界面的最上方」。所以就放在**第一个 item** ——
            // 在绿底横幅与「当前/接下来」聚焦卡片之上，一进今天页就看得见。
            item {
                Spacer(Modifier.height(4.dp))
                InstantLockEntry(onClick = { instantLockDialogOpen = true })
            }

            // ---------------- 有进展 → 绿底横幅（留言入口，用户要求 3 / v2.12） ----------------
            //
            // v2.12：判据从「当天所有习惯都打卡完成」改成「**有习惯完成 或 规划结束**」。
            // 全完成那一档的绿底观感一字未改，只是不再是唯一的入口条件；
            // 文案也按三种进展分档，绝不会在没全完成时说"所有任务已完成"。
            if (state.canLeaveNote) {
                val progress = state.progress
                item {
                    Spacer(Modifier.height(4.dp))
                    NoteBanner(
                        title = when {
                            // ① 全完成：保留用户明确要过的观感与文案
                            state.allDone -> "所有任务已完成"
                            // ② 有习惯完成但没全完成
                            progress.anyHabitChecked -> "今天已有进展"
                            // ③ 习惯一条都没打，但有规划已经结束
                            progress.anyPlanEnded -> "今天的规划已结束"
                            // ④ 只因为今天已经写过留言才显示（留言入口要一直可达）
                            else -> "今天的留言"
                        },
                        progressText = if (!state.allDone && progress.anyHabitChecked) {
                            "已完成 ${state.checkedCount}/${state.totalCount}"
                        } else {
                            null
                        },
                        note = state.dayNote?.text,
                        onClick = { noteDialogOpen = true }
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                LockStatusCard(
                    state = state.cardState,
                    title = state.cardTitle,
                    message = state.cardMessage,
                    canCheckIn = state.canCheckIn,
                    onCheckIn = {
                        val ruleId = state.checkInRuleId
                        val spotId = state.checkInSpotId
                        if (ruleId != null && spotId != null) {
                            onCheckIn(ruleId, spotId, state.checkInHabitId)
                        }
                    }
                )
            }

            // ---------------- 当前 / 接下来（用户要求 2） ----------------
            // 全部打完了就不再显示"接下来"—— 那时上面已经是完成的横幅
            state.nowNext?.let { nowNext ->
                item {
                    NowNextCard(nowNext)
                }
            }

            item {
                Text(
                    text = "今日任务（${state.checkedCount}/${state.totalCount}）" +
                        if (state.planCount > 0) " · 规划 ${state.planCount} 条" else "",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (state.agenda.isEmpty() && !state.loading) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(Motion.enterSpec()) + expandVertically(Motion.enterSpec())
                    ) {
                        // 首页不可编辑，所以提示指向「添加」页 ——
                        // 用户要求"把加号移到（添加）的新界面"
                        EmptyHint("今天没有要打卡的习惯，也没有规划。到「添加」页可以新建")
                    }
                }
            }

            // ---------------- 习惯与规划混排，按时间从早到晚 ----------------
            //
            // 用 agenda 的下标当 key 而不是 habit.id：规划规则的 id 恒为 0
            // （它们不落库），拿它当 key 会让多条规划互相顶掉。
            itemsIndexed(state.agenda, key = { index, _ -> "agenda-$index" }) { _, item ->
                when (item) {
                    is AgendaItem.HabitTask -> HabitRow(
                        item = item.entry,
                        onClick = {
                            val habit = item.entry.habit
                            // 打卡条件是「指定地点」时必须去打卡点完成，不能在首页点一下就算；
                            // 并把 habitId 带过去，室内定位失败时才能就地改成「无条件打卡」
                            if (habit.checkInCondition == CheckInCondition.LOCATION &&
                                habit.spotId != null
                            ) {
                                onCheckIn(0L, habit.spotId, habit.id)
                            } else if (item.entry.checkedToday) {
                                // 已打卡：**不做任何取消**（用户要求）。只是让 ViewModel
                                // 给一句提示，而不是弹打卡确认框 —— 再确认一次也没有意义
                                viewModel.checkIn(habit.id)
                            } else {
                                // 未打卡：先确认再写入
                                pendingCheckIn = item.entry
                            }
                        },
                        // ⚠️ v2.5：**点击就是打卡，没有编辑入口**。
                        // 用户要求"把习惯和明日规划的编辑功能隐藏" ——
                        // 长按曾经是编辑的快捷方式，现在也去掉，编辑统一去「添加」页。
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.enterSpec(),
                            fadeOutSpec = Motion.exitSpec(),
                            placementSpec = Motion.listSpec()
                        )
                    )

                    // 规划**不可打卡**：点它什么都不做，只读展示
                    // （与 Plan.toLockRule() 的 allowCheckInUnlock = false 同一条口径）。
                    // 仍然用同一行样式，让"时间从早到晚"这条时间轴读起来是连续的。
                    is AgendaItem.PlanTask -> PlanRow(
                        item = item,
                        modifier = Modifier.animateItem(
                            fadeInSpec = Motion.enterSpec(),
                            fadeOutSpec = Motion.exitSpec(),
                            placementSpec = Motion.listSpec()
                        )
                    )
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

/**
 * 「当前 / 接下来」聚焦卡片（用户要求 2）。
 *
 * 内容全部来自纯函数 [NowNextPicker.pick]：
 *  - [NowNextPicker.Mode.NOW] → 提醒时间刚过，"现在该做"
 *  - [NowNextPicker.Mode.NEXT] → 提醒时间还没到（或没有提醒时间），"接下来"
 */
@Composable
private fun NowNextCard(nowNext: NowNextPicker.NowNext) {
    val isNow = nowNext.mode == NowNextPicker.Mode.NOW
    val accent = if (isNow) StatusColors.Warn else StatusColors.Unlocked

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = StatusColors.soft(accent, isSystemInDarkTheme())
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (isNow) "现在该做" else "接下来",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = nowNext.habit.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        val times = nowNext.habit.remindTimes
                        if (times.isNotEmpty()) {
                            append("提醒时间 ")
                            append(times.joinToString("、") { formatTime(it) })
                        }
                        val distance = nowNext.distanceLabel
                        if (distance.isNotEmpty()) {
                            if (isNotEmpty()) append("，")
                            append(distance)
                        }
                        if (isEmpty()) {
                            // 没有提醒时间时的兜底文案 —— 不编造"还有 X 分钟"
                            append("今天还没有完成")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = StatusColors.Idle
                )
            }
        }
    }
}

/**
 * 「即时锁机」入口（今天页最上方，v2.12）。
 *
 * 用户要求：「添加即时锁机功能，锁机可选时间从 0 分到 12 小时。
 * **入口放在今天界面的最上方**。」
 *
 * 做成一张整宽的按钮卡：它是"我现在就要把自己锁住"这个动作，
 * 不参与列表的时间轴，所以刻意长得跟下面的习惯/规划行不一样。
 */
@Composable
private fun InstantLockEntry(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = StatusColors.Locked.copy(alpha = 0.10f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = StatusColors.Locked
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "即时锁机",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "现在就开始锁，最长 12 小时（到点自动解除）",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
        }
    }
}

/**
 * 即时锁机的时长选择对话框（v2.12）。
 *
 * ## 形态
 *
 * 用户建议：「一个对话框 + 若干快捷档位（如 15 分 / 30 分 / 1 小时 / 2 小时 /
 * 4 小时 / 8 小时 / 12 小时）+ 可自填」。所以这里是三段：
 *  1. **0 分**（单独一档，语义见 `InstantLock` 的 KDoc：立刻锁、到下一个整分钟解除）
 *  2. 七个快捷档位
 *  3. 自填「小时 + 分钟」（合成规则在纯函数 [InstantLock.minutesOf] 里，有单测）
 *
 * ## 为什么不需要"确认删除/二次确认"
 *
 * 点「开始锁机」就等于确认 —— 时长本身还要再选一次，已经是一次确认了。
 * 而且即时锁机**只锁到点**，最长 12 小时，不存在"锁死自己出不来"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantLockDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    // 0 分是默认值：它是"最短的一次锁机"，也是最不容易误伤用户的那一档
    var minutes by remember { mutableStateOf(0) }
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }

    /** 自填两个输入框 → 总分钟数（规则与 [InstantLock.minutesOf] 完全一致）。 */
    fun applyText(h: String, m: String) {
        hoursText = h
        minutesText = m
        minutes = InstantLock.minutesOf(h, m)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("即时锁机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "从现在起锁住，到点自动解除 —— 这是你主动锁的，所以打卡解不开它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )

                Text(
                    "当前选择：${InstantLock.label(minutes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )

                // ---- 0 分单独一档（用户要求允许 0 分） ----
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = minutes == 0,
                        onClick = { applyText("", "") },
                        label = { Text("0 分") }
                    )
                    Text(
                        "立刻锁，到下一个整分钟解除",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                // ---- 快捷档位 ----
                InstantLock.PRESETS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { preset ->
                            FilterChip(
                                selected = minutes == preset,
                                onClick = { applyText((preset / 60).toString(), (preset % 60).toString()) },
                                label = { Text(presetLabel(preset)) }
                            )
                        }
                    }
                }

                // ---- 自填 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(2)
                            applyText(digits, minutesText)
                        },
                        label = { Text("小时") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(2)
                            applyText(hoursText, digits)
                        },
                        label = { Text("分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "最长 ${InstantLock.MAX_MINUTES / 60} 小时，超出会被夹到上限",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(minutes) }) { Text("开始锁机") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 快捷档位的短标签：`30 分` / `2 小时`。 */
private fun presetLabel(minutes: Int): String = if (minutes < 60) {
    "$minutes 分"
} else {
    "${minutes / 60} 小时"
}

/**
 * 绿底横幅 —— 今天的**留言入口**（用户要求 3；v2.12 放宽了出现条件）。
 *
 * 点它就能留言；已经写过留言时直接在横幅上显示摘要，
 * 这样"今天写过什么"不用再点进去看。
 *
 * ## 为什么标题是参数
 *
 * v2.12 起这个横幅有三种出现理由（全完成 / 部分完成 / 规划结束 / 只有留言），
 * 文案必须**分得开** —— 尤其是绝不能在"只完成了一部分"时说"所有任务已完成"。
 * 所以标题由调用方按 `TodayUiState` 分档给出，这里只负责画。
 *
 * @param progressText 进度摘要（如「已完成 1/2」）；`null` 表示这一档不显示
 */
@Composable
private fun NoteBanner(
    title: String,
    progressText: String?,
    note: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = StatusColors.Unlocked)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Color.White
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (progressText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (note.isNullOrBlank()) {
                        "点这里给自己留句话"
                    } else {
                        "「${noteSummary(note)}」"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "留言",
                tint = Color.White
            )
        }
    }
}

/**
 * 当日留言输入框（多行）。
 *
 * 已有留言会被预填，允许**保存**也允许**清除**（清除 = 存空文本，
 * 由 `DayNoteRepository.save` 落成删除）。
 */
@Composable
private fun DayNoteDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit
) {
    var draft by remember { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今日留言") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_NOTE_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    placeholder = { Text("今天过得怎么样？") },
                    supportingText = { Text("${draft.length}/$MAX_NOTE_LENGTH") }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "留言会显示在打卡日历上，点有留言的那天就能看到。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (initialText.isNotBlank()) {
                    TextButton(onClick = onClear) { Text("清除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/** 留言摘要：横幅上只放得下一行，超过就截断。 */
private fun noteSummary(note: String, max: Int = 30): String {
    val flat = note.replace('\n', ' ').trim()
    return if (flat.length <= max) flat else flat.take(max) + "…"
}

private fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

private const val MAX_NOTE_LENGTH = 500

@Composable
private fun LockStatusCard(
    state: LockCardState,
    title: String,
    message: String,
    canCheckIn: Boolean,
    onCheckIn: () -> Unit
) {
    // 四态之间的颜色平滑过渡，而不是硬切 —— 锁机开始/结束时卡片"变色"比"跳变"舒服得多
    val target = when (state) {
        LockCardState.IDLE -> StatusColors.Idle
        LockCardState.WARN -> StatusColors.Warn
        LockCardState.LOCKED -> StatusColors.Locked
        LockCardState.UNLOCKED -> StatusColors.Unlocked
    }
    val bg by animateColorAsState(
        targetValue = target,
        animationSpec = Motion.enterSpec(Motion.SLOW),
        label = "lock-card-bg"
    )
    val fg = Color.White

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(20.dp)) {
            // 文案随状态切换做交叉淡入淡出，用 state 作为 key
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn(Motion.enterSpec()) + scaleIn(initialScale = 0.96f)) togetherWith
                        fadeOut(Motion.exitSpec())
                },
                label = "lock-card-content"
            ) { animatedState ->
                Column {
                    Text(
                        text = when (animatedState) {
                            LockCardState.IDLE -> title
                            LockCardState.WARN -> title
                            LockCardState.LOCKED -> title
                            LockCardState.UNLOCKED -> title
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = fg
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(text = message, style = MaterialTheme.typography.bodyMedium, color = fg)
                    }
                }
            }

            AnimatedVisibility(
                visible = canCheckIn,
                enter = fadeIn(Motion.enterSpec()) + expandVertically(Motion.enterSpec()),
                exit = fadeOut(Motion.exitSpec()) + shrinkVertically(Motion.exitSpec())
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onCheckIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("立即打卡解锁")
                    }
                }
            }
        }
    }
}

/**
 * 一条习惯。**整行可点 = 打卡**，没有编辑入口（v2.5）。
 *
 * 用户要求"把今天界面变成一个只显示和打卡，不可编辑的界面"，
 * 所以这里连"编辑"两个字都不再出现 —— 编辑习惯请到「添加」页点它。
 */
@Composable
private fun HabitRow(
    item: TodayHabit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 勾选时图标轻微回弹 + 变色，给一个"完成"的即时反馈
    val checkScale by animateFloatAsState(
        targetValue = if (item.checkedToday) 1f else 0.86f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "check-scale"
    )
    val iconTint by animateColorAsState(
        targetValue = if (item.checkedToday) StatusColors.Unlocked else StatusColors.Idle,
        animationSpec = Motion.enterSpec(),
        label = "check-tint"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.checkedToday) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.scale(checkScale)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.habit.name, style = MaterialTheme.typography.bodyLarge)
                val detail = buildString {
                    append("连续 ${item.stats.current} 天")
                    if (item.stats.longest > 0) append(" · 最长 ${item.stats.longest} 天")
                    if (item.habit.checkInCondition == CheckInCondition.LOCATION) {
                        append(" · " + item.habit.checkInConditionLabel)
                    }
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
            }
            // 最早提醒时间：列表按它排序，显示出来用户才知道顺序是从哪来的
            val remind = item.habit.remindTimes.minOrNull()
            if (remind != null) {
                Text(
                    text = formatTime(remind),
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusColors.Idle
                )
            }
        }
    }
}

/**
 * 一条规划（v2.5）。
 *
 * **不可打卡、不可编辑、点了没反应** —— 用户要求"点规划 = 什么都不做，
 * 或只弹一个只读详情"。这里选"什么都不做"：弹详情还要多一次点击才能关掉，
 * 而规划的全部信息（名称 + 时段）已经写在这一行上了。
 *
 * 样式上留一条左侧色条与淡色底：一眼能看出"这不是习惯，不能打勾"，
 * 但仍处在同一条时间轴上（用户要求按时间从早到晚混排）。
 */
@Composable
private fun PlanRow(
    item: AgendaItem.PlanTask,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = accent
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.plan.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "规划 · ${item.timeLabel} · 到点自动解锁",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = StatusColors.Idle)
    }
}

private fun formatDate(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"
