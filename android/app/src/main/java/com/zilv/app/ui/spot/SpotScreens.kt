package com.zilv.app.ui.spot

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.model.Spot
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel
import com.zilv.app.ui.theme.Motion

/** 打卡点列表（PRD §4.6 T5.2）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotListScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onNew: () -> Unit,
    viewModel: SpotListViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从二级页面返回时重新载入 —— ViewModel 被导航栈保留，
    // init 不会重跑，不刷新的话列表会停在旧状态（真实 bug 的根因）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // 删除失败之类的一次性提示（仓库层抛异常时 ViewModel 会写进来）
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("打卡点") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "新建打卡点")
            }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // v2.8：原来那句"创建打卡点不需要地图：站在目标位置按
                    // 「用当前位置创建」即可。"收进「?」—— 它是操作说明，
                    // 而按钮本身就叫「用当前位置创建」，路径没有丢
                    Text("创建打卡点", style = MaterialTheme.typography.titleSmall)
                    HelpHint(
                        text = "创建打卡点不需要地图：站在目标位置按「用当前位置创建」即可。"
                    )
                }
            }

            if (state.spots.isEmpty() && !state.loading) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("还没有打卡点，点右下角新建", color = StatusColors.Idle)
                    }
                }
            }

            items(state.spots, key = { it.id }) { spot ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem(fadeInSpec = Motion.enterSpec(), fadeOutSpec = Motion.exitSpec(), placementSpec = Motion.listSpec())
                        .clickable { onEdit(spot.id) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(spot.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "半径 ${spot.radiusM} 米 · 精度门槛 ${spot.minAccuracyM.toInt()} 米",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                            Text(
                                "%.4f, %.4f".format(spot.lat, spot.lng),
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                        TextButton(onClick = { viewModel.delete(spot.id) }) { Text("删除") }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }
}

/**
 * 打卡点详情 / 编辑（PRD §4.6 T5.3）。
 *
 * ## v2.12：已创建好的打卡点在这里**只能看，不能改**
 *
 * 用户要求：「打卡点创建好之后不能再修改。新建打卡点照旧；创建完成后进入编辑页
 * 只能看，不能改（名称、坐标、半径等字段都只读）」。
 *
 * 所以 `spotId != 0` 时整页进入只读态：所有输入框 `enabled = false`、
 * 半径/精度档位与「用当前位置创建」按钮都不出现、「保存」按钮也不出现，
 * 顶部给出一句「为什么不能改 + 怎么办」。想调整就**删掉重建** ——
 * 删除入口留在这一页（以及打卡点列表里），因为"建错了清不掉"比"建错了不能改"更糟。
 *
 * ⚠️ 界面只是第一道。真正的拒绝在 `SpotRepository.save`（抛
 * `SpotImmutableException`），以及 `SpotEditViewModel` 里每个 setter 的
 * 只读短路 —— 后两者都能被纯 JVM 单测覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotEditScreen(
    spotId: Long,
    onBack: () -> Unit,
    viewModel: SpotEditViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(spotId) { viewModel.load(spotId) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    // 只读态下删除要再确认一次：这是这一页上唯一还能改变数据的东西
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个打卡点？") },
            text = {
                Text(
                    "「${state.name}」会被删除。\n" +
                        "已经写在记录里的历史打卡不会被抹掉。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            spotId == 0L -> "新建打卡点"
                            // 只读时标题就说清"这是详情，不是编辑器"
                            else -> "打卡点详情"
                        }
                    )
                },
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

            // ---- 只读态：先说清"为什么改不了、怎么办" ----
            if (state.readOnly) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = StatusColors.Idle.copy(alpha = 0.10f)
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "这里只能查看",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            SpotEditViewModel.READ_ONLY_HINT,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.Idle
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.name,
                // 只读态下连回调都不给：界面不可能改出"看起来改了其实没改"的假象
                onValueChange = if (state.readOnly) ({}) else viewModel::setName,
                enabled = !state.readOnly,
                label = { Text("名称，如「三教一楼」") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 主路径：只在新建时给（坐标是锁定字段之一）
            if (!state.readOnly) {
                Button(
                    onClick = viewModel::useCurrentLocation,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.locating) "正在定位…" else "用当前位置创建")
                }
            }

            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("坐标", style = MaterialTheme.typography.bodySmall, color = StatusColors.Idle)
                    Text(
                        if (state.hasCoordinates) {
                            "%.6f, %.6f".format(state.lat, state.lng)
                        } else {
                            "尚未设置"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    state.locationAccuracyM?.let {
                        Text(
                            "定位精度 ${it.toInt()} 米",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusColors.Idle
                        )
                    }
                }
            }

            SectionTitle("半径")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(50, 100, 200, 500).forEach { r ->
                    OutlinedButton(
                        onClick = { viewModel.setRadiusM(r) },
                        enabled = !state.readOnly
                    ) {
                        Text(if (state.radiusM == r) "● $r 米" else "$r 米")
                    }
                }
            }

            SectionTitle("最小定位精度门槛")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30f, 50f, 80f).forEach { a ->
                    OutlinedButton(
                        onClick = { viewModel.setMinAccuracyM(a) },
                        enabled = !state.readOnly
                    ) {
                        Text(if (state.minAccuracyM == a) "● ≤${a.toInt()} 米" else "≤${a.toInt()} 米")
                    }
                }
            }

            OutlinedTextField(
                value = state.address ?: "",
                onValueChange = if (state.readOnly) ({}) else viewModel::setAddress,
                enabled = !state.readOnly,
                label = { Text("备注（可选）") },
                modifier = Modifier.fillMaxWidth()
            )

            if (state.error != null) {
                Text(state.error!!, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(6.dp))

            if (state.readOnly) {
                // ---- 删除仍然允许（用户倾向 + 本实现的取舍，见 SpotRepository.save） ----
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这个打卡点")
                }
            } else {
                Button(
                    onClick = viewModel::save,
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("保存")
                }

                if (!state.canSave) {
                    Text(
                        text = when {
                            state.name.isBlank() -> "请先填写名称"
                            !state.hasCoordinates -> "请用当前位置创建，或手动输入坐标"
                            else -> "请检查半径设置"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}