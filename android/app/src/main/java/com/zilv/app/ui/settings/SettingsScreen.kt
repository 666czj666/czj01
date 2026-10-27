package com.zilv.app.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.permission.PermissionViewModel
import com.zilv.app.ui.theme.Motion
import com.zilv.app.ui.theme.StatusColors
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 设置页（PRD §4.5）。
 *
 * ⚠️ 这里**只放用户真正会用到的功能入口**。v1.1 清理掉了五类内容：
 *
 * | 删掉的 | 原因 |
 * | --- | --- |
 * | 「权限体检」重复行 | 上面的告警横幅点进去就是同一页 |
 * | 「锁机规则」 | 已经是底部导航的独立标签 |
 * | 「通知脱敏」 | 写着"M6 接入"但点了没反应的死占位 |
 * | 「拦截自检」独立入口 | 内容并入了「权限体检」页底部（见该页说明） |
 * | 底部的「开发依据：PRD.md」 | 开发信息，与使用者无关 |
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onManageHabits: () -> Unit,
    onPermissions: () -> Unit,
    onAllowList: () -> Unit,
    onManageSpots: () -> Unit,
    onBackup: () -> Unit,
    permissionViewModel: PermissionViewModel = koinViewModel(),
    settingsViewModel: SettingsViewModel = koinViewModel()
) {
    val permState by permissionViewModel.uiState.collectAsStateWithLifecycle()
    val locked by settingsViewModel.locked.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 进场动画：整块内容淡入并轻微上移，避免页面"啪"地出现
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    // v2.14：锁机状态会变（按时间窗口自动开始/结束）。
    // 停在设置页时每秒复检一次 —— 与 InterceptStatusSection 每秒重读快照同一做法。
    // 锁机开始 → 「白名单」入口立刻变灰不可点；锁机结束 → 立刻恢复。
    LaunchedEffect(Unit) {
        while (true) {
            settingsViewModel.refreshLockState()
            delay(1_000)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            AnimatedVisibility(
                visible = entered,
                enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.enter)) +
                    slideInVertically(tween(Motion.MEDIUM, easing = Motion.enter)) { it / 6 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 权限体检放最上面：它是"拦截功能是否真的在工作"的唯一入口
                    PermissionBanner(
                        ready = permState.ready,
                        missing = permState.summary.missingRequired.size,
                        onClick = onPermissions
                    )

                    SettingsRow(
                        icon = Icons.Filled.Lock,
                        title = "白名单",
                        // 用户原话：「锁机时设置白名单按钮改成灰色，点击不能有反应。」
                        // 置灰**只是界面层**；仓库层的 RuleLockedException 守卫仍然保留
                        // （置灰挡不住其它调用路径）。
                        subtitle = if (locked) "锁机中不可修改" else "锁机期间可用的应用",
                        // v2.8：原来那句"只有列表里的应用能用，其余在锁机时被拦下"
                        // 收进「?」—— 它是解释，不是这个入口叫什么
                        help = if (locked) LOCKED_ALLOWLIST_HELP else ALLOWLIST_HELP,
                        enabled = !locked,
                        onClick = onAllowList
                    )
                    SettingsRow(
                        icon = Icons.Filled.Checklist,
                        title = "习惯管理",
                        subtitle = "添加、编辑、归档或删除习惯",
                        onClick = onManageHabits
                    )
                    SettingsRow(
                        icon = Icons.Filled.Place,
                        title = "打卡点",
                        subtitle = "站在目标位置一键创建，不需要地图",
                        onClick = onManageSpots
                    )
                    SettingsRow(
                        icon = Icons.Filled.Backup,
                        title = "数据备份与导出",
                        subtitle = "导出或恢复习惯、规则、打卡点与白名单",
                        onClick = onBackup
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "自律 v${appVersionName(context)}",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val ALLOWLIST_HELP =
    "只有列表里的应用能用，其余在锁机时被拦下。" +
        "桌面与系统设置永远可用。"

/**
 * 锁机期间「白名单」入口的解释（v2.14）。
 *
 * 入口已经置灰不可点，所以这句是**唯一**能告诉用户"为什么灰、什么时候能用"的地方。
 * 刻意说清"等本次锁机结束或先打卡解锁"，否则用户只会觉得这个按钮坏了。
 */
private const val LOCKED_ALLOWLIST_HELP =
    "锁机期间不能改白名单 —— 否则锁机时删掉限制就等于直接解锁。" +
        "等本次锁机结束，或先在「今天」页打卡解锁，再回来修改。"

/**
 * 从系统读取真实版本号。
 *
 * 刻意不硬编码也不依赖 BuildConfig —— 硬编码会在升版本时忘记同步，
 * 而这个数字恰恰是排查"用户装的是哪一版"时唯一可信的依据。
 */
private fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty().ifBlank { "?" }

@Composable
private fun PermissionBanner(ready: Boolean, missing: Int, onClick: () -> Unit) {
    val bg = if (ready) StatusColors.Unlocked else StatusColors.Warn
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (ready) "✅" else "⚠️",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (ready) "权限配置完成" else "有 $missing 项必需权限未开启",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = if (ready) "拦截功能可正常工作" else "点击体检并修复",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color.White
            )
        }
    }
}

/**
 * 设置页的一个入口行。
 *
 * @param help 非 `null` 时在标题后面挂一个小「?」，点开才显示这句解释。
 *        v2.8 起，**成句的解释**走这里；`subtitle` 只留"这个入口是什么"的短句，
 *        否则每一行都会变成两行字，设置页就不再是一屏能扫完的列表了。
 * @param enabled 为 `false` 时整行**置灰且不可点击**（v2.14：锁机期间的「白名单」）。
 *        用 `clickable(enabled = false)` 而不是"点了不发请求"：
 *        前者连水波纹都没有，用户按下去**完全没有反馈**，正是用户要的"点击不能有反应"。
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    help: String? = null,
    enabled: Boolean = true
) {
    // 置灰用 onSurface 的 38% 透明度（Material 3 的 disabled 口径），
    // 不做"半透明整行" —— 那样文字与图标一起糊掉，反而看不出是个入口。
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    contentColor
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
                    if (help != null) {
                        Spacer(Modifier.width(6.dp))
                        HelpHint(text = help)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) StatusColors.Idle else contentColor
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = if (enabled) StatusColors.Idle else contentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
