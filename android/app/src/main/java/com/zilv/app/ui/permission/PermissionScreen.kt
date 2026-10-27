package com.zilv.app.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.permission.LocationPermissionFlow
import com.zilv.app.domain.permission.PermissionStep
import com.zilv.app.permission.PermissionChecker
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import com.zilv.app.ui.components.InterceptStatusSection
import com.zilv.app.ui.theme.Motion

/**
 * 权限向导 / 权限体检（PRD §4.1、§4.7）。
 *
 * 同一屏承担两个角色：
 *  - 首次启动时作为分步向导
 *  - 平时作为"权限体检"页，一屏看完 9 项状态（要求 3 秒内看完）
 *
 * 从系统设置页返回时自动复检（[DisposableEffect] + onResume 语义）。
 *
 * ## v2.14：修「前台定位按了无反应」
 *
 * 9 项里唯一一项是**运行时权限**的就是定位 —— 它没有"直接授予"的系统设置页
 * （`PermissionChecker.settingsIntentFor(LOCATION)` 返回 null）。原先所有非手动项
 * 都走 `settingsIntent(step)` + `if (intent != null) startActivity`，null 被静默吞掉，
 * 于是**只有这一项点了什么都不发生**。
 *
 * 现在定位单独走 `ActivityResultContracts.RequestMultiplePermissions`
 * （精确 + 近似一起请求，Android 12+ 才会给出"精确"选项），
 * 请求结果由纯函数 [LocationPermissionFlow.afterRequest] 裁决：
 *  - 已授予 → `refresh()`，那一行立刻从"未授予"变成"已授予"
 *  - 普通拒绝 → `refresh()`，用户可再点一次
 *  - 永久拒绝（不再询问）→ 退化成**应用详情设置页**
 *
 * 交互形式与同页其它项保持一致（同样是"未授予时一个按钮"，按钮文案不变），
 * 只是这个按钮的动作从"跳设置"变成"就地请求"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    onBack: () -> Unit,
    viewModel: PermissionViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从二级页面返回时重新载入 —— ViewModel 被导航栈保留，
    // init 不会重跑，不刷新的话列表会停在旧状态（真实 bug 的根因）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    val context = LocalContext.current

    // 回到本页时重新检测：用户可能刚在系统设置里改动过
    DisposableEffect(Unit) {
        viewModel.refresh()
        onDispose { }
    }

    // 已经判定过"永久拒绝"：下一次点击直接跳应用详情设置页。
    // 用 rememberSaveable 是为了旋转屏幕后不会退回到"再请求一次 → 系统静默拒绝"
    // 那种看起来仍然"按了没反应"的循环。
    var locationPermanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // shouldShowRequestPermissionRationale 是"还能不能弹框"的判据，
        // 必须在**请求之后**读（请求之前 false 只表示"还没问过"）。
        val activity = context.findActivity()
        val fineRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } ?: false
        val coarseRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } ?: false

        when (
            LocationPermissionFlow.afterRequest(
                granted = LocationPermissionFlow.isGranted(result),
                fineRationale = fineRationale,
                coarseRationale = coarseRationale,
                previouslyPermanentlyDenied = locationPermanentlyDenied
            )
        ) {
            LocationPermissionFlow.AfterRequest.OpenAppDetails -> {
                locationPermanentlyDenied = true
                runCatching { context.startActivity(viewModel.appDetailsIntent()) }
            }
            // 已授予与普通拒绝都只需要重新检测：授权回来那一行要立刻变成"已授予"
            LocationPermissionFlow.AfterRequest.RefreshOnly,
            LocationPermissionFlow.AfterRequest.AskAgain -> Unit
        }
        viewModel.refresh()
    }

    /** 定位这一项的动作：能弹框就请求，已经永久拒绝就直接去应用详情设置页。 */
    val requestLocation: () -> Unit = {
        if (locationPermanentlyDenied) {
            runCatching { context.startActivity(viewModel.appDetailsIntent()) }
        } else {
            locationLauncher.launch(LocationPermissionFlow.PERMISSIONS.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限体检") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                SummaryCard(
                    ready = state.ready,
                    progress = state.progress,
                    missingRequired = state.summary.missingRequired.size
                )
            }

            items(PermissionStep.ordered, key = { it.name }) { step ->
                StepCard(
                    step = step,
                    granted = state.summary.isGranted(step),
                    manual = step in PermissionChecker.MANUAL_STEPS,
                    onRequestPermission = requestLocation,
                    onOpenSettings = {
                        val intent = viewModel.settingsIntent(step)
                        if (intent != null) runCatching { context.startActivity(intent) }
                    },
                    onConfirmManual = { viewModel.confirmManually(step) },
                    onRevokeManual = { viewModel.revokeManual(step) }
                )
            }

            item {
                Spacer(Modifier.height(18.dp))
                // 运行状态并入这里（原「拦截自检」独立页已按用户要求从设置移除）
                InterceptStatusSection()
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * 拿到承载 Context 的 Activity（`LocalContext` 在部分场景下是 ContextWrapper）。
 *
 * `shouldShowRequestPermissionRationale` 只接受 Activity —— 拿它来兜底，
 * 拿不到时按"不能弹框"处理（`afterRequest` 那一条分支要求两个 rationale 都为 false，
 * 单看这一个值不会误判成永久拒绝，因为还要与 `previouslyPermanentlyDenied` 分开判断）。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SummaryCard(ready: Boolean, progress: Float, missingRequired: Int) {
    val bg = if (ready) StatusColors.Unlocked else StatusColors.Warn
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = if (ready) "✅ 配置完成" else "⚠️ 还有 $missingRequired 项必需权限未开启",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(10.dp))
            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                animationSpec = Motion.enterSpec(Motion.SLOW),
                label = "perm-progress"
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "必需项完成度 ${(progress * 100).roundToInt()}%（可跳过项不计入）",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
    }
}

@Composable
private fun StepCard(
    step: PermissionStep,
    granted: Boolean,
    manual: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onConfirmManual: () -> Unit,
    onRevokeManual: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (granted) StatusColors.Unlocked else StatusColors.Idle
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                if (!step.required) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "可跳过",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusColors.Idle
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(step.why, style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)

            if (!granted) {
                Spacer(Modifier.height(10.dp))
                when {
                    manual -> Button(onClick = onConfirmManual) { Text("我已完成") }
                    // 运行时权限（目前只有「前台定位」）没有"直接授予"的系统页，
                    // 必须就地发起请求 —— 否则点了会毫无反应（v2.14 修的 bug）。
                    step == PermissionStep.LOCATION ->
                        Button(onClick = onRequestPermission) { Text("去开启") }

                    else -> Button(onClick = onOpenSettings) { Text("去开启") }
                }
            } else if (manual) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRevokeManual) { Text("撤销确认") }
            }
        }
    }
}