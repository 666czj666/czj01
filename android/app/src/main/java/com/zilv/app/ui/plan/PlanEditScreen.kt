package com.zilv.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.components.WheelTimePickerDialog
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel

/**
 * 「明日规划」新建 / 编辑页（v2.5）。
 *
 * 用户要求："点击明日规划就再弹出来一个窗口，新窗口有开始时间和结束时间的设定。"
 *
 * 时间设定的**真正落点**在这一页（弹窗只负责开场与预填），因为：
 *  - 这里能显示仓库层给出的拒绝理由（"只能添加明天的规划" / "不能跨 0 点" /
 *    "起止不能相同" / "正在进行中无法修改"），弹窗里没地方放
 *  - 编辑已有规划时本来就需要一整页：改名称、改时间、删除
 *
 * ## 三条硬约束在界面上的体现
 *
 *  1. **只能建明天的**：新建时日期由 `PlanRepository.tomorrow()` 决定并只读显示；
 *     用户若在 23:59 打开这一页、0 点后才点保存，仓库层会拒绝并给出可读理由
 *  2. **不能跨 0 点**：结束时早于开始时，界面上直接红字拦下（`inputProblem`）
 *  3. **起止不能相同**：同样在界面上拦下 —— `TimeWindow(x, x)` 是**全天 1440 分钟**
 *     而不是 0 分钟，放过去就等于整天锁机（这个坑本项目踩过多次）
 *
 * @param planId `0` 表示新建，否则是 `plans.id`
 * @param initialName 从「添加」页弹窗带过来的昵称（只在新建、且名称还空着时生效）
 * @param initialStartMinute / [initialEndMinute] 从「添加」页时间窗带过来的起止
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanEditScreen(
    planId: Long,
    onBack: () -> Unit,
    initialName: String? = null,
    initialStartMinute: Int? = null,
    initialEndMinute: Int? = null,
    viewModel: PlanEditViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(planId) { viewModel.load(planId) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    // 预填必须晚于 load（load 会用库里的值整份替换状态）。
    // 两个 LaunchedEffect 的 key 里都带 loadedOnce，所以它们只会在
    // "载入完成"那一刻跑一次，不会在用户改名称/改时间时反复触发。
    //
    // 预填自身也都是**幂等**的（名称非空就跳过、时间已设过就跳过），
    // 所以即使重新组合跑到第二次也不会覆盖用户刚输入的内容。
    LaunchedEffect(initialName, state.loadedOnce) {
        initialName?.let { viewModel.prefillName(it) }
    }
    LaunchedEffect(initialStartMinute, initialEndMinute, state.loadedOnce) {
        viewModel.prefillTimes(initialStartMinute, initialEndMinute)
    }

    // 正在用滚轮选哪一个：true = 开始，false = 结束
    var pickingStart by remember { mutableStateOf(true) }
    var pickingTime by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "新建明日规划" else "编辑规划") },
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

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("规划名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ------------------------------------------------ 生效日期（只读）
            Text("生效日期", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(formatDate(state.date), style = MaterialTheme.typography.bodyLarge)
                    // v2.8：原来这里是一整句解释，收进「?」——
                    // "已有规划的生效日期不能改"这句结论仍然留在副标题位置上，
                    // 因为它是编辑时**当下**要知道的约束
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.isNew) {
                                "新建的规划只能排明天"
                            } else {
                                "已有规划的生效日期不能改"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.Idle
                        )
                        Spacer(Modifier.width(6.dp))
                        HelpHint(
                            text = if (state.isNew) {
                                "规划只能添加**明天**的。过了 0 点就变成今天了，" +
                                    "那时保存会被拒绝。"
                            } else {
                                "已有规划的生效日期不能改。"
                            }
                        )
                    }
                }
            }

            // ------------------------------------------------ 时间设定
            Text("时间设定", style = MaterialTheme.typography.titleSmall)
            TimeRow(
                label = "开始时间",
                value = state.startMinute?.let(::hhmm) ?: "未设置",
                onClick = {
                    pickingStart = true
                    pickingTime = true
                }
            )
            TimeRow(
                label = "结束时间",
                value = state.endMinute?.let(::hhmm) ?: "未设置",
                onClick = {
                    pickingStart = false
                    pickingTime = true
                }
            )
            // v2.8：这句原来一直显示在时间设定下面。拆成两半 ——
            // "当前时段：…"是**数据**（留在屏幕上），后面那条约束收进「?」
            // （紧挨着它的问题红字仍然是可见的，用户不会失去行动依据）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "当前时段：${state.timeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
                Spacer(Modifier.width(6.dp))
                HelpHint(
                    text = "规划不能跨 0 点，起止也不能相同" +
                        "（相同会被当成全天锁机）。"
                )
            }

            // 本地能看出来的问题（填没填全、起止顺序）先在这里说清；
            // "只能明天 / 进行中不能改"这类要看库的判断由仓库层给理由
            state.inputProblem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }

            // ------------------------------------------------ 删除（仅编辑时）
            if (!state.isNew) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = viewModel::requestDelete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("删除这条规划", color = MaterialTheme.colorScheme.error)
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
            text = { Text("删除后无法恢复。正在进行中的规划删不掉 —— 它的解锁条件只有到点解锁。") },
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

    // ---- 滚轮时间选择（复用项目既有的 WheelTimePickerDialog）----
    if (pickingTime) {
        val current = if (pickingStart) state.startMinute else state.endMinute
        WheelTimePickerDialog(
            title = if (pickingStart) "选择开始时间" else "选择结束时间",
            initialMinuteOfDay = current ?: DEFAULT_TIME_MINUTE,
            onDismiss = { pickingTime = false },
            onConfirm = { minuteOfDay ->
                if (pickingStart) viewModel.setStartMinute(minuteOfDay)
                else viewModel.setEndMinute(minuteOfDay)
                pickingTime = false
            }
        )
    }
}

/** 打开滚轮时的默认位置：09:00。 */
private const val DEFAULT_TIME_MINUTE = 9 * 60

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
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
            Text(label, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun hhmm(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

private fun formatDate(date: java.time.LocalDate): String =
    "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"
