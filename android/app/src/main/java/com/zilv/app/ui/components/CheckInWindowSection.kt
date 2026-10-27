package com.zilv.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zilv.app.domain.lock.CheckInWindowRules
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.ui.lock.formatWindow
import com.zilv.app.ui.theme.StatusColors

/**
 * 「打卡时间」选择器（"开始时间 + 结束时间"两个滚轮）。
 *
 * ## 为什么长这样
 *
 * 用户要求："把打卡时间选择改成和明日规划那样（明日规划用的是
 * **开始时间 + 结束时间**两个滚轮选择器；打卡时间现在是个两步式流程，
 * 改成同一种交互）"。
 *
 * 以前是**两步式**：点「添加打卡时间」→ 选开始 → 对话框关掉 → 再点一次
 * （按钮文字变成"设置结束时间"）→ 选结束。问题有两个：
 *  1. 用户在中途看不出"我这次选的会不会被拒"，只有点完第二步才知道
 *  2. 与「明日规划」里的时间设定不是同一种手感，同一个 App 里两套交互
 *
 * 现在与「明日规划」逐点对齐：
 *  - 两行卡片，左边是标签（开始时间 / 结束时间），右边是当前值，点一下开滚轮
 *  - 滚轮关掉后**立刻**在下面显示"本次打卡时间：xx:xx-xx:xx"，越界/起止相同
 *    当场红字提示，并对「添加」按钮置灰
 *  - 最后点一下「添加」才真的落到 [windows] 里
 *
 * 校验用的是 [CheckInWindowRules]（纯函数，有单测）——
 * 与两个 ViewModel 的 `addCheckInWindow` 是**同一个**判据，
 * 所以"界面说不行、点添加却存进去了"这类不一致不可能出现。
 *
 * ## 结束时间取 00:00 的含义
 *
 * 读作 **24:00**（当天最后一刻），不是 0 分钟 —— 与 `TimeWindow.of(..., endMeansMidnight = true)`
 * 完全一致（见 [CheckInWindowRules.toWindow]）。
 *
 * @param windows 已经加好的打卡窗口（调用方持有，这里只读 + 请求增删）
 * @param onAdd 用户点「添加」时回调，参数是当天第几分钟
 * @param onRemove 点某一行的「移除」
 * @param emptyHint 一个窗口都没有时显示的说明（不同页面措辞不同）
 */
@Composable
fun CheckInWindowSection(
    windows: List<TimeWindow>,
    onAdd: (startMinute: Int, endMinute: Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    emptyHint: String? = null,
    addLabel: String = "添加打卡时间"
) {
    var draftStart by remember { mutableIntStateOf(CheckInWindowRules.DEFAULT_START_MINUTE) }
    var draftEnd by remember { mutableIntStateOf(CheckInWindowRules.DEFAULT_END_MINUTE) }
    // 正在用滚轮选哪一个：true = 开始，false = 结束（与 PlanEditScreen 同一手法）
    var pickingStart by remember { mutableStateOf(true) }
    var pickingTime by remember { mutableStateOf(false) }

    val problem = CheckInWindowRules.problem(draftStart, draftEnd)

    Column(modifier) {
        if (windows.isEmpty() && emptyHint != null) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
        }

        windows.forEachIndexed { index, window ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatWindow(window) + if (window.crossesMidnight) "  （跨零点）" else "",
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onRemove(index) }) { Text("移除") }
            }
        }

        Spacer(Modifier.height(8.dp))

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

        Spacer(Modifier.height(8.dp))
        Text(
            "本次打卡时间：${rangeLabel(draftStart, draftEnd)}",
            style = MaterialTheme.typography.bodySmall,
            color = StatusColors.Idle
        )
        if (problem != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            // 起止相同 / 越界时按钮直接不可点，原因就在上面那行红字里
            enabled = problem == null,
            onClick = { onAdd(draftStart, draftEnd) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(addLabel)
        }
    }

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

/** 一排"标签 + 当前值"，点击后打开滚轮。*/
@Composable
fun TimeSettingRow(label: String, value: String, onClick: () -> Unit) {
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

/** `08:00-09:00`；结束取 00:00 时写成 `24:00`，与真正落库的窗口一致。 */
private fun rangeLabel(startMinute: Int, endMinute: Int): String {
    val end = if (endMinute == 0) "24:00" else hhmm(endMinute)
    return "${hhmm(startMinute)}-$end"
}

private fun hhmm(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
