package com.zilv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zilv.app.domain.quota.UsageQuota

/**
 * 可复用的「?」帮助提示（v2.8）。
 *
 * ## 为什么要这个组件
 *
 * 用户要求："把界面简化，把成句的静态说明文案收成一个问号，放在旁边，
 * 点击才展示"。
 *
 * 于是本项目定下一条**分界线**（判断不清时一律保留可见）：
 *
 * | 收起（本组件负责） | 保持可见（**绝不**收进这里） |
 * | --- | --- |
 * | 解释"这是什么 / 怎么用"的成句段落（大体 ≥ 12~15 个汉字） | 报错与校验提示（"请设置锁机时长"） |
 * | 讲道理的背景交代（"为什么只能这样"） | 状态与数据（剩余时长、本月打卡 N 天） |
 * | 一屏只读一次的规则说明 | 动态引导（"去「添加」里点击加号添加"） |
 * | | 按钮文字、字段标签、列表项短标题 |
 *
 * 判断标准不是字数，而是**"不看到它，用户会不会看不懂眼下出了什么事"** ——
 * 会，就必须留在屏幕上。
 *
 * ## 为什么是 AlertDialog 而不是 Popup
 *
 * 选 [AlertDialog]，理由三条（都是本项目实际会遇到的情况）：
 *
 *  1. **要点只有一个**：说明读完就关，`AlertDialog` 天生就是这个语义，
 *     我只要给一个「知道了」按钮；[androidx.compose.ui.window.Popup] 得自己摆盒子、
 *     自己算位置、自己处理"点外面关掉"，代码更多、出错的余地更大。
 *  2. **点空白处关掉是默认行为**：`AlertDialog` 的 `dismissOnClickOutside`
 *     默认就是 `true`，`onDismissRequest` 一份实现同时覆盖
 *     "点空白" / "按返回键" / "点知道了" 三条路径。
 *  3. **对话框套对话框是支持的**：本项目好几处说明位于 `Dialog`/`AlertDialog`
 *     内部（额度编辑器、新建习惯弹窗、应用多选弹窗）。`AlertDialog` 走的是
 *     Compose 自己的 `DialogWrapper`，在已有 Dialog 之上再开一层是原生支持的行为，
 *     不需要手工处理层级。
 *
 * 代价说清楚：`AlertDialog` 的位置是**固定居中**的（改不动），所以点开问号时
 * 说明会出现在屏幕正中，而不是贴着那个小问号。这一点是有意接受的 ——
 * 说明文字往往有好几行，贴着 18dp 的问号反而会挤成一团。
 *
 * ## 尺寸与对齐
 *
 * 问号本身只有 [size]（默认 18dp）那么大，并且**不带内边距**：
 * 它是要"跟在标题后面"的，多一个 padding 就会把标题和问号推开，
 * 看起来像两个不相干的控件。调用方用 [TitleWithHelp] 可以得到正确的对齐。
 *
 * 图标用的是 `Icons.AutoMirrored.Outlined.HelpOutline`（而不是文字 `?`）：
 * 矢量图标在任何字号/密度下都不会像文字那样被行高裁边，也不必手工调字号。
 * AutoMirrored 变体是必须的 —— 非镜像的那份在 Compose 1.7 已经标记
 * `@Deprecated`，直接用会在构建里刷一片告警。
 *
 * @param text 点开后展示的说明文案。**必须是原文案的完整内容** ——
 *        收起文案只是把"一直占屏幕"改成"点一下才出现"，不是删信息。
 * @param contentDescription 无障碍描述（TalkBack 朗读的就是它），默认「说明」
 * @param modifier 便于调用方补间距或对齐
 * @param size 问号圆点的直径，默认 18dp
 * @param defaultVisible 是否默认展开说明。留 `false` —— 收起才是这个组件的意义
 */
@Composable
fun HelpHint(
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String = "说明",
    size: Int = 18,
    defaultVisible: Boolean = false
) {
    var visible by remember { mutableStateOf(defaultVisible) }

    // 颜色跟随当前文字色：这样放进任何容器（Card / Dialog / 彩色卡片）
    // 都会自动取到对的那一档，不需要调用方逐个指定
    val tint = LocalContentColor.current

    Box(
        modifier = modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.12f), CircleShape)
            .clickable { visible = true }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            // 用 AutoMirrored 变体：非镜像版在 1.7 里已标记 @Deprecated
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            // 图标比外圈小 4dp，视觉上是"一个小圆点里嵌着问号"，
            // 不是"一个问号顶满整块"——后者在标题旁边会过于抢眼
            modifier = Modifier.size((size - 4).dp)
        )
    }

    if (visible) {
        AlertDialog(
            onDismissRequest = { visible = false },
            // 正文用 bodyMedium 而不是 bodySmall：收起之后用户是**专门**点开来看的，
            // 这一段就是主角，没必要再小一号
            text = { Text(text = text, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { visible = false }) { Text("知道了") }
            }
        )
    }
}

/**
 * 多处重复的说明文案，收在这里只写一份。
 *
 * ## 只收真正重复的
 *
 * 判断标准是"同一句话在两个以上页面各写了一遍"，不是为了抽象而抽象 ——
 * 只出现一次的解释（例如"规划只能排在明天"）就留在它自己的页面里，
 * 搬到这里反而会让人改成别处文案时找不到它。
 *
 * ## 为什么不改在别处引用它的那份
 *
 * `习惯（N）　点击可编辑` 之类的**短标题**不进这里：它们是标题不是说明，
 * 而且每处措辞本来就该跟着上下文走。
 */
object AppHelp {

    /**
     * 「使用时间限制」区块的完整说明。
     *
     * 原文案在 `AddScreen` 与 `QuotaScreen` 各写了一遍（措辞略有出入、信息一致），
     * v2.8 收敛成一份 —— 两处现在点开问号看到的是**同一段话**，
     * 不会再出现"添加页说 744 小时、额度页说 31 天"这种口径分叉。
     *
     * 原文案（完整保留，只是从"一直显示"改成"点开才显示"）：
     *
     * > 给一个或多个应用设一份共用的使用时长（最长 744 小时 = 31 天），
     * > 到点刷新（每天 0 点 / 每周一 / 每月 1 号），旧额度不保留。
     * > 额度用完时这些应用会被禁用。
     */
    const val QUOTA = "给一个或多个应用设一份共用的使用时长（最长 744 小时 = 31 天），" +
        "到点刷新（每天 0 点 / 每周一 / 每月 1 号），旧额度不保留。" +
        "额度用完时这些应用会被禁用。"

    /**
     * 「额度编辑器 · 时长」的输入说明。
     *
     * 原文案（完整保留）：
     *
     * > 先填小时、再填分钟。0 分钟 = 一打开就被拦；上限 744 小时（44640 分钟 = 31 天）。
     */
    const val QUOTA_DURATION = "先填小时、再填分钟。" +
        "0 分钟 = 一打开就被拦；" +
        "上限 ${UsageQuota.MAX_LIMIT_MINUTES / 60} 小时" +
        "（${UsageQuota.MAX_LIMIT_MINUTES} 分钟 = 31 天）。"

    /**
     * 「额度过期怎么算」的说明 —— 额度编辑器与添加页说的是同一件事。
     *
     * 原文案（完整保留）：
     *
     * > 到刷新点重新计算，不结转、不欠账；本周期用完后这些应用就打不开了。
     */
    const val QUOTA_RESET = "到刷新点重新计算，不结转、不欠账；本周期用完后这些应用就打不开了。"
}

/**
 * 「标题 + 问号」一行。
 *
 * ## 为什么单独抽出来
 *
 * "问号要跟在标题后面、并且与标题对齐"这件事，如果在每个调用点各写一遍
 * `Row(verticalAlignment = ...) { Text(title); HelpHint(desc) }`，
 * 迟早会有人写成 `Alignment.Top` 或漏掉间距，于是同一个 App 里
 * 问号的位置一会儿高一会儿低。抽成一个组件之后，"问号长在哪"只有一份实现。
 *
 * 用 [Alignment.CenterVertically] 而不是字面意义的 baseline 对齐：
 * 中文标题（`titleSmall`）的视觉中心与问号圆点的中心基本重合，
 * 而真正的 `alignByBaseline` 会让圆点**压在标题下沿以下**，
 * 看起来像掉了一行。实测居中才是"跟在标题后面的小问号"的观感。
 *
 * @param text 标题文字（保持原来的 `titleSmall` 样式）
 * @param help 为 `null` 时不显示问号（页面上有很多标题本来就没有说明）
 * @param modifier 默认补 `top = 4.dp`，与原来自各页面 `SectionTitle` 的间距一致；
 *        放进弹窗/卡片内部这种**不需要上边距**的场合时传 `Modifier` 覆盖掉它
 */
@Composable
fun TitleWithHelp(
    text: String,
    help: String?,
    modifier: Modifier = Modifier.padding(top = 4.dp)
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall)
        if (help != null) {
            HelpHint(text = help)
        }
    }
}
