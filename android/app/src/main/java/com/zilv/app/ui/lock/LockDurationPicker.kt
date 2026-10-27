package com.zilv.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.ui.theme.StatusColors
import kotlin.math.roundToInt

/** 时长选择器的下限（分钟）。 */
const val MIN_LOCK_DURATION_MINUTES = 15

/** 时长选择器的步长（分钟）。 */
const val LOCK_DURATION_STEP_MINUTES = 15

/** 快捷档位：30 分 / 1 小时 / 2 小时 / 4 小时 / 8 小时 / 24 小时。 */
val LOCK_DURATION_PRESETS: List<Int> =
    listOf(30, 60, 120, 240, 480, LockRule.MAX_LOCK_DURATION_MINUTES)

/**
 * Compose `Slider` 的 `steps` 语义是**端点之间**的档位数，
 * 而我们要的是"从 15 到 1440，每 15 分钟一档"，所以端点本身要减掉。
 */
private val LOCK_DURATION_SLIDER_STEPS: Int =
    (LockRule.MAX_LOCK_DURATION_MINUTES - MIN_LOCK_DURATION_MINUTES) / LOCK_DURATION_STEP_MINUTES - 1

/**
 * 把滑块给到的原始值对齐到 15 分钟的整数档。
 *
 * `steps` 已经会吸附，这里再算一次是为了**不依赖控件的吸附实现**：
 * 无论上来的是 137 还是 137.4，落库的都只会是 15 的整数倍。
 */
private fun snapToStep(value: Float): Int {
    val minutes = (value / LOCK_DURATION_STEP_MINUTES).roundToInt() * LOCK_DURATION_STEP_MINUTES
    return minutes.coerceIn(MIN_LOCK_DURATION_MINUTES, LockRule.MAX_LOCK_DURATION_MINUTES)
}

/**
 * 「打卡截止 → 锁机区间」预览，如 `10:00 打卡截止 → 锁机 10:00-12:00`。
 *
 * 用 [LockRule.derivedLockWindows] 算 —— 与调度器读的是同一条推导，
 * 所以预览里写的就是真正会生效的窗口，不会出现"界面说 10:00、实际锁 21:00"。
 */
private fun lockWindowPreview(checkInWindows: List<TimeWindow>, minutes: Int): String? {
    if (checkInWindows.isEmpty() || minutes <= 0) return null
    val rule = LockRule(
        name = "锁机",
        checkInWindows = checkInWindows,
        lockDurationMinutes = minutes
    )
    val deadline = rule.checkInDeadlineMinute ?: return null
    val window = rule.derivedLockWindows.firstOrNull() ?: return null
    return "%02d:%02d 打卡截止 → 锁机 %s".format(deadline / 60, deadline % 60, formatWindow(window))
}

/**
 * 锁机时长选择器：滑块（15..1440 分钟，步长 15）+ 快捷档位 + 实时预览。
 *
 * v2.1 用户要求："不自选锁机时间删除，改为超过打卡时间之后锁机，
 * 锁机的自选时间开始改为自选时长，**最长为 24 小时**"。
 * 习惯编辑页与锁机「详细设置」页共用这一个控件，避免两处口径分叉。
 *
 * @param minutes 当前时长（分钟）；`0` 表示"还没设置"（滑块停在最小档，文案由调用方给）
 * @param label 实时文案，调用方传 `LockRule.lockDurationLabel` 那一套口径
 * @param checkInWindows 只用于预览"打卡截止 → 锁机区间"；空列表则不预览
 * @param onSelect 选中回调，拿到的永远是 15 的整数倍且不超过 1440
 */
@Composable
fun LockDurationPicker(
    minutes: Int,
    label: String,
    checkInWindows: List<TimeWindow>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)

        Slider(
            value = minutes
                .coerceIn(MIN_LOCK_DURATION_MINUTES, LockRule.MAX_LOCK_DURATION_MINUTES)
                .toFloat(),
            onValueChange = { onSelect(snapToStep(it)) },
            valueRange = MIN_LOCK_DURATION_MINUTES.toFloat()..
                LockRule.MAX_LOCK_DURATION_MINUTES.toFloat(),
            steps = LOCK_DURATION_SLIDER_STEPS,
            modifier = Modifier.fillMaxWidth()
        )

        // 快捷档位分两行，避免窄屏上六个芯片挤成一团
        LOCK_DURATION_PRESETS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    FilterChip(
                        selected = minutes == preset,
                        onClick = { onSelect(preset) },
                        label = { Text(formatLockDuration(preset)) }
                    )
                }
            }
        }

        lockWindowPreview(checkInWindows, minutes)?.let { preview ->
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
        }
    }
}
