package com.zilv.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zilv.app.intercept.InterceptDiagnostics
import com.zilv.app.ui.theme.Motion
import com.zilv.app.ui.theme.StatusColors
import kotlinx.coroutines.delay

/**
 * 「锁机运行状态」区块。
 *
 * 原先是一个独立的设置项「拦截自检」，v1.1 按用户要求从设置页移除 ——
 * 它属于诊断信息，不是使用者日常要点的功能。
 *
 * 但**能力保留了下来**：并进「权限体检」页的底部，默认收起。
 * 理由很实际：拦截是否生效要经过四个彼此独立的环节
 * （无障碍服务连接 → 收到窗口事件 → 状态判为锁机 → 执行拦截动作），
 * 任何一环断掉，用户看到的现象都是"没拦不住"，从现象无法区分是哪一环。
 * 没有这个区块，远程排查就只能靠猜。
 *
 * @param defaultExpanded 是否默认展开（默认收起，避免干扰权限体检的主线）
 */
@Composable
fun InterceptStatusSection(
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }

    // 每秒重读一次快照：这些值由无障碍服务在其他线程更新
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val rows = remember(tick) { InterceptDiagnostics.snapshot() }
    val verdict = remember(tick) { InterceptDiagnostics.verdict() }
    val blocked = remember(tick) { InterceptDiagnostics.blockedCount }
    // v2.14：拦到过东西通常说明链路正常，但**若最近拦到的是关键系统组件**
    // （系统界面 / 截屏 / 智能助理），那就是缺陷 —— 必须把结论显示出来，
    // 不能用"✅ 拦截链路正常"盖过去（用户正是拿这一行来反馈问题的）。
    val criticalBlocked = remember(tick) { InterceptDiagnostics.lastBlockedWasCriticalComponent }
    val healthy = blocked > 0L && !criticalBlocked

    // v2.15：「复制诊断信息」——
    // 之前的反馈方式只有"截图这一页"，而这次 bug 的关键信息恰恰是**包名**
    // （一个「系统界面组件」的显示名背后可能是一堆不同的包，只有包名能定位是哪一个），
    // 长英文字符串在聊天软件里一压缩就糊。复制成纯文本就没有这个问题。
    val context = LocalContext.current

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.enterSpec(),
        label = "diag-chevron"
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (healthy) "✅" else "⚠️",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "锁机运行状态",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        if (healthy) "拦截链路正常" else verdict,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle
                    )
                }
                Icon(
                    if (chevronRotation > 90f) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = StatusColors.Idle,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(0f)
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(Motion.enterSpec()) + expandVertically(Motion.enterSpec()),
            exit = fadeOut(Motion.exitSpec()) + shrinkVertically(Motion.exitSpec())
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { (label, value) ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = StatusColors.Idle
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                // v2.15：一次把现场发出去 —— 复制的是**整段自检快照**（含包名与各集合大小）
                OutlinedButton(
                    onClick = { copyDiagnostics(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("复制诊断信息")
                }
                Text(
                    "点上面的按钮会把这一页的内容（含最近被拦的「包名」与各集合大小）" +
                        "整段复制到剪贴板，直接粘贴发我就行，比截图更准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "数据只反映本次运行（重启 App 会清零）。若刚改动过无障碍设置，" +
                        "请到系统设置里关闭再重新开启一次服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
        }
    }
}

/**
 * 把自检快照复制到剪贴板并给出提示（v2.15）。
 *
 * ## 为什么失败也要 Toast
 *
 * 剪贴板在个别 ROM 上会被安全策略拒绝（后台读剪贴板、或系统应用劫持）。
 * 静默失败最糟：用户以为复制成功了，发过来的是一段空消息。
 * 所以两种结果都给提示，并告诉用户退路（截图）。
 *
 * ## 版本号
 *
 * 这里从 `PackageManager` 现读 `versionName` 与 `lastUpdateTime`（与设置页一致，不硬编码）——
 * 排查时第一件事就是确认用户装的是哪一版：同一个 `versionName`（本项目常见）
 * 之下可能连出好几轮修复，只有**覆盖安装时间**能证明"新的那次真的装上了"。
 *
 * @return 是否复制成功（供测试/调试观察；UI 只关心 Toast）
 */
private fun copyDiagnostics(context: Context): Boolean {
    // 版本号 + 本次安装时间：两者一起才能确认"用户装的到底是哪一次构建"
    // （同一个 versionName 内可能连出好几轮修复，这是本项目的常态）。
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()

    val text = InterceptDiagnostics.diagnosticReport(
        appVersion = packageInfo?.versionName.orEmpty(),
        installedAtMillis = packageInfo?.lastUpdateTime ?: 0L
    )

    val copied = runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
    }.isSuccess

    runCatching {
        Toast.makeText(
            context,
            if (copied) "已复制，直接粘贴发送即可" else "复制失败，请改发这一页的截图",
            Toast.LENGTH_SHORT
        ).show()
    }
    return copied
}

/** 剪贴板条目的标签（部分系统会在粘贴提示里显示它）。 */
private const val CLIP_LABEL = "自律 · 拦截诊断"
