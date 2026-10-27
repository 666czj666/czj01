package com.zilv.app.ui.habits

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zilv.app.ui.components.HelpHint
import com.zilv.app.ui.theme.StatusColors
import org.koin.androidx.compose.koinViewModel

/**
 * 「新建习惯」弹窗。
 *
 * 用户要求："把新建习惯界面改为弹窗（原来是整页 `HabitEditScreen`，改成对话框）"。
 *
 * ## 复用了整页的什么
 *
 * 字段区块**一个都没有重写**，全部直接复用 [HabitEditScreen] 里那几个
 * `Habit*Section`（同一个文件、同一份代码）：
 *
 * | 区块 | 弹窗里 | 说明 |
 * | --- | --- | --- |
 * | 习惯名称 | ✅ | 弹窗里唯一的文本输入 |
 * | 打卡条件（无 / 指定地点 + 打卡点） | ✅ | 含"去创建打卡点" |
 * | 频率（每天 / 指定每月几号 / 指定星期） | ✅ | v2.14 起只剩三种频率 |
 * | 惩罚（无 / 锁机）+ 锁机时长 + 打卡时间 | ❌ | 见下 |
 * | 归档 / 删除 | ❌ | 新建时没有东西可归档 |
 *
 * ## 为什么**不放**惩罚区块
 *
 * 三个理由，都不是偷懒：
 *  1. **技术上前提不成立**。锁机配置挂在 `habitId` 上，而这条习惯此刻还没落库
 *     （`HabitEditUiState.canOpenLockSettings` 为 false）。整页在同一种情况下
 *     也是显示"先保存习惯，再设置惩罚"。
 *  2. **弹窗装不下**。整页那一屏是「名称 + 打卡条件 + 频率 + 锁机时长滑块 +
 *     打卡时间（两个滚轮 + 已加窗口列表）+ 解锁方式 + 严格模式 + 文案」，
 *     全塞进弹窗会变成一个必须滚三屏的小表单 —— 那正是用户想摆脱的东西。
 *  3. **新建时的路径是"先建起来，再去调"**。习惯名和频率是每天要看的，
 *     惩罚是建一次改很少的配置。弹窗只收前者，符合"轻量新建"的意图。
 *
 * 弹窗底部会**明说**这一点（"惩罚（锁机）保存后可在编辑习惯里设置"），
 * 而不是让用户找不到 —— 这与整页那句"先保存习惯，再设置惩罚"是同一件事。
 *
 * ## 保存之后
 *
 * 保存成功后弹窗自己关掉，用户留在「添加」页 —— 习惯列表走 Flow 观察，
 * 新习惯会自动出现在下面的「习惯」区块里，不需要手动刷新。
 *
 * @param onDismiss 关闭弹窗（保存成功 / 点取消 / 点外面）
 * @param onManageSpots 选「指定地点」但没有打卡点时，跳去创建打卡点
 * @param viewModel 与整页是**同一个** ViewModel 类；它挂在宿主页面
 *        （「添加」页 / 「习惯管理」页）的返回栈条目上，所以每次打开弹窗
 *        都会用 [HabitEditViewModel.startCreate] 重置成一张白纸
 */
@Composable
fun HabitCreateDialog(
    onDismiss: () -> Unit,
    onManageSpots: () -> Unit,
    viewModel: HabitEditViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /**
     * 是否已经跑过一次「重置成白纸」。
     *
     * ⚠️ 这个标记是必须的：弹窗关闭后 ViewModel 仍然活着，上一次保存成功留下的
     * `saved = true` 会一直在。弹窗下一次打开时，如果直接拿它当关闭条件，
     * 弹窗会**刚出现就自己关掉**。
     *
     * [HabitEditViewModel.startCreate] 本身是同步重置的，所以正常情况下不会有
     * 那个瞬间；但两个 `LaunchedEffect` 在同帧里的执行顺序不该被当成保证，
     * 这里再用一个显式标记兜住（两道防线都留着）。
     */
    var ready by remember { mutableStateOf(false) }

    // 每次进入组合（= 每次打开弹窗）都重置一次。
    // 用 LaunchedEffect(Unit)：弹窗关闭时它离开组合，下次打开会重新跑。
    //
    // allowDuringLock = true：用户要求"锁机时间不能控制添加界面"，
    // 新建习惯在锁机期间必须能正常保存（新建不会关掉任何正在生效的锁）。
    LaunchedEffect(Unit) {
        viewModel.startCreate(allowDuringLock = true)
        ready = true
    }
    LaunchedEffect(state.saved, ready) {
        if (ready && state.saved) onDismiss()
    }

    // 从打卡点管理页回来时刷新可选打卡点（与整页同一个理由）
    LifecycleResumeEffect(Unit) {
        viewModel.reloadSpots()
        onPauseOrDispose { }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 40.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("新建习惯", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))

                HabitNameField(state = state, viewModel = viewModel)

                HabitCheckInConditionSection(
                    state = state,
                    viewModel = viewModel,
                    onManageSpots = onManageSpots
                )

                HabitFrequencySection(state = state, viewModel = viewModel)

                Spacer(Modifier.height(10.dp))
                // v2.8：这句"惩罚要等保存后才能设"原来是两行正文，现在收成「?」——
                // 它讲的是"这一步为什么现在做不了"，属于说明而不是校验；
                // 弹窗里仍然留着 TextField 旁边的校验红字（那才是"当下要据此行动"的）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "惩罚（锁机）保存后才能设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.Idle
                    )
                    Spacer(Modifier.width(6.dp))
                    HelpHint(
                        text = "惩罚（锁机）要等习惯保存之后才能设置 —— " +
                            "保存后在「添加」页点这条习惯，进去就能设。"
                    )
                }

                HabitSaveSection(state = state, viewModel = viewModel)

                Spacer(Modifier.height(4.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}
