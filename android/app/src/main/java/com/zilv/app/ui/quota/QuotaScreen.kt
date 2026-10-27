package com.zilv.app.ui.quota

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import com.zilv.app.ui.components.AppHelp
import com.zilv.app.ui.components.AppIcon
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.components.TitleWithHelp
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel

/**
 * 「软件使用时间限时」管理页（v2.7）。
 *
 * 用户要求："添加软件使用时间限时功能，自定义一个使用时间额度，
 * 自定义一个或多个（共用）软件使用该时间额度。自定义时间额度刷新间隔
 * （每天 0 点，每周一，每月 1 号）到点刷新（旧额度不可保留）。
 * 时间额度为零时禁用指定软件。"
 *
 * 页面结构：
 *  - 顶部一段说明（把"共用""不结转""刷新点""用尽即禁用"四件事说清）
 *  - 额度列表：名称 / 时长 / 刷新周期 / 绑定应用数 / **文字状态**
 *  - 右下加号 → 编辑器（名称、**先小时再分钟**的时长输入 + 快捷档位、
 *    三选一周期、应用多选）
 *  - 每行：关闭开关 / 编辑 / 删除
 *
 * ## v2.11 的两条新规则（用户第 3 条）
 *
 *  1. **只给文字状态**：不再显示"还剩 N 分钟 / 已用 N 分钟"，
 *     也不画进度条（长度就是"已用 / 上限"，反推同样是暴露）。
 *     状态由 `QuotaStatus.of` 这个纯函数算，共**四种**：
 *     【限额生效中】/【额度已耗尽】/【已关闭】/【配置无效】
 *  2. **用尽即冻结**：`mutationLocked` 为真时，关闭 / 编辑 / 删除
 *     三个入口一起置灰，并在按钮上方说明原因。
 *     界面只是第一道 —— 真正的拒绝在 `QuotaViewModel` 与
 *     `UsageQuotaRepository.ensureMutable`
 *
 *     ⚠️ 本轮：上限 == 0 的存量坏数据显示【配置无效】且**不冻结** ——
 *     冻结是为了防"用完就关掉"，而它永远不可能被"用完"，
 *     冻住只会让用户无路可走（见 `UsageQuota.allowsMutation` 的 KDoc）。
 *
 * ## 时长为什么是"先填小时、再填分钟"（本轮用户要求）
 *
 * 上限从 1440 分钟（24 小时）提到 **44640 分钟（744 小时 = 31 天）** 之后，
 * 原来的滑块已经不可用了：一像素 ≈ 31 分钟，拖不出"2 小时"这种值。
 * 改成两个数字输入框，小时在前、分钟在后 —— 顺序与用户描述一致，
 * 合计值实时显示在标题上（`本周期时长：2 小时 30 分`）。
 *
 * ## 为什么编辑用弹窗而不是新页面
 *
 * 与「添加」页的两个弹窗一致：额度是个**小表单**（4 个字段 + 一个多选），
 * 弹窗能让用户在改的时候一直看到"我还有哪些额度"，而新页面会把上下文顶掉。
 * 多选应用是唯一的例外 —— 它需要搜索 + 长列表，所以单独开一层对话框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaScreen(
    onBack: () -> Unit,
    viewModel: QuotaViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 回到这一页时重算用量 —— "已用 / 剩余"是时间的函数，
    // ViewModel 被导航栈保留、init 不会重跑，不刷新就会停在旧数字上。
    LifecycleResumeEffect(Unit) {
        viewModel.refreshUsage()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("使用时间限时") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::startCreate) {
                Icon(Icons.Filled.Add, contentDescription = "新建额度")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // v2.8：页面顶部原来那一段说明（"给一个或多个应用设一份**共用**的使用时长。
                // 到刷新点重新计算，不结转、不欠账；本周期用完后这些应用就打不开了。"）
                // 已经并进下面"额度（N）"标题旁的「?」，这里只留原来那点顶部呼吸空间
                item { Spacer(Modifier.height(4.dp)) }

                if (state.isEmpty && !state.loading) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                "还没有额度。点右下角加号新建一个。",
                                modifier = Modifier.padding(20.dp),
                                color = StatusColors.Idle
                            )
                        }
                    }
                }

                if (state.rows.isNotEmpty()) {
                    item {
                        // v2.8：原先页面顶部那一整段"共用 / 到刷新点重新计算 / 不结转、不欠账 /
                        // 用完打不开"的正文收进了这个「?」。
                        // 文案与「添加」页那一处**共用同一个常量**（AppHelp.QUOTA），
                        // 于是两页不可能再出现"744 小时"和"31 天"各说一遍的分叉。
                        TitleWithHelp(
                            text = "额度（${state.rows.size}）· 启用 ${state.enabledCount}" +
                                if (state.exhaustedCount > 0) {
                                    " · 已用尽 ${state.exhaustedCount}"
                                } else {
                                    ""
                                },
                            help = AppHelp.QUOTA,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                items(state.rows, key = { it.quota.id }) { row ->
                    QuotaCard(
                        row = row,
                        onEdit = { viewModel.startEdit(row.quota) },
                        onDelete = { viewModel.delete(row.quota.id) },
                        onToggle = { viewModel.setEnabled(row.quota.id, it) }
                    )
                }

                item { Spacer(Modifier.height(24.dp)) }
                item { Spacer(Modifier.height(56.dp)) }
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

    // 编辑器
    state.draft?.let { draft ->
        QuotaEditorDialog(
            draft = draft,
            problem = state.draftProblem,
            candidates = viewModel.candidatesExcept(draft.id),
            onName = viewModel::updateName,
            onLimit = viewModel::updateLimit,
            onPeriod = viewModel::updatePeriod,
            onToggleEnabled = viewModel::toggleEnabled,
            onTogglePackage = viewModel::togglePackage,
            onDismiss = viewModel::cancelEdit,
            onSave = { viewModel.saveDraft() }
        )
    }
}

// ==================================================================== 列表行

@Composable
private fun QuotaCard(
    row: QuotaRow,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val quota = row.quota

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(quota.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${quota.limitText} · ${quota.resetPeriod.label} · " +
                    "${quota.packageNames.size} 个应用共用",
                style = MaterialTheme.typography.bodySmall,
                color = StatusColors.Idle
            )

            Spacer(Modifier.height(10.dp))

            // ---- v2.11（用户第 3 条规则 1）：只给文字状态，不给任何分钟数 ----
            //
            // 以前这里是「进度条 + 已用 N 分钟 · 还剩 N 分钟」。
            // 用户要求："**不展示**还剩多少分钟、也不展示已用多少分钟
            // （已用 + 上限等于剩余，等于变相暴露），**只展示文字状态**：
            // 【限额生效中】 / 【额度已耗尽】。"
            //
            // 进度条也一并去掉：它的长度就是"已用 / 上限"，反推一下
            // 同样能算出还剩多少分钟 —— 那正是"变相暴露"。
            Text(
                text = row.status.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = when (row.status) {
                    QuotaStatus.EXHAUSTED -> MaterialTheme.colorScheme.error
                    QuotaStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    QuotaStatus.DISABLED -> StatusColors.Idle
                    // 本轮新增：坏数据要用最显眼的颜色提醒用户去修它 ——
                    // 但**不能**和"已耗尽"用同一个颜色（那会让人以为等刷新就好）
                    QuotaStatus.INVALID -> MaterialTheme.colorScheme.tertiary
                }
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = "下一刷新点：${quota.resetPeriod.label}",
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.Idle
            )

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            // ---- v2.11（用户第 3 条规则 2）：用完的额度不许关/改/删 ----
            //
            // 用户要求："剩余额度 == 0 时，【关闭】【编辑】【删除】三个按钮
            // 全部置灰、不可点击。目的是防止「额度用完就把额度关掉 / 删掉」
            // 来绕过限制。"
            //
            // 这里只是**第一道**（置灰让人点不到）；真正的拒绝在
            // `QuotaViewModel` 与 `UsageQuotaRepository.ensureMutable`。
            //
            // ⚠️ 本轮：`mutationLocked` 对【配置无效】（上限 == 0）的存量坏数据
            // 是 **false** —— 它不冻结。下面这段"为什么点不动"的提示因此不会
            // 出现在坏数据上；取而代之的是一句"怎么把它修好"。
            val locked = row.mutationLocked
            if (locked) {
                Text(
                    // 置灰必须给原因，否则用户只会觉得"按钮坏了"
                    text = row.mutationLockedHint ?: "这份额度现在改不了",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(6.dp))
            } else if (row.status == QuotaStatus.INVALID) {
                // 上限 == 0 的坏数据：三个按钮都能点，但得告诉用户该干什么。
                // ⚠️ 这里**不许**出现分钟数（用户第 3 条规则 1），
                // 所以只说"没有配好时长"，不说"0 分钟"。
                Text(
                    text = "这份额度没有配好时长，改成一个有效时长（或删掉它）即可",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // ---- v2.12（Bug 2）：开关的可点性**按方向**判，不再一刀切 ----
                    //
                    // v2.11 这里是 `Switch(enabled = !locked)`，而 `locked`
                    // 对【已关闭】的额度是 true —— 于是"关闭"点得动、
                    // "打开"点不动，一份额度关掉之后就再也开不回来。
                    //
                    // 现在用 `row.canToggle`（问的是 UsageQuota.allowsEnabledChange）：
                    // 拨向「打开」恒为允许，拨向「关闭」才受冻结约束。
                    // 文案也跟着变，让用户看得出"还能开回来"。
                    text = row.switchLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusColors.Idle
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = quota.enabled,
                    enabled = row.canToggle,
                    onCheckedChange = onToggle
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit, enabled = !locked) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("编辑")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDelete, enabled = !locked) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除")
                }
            }
        }
    }
}

// ==================================================================== 编辑器

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuotaEditorDialog(
    draft: QuotaDraft,
    problem: String?,
    candidates: List<AppOption>,
    onName: (String) -> Unit,
    onLimit: (Int) -> Unit,
    onPeriod: (ResetPeriod) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var pickerOpen by remember { mutableStateOf(false) }

    // ---- 时长用"先填小时、再填分钟"两个输入框（本轮用户要求）----
    //
    // 为什么不用滑块：上限从未改动前的 1440 分钟（24 小时）提到 **44640 分钟
    // （744 小时）** 之后，滑块的每一像素代表的分钟数变成原来的 31 倍 ——
    // 想精确设"2 小时"基本不可能拖得到。自填数字既能精确到分钟，
    // 也不再受滑块量程的限制。
    //
    // 两个输入框只在**弹窗打开期间**存在（弹窗关闭即离开组合），所以用
    // remember 记文本即可：初值就是草稿里的值，用户在弹窗里怎么改都只走
    // onLimit（真值仍然是 draft.limitMinutes）。
    var hoursText by remember { mutableStateOf((draft.limitMinutes / 60).toString()) }
    var minutesText by remember { mutableStateOf((draft.limitMinutes % 60).toString()) }

    /**
     * 把两个输入框的内容合成总分钟数（合成规则在 [QuotaViewModel.limitMinutesOf]）。
     *
     * 只有在**输入真的被夹过**的时候才回写输入框：否则用户正打字时会被打断
     * （比如小时栏刚清空、光标还在那里）。空串按 0 算 —— 与合成口径一致，
     * 所以"清空小时栏"不会被改写成"0"。
     */
    fun digitsOf(text: String): Int = text.filter { it.isDigit() }.toIntOrNull() ?: 0

    fun pushLimit(hours: String, minutes: String) {
        val total = QuotaViewModel.limitMinutesOf(hours, minutes)
        if (digitsOf(hours) != total / 60) {
            hoursText = (total / 60).toString()
        }
        if (digitsOf(minutes) != total % 60) {
            minutesText = (total % 60).toString()
        }
        onLimit(total)
    }

    /** 快捷档位：点一下就把两个输入框一起改写（用户仍可再手动微调）。 */
    fun applyPreset(totalMinutes: Int) {
        hoursText = (totalMinutes / 60).toString()
        minutesText = (totalMinutes % 60).toString()
        onLimit(totalMinutes)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 56.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        if (draft.isNew) "新建额度" else "编辑额度",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = onName,
                        label = { Text("额度名称") },
                        supportingText = { Text("例如「短视频」「游戏」") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // ---- 时长：先填小时，再填分钟 ----
                    Spacer(Modifier.height(6.dp))
                    // v2.8：输入规则收进「?」（原文案完整保留在 AppHelp.QUOTA_DURATION）
                    TitleWithHelp(
                        text = "本周期时长：${UsageQuota.formatMinutes(draft.limitMinutes)}",
                        help = AppHelp.QUOTA_DURATION
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = hoursText,
                            onValueChange = { raw ->
                                // 只接受数字：用户手动输入时不该因为打错字就变成 0。
                                // 3 位足够（上限 744 小时）
                                val digits = raw.filter { it.isDigit() }.take(3)
                                hoursText = digits
                                pushLimit(digits, minutesText)
                            },
                            label = { Text("小时") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minutesText,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(2)
                                minutesText = digits
                                pushLimit(hoursText, digits)
                            },
                            label = { Text("分钟") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // 快捷档位：覆盖"刷一会儿"到"一天两小时"的常见需求
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UsageQuota.QUICK_PRESETS.take(5).forEach { preset ->
                            FilterChip(
                                selected = draft.limitMinutes == preset,
                                onClick = { applyPreset(preset) },
                                label = { Text("$preset") }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UsageQuota.QUICK_PRESETS.drop(5).forEach { preset ->
                            FilterChip(
                                selected = draft.limitMinutes == preset,
                                onClick = { applyPreset(preset) },
                                label = { Text("$preset") }
                            )
                        }
                        // ---- v2.11：不再提供"0（直接禁用）"这一档 ----
                        //
                        // 用户第 3 条规则 2 让"剩余 == 0"的额度永远关不掉也删不掉。
                        // 于是**新建**一个 0 分钟额度就等于当场把自己锁死
                        // （见 QuotaViewModel.validate 与 UsageQuotaRepository.add）。
                        // 留一个点一下就必然被拒的档位只会让人困惑，所以整档去掉；
                        // 想"一打开就拦"请设 1 分钟。
                        //
                        // 本轮用户把话说到底了："**不予许设置成 0 分钟**" ——
                        // 0 从根本上不是合法配置，所有写入口（新建 / 编辑保存 /
                        // 备份恢复）都不接受它。而库里**已经**有的 0 分钟行状态是
                        // 【配置无效】、**不冻结**，用户可以从这一行的「编辑」
                        // 进去把它改成 ≥1 分钟（或者直接删掉）。
                    }

                    Spacer(Modifier.height(14.dp))

                    // ---- 刷新周期（三选一）----
                    TitleWithHelp(
                        text = "刷新间隔（到点重置，旧额度不保留）",
                        help = AppHelp.QUOTA_RESET,
                        modifier = Modifier
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ResetPeriod.entries.forEach { period ->
                            FilterChip(
                                selected = draft.resetPeriod == period,
                                onClick = { onPeriod(period) },
                                label = { Text(period.shortLabel) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        draft.resetPeriod.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle
                    )

                    Spacer(Modifier.height(14.dp))

                    // ---- 应用多选 ----
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            // 弹窗内部不需要标题的默认上边距
                            TitleWithHelp(
                                text = "应用（共用这份额度）",
                                help = "这些应用共用上面那一份时长。" +
                                    "已经绑给其它额度的应用也能再选 —— " +
                                    "规则是「任一额度用完即禁用」。",
                                modifier = Modifier
                            )
                            Text(
                                if (draft.packageNames.isEmpty()) {
                                    "还没有选应用"
                                } else {
                                    "已选 ${draft.packageNames.size} 个"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                        OutlinedButton(onClick = { pickerOpen = true }) { Text("选择") }
                    }

                    // 已选应用的图标条：让用户一眼确认"选的是这几个"
                    if (draft.packageNames.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            draft.packageNames.take(6).forEach { pkg ->
                                val label = candidates.firstOrNull { it.packageName == pkg }?.label ?: pkg
                                AppIcon(packageName = pkg, fallbackLabel = label, size = 34.dp)
                            }
                            if (draft.packageNames.size > 6) {
                                Text("…", modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            TitleWithHelp(
                                text = "启用",
                                help = "停用后不参与拦截，配置会保留",
                                modifier = Modifier
                            )
                        }
                        Switch(checked = draft.enabled, onCheckedChange = onToggleEnabled)
                    }

                    if (problem != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            problem,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = problem == null, onClick = onSave) { Text("保存") }
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        AppPickerDialog(
            selected = draft.packageNames,
            candidates = candidates,
            onToggle = onTogglePackage,
            onDismiss = { pickerOpen = false }
        )
    }
}

// ==================================================================== 应用多选

/**
 * 应用多选对话框。
 *
 * 复用 v1.3 的 [AppIcon] 显示图标（带缓存的异步加载，失败回退首字母占位），
 * 候选来自 `SystemPackageDetector.installedLaunchableApps()`
 * —— 与白名单页用的是**同一个**来源，所以两处的应用列表口径一致。
 */
@Composable
private fun AppPickerDialog(
    selected: Set<String>,
    candidates: List<AppOption>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyword by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 56.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    // 标题保持原来的 titleLarge（这个对话框比内层的选择器高一级），
                    // 所以这里不能直接用 TitleWithHelp（它固定 titleSmall）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("选择应用（可多选）", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(6.dp))
                        HelpHint(
                            // v2.8：原文案整句收进「?」
                            text = "这些应用共用上面那一份时长。" +
                                "已被其它额度绑定的应用会标出来 —— 仍可选，" +
                                "规则是「任一额度用完即禁用」。"
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索应用名或包名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    val filtered = candidates.filter {
                        keyword.isBlank() ||
                            it.label.contains(keyword, ignoreCase = true) ||
                            it.packageName.contains(keyword, ignoreCase = true)
                    }

                    if (filtered.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (candidates.isEmpty()) "正在读取已安装应用…" else "没有匹配的应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.Idle
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            AppPickRow(
                                app = app,
                                checked = app.packageName in selected,
                                onToggle = { onToggle(app.packageName) }
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text("已选 ${selected.size} 个", modifier = Modifier.weight(1f))
                        Button(onClick = onDismiss) { Text("完成") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPickRow(app: AppOption, checked: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(packageName = app.packageName, fallbackLabel = app.label, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.Idle
                )
                if (app.usedElsewhere) {
                    Text(
                        "已被其它额度绑定",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            if (checked) {
                Text("已选", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            } else {
                AssistChip(onClick = onToggle, label = { Text("加入") })
            }
        }
    }
}
