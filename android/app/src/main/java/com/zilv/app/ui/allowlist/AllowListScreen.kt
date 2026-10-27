package com.zilv.app.ui.allowlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.data.mapper.AllowApp
import com.zilv.app.intercept.SystemPackageDetector
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import androidx.compose.foundation.layout.width
import com.zilv.app.ui.components.AppIcon
import com.zilv.app.ui.components.TitleWithHelp

/**
 * 白名单管理（PRD T4.3 / §3.4）。
 *
 * 内置强制项只读展示，并明确写出"不可移除"的原因 ——
 * 这是用户理解"为什么设置页关不掉"的唯一途径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllowListScreen(
    onBack: () -> Unit,
    viewModel: AllowListViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从二级页面返回时重新载入 —— ViewModel 被导航栈保留，
    // init 不会重跑，不刷新的话列表会停在旧状态（真实 bug 的根因）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    var keyword by remember { mutableStateOf("") }

    // v2.14：锁机开始时操作按钮要立刻变灰（用户要求"点击不能有反应"）。
    // 每秒只读一次锁机状态（refreshLockState），不重扫已安装应用 ——
    // 与设置页 / InterceptStatusSection 的 1 秒 tick 是同一做法。
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshLockState()
            kotlinx.coroutines.delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("白名单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // v2.8：这一页顶部原来有一整句"锁机期间只有白名单里的应用能打开。
                // 桌面与系统设置永远可用。" —— 已并进下面"我的白名单"标题旁的「?」，
                // 所以这里连占位的 item 都不留（原来它只是一个 Spacer）

                // ---------------- 我的白名单 ----------------
                item { SectionHeader("我的白名单（${state.userApps.size}）", help = WHITELIST_HELP) }
                if (state.locked) {
                    // v2.14：锁机期间所有操作置灰 + 顶部一句话说明原因。
                    // 仓库层仍会抛 RuleLockedException（守卫没删），这里只是让用户
                    // **在点之前**就看出不能改。
                    item {
                        Text(
                            LOCKED_HINT,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.Idle
                        )
                    }
                }
                if (state.userApps.isEmpty()) {
                    item { EmptyRow("还没有添加任何应用，下面可以添加") }
                }
                items(state.userApps, key = { "u-${it.packageName}" }) { app ->
                    AllowRow(
                        app = app,
                        removable = true,
                        actionsEnabled = !state.locked,
                        onRemove = { viewModel.removeApp(app.packageName) }
                    )
                }

                // ---------------- 可添加 ----------------
                item { SectionHeader("可添加的应用") }
                item {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索应用名或包名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                val filtered = state.candidates.filter {
                    keyword.isBlank() ||
                        it.label.contains(keyword, ignoreCase = true) ||
                        it.packageName.contains(keyword, ignoreCase = true)
                }
                if (filtered.isEmpty()) {
                    item { EmptyRow(if (state.loading) "正在读取已安装应用…" else "没有匹配的应用") }
                }
                items(filtered.take(200), key = { "c-${it.packageName}" }) { app ->
                    CandidateRow(
                        app = app,
                        actionsEnabled = !state.locked,
                        onAdd = { viewModel.addApp(app.packageName, app.label) }
                    )
                }

                // ---------------- 内置强制项 ----------------
                item {
                    SectionHeader(
                        "内置强制白名单（${state.builtin.size}）· 不可移除",
                        help = BUILTIN_HELP
                    )
                }
                item {
                    // v2.8：原来那句"这些是系统必需组件，由运行时探测得到。任何一项
                    // 被拦都可能导致手机无法使用，因此不允许移除。"收进标题旁的「?」；
                    // 这里只留"不可以动它"这个结论（用户当下要据此行动的信息）
                    Text(
                        "系统必需组件，不允许移除",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle
                    )
                }
                items(state.builtin, key = { "b-${it.packageName}" }) { app ->
                    AllowRow(
                        app = app,
                        removable = false,
                        // 内置项本来就不可移除（只显示"强制"），是否锁机都不影响它
                        actionsEnabled = !state.locked,
                        onRemove = { viewModel.removeApp(app.packageName) }
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
            }

            state.message?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(2500)
                    viewModel.clearMessage()
                }
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter)) { Text(msg) }
            }
        }
    }
}

/**
 * 分组标题，可在标题后挂一个「?」。
 *
 * v2.8：本页两段说明（"锁机期间只有白名单里的应用能打开…"、
 * "这些是系统必需组件…不允许移除。"）收进这里。
 * **原文案一字未删**，只是从"一直占屏幕"改成"点一下才出现"。
 */
@Composable
private fun SectionHeader(text: String, help: String? = null) {
    TitleWithHelp(text = text, help = help, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}

private const val WHITELIST_HELP =
    "锁机期间只有白名单里的应用能打开。桌面与系统设置永远可用。"

/**
 * 锁机期间本页顶部的提示（v2.14）。
 *
 * 为什么必须写出来：所有操作按钮都已置灰，如果只说"不能操作"，用户会以为按钮坏了。
 * 这句给出**原因 + 出路**（等锁机结束，或先打卡解锁）。
 */
private const val LOCKED_HINT =
    "锁机进行中：白名单暂时不可修改（否则锁机时删掉限制就等于直接解锁）。" +
        "等本次锁机结束，或先在「今天」页打卡解锁。"

private const val BUILTIN_HELP =
    "这些是系统必需组件，由运行时探测得到。" +
        "任何一项被拦都可能导致手机无法使用，因此不允许移除。"

@Composable
private fun EmptyRow(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
    }
}

@Composable
private fun AllowRow(
    app: AllowApp,
    removable: Boolean,
    actionsEnabled: Boolean,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标（带缓存的异步加载，失败时回退首字母占位）
            AppIcon(
                packageName = app.packageName,
                fallbackLabel = app.label,
                size = 40.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
            if (removable) {
                // 锁机期间 enabled=false：按钮变灰、点击无任何反应
                // （Material 3 的 Button 在 disabled 状态连水波纹都不会有）
                TextButton(onClick = onRemove, enabled = actionsEnabled) { Text("移除") }
            } else {
                Text("强制", style = MaterialTheme.typography.labelSmall, color = StatusColors.Idle)
            }
        }
    }
}

@Composable
private fun CandidateRow(
    app: SystemPackageDetector.Detected,
    actionsEnabled: Boolean,
    onAdd: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 应用图标（带缓存的异步加载，失败时回退首字母占位）
            AppIcon(
                packageName = app.packageName,
                fallbackLabel = app.label,
                size = 40.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
            }
            // 锁机期间置灰 + 点击无反应（与上面的「移除」同一口径）
            TextButton(onClick = onAdd, enabled = actionsEnabled) { Text("加入") }
        }
    }
}