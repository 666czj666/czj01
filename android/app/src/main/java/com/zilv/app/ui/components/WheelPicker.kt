package com.zilv.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zilv.app.ui.theme.StatusColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * 滚动（滚轮）选择器。
 *
 * 为什么自己写而不是用 `androidx.wheelpicker`：
 * 本项目全程避免为一个纯 UI 控件引入外部依赖（还要考虑国内镜像能否拉到包），
 * 而 `LazyColumn` + `rememberSnapFlingBehavior` 已经能给出同样的手感。
 *
 * 关键实现点：**居中项用"离视口中心最近的可见项"判定**，
 * 而不是假设 `firstVisibleItemIndex` 就是居中项 ——
 * 后者依赖 contentPadding 与吸附位置的巧合，换个可见行数就会错位。
 *
 * @param items 候选项文本
 * @param initialIndex 初始选中项（不参与后续同步，避免与父层形成回写循环）
 * @param onSelectedChange 滚动停止后的选中回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WheelPicker(
    items: List<String>,
    initialIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 44.dp,
    visibleCount: Int = 5,
    textStyle: TextStyle = LocalTextStyle.current
) {
    require(items.isNotEmpty()) { "WheelPicker 的候选项不能为空" }
    require(visibleCount % 2 == 1) { "visibleCount 必须是奇数，实际 $visibleCount" }

    val half = visibleCount / 2
    val safeInitial = initialIndex.coerceIn(0, items.lastIndex)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)
    val flingBehavior = rememberSnapFlingBehavior(state)

    // 选中项用状态驱动，保证高亮跟着滚动实时变化
    var centered by remember { mutableIntStateOf(safeInitial) }

    // 滚动停止后上报选中项
    LaunchedEffect(state, items.size) {
        snapshotFlow { state.isScrollInProgress to centeredIndex(state.layoutInfo) }
            .filter { (scrolling, _) -> !scrolling }
            .map { (_, index) -> index }
            .distinctUntilChanged()
            .collect { index ->
                centered = index
                onSelectedChange(index)
            }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 中央高亮条（画在文字下面）
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp)
                )
        )

        LazyColumn(
            state = state,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = itemHeight * half),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight * visibleCount)
        ) {
            itemsIndexed(items) { index, label ->
                val distance = abs(index - centered)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = textStyle,
                        fontWeight = if (distance == 0) FontWeight.Bold else FontWeight.Normal,
                        // 离中心越远越淡，制造滚轮的纵深感
                        color = when (distance) {
                            0 -> MaterialTheme.colorScheme.onSurface
                            1 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            else -> StatusColors.Idle.copy(alpha = 0.35f)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 离视口中心最近的可见项的下标。
 *
 * 用几何位置判定而不是 `firstVisibleItemIndex`，这样换可见行数、
 * 换 contentPadding 都不会算错。
 */
private fun centeredIndex(info: LazyListLayoutInfo): Int {
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0

    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return visible.minByOrNull { item ->
        abs(item.offset + item.size / 2 - viewportCenter)
    }?.index ?: 0
}

/**
 * 滚轮式时间选择对话框（时 + 分，**分钟间隔 1 分钟**）。
 *
 * @param initialMinuteOfDay 初始时间（当天第几分钟）
 * @param onConfirm 回调，参数同样是当天第几分钟
 */
@Composable
fun WheelTimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onConfirm: (minuteOfDay: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val safe = initialMinuteOfDay.coerceIn(0, 24 * 60 - 1)

    val hours = remember { (0..23).map { "%02d".format(it) } }
    // 分钟：0..59，**每个选项间隔 1 分钟**
    val minutes = remember { (0..59).map { "%02d".format(it) } }

    var hour by remember { mutableIntStateOf(safe / 60) }
    var minute by remember { mutableIntStateOf(safe % 60) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WheelPicker(
                    items = hours,
                    initialIndex = hour,
                    onSelectedChange = { hour = it },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    ":",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                WheelPicker(
                    items = minutes,
                    initialIndex = minute,
                    onSelectedChange = { minute = it },
                    modifier = Modifier.weight(1f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hour * 60 + minute) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
