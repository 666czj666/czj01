package com.zilv.app.ui.records

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.zilv.app.domain.model.HabitType
import com.zilv.app.ui.components.AppIcon
import com.zilv.app.ui.theme.Motion
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel

/**
 * 某一天的明细（用户要求：点击打卡日历可以观看某一天的记录，
 * 包括打卡记录和各 app 使用时长）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    epochDay: Long,
    onBack: () -> Unit,
    viewModel: DayDetailViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(epochDay) { viewModel.load(epochDay) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.dateLabel) },
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

            if (!state.loading && !state.hasAnything) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("这一天没有记录", color = StatusColors.Idle)
                    }
                }
            }

            // ---------------- 那天的留言 ----------------
            // 放在打卡记录**上面**：留言是"这一天过得怎么样"的一句话总结，
            // 先读它再往下看明细，比翻到最后才看到更顺
            if (state.hasNote) {
                item { SectionTitle("那天的留言") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.cardColors(
                            containerColor = StatusColors.Unlocked.copy(alpha = 0.12f)
                        )
                    ) {
                        Text(
                            text = state.note.orEmpty(),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }

            // ---------------- 打卡记录 ----------------
            if (state.records.isNotEmpty()) {
                item {
                    SectionTitle("打卡记录（${state.checkedCount}/${state.records.size}）")
                }
                items(state.records, key = { "h-${it.habitId}" }) { record ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = Motion.enterSpec(),
                                fadeOutSpec = Motion.exitSpec(),
                                placementSpec = Motion.listSpec()
                            ),
                        shape = MaterialTheme.shapes.small,
                        colors = CardDefaults.cardColors(
                            containerColor = if (record.checked) {
                                StatusColors.Unlocked.copy(alpha = 0.10f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (record.checked) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Outlined.Circle
                                },
                                contentDescription = null,
                                tint = if (record.checked) StatusColors.Unlocked else StatusColors.Idle
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = record.habitName + if (record.archived) "（已归档）" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = buildString {
                                        append(record.statusLabel)
                                        if (record.times.isNotEmpty()) {
                                            append(" · ")
                                            append(record.times.joinToString("、"))
                                        }
                                        // 到点打卡习惯若不是地点验证，明确说明不计入天数
                                        if (record.checked &&
                                            record.habitType == HabitType.LOCATION &&
                                            !record.locationVerified
                                        ) {
                                            append("（不计入连续天数）")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusColors.Idle
                                )
                            }
                        }
                    }
                }
            }

            // ---------------- 使用时长 ----------------
            item {
                Spacer(Modifier.height(6.dp))
                SectionTitle("应用使用时长")
            }

            if (state.usageEntries.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                        Text(
                            "这一天没有采集到使用时长",
                            modifier = Modifier.padding(16.dp),
                            color = StatusColors.Idle
                        )
                    }
                }
            } else {
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "屏幕点亮 ${formatDuration(state.totalUsageMillis)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "共 ${state.usageEntries.size} 个应用有前台记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                    }
                }

                val max = state.usageEntries.firstOrNull()?.durationMillis ?: 1L
                items(state.usageEntries, key = { "u-${it.packageName}" }) { entry ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem(
                                fadeInSpec = Motion.enterSpec(),
                                fadeOutSpec = Motion.exitSpec(),
                                placementSpec = Motion.listSpec()
                            ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                packageName = entry.packageName,
                                fallbackLabel = entry.label,
                                size = 36.dp
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(entry.label, modifier = Modifier.weight(1f))
                                    Text(
                                        formatDuration(entry.durationMillis),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Spacer(Modifier.height(5.dp))
                                LinearProgressIndicator(
                                    progress = {
                                        (entry.durationMillis.toFloat() / max).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
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
        modifier = Modifier.padding(top = 6.dp)
    )
}
