package com.zilv.app.ui.habits

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import com.zilv.app.ui.theme.Motion
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel

/**
 * 习惯管理列表（PRD §4.5 T2.5）。
 *
 * 提供两种移除方式，界面文案必须让人一眼看出区别：
 * 「归档」可恢复，「删除」不可恢复（连打卡记录一起清掉）。
 *
 * 新建入口：用户要求"把新建习惯界面改为弹窗"，所以右下角加号与「添加」页
 * 一样打开 [HabitCreateDialog]，而不再跳整页。编辑已有习惯仍然是整页
 * （字段太多，见 [HabitCreateDialog] 的取舍说明）。
 *
 * @param onManageSpots 弹窗里选了「指定地点」却没有打卡点时跳去创建
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitListScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onManageSpots: () -> Unit = {},
    viewModel: HabitListViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /** 新建习惯弹窗是否打开 */
    var showCreateDialog by remember { mutableStateOf(false) }

    // 从二级页面返回时重新载入 —— ViewModel 被导航栈保留，
    // init 不会重跑，不刷新的话列表会停在旧状态（真实 bug 的根因）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("习惯管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建习惯")
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

            item {
                AnimatedVisibility(
                    visible = state.isEmpty && !state.loading,
                    enter = fadeIn(Motion.enterSpec()) + expandVertically(Motion.enterSpec()),
                    exit = fadeOut(Motion.exitSpec()) + shrinkVertically(Motion.exitSpec())
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("还没有习惯，点右下角新建", color = StatusColors.Idle)
                    }
                }
            }

            items(state.habits, key = { it.id }) { habit ->
                HabitRow(
                    habit = habit,
                    onEdit = { onEdit(habit.id) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = Motion.enterSpec(),
                        fadeOutSpec = Motion.exitSpec(),
                        placementSpec = Motion.listSpec()
                    ),
                    trailing = {
                        TextButton(onClick = { viewModel.archive(habit.id) }) { Text("归档") }
                        IconButton(onClick = { viewModel.requestDelete(habit.id) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                )
            }

            // ---- 已归档分组：可折叠，提供恢复入口 ----
            if (state.archivedCount > 0) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleArchivedSection() }
                            .animateItem(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Inventory2,
                                contentDescription = null,
                                tint = StatusColors.Idle,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "已归档 ${state.archivedCount} 个",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val rotation by animateFloatAsState(
                                targetValue = if (state.showArchived) 180f else 0f,
                                animationSpec = Motion.enterSpec(),
                                label = "archived-chevron"
                            )
                            Icon(
                                if (rotation > 90f) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (state.showArchived) "收起" else "展开",
                                tint = StatusColors.Idle,
                                modifier = Modifier
                                    .size(20.dp)
                                    .rotate(0f)
                            )
                        }
                    }
                }

                if (state.showArchived) {
                    items(state.archivedHabits, key = { "archived-${it.id}" }) { habit ->
                        HabitRow(
                            habit = habit,
                            onEdit = { onEdit(habit.id) },
                            dimmed = true,
                            modifier = Modifier.animateItem(
                                fadeInSpec = Motion.enterSpec(),
                                fadeOutSpec = Motion.exitSpec(),
                                placementSpec = Motion.listSpec()
                            ),
                            trailing = {
                                TextButton(onClick = { viewModel.unarchive(habit.id) }) {
                                    Icon(
                                        Icons.Filled.RestoreFromTrash,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("恢复")
                                }
                                IconButton(onClick = { viewModel.requestDelete(habit.id) }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    // ---- 删除二次确认 ----
    state.pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text("删除「${target.name}」？") },
            text = {
                Text(
                    "这个习惯和它的**全部打卡记录**都会被清掉，无法恢复。\n\n" +
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

    // ---- 新建习惯（弹窗，与「添加」页同一个）----
    if (showCreateDialog) {
        HabitCreateDialog(
            onDismiss = { showCreateDialog = false },
            onManageSpots = onManageSpots
        )
    }
}

@Composable
private fun HabitRow(
    habit: Habit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    trailing: @Composable () -> Unit
) {
    val container by animateColorAsState(
        targetValue = if (dimmed) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = Motion.enterSpec(),
        label = "habit-row-bg"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    habit.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium,
                    color = if (dimmed) StatusColors.Idle else Color.Unspecified
                )
                Text(
                    describe(habit),
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
            trailing()
        }
    }
}

internal fun describe(habit: Habit): String {
    val type = when (habit.type) {
        HabitType.SIMPLE -> "纯打卡"
        HabitType.LOCATION -> "到点打卡"
        HabitType.TIMED -> "计时"
    }
    val freq = when (val f = habit.frequency) {
        is HabitFrequency.Daily -> "每天"
        // v2.14：「每周 N 次」已按用户要求删除（旧数据在解码时映射成「指定星期」，
        // 因此这里只需要三种分支，不会再出现"每周 x 次"的文案）。
        // v2.13：「每月 N 次」已被「指定每月几号」取代。
        // 文案要说清"是这个月里的哪几号"，而不是"这个月要做几次"——
        // 两者最容易被误读成同一件事（旧配置经兼容映射读出来时会显示成
        // "每月 1、2、3 号"，用户一眼就能看出它被改成了什么）。
        is HabitFrequency.SpecificMonthDays ->
            if (f.days.isEmpty()) "每月（未选日期）" else "每月 ${f.sortedDays().joinToString("、")} 号"

        is HabitFrequency.SpecificDays -> {
            val names = mapOf(
                1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日"
            )
            "周" + f.days.sortedBy { it.value }.joinToString("") { names[it.value] ?: "" }
        }
    }
    return "$type · $freq"
}
