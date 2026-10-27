package com.zilv.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.ui.components.CheckInWindowSection
import com.zilv.app.ui.components.TitleWithHelp
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek

/**
 * 锁机「详细设置」（PRD T3.3 / §4.5）。按习惯作用域：一个习惯至多一份配置。
 *
 * v2.1 结构变化：**删掉了「锁机时间」区块**（用户要求："不自选锁机时间删除"），
 * 换成「锁机时长」——锁机从**打卡截止时刻**开始，用户只能选持续多久（上限 24 小时）。
 * 「打卡时间」区块保留在这里（与习惯编辑页共用同一份数据，两边都能改）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockRuleEditScreen(
    habitId: Long,
    onBack: () -> Unit,
    viewModel: LockRuleEditViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(habitId) { viewModel.load(habitId) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    // 打卡点归**习惯的打卡条件**管（v2.2），规则页不再要求选点，
    // 所以这里只剩"没有配置名称/窗口"之类的兜底提示。
    val requiredSpotLabel = ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("锁机设置") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                label = { Text("配置名称，如「早课」「上班」") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用", modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = viewModel::setLockEnabled)
            }

            SectionTitle("重复（不选＝每天）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    DayOfWeek.MONDAY to "一", DayOfWeek.TUESDAY to "二",
                    DayOfWeek.WEDNESDAY to "三", DayOfWeek.THURSDAY to "四",
                    DayOfWeek.FRIDAY to "五", DayOfWeek.SATURDAY to "六",
                    DayOfWeek.SUNDAY to "日"
                ).forEach { (day, label) ->
                    FilterChip(
                        selected = day in state.days,
                        onClick = { viewModel.toggleDay(day) },
                        label = { Text(label) }
                    )
                }
            }

            // ---------------------------------------------------- 打卡时间
            //
            // 刻意放在「锁机时长」**之前**：用户要先明白"什么时候必须打卡"，
            // 才看得懂"没打卡会怎样" —— 而锁机起点就是这里的截止时刻。
            //
            // 本轮把"两步式"（点按钮 → 选开始 → 再点一次 → 选结束）换成
            // 「开始时间 + 结束时间」两个滚轮，与「明日规划」及习惯编辑页的
            // 惩罚区块完全同一种交互 —— 三处共用 [CheckInWindowSection]。
            SectionTitle("打卡时间", help = CHECK_IN_WINDOW_HELP)
            CheckInWindowSection(
                windows = state.checkInWindows,
                onAdd = viewModel::addCheckInWindow,
                onRemove = viewModel::removeCheckInWindow,
                emptyHint = "不设置＝不强制打卡，也就没有锁机起点。",
                modifier = Modifier.fillMaxWidth()
            )

            // ---------------------------------------------------- 锁机时长
            //
            // v2.1：用户不再自选锁机时间，只选自选**时长** ——
            // 锁机从上面的打卡截止时刻开始，最多 24 小时。
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

            // ------------------------------------------- 约束：锁机必须晚于打卡
            val problem = state.lockWindowProblem
            if (problem != null) {
                Text(
                    "❌ $problem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // ------------------------------------------------ 解锁方式（v2.2）
            //
            // 用户要求："解锁方法 = 打卡（任何打卡方法都可以），或者过完锁机时间"。
            // 于是原先那个「仅定时 / 到点打卡」选择器**整块删除** ——
            // 「仅定时解锁」的含义是"打卡没用"，与上面这条直接冲突。
            //
            // 打卡点也不再在这里选：它归**习惯的打卡条件**管（习惯编辑页）。
            // 这里只把规则说清楚，不再给任何开关。
            SectionTitle("解锁方式", help = UNLOCK_HELP)
            NumberRow(
                label = "提前打卡有效期（分钟）",
                value = state.earlyCheckInMinutes,
                onChange = viewModel::setEarlyCheckInMinutes
            )

            NumberRow(
                label = "宽限期（分钟，到点后缓冲）",
                value = state.graceMinutes,
                onChange = viewModel::setGraceMinutes
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "严格模式（离开打卡点后重新拦截）",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = state.strictMode,
                    onCheckedChange = viewModel::setStrictMode
                )
            }

            OutlinedTextField(
                value = state.message,
                onValueChange = viewModel::setMessage,
                label = { Text("遮挡层文案，如「去图书馆，别在这刷手机」") },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.error != null) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(6.dp))
            Button(
                onClick = viewModel::save,
                // canSaveOnScreen：v2.1 起没设锁机时长就不让保存
                enabled = state.canSaveOnScreen && !state.saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.saving) "保存中…" else "保存")
            }

            if (!state.canSaveOnScreen) {
                Text(
                    text = when {
                        state.lockDurationMinutes <= 0 -> "请设置锁机时长"
                        state.lockWindowProblem != null -> state.lockWindowProblem!!
                        state.checkInWindows.isEmpty() && state.windows.isEmpty() ->
                            "请先添加打卡时间 —— 锁机从打卡截止时刻开始"
                        else -> requiredSpotLabel.ifBlank { "请检查锁机配置" }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange(value - 5) }) { Text("−5") }
        Text("$value", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onChange(value + 5) }) { Text("+5") }
    }
}

@Composable
private fun SectionTitle(text: String, help: String? = null) {
    TitleWithHelp(text = text, help = help, modifier = Modifier.padding(top = 6.dp))
}

/**
 * 本页三段说明文案（v2.8 从标题下面收进「?」）。
 *
 * 刻意**保留可见**的（这些是"当下要据此行动"的，不是说明）：
 *   - "请设置锁机时长" / "请先添加打卡时间 —— 锁机从打卡截止时刻开始"（校验）
 *   - "❌ $problem"（锁机窗口与打卡窗口冲突的实时错误）
 *   - "不设置＝不强制打卡，也就没有锁机起点。"（`CheckInWindowSection` 的
 *     emptyHint：一个打卡时间都没加时才会出现，是"为什么存不了"的直接原因）
 */
private const val CHECK_IN_WINDOW_HELP =
    "没在这个时间打卡，超过截止时刻就会被锁；锁机期间补打卡也能解锁。" +
        "结束时间填 00:00 表示当天最后一刻（24:00），不是 0 分钟。"

private const val LOCK_DURATION_HELP =
    "超过打卡时间还没打卡，就从打卡截止时刻开始锁这么久（最长 24 小时）。" +
        "选好时长后，下面会实时预览「打卡截止 → 锁机区间」。"

private const val UNLOCK_HELP =
    "打卡即可解锁（在习惯页点的打卡、到打卡点打卡都算），" +
        "或者等这次锁机时长走完自动解除。"
