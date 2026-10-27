package com.zilv.app.ui.records

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.data.mapper.RuleCheckInStatus
import com.zilv.app.data.repository.LockSessionRepository
import com.zilv.app.domain.report.LockReportBuilder
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import com.zilv.app.ui.theme.Motion

internal fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000L
    return when {
        totalMinutes < 1 -> "不到 1 分钟"
        totalMinutes < 60 -> "$totalMinutes 分钟"
        else -> "${totalMinutes / 60} 小时 ${totalMinutes % 60} 分"
    }
}

private fun formatDateTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        .let { "%d月%d日 %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

// ---------------------------------------------------------------- 使用时长页

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    viewModel: UsageViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用时长") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // v2.8：原来这一整句说明（"由无障碍服务自采集，不需要
                    // 「使用情况访问」权限"）收进「?」。
                    // 这里**没有**再补一行"屏幕点亮 N" —— 下面 StatBox 就是它，
                    // 上面重复一遍等于让人以为有两个不同的数字
                    Text("关于这份数据", style = MaterialTheme.typography.labelLarge, color = StatusColors.Idle)
                    HelpHint(
                        text = "由无障碍服务自采集，**不需要**「使用情况访问」权限。"
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatBox("屏幕点亮", formatDuration(state.totalMillis), Modifier.weight(1f))
                    StatBox(
                        "最长连续",
                        if (state.longestMillis > 0) {
                            "${formatDuration(state.longestMillis)}\n${state.longestAppLabel.orEmpty()}"
                        } else {
                            "—"
                        },
                        Modifier.weight(1f)
                    )
                }
            }

            if (state.entries.isEmpty() && !state.loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("今天还没怎么用手机", color = StatusColors.Idle)
                    }
                }
            }

            items(state.entries, key = { it.packageName }) { entry ->
                val max = state.entries.firstOrNull()?.durationMillis ?: 1L
                Card(modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.label, modifier = Modifier.weight(1f))
                            Text(formatDuration(entry.durationMillis), fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (entry.durationMillis.toFloat() / max).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------- 拦截日志页

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedLogScreen(
    onBack: () -> Unit,
    viewModel: BlockedLogViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拦截日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 这行是**数据**（共被拦 N 次），必须留在屏幕上；说明收进「?」
                    Text("共被拦 ${state.totalCount} 次", fontWeight = FontWeight.Bold)
                    HelpHint(
                        text = "同一应用 5 秒内的连续尝试已合并为 1 次，" +
                            "所以这里是真实次数而不是点击次数。"
                    )
                }
            }

            if (state.rows.isEmpty() && !state.loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("还没有拦截记录", color = StatusColors.Idle)
                    }
                }
            }

            items(state.rows, key = { it.packageName }) { row ->
                Card(modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.label)
                            // v2.15：标签会骗人 —— 很多厂商把状态栏/音量面板/截屏拆成独立包，
                            // 它们的应用名都叫「系统界面组件」。只有包名能定位到底是哪一个，
                            // 所以这里必须显示出来（真机排查"系统界面组件被拦"时唯一的线索）。
                            Text(
                                row.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                            Text(
                                "最近 ${formatDateTime(row.lastAtMillis)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                        Text("${row.count} 次", fontWeight = FontWeight.Bold)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------- 锁机历史

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockHistoryScreen(
    onBack: () -> Unit,
    onOpenReport: (Long) -> Unit,
    viewModel: LockHistoryViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("锁机历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (state.sessions.isEmpty() && !state.loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("还没有结束的锁机窗口", color = StatusColors.Idle)
                    }
                }
            }

            items(state.sessions, key = { it.id }) { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenReport(session.id) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                session.ruleName + if (!session.reportSeen) "  ●" else "",
                                fontWeight = if (!session.reportSeen) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                "${formatDateTime(session.windowStartMillis)} – " +
                                    formatDateTime(session.windowEndMillis).substringAfter(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                            Text(
                                when (session.state) {
                                    LockSessionRepository.STATE_UNLOCKED -> when (session.unlockReason) {
                                        LockSessionRepository.REASON_CHECKIN -> "已打卡解锁"
                                        LockSessionRepository.REASON_EMERGENCY -> "紧急放行"
                                        else -> "已解锁"
                                    }

                                    LockSessionRepository.STATE_EXPIRED -> "窗口结束"
                                    else -> "进行中"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                        Text(
                            "查看报告 ▸",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(4.dp)
                                .width(80.dp)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ---------------------------------------------------------------- 锁机报告

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockReportScreen(
    sessionId: Long,
    onBack: () -> Unit,
    viewModel: LockReportViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.ruleName.ifBlank { "锁机报告" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        val report = state.report
        if (report == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(state.error ?: "正在生成报告…", color = StatusColors.Idle)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatDateTime(report.windowStart.toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()).substringBefore(" ")}  " +
                        "%02d:%02d – %02d:%02d".format(
                            report.windowStart.hour, report.windowStart.minute,
                            report.windowEnd.hour, report.windowEnd.minute
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigStat("锁机时长", formatDuration(report.lockDurationMillis), Modifier.weight(1f))
                    BigStat("用手机", formatDuration(report.phoneUsedMillis), Modifier.weight(1f))
                }
            }

            // ---- 打卡 ----
            if (report.checkIn != null) {
                item { SectionTitle("打卡") }
                item {
                    val c = report.checkIn
                    Card(modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "%02d:%02d".format(c.at.hour, c.at.minute),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                when {
                                    c.status == RuleCheckInStatus.ABNORMAL_UNLOCK -> "异常解锁（定位降级）"
                                    c.isLate -> "迟到 ${c.lateMinutes} 分钟"
                                    c.isEarly -> "提前 ${-c.minutesFromStart} 分钟"
                                    else -> "准时"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                    }
                }
            }

            // ---- 关键应用使用时长 ----
            item { SectionTitle("关键应用使用时长") }
            if (report.keyApps.isEmpty()) {
                item { EmptyHint("这段时间你没碰手机 👏") }
            } else {
                val max = report.keyApps.first().durationMillis
                items(report.keyApps, key = { it.packageName }) { app ->
                    Card(modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(app.label, modifier = Modifier.weight(1f))
                                Text(formatDuration(app.durationMillis))
                            }
                            Spacer(Modifier.height(5.dp))
                            LinearProgressIndicator(
                                progress = { (app.durationMillis.toFloat() / max).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // ---- 被拦住的 ----
            item { SectionTitle("被拦住的") }
            if (report.blockedApps.isEmpty()) {
                item { EmptyHint("本次窗口没有被拦记录") }
            } else {
                items(report.blockedApps, key = { "b-${it.packageName}" }) { app ->
                    Card(modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(app.label, modifier = Modifier.weight(1f))
                            Text("${app.count} 次", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ---- 异常 ----
            if (report.anomalies.isNotEmpty()) {
                item { SectionTitle("异常") }
                items(report.anomalies, key = { it }) { line ->
                    Card(modifier = Modifier.fillMaxWidth().animateItem(), shape = RoundedCornerShape(10.dp)) {
                        Text(line, modifier = Modifier.padding(14.dp))
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = StatusColors.Idle
        )
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
        }
    }
}
