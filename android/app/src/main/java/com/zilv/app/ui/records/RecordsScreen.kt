package com.zilv.app.ui.records

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.records.HeatmapBuilder
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.zilv.app.domain.records.MonthCalendar
import com.zilv.app.ui.theme.Motion

/**
 * 记录页（PRD §4.4）：本周概览 + 打卡日历热力图。
 *
 * 使用时长 / 拦截日志 / 锁机历史将在 M6 接入，此处先以占位入口呈现。
 *
 * v2.8：三个"更多"入口的副标题（原来写成 `各应用前台时长排行（无需额外权限）`
 * 这样一整句）拆成"短副标题 + 「?」"，解释部分点开才显示。
 * 日历下面那两行**刻意没有**收起：`本月打卡 N 天` 是数据，
 * `绿底＝打过卡 橙点＝写过留言 右上小方框＝有规划` 是读图必需的图例 ——
 * 收起来用户就看不懂格子上的标记了。
 */

// ---------------------------------------------------------------- 本页说明文案
//
// v2.8：原来直接写在 `EntryRow` 的 subtitle 里，现在收进「?」。原文案一字未删。
//
// ⚠️ 只放"这一页才需要"的那半句。更完整的解释在各自的详情页顶部，
// 这里再抄一遍就是两处维护同一句话了（"无需额外权限"那类细节留在详情页）。

private const val USAGE_HELP =
    "各应用前台时长排行（无需额外权限）。点进去看每个应用的具体时长。"

private const val BLOCKED_LOG_HELP =
    "被拦次数排行（已按 5 秒窗口合并）。点进去看每个应用被拦了多少次。"

private const val LOCK_HISTORY_HELP =
    "每次锁机窗口的完整报告。点进去看那一次锁了多久、用手机多久。"
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    onOpenUsage: () -> Unit,
    onOpenBlockedLog: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDay: (Long) -> Unit,
    viewModel: RecordsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从二级页面返回时重新载入 —— ViewModel 被导航栈保留，
    // init 不会重跑，不刷新的话列表会停在旧状态（真实 bug 的根因）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("记录") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    value = "${(state.weeklyCompletion * 100).roundToInt()}%",
                    label = "近 7 天完成率",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    value = "${state.currentStreak} 天",
                    label = "当前连续",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    value = "${state.longestStreak} 天",
                    label = "最长连续",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    value = "${state.totalCheckIns}",
                    label = "累计打卡",
                    modifier = Modifier.weight(1f)
                )
            }

            Text("打卡日历", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    MonthCalendarHeader(
                        label = state.monthLabel,
                        canGoNext = !state.atCurrentMonth,
                        onPrev = viewModel::previousMonth,
                        onNext = viewModel::nextMonth,
                        onToday = viewModel::goToCurrentMonth
                    )
                    Spacer(Modifier.height(8.dp))
                    WeekdayHeader()
                    Spacer(Modifier.height(4.dp))
                    MonthGrid(
                        month = state.month,
                        onDayClick = { day -> onOpenDay(day.date.toEpochDay()) }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = buildString {
                            append("本月打卡 ${state.month.checkedDayCount} 天")
                            if (state.month.totalCheckIns > 0) {
                                append(" · 共 ${state.month.totalCheckIns} 次")
                            }
                            if (state.month.notedDayCount > 0) {
                                append(" · 留言 ${state.month.notedDayCount} 天")
                            }
                            if (state.month.plannedDayCount > 0) {
                                append(" · 规划 ${state.month.plannedDayCount} 天")
                            }
                            append("　（点某一天看当天明细）")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        // 图例：三种标记必须能分清，否则用户看到方框会以为是渲染问题。
                        // 纯文字说明，不画示意图 —— 美术效果不在本次范围。
                        text = "绿底＝打过卡　橙点＝写过留言　右上小方框＝有规划",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusColors.Idle
                    )
                }
            }

            Text("更多", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
            // v2.8：三个入口原来的副标题都是成句的解释，收进各行的「?」；
            // 行上只留"这里面是什么"（短句），否则"更多"这一段会比上面的日历还长
            EntryRow("使用时长", "各应用前台时长排行", USAGE_HELP, onOpenUsage)
            EntryRow("拦截日志", "被拦次数排行", BLOCKED_LOG_HELP, onOpenBlockedLog)
            EntryRow("锁机历史", "每次锁机窗口的完整报告", LOCK_HISTORY_HELP, onOpenHistory)

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
        }
    }
}

/**
 * 「更多」里的一个入口行。
 *
 * @param help 非 `null` 时在标题后面挂一个小「?」（v2.8）：`subtitle` 只留
 *        "这里面是什么"的短句，解释性的补充（"无需额外权限""已按 5 秒合并"）
 *        点开才显示
 */
@Composable
private fun EntryRow(title: String, subtitle: String, help: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (help != null) {
                    Spacer(Modifier.width(6.dp))
                    HelpHint(text = help)
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
        }
    }
}

// ---------------------------------------------------------------- 月历组件

/** 月份切换栏：‹ 2026 年 10 月 ›，另有回到本月的快捷入口。 */
@Composable
private fun MonthCalendarHeader(
    label: String,
    canGoNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "上个月")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        // 不允许翻到未来的月份 —— 没有意义的空白页
        IconButton(onClick = onNext, enabled = canGoNext) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "下个月",
                tint = if (canGoNext) MaterialTheme.colorScheme.onSurface else StatusColors.Idle
            )
        }
        TextButton(onClick = onToday) { Text("今天") }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth()) {
        MonthCalendar.weekdayLabels().forEach { name ->
            Text(
                text = name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.Idle,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 月历网格。
 *
 * 用户要求："显示所有天数，有一个或多个打卡记录的日期下以绿色为底色"。
 * 所以每个日期格都要画出来（含邻月补位），有记录的整格铺绿底。
 */
@Composable
private fun MonthGrid(
    month: MonthCalendar.Month,
    onDayClick: (MonthCalendar.Day) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        month.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    DayCell(day = day, onClick = { onDayClick(day) }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 月历格子。
 *
 * 格子上共有**三个可以同时存在**的标记，刻意用三种互不混淆的形态：
 *
 * | 标记 | 形态 | 位置 | 含义 |
 * | --- | --- | --- | --- |
 * | 打卡 | 整格绿底 | 铺满 | 那天有一个或多个打卡记录 |
 * | 留言 | 实心小圆点 | 数字下方 | 那天写过留言 |
 * | 规划 | **空心小方框** | 右上角 | 那天有「明日规划」 |
 */
@Composable
private fun DayCell(
    day: MonthCalendar.Day,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 打卡日铺绿底："有一个或多个打卡记录"都算
    val bg by animateColorAsState(
        targetValue = if (day.hasCheckIn && day.inMonth) {
            StatusColors.Unlocked.copy(alpha = 0.85f)
        } else {
            Color.Transparent
        },
        animationSpec = Motion.enterSpec(),
        label = "day-bg"
    )

    // 留言标记的颜色：绿底上必须用白色才看得见；没打卡的日子用提醒色（橙）
    // 而不是绿色 —— 绿色在这个格子上已经被"打过卡"占用了，两种信息要能分辨
    val noteDotColor = when {
        !day.inMonth -> StatusColors.Idle.copy(alpha = 0.4f)
        day.hasCheckIn -> Color.White
        else -> StatusColors.Warn
    }

    // 规划标记的颜色：再换一个色相（主题主色）。
    // 绿底上白色描边、其余情况用主色 —— 与留言的橙点/白点都不同色。
    val planBorderColor = when {
        !day.inMonth -> StatusColors.Idle.copy(alpha = 0.4f)
        day.hasCheckIn -> Color.White
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (day.isToday) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // 有规划的那天在**右上角**画一个描边小方框（用户要求：日历上可以看见当天的规划）。
        //
        // 为什么是"空心方框 + 对角"而不是再画一个点：
        //  - 三个标记必须能**同时出现**：绿底 = 打过卡、圆点 = 写过留言、方框 = 那天有规划。
        //    圆点是实心、方框是空心，形状与填充方式都不同，缩到 6dp 也分得开
        //  - 纵向再挤一行会把日期数字压扁（格子只有 1/7 屏宽），所以换到对角
        if (day.hasPlan) {
            Canvas(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(6.dp)
            ) {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = planBorderColor,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = day.date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
                // 补位日淡化；打卡日文字转白以保持对比度
                color = when {
                    !day.inMonth -> StatusColors.Idle.copy(alpha = 0.4f)
                    day.hasCheckIn -> Color.White
                    day.isToday -> MaterialTheme.colorScheme.primary
                    else -> Color.Unspecified
                }
            )
            // 同一天多次打卡时在数字下方点一个小计数，不挤占格子
            if (day.checkInCount > 1 && day.inMonth) {
                Text(
                    text = "×${day.checkInCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
            // 有留言的那天点一个小圆点（用户要求：留言记录可以在日历上看见）。
            // 与绿底**可以同时存在** —— 两者说的是不同的事：
            // 绿底 = 那天打过卡，圆点 = 那天写过留言
            if (day.hasNote) {
                Spacer(Modifier.height(2.dp))
                Canvas(Modifier.size(4.dp)) {
                    drawCircle(color = noteDotColor)
                }
            }
        }
    }
}