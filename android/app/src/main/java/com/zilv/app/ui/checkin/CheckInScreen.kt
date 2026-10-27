package com.zilv.app.ui.checkin

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.spot.CheckInValidator
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * 打卡页（PRD §4.3）。
 *
 * 状态机 6 态：定位中 / 需要权限 / 精度不达标 / 距离过远 / 可打卡 / 成功。
 * 精度不达标时**不计失败**，只累加计数；连续 3 次后出现室内降级通道。
 *
 * v2.2：定位失败（`currentLocation()` 返回 null）时明说**「室内定位失败」**，
 * 并给一个「改用无条件打卡」的快捷入口（把该习惯的打卡条件改成「无」）。
 *
 * @param habitId 这次打卡挂在哪条习惯上；`-1` 表示不是从习惯进来的
 *        （例如从遮挡层 / 首页的锁机卡片直接进打卡页）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(
    ruleId: Long,
    spotId: Long,
    onBack: () -> Unit,
    habitId: Long = CheckInUiState.NO_HABIT,
    viewModel: CheckInViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(ruleId, spotId, habitId) { viewModel.start(ruleId, spotId, habitId) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    // ---------------- 「改用无条件打卡」对话框（v2.12，用户第 4 条） ----------------
    //
    // 用户原话：「达到 3 次 → **弹出对话框**（不是页面里的卡片），让用户选择
    // 『改用无条件打卡』或『继续重试』」。
    //
    // 为什么必须是对话框（这也是本次修 Bug 1 的关键一环）：
    // 原先那块卡片是**页面末尾的一个 item**，而整页不可滚动 ——
    // 状态徽标 + 长消息 + 信息卡 + 两个按钮已经把一屏占满，
    // 卡片被挤到屏幕外，用户看不到也滚不到，症状就是"失败三次了什么都没弹"。
    // 对话框不占布局流，永远不会被挤出去。
    if (state.showUnconditionalDialog) {
        UnconditionalOfferDialog(
            onSwitch = viewModel::useUnconditionalCheckIn,
            onRetry = viewModel::dismissUnconditionalOffer
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("到${state.spot?.name ?: "打卡点"}打卡") },
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
                // ⚠️ 必须可滚动：这一页的内容（状态徽标 + 消息 + 信息卡 + 两个按钮 +
                // 降级通道卡片）在窄屏上早就超过一屏了。不可滚动时排在后面的东西
                // 会被直接裁掉 —— 那正是 Bug 1「无条件打卡入口看不见」的成因之一。
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))

            // 状态徽标
            StatusBanner(phase = state.phase)

            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            InfoCard(
                distanceM = state.distanceM,
                accuracyM = state.location?.accuracyM,
                thresholdM = state.spot?.minAccuracyM,
                radiusM = state.spot?.radiusM,
                directionHint = state.directionHint
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = viewModel::checkIn,
                enabled = state.canCheckIn,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.canCheckIn) "立即打卡" else "尚未到达打卡范围")
            }

            OutlinedButton(
                onClick = viewModel::refreshLocation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.phase == CheckInPhase.Locating) "正在定位…" else "重新定位")
            }

            if (state.offerFallback) {
                FallbackCard(
                    failures = state.consecutiveAccuracyFailures,
                    onFallback = { viewModel.abnormalUnlock() }
                )
            }

            // ⚠️ v2.12：这里原来还有一块「改用无条件打卡」的**内联卡片**
            // （以及没到门槛时的「再失败 N 次…」提示卡）—— 两块都删掉了：
            //  - 到了门槛 → 改成上面那个**对话框**（用户明确要求）
            //  - 没到门槛 → 用户要求"把无条件打卡的提示隐藏"，一个字都不显示
            // 所以 `UnconditionalCard` / `UnconditionalPendingCard` 连同它们的
            // 文案一起从这个文件里消失，规则本身仍在 ViewModel 里（有单测）。

            if (state.switchedToUnconditional) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = StatusColors.Unlocked.copy(alpha = 0.12f)
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "✅ 已改为无条件打卡",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "回去点一下就能打卡（室内也可以）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.Idle
                        )
                        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                            Text("返回")
                        }
                    }
                }
            }

            if (state.consecutiveAccuracyFailures > 0 && !state.offerFallback) {
                Text(
                    // v2.12：这里**只**说明"精度不达标不计失败、可以反复重试"这一件事。
                    // 原来它还捎带一句"再失败 N 次可以改用无条件打卡" ——
                    // 用户要求把那个提示隐藏，那句话已删除。
                    text = "精度不达标不计失败，可反复重试（已连续 " +
                        state.consecutiveAccuracyFailures + " 次）",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 「改用无条件打卡」询问对话框（v2.12）。
 *
 * 两个出口写得很直白，因为它们**不是一回事**（历史上把两者混起来正是用户抱怨的点）：
 *  - 「继续重试」→ 什么都不改，就是把这次询问收起来（用户接着按「重新定位」）
 *  - 「改用无条件打卡」→ 把这条习惯的打卡条件改成「无」，**永久**放宽，
 *    之后在室内点一下就能打卡
 */
@Composable
private fun UnconditionalOfferDialog(
    onSwitch: () -> Unit,
    onRetry: () -> Unit
) {
    AlertDialog(
        // 点空白处 = 继续重试（不做任何破坏性改动）
        onDismissRequest = onRetry,
        title = { Text("一直定位不到？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "已经连续 " + CheckInValidator.DEFAULT_FALLBACK_THRESHOLD +
                        " 次拿不到定位了。"
                )
                Text(
                    "可以把这条习惯的打卡条件改成「无」：以后什么时候都能打卡" +
                        "（室内也能用），点一下就算完成。改成这样等于放弃了地点校验。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "与下面的「拍照存证 + 临时解锁」不是一回事：" +
                        "临时解锁只解这一次锁机，且记为异常解锁；" +
                        "改用无条件打卡是永久放宽这条习惯的打卡条件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSwitch) { Text("改用无条件打卡") }
        },
        dismissButton = {
            TextButton(onClick = onRetry) { Text("继续重试") }
        }
    )
}

@Composable
private fun StatusBanner(phase: CheckInPhase) {
    val (emoji, label, color) = when (phase) {
        CheckInPhase.Locating -> Triple("📡", "定位中", StatusColors.Idle)
        CheckInPhase.NeedPermission -> Triple("🔑", "需要权限", StatusColors.Warn)
        CheckInPhase.AccuracyTooLow -> Triple("📶", "信号弱", StatusColors.Warn)
        CheckInPhase.TooFar -> Triple("🚶", "还没到", StatusColors.Warn)
        CheckInPhase.Ready -> Triple("📍", "已到达", StatusColors.Unlocked)
        CheckInPhase.Success -> Triple("✅", "打卡成功", StatusColors.Unlocked)
        CheckInPhase.Error -> Triple("⚠️", "出错了", MaterialTheme.colorScheme.error)
        CheckInPhase.Idle -> Triple("⏸", "准备中", StatusColors.Idle)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text("$emoji  $label", color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoCard(
    distanceM: Double?,
    accuracyM: Float?,
    thresholdM: Float?,
    radiusM: Int?,
    directionHint: String?
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InfoRow("距打卡点", distanceM?.let { "${it.roundToInt()} 米" } ?: "—")
            InfoRow("打卡半径", radiusM?.let { "$it 米" } ?: "—")
            InfoRow(
                "定位精度",
                accuracyM?.let { acc ->
                    val ok = thresholdM != null && acc <= thresholdM
                    "${acc.roundToInt()} 米  ${if (ok) "✓ 达标" else "✗ 需 ≤ ${thresholdM?.roundToInt()} 米"}"
                } ?: "—"
            )
            if (directionHint != null) {
                InfoRow("方向", directionHint)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = StatusColors.Idle)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FallbackCard(failures: Int, onFallback: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = StatusColors.Warn.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 标题里带着"已连续 N 次"这个**实时计数**，必须留在屏幕上
                    "定位一直失败？（已连续 $failures 次）",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(6.dp))
                // v2.8：原来这段"会被记为异常解锁、不计入正常打卡"的说明收进「?」
                HelpHint(
                    text = "室内常年精度不达标。可以拍照存证后临时解锁 —— " +
                        "这一次会被记为「异常解锁」，在报告里单独列出，不计入正常打卡。"
                )
            }
            Button(onClick = onFallback) { Text("拍照存证 + 临时解锁 5 分钟") }
        }
    }
}

// ⚠️ v2.12：这里原来有两块「改用无条件打卡」的内联卡片
// （`UnconditionalCard` 达到门槛时给按钮 / `UnconditionalPendingCard` 没到门槛时
//  给"再失败 N 次…"的提示），两块都已删除：
//  - 达到门槛 → `UnconditionalOfferDialog`（用户明确要求"弹出对话框，不是页面里的卡片"）
//  - 没到门槛 → 一个字都不显示（用户要求"把无条件打卡的提示隐藏"）
//
// 删掉它们同时修掉了一个真实缺陷：内联卡片是页面末尾的一个 item，
// 而整页原先**不可滚动**，在手机上会被裁到屏幕外 —— 那是 Bug 1
// "失败三次了什么都没弹"的成因之一（规则本身没问题，见 `CheckInViewModel`）。

