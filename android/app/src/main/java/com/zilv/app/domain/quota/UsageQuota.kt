package com.zilv.app.domain.quota

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 额度刷新间隔。
 *
 * 用户要求："自定义时间额度刷新间隔（每天 0 点，每周一，每月 1 号）到点刷新
 * （旧额度不可保留）"。
 *
 * 三个周期都是**日历对齐**的，跟着本地时区走（本项目所有日期口径都统一为
 * 本地日期，见 `check_ins` / `day_notes` / `plans` 的 `epoch_day` 列）。
 */
enum class ResetPeriod {
    /** 每天 00:00 刷新。 */
    DAILY,

    /** 每周一 00:00 刷新。 */
    WEEKLY,

    /** 每月 1 号 00:00 刷新。 */
    MONTHLY;

    /** 界面上的中文名。 */
    val label: String
        get() = when (this) {
            DAILY -> "每天 0 点刷新"
            WEEKLY -> "每周一 0 点刷新"
            MONTHLY -> "每月 1 号 0 点刷新"
        }

    /** 简短名字，列表里用。 */
    val shortLabel: String
        get() = when (this) {
            DAILY -> "每天"
            WEEKLY -> "每周"
            MONTHLY -> "每月"
        }

    /**
     * 本周期起点。**唯一的周期计算入口** —— 详见
     * [QuotaCalculator.periodStart] 的定义与边界说明。
     */
    fun startOf(now: LocalDateTime): LocalDateTime = QuotaCalculator.periodStart(this, now)
}

/**
 * 「软件使用时间限时」额度。
 *
 * 用户要求："自定义一个使用时间额度，自定义一个或多个（共用）软件使用该时间额度。"
 *
 * ## 一个额度 ↔ 多个应用，**共用**同一份时长
 *
 * 绑定关系存在 `usage_quota_apps` 表里（[packageNames]），而不是额度行上的一列：
 * 一个额度要绑几个应用是用户自己决定的，拆成关联表才能干净地增删。
 * "共用"的语义是：这些应用**加在一起**的当周期使用时长与 [limitMinutes] 比，
 * 而不是每个应用各有一份额度。
 *
 * ## 旧额度不可保留
 *
 * 判定只看"本周期内"的用量（见 [QuotaCalculator.usedMinutesInPeriod]），
 * 不做任何结转 / 欠账 —— 上个周期多用了不会扣本周期，少用了也不会攒到本周期。
 *
 * ## 这个类本身不抛异常
 *
 * 与 [com.zilv.app.domain.plan.Plan] 同一口径：从库里读出来的脏数据不能让
 * 列表页崩掉，所以不变式表达成可查询的 [isValid] / [invalidReason]，
 * 由仓库层的写入口负责拒绝（抛
 * [com.zilv.app.data.repository.QuotaRejectedException]），读路径永远安全。
 */
data class UsageQuota(
    val id: Long = 0L,
    val name: String,
    /**
     * 本周期内允许使用的总分钟数。
     *
     * ## 合法区间是 `1..MAX_LIMIT_MINUTES`，**0 不再是一种配置**
     *
     * v2.11 之前 0 是合法的，语义是"一打开就被拦"（用户原话：
     * "时间额度为零时禁用指定软件"）。但"剩余 == 0 → 三个按钮全部冻结"
     * 这条规则一上，0 分钟额度就变成了**永久僵尸**：它一建出来就"用尽"，
     * 于是它自己也关不掉、改不了、删不掉。
     *
     * 用户看过这份风险说明后的决定是"**不予许设置成 0 分钟**" ——
     * 即 0 从根本上就不是一个合法配置。于是：
     *  - 所有**写入口**（新建 / 编辑保存 / 备份恢复）都至少 1 分钟
     *    （见 [MIN_CREATABLE_LIMIT_MINUTES] 与 `QuotaImport`）
     *  - 库里**已经**有的 0 分钟行不再被当成"用尽"，而是 [QuotaStatus.INVALID]
     *    （配置无效）—— 于是它不冻结，用户能把它改成 ≥1 分钟或者删掉
     *  - 它也不再参与拦截：0 不是"额度很小"，而是"这份额度没配好"，
     *    拿它去锁应用没有依据（见 [isConfigured]）
     *
     * ⚠️ 注意这个字段仍然是**任意 Int**：读路径永远不抛异常，
     * 也不把 0 当成"错误"崩掉列表。怎么对待 0 由下面几个判据决定。
     */
    val limitMinutes: Int = DEFAULT_LIMIT_MINUTES,
    val resetPeriod: ResetPeriod = ResetPeriod.DAILY,
    val enabled: Boolean = true,
    val createdAt: Long = 0L,
    /** 共用这个额度的应用包名（来自 `usage_quota_apps`，无序、去重）。 */
    val packageNames: Set<String> = emptySet()
) {

    /** 名称非空、时长在合法区间、且至少绑了一个应用。 */
    val isValid: Boolean get() = invalidReason == null

    /** 不合法时给用户看的原因；合法时返回 null。 */
    val invalidReason: String?
        get() = when {
            name.isBlank() -> "额度名称不能为空"
            limitMinutes !in MIN_LIMIT_MINUTES..MAX_LIMIT_MINUTES ->
                "时长必须在 $MIN_LIMIT_MINUTES..$MAX_LIMIT_MINUTES 分钟之间（实际 $limitMinutes）"

            packageNames.none { it.isNotBlank() } -> "至少要选一个应用"
            else -> null
        }

    /**
     * 这份额度**有没有配一个真正的时长**（v2.11 本轮）。
     *
     * `limitMinutes >= 1` 才为真。0（以及任何被写进来的非正数）是"没配好"，
     * 不是"配成了 0 分钟"—— 见 [limitMinutes] 的说明。
     *
     * ## 为什么单独一个判据，而不是复用 [isValid]
     *
     * [isValid] 管的是"这条记录能不能落库 / 能不能进快照"，它的时长区间
     * 仍然是 `0..MAX`（读路径不收紧：旧库、旧备份里的 0 分钟行必须仍能读出、
     * 仍能进备份，否则恢复时会被静默丢掉）。而这里管的是**业务语义**：
     * "这份额度算不算一个生效的限制"。
     *
     * 两个判据分开之后，两件事各自都不会被对方牵着走：
     *  - 0 分钟行照样能被读出来、能被导出（[isValid] 不变）
     *  - 但它**不参与拦截**（见 `UsageQuotaRepository.disabledPackages` /
     *    `boundPackages`），状态是 [QuotaStatus.INVALID]，也不冻结
     */
    val isConfigured: Boolean get() = limitMinutes >= MIN_CREATABLE_LIMIT_MINUTES

    /** 参与拦截的、去掉空串的包名集合。 */
    val effectivePackages: Set<String> get() = packageNames.filter { it.isNotBlank() }.toSet()

    /** 人类可读的时长，如 `1 小时 30 分`。 */
    val limitText: String get() = formatMinutes(limitMinutes)

    /** [limitMinutes] 换算成毫秒 —— 与用量数据同口径。 */
    val limitMillis: Long get() = limitMinutes * 60_000L

    /** 本周期起点；[now] 由调用方给（可测）。 */
    fun periodStart(now: LocalDateTime): LocalDateTime = resetPeriod.startOf(now)

    /**
     * 这份额度现在**允不允许**被编辑 / 删除 / 停用（v2.11，用户第 3 条规则 2）。
     *
     * ## 用户要求
     *
     * "剩余额度 == 0 时，【关闭】【编辑】【删除】三个按钮全部置灰、不可点击。
     * 目的是防止'额度用完就把额度关掉/删掉'来绕过限制。"
     *
     * 判据就是"这份额度**此刻是不是正在生效**"（[QuotaStatus.ACTIVE]）：
     *  - 生效中（有剩余）→ 随便改
     *  - 已耗尽 → 三个动作全禁
     *  - 已关闭 → 也禁（一份**停用的**额度改不了并不影响任何拦截；
     *    而且"先关掉再删掉"这条路必须一起堵上，否则绕过的口子还开着。
     *    要改就先把它启用回来 —— 启用**不在**被禁的三个动作里）
     *    ⚠️ v2.12 修的正是这句"要改就先把它启用回来"：v2.11 时界面把**开关本身**
     *    也一起置灰了，于是那条出口实际不存在（关了就打不开）。
     *    现在「打开」方向由 [allowsEnabledChange] 单独裁定 —— **恒为允许**。
     *  - **配置无效（[QuotaStatus.INVALID]，上限 == 0）→ 放行**
     *    （v2.11 本轮，见下）
     *
     * ## 为什么"配置无效"必须放行（本轮用户决定）
     *
     * 用户对"0 分钟额度会被永久冻结"这个风险的答复是
     * "**不予许设置成 0 分钟**"，并要求把 0 判成
     * **"配置非法"而不是"额度耗尽"**、**不冻结**：
     * "允许编辑（好把它改成 ≥1 分钟）与删除（好清掉它）。『关闭』也允许。"
     *
     * 理由（用户原话）：**冻结是为了防"用完就关掉"，而一个上限为 0 的配置
     * 永远不可能被"用完"，它是坏数据；把它冻住只会让用户无路可走。**
     *
     * 这一点都不放宽真正的耗尽场景：上限 ≥ 1 且剩余 == 0 仍然是
     * [QuotaStatus.EXHAUSTED]，三个动作**照旧全禁**。
     * 界面上那条钉子见 `QuotaViewModelTest`
     * 的 `上限 5 分钟的本周期用满 5 分钟后三个动作仍然冻结` 与
     * `UsageQuotaRepositoryTest` 的同名那一条。
     *
     * ## 为什么放在领域层
     *
     * 它是**纯判据**：不查库、不看时间、只吃一个 [QuotaStatus]。
     * 守卫的三层入口（界面置灰 / ViewModel 拒绝 / 仓库层拒绝）全都问
     * 同一个函数，于是三层的口径不可能分叉。
     */
    fun allowsMutation(status: QuotaStatus): Boolean =
        status == QuotaStatus.ACTIVE || status == QuotaStatus.INVALID

    /**
     * **把启用开关拨到 [target] 允许吗？**（v2.12 修 Bug：方向必须分开判）
     *
     * ## 用户要求（Bug 2）
     *
     * > 「时间限额关闭后无法再次打开（显示为灰色）……**「打开（启用）」方向必须
     * > 永远可点**，无论当前是什么状态；「关闭」方向在冻结时仍应被拒
     * > （那才是防绕过的本意）。」
     *
     * ## 这是 v2.11 留下的一个真 bug
     *
     * v2.11 把 [QuotaStatus.DISABLED] 也纳入了 [allowsMutation]（本意是堵
     * "先关掉再删掉"那条绕过路径），结果界面把**开关本身**也一起置灰了：
     * 「关闭」点得动（那时还是 ACTIVE）、「打开」点不动（已经变成 DISABLED）。
     * 于是"改回来"的路被自己堵死 —— 一个额度一旦关掉就再也开不回来。
     *
     * ## 修法：判据按**方向**分开，而不是一刀切
     *
     * | 拨到 | 判据 | 为什么 |
     * |---|---|---|
     * | `true`（打开/启用） | **恒为 true** | 往"更严"的方向走；而且它是"已关闭的额度怎么改回来"的**唯一出口** |
     * | `false`（关闭/停用） | [allowsMutation] | "用完就关掉"正是要堵的绕过第一步 |
     *
     * 注意「编辑」「删除」仍然走 [allowsMutation]（不看方向）——
     * 所以一份 DISABLED 的额度依然改不了、删不掉（"先关掉再删掉"照旧堵着），
     * 但**开得回来**。这正是用户要的那条分界线。
     *
     * ## 为什么放在领域层
     *
     * 与 [allowsMutation] 同样理由：它是**纯判据**，不查库、不看时间。
     * 三个调用点（界面 `QuotaRow.canToggle` / ViewModel / 仓库层
     * `setEnabled`）问的是同一个函数，于是三层口径不可能分叉。
     *
     * @param target `true` = 打开（启用），`false` = 关闭（停用）
     */
    fun allowsEnabledChange(target: Boolean, status: QuotaStatus): Boolean =
        if (target) true else allowsMutation(status)

    /**
     * 开关拨不动时给用户看的一句话；拨得动时返回 null。
     *
     * ⚠️ 只有「关闭」方向会走到拒绝分支 —— 「打开」永远没有理由。
     */
    fun enabledChangeRejectionReason(target: Boolean, status: QuotaStatus): String? {
        if (allowsEnabledChange(target, status)) return null
        return "「$name」本周期额度已用完，暂时关不掉 —— " +
            "本意就是防止「用完就关掉」来绕过限制。到刷新点自动恢复之后就能改了。"
    }

    /**
     * 为什么不允许改动 —— 给用户看的一句话；允许时返回 null。
     *
     * 必须**可读**：用户点了置灰的按钮之外的入口（比如从别处调用）时，
     * 他得知道"为什么不行、什么时候才行"。
     */
    fun mutationRejectionReason(status: QuotaStatus): String? = when {
        allowsMutation(status) -> null

        status == QuotaStatus.DISABLED ->
            "「$name」现在是关闭状态，改不了。先把它打开，再修改或删除。"

        // 剩下的就是 EXHAUSTED。
        // ⚠️ INVALID（上限 == 0）走不到这里 —— 它上面已经被放行了。
        else ->
            "「$name」本周期额度已用完，暂时改不了也删不掉 —— " +
                "本意就是防止「用完就关掉 / 删掉」来绕过限制。" +
                "到刷新点自动恢复之后就能改了。"
    }

    companion object {
        /** 默认 30 分钟 —— 一个不太松也不太紧的起点，用户改起来方便。 */
        const val DEFAULT_LIMIT_MINUTES = 30

        const val MIN_LIMIT_MINUTES = 0

        /**
         * **写入口**允许的最小分钟数（v2.11）。
         *
         * ## 为什么 0 分钟不是合法配置
         *
         * 用户第 3 条点名了一个边界："如果用户把额度设成 0 分钟，剩余额度立刻
         * 就是 0 → 【关闭】【编辑】【删除】三个按钮**永久置灰**，这个额度再也
         * 删不掉了（除非清数据）。"
         *
         * 用户看过这份风险说明后的决定是"**不予许设置成 0 分钟**" ——
         * 0 从根本上就不是一个合法配置。所以本轮把它在**所有入口**堵死：
         *  - 界面：没有"0（直接禁用）"这一档快捷档位
         *  - ViewModel：`QuotaViewModel.validate` 对新建拒绝 0
         *  - 仓库：`UsageQuotaRepository.add` 拒绝 0
         *  - **备份恢复**：`QuotaImport.restore` 把快照里的 0 **夹到 1**
         *    （不再是"原样搬回来"）
         *
         * **读路径**（[isValid] 的区间、`DataBackup.decode`）不收紧：
         * 库里已有的、旧备份里的 0 分钟行仍然读得出来、仍然能进备份 ——
         * 否则恢复时会静默少几条，用户会以为备份坏了。
         * 它们的归宿是 [QuotaStatus.INVALID]：不拦截、不冻结，
         * 用户能把它改成 ≥1 分钟或者删掉（见 [isConfigured] 与
         * [allowsMutation]）。
         */
        const val MIN_CREATABLE_LIMIT_MINUTES = 1

        /**
         * 上限 **744 小时**（44640 分钟 = 31 天）。
         *
         * ⚠️ 这个值**不再与 `TimeWindow` 的 1440 同口径**，是刻意的：
         * 用户明确要求"限额时长上限增加到 744 小时（31 天）"。
         *
         * 取 31 天而不是 30 天：任何一个自然月都不超过 31 天，所以
         * "每月 1 号刷新"的额度设成上限时，**一个周期内永远用不完** ——
         * 这正是用户想要的效果（把额度当成一个可调的总闸门，
         * 而不是被迫在"每天/每周/每月"里选一个贴合的值）。
         *
         * 之所以以前卡在 1440：额度当初被设计成"天粒度"的配额。
         * 现在放开到 744 小时，语义变成"最长可以给到一整个月"，
         * 而**周期本身仍然照旧**（每天 0 点 / 每周一 / 每月 1 号刷新、不结转）。
         *
         * 注意：这个常量还被 [QuotaEnforcer.NONE] 当成"不限制"的额度值用，
         * 判定时会做 `limitMinutes * 60_000L`（Long 乘法）——
         * 44640 * 60000 = 2_678_400_000 不会溢出，见那里的 KDoc。
         */
        const val MAX_LIMIT_MINUTES = 744 * 60

        const val MAX_NAME_LENGTH = 24

        /**
         * 滑块/输入框旁给的快捷档位（分钟）。
         *
         * 用户要求"给几个快捷档位"：这几个覆盖了"刷一会儿"到"一天两小时"
         * 的常见需求，点一下就能选中，不必一分钟一分钟地拖。
         */
        val QUICK_PRESETS: List<Int> = listOf(10, 15, 30, 45, 60, 90, 120)

        /** 分钟数格式化成中文，界面上统一用它。 */
        fun formatMinutes(minutes: Int): String = when {
            minutes <= 0 -> "0 分（用尽即拦）"
            minutes < 60 -> "$minutes 分"
            minutes % 60 == 0 -> "${minutes / 60} 小时"
            else -> "${minutes / 60} 小时 ${minutes % 60} 分"
        }
    }
}

/**
 * 额度在界面上**唯一**允许展示的状态（v2.11，用户第 3 条规则 1；本轮扩到四态）。
 *
 * ## 为什么不再显示"还剩几分钟 / 已用几分钟"
 *
 * 用户要求："**不展示**还剩多少分钟、也不展示已用多少分钟（已用 + 上限
 * 等于剩余，等于变相暴露），**只展示文字状态**：`【限额生效中】` /
 * `【额度已耗尽】`。"
 *
 * 这一条把"界面能说什么"收成一个**有限集合**：以前是任意的分钟数
 * （任何一处漏掉一个 `%d` 就又把数字露出来了），现在只有下面四种取值，
 * 于是"漏露"这件事在类型上就不可能发生。
 *
 * ## 四种状态与判据
 *
 * | 状态 | 判据（按优先级从上到下） | 含义 |
 * |---|---|---|
 * | [INVALID] | 上限 == 0 | **配置无效**：这不是一份额度，是坏数据 |
 * | [DISABLED] | 停用 | 用户自己关掉了，不参与拦截 |
 * | [EXHAUSTED] | 启用中；剩余 == 0 | 本周期额度用完，绑定应用打不开 |
 * | [ACTIVE] | 启用中；剩余 > 0 | 限额此刻真的在生效 |
 *
 * ### 优先级顺序（本轮新增的一档排在最前）
 *
 * `INVALID` > `DISABLED` > `EXHAUSTED` > `ACTIVE`。三条理由：
 *
 *  1. **上限 == 0 最高**：这个判据与时间、与用量、与开关都无关，
 *     它说的是"这份配置本身不成立"。一份不成立的配置不该被描述成
 *     "已耗尽"（那会让用户以为"用完了，等刷新就好"）或者"已关闭"
 *     （那会让用户以为"打开就行"）—— 两种误导都会让他修不好它。
 *  2. **停用优先于用尽**（既有语义，一个字都没改）：一份被停用的额度
 *     即便算出来剩余为 0，它此刻**并没有**在生效（不参与拦截），
 *     说成"额度已耗尽"会让用户以为应用被锁住了。这与
 *     `QuotaCalculator.exhaustedPackages` 只吃启用额度是同一个口径。
 *  3. **用尽优先于生效中**：剩余 == 0 就是"打不开了"。
 *
 * ⚠️ **为什么不把上限 == 0 判成"已耗尽"**（本轮用户决定的核心理由）：
 *
 * > 冻结是为了防"用完就关掉"，而一个上限为 0 的配置**永远不可能被"用完"**，
 * > 它是坏数据；把它冻住只会让用户无路可走。
 *
 * 所以 [INVALID] 不冻结：编辑 / 删除 / 关闭全部放行（见
 * [UsageQuota.allowsMutation]），用户能把这份坏数据修好或者清掉。
 * **真正的耗尽场景一个字都没放宽** —— 上限 ≥ 1 且用满时仍然是
 * [EXHAUSTED]，三个按钮照旧全禁。
 *
 * ## 为什么是 enum + 纯工厂函数
 *
 * [of] 是**纯函数**（输入是启用与否 + 上限 + 剩余分钟，输出是状态），
 * 于是这四条判据能被纯 JVM 单测逐条钉死（见 `QuotaStatusTest`），
 * 界面只负责把 [label] 画出来。
 */
enum class QuotaStatus {
    /**
     * 上限 == 0 的存量坏数据 —— 【配置无效】（本轮新增）。
     *
     * 0 分钟额度"永远用不完"，所以它既不是"已耗尽"也不是"生效中"，
     * 而是"这份配置不成立"。它同样**不参与拦截**
     * （`UsageQuotaRepository.disabledPackages` / `boundPackages` 都按
     * `UsageQuota.isConfigured` 过滤），于是把它显示成"已耗尽、应用已被锁"
     * 会是纯粹的谎报。
     */
    INVALID,

    /** 用户停用了 —— 【已关闭】。 */
    DISABLED,

    /** 启用中但本周期已用尽 —— 【额度已耗尽】。 */
    EXHAUSTED,

    /** 启用中且有剩余 —— 【限额生效中】。 */
    ACTIVE;

    /**
     * 界面上显示的文字。
     *
     * 用【】包起来是用户点名的写法；四种措辞必须**一眼能分辨**，
     * 因为这正是取代"还剩 N 分钟"那句数字的信息量。
     *
     * ⚠️ **任何一条都不许带数字**：[INVALID] 尤其不能写成
     * "配置无效（0 分钟）" —— 那是把用户第 3 条规则 1
     * （不暴露分钟数）从这个新口子漏回来。`QuotaStatusTest`
     * 里有一条"四种文案都不含数字"的钉子。
     */
    val label: String
        get() = when (this) {
            ACTIVE -> "【限额生效中】"
            EXHAUSTED -> "【额度已耗尽】"
            DISABLED -> "【已关闭】"
            INVALID -> "【配置无效】"
        }

    /** 更短的一行，列表标题那种窄位置用。 */
    val shortLabel: String
        get() = when (this) {
            ACTIVE -> "生效中"
            EXHAUSTED -> "已耗尽"
            DISABLED -> "已关闭"
            INVALID -> "配置无效"
        }

    companion object {

        /**
         * 唯一的状态判定入口（判据的优先级见枚举本身的表格）。
         *
         * @param enabled 额度是否启用
         * @param remainingMinutes 本周期剩余分钟（用尽就是 0）
         * @param limitMinutes 这份额度的上限；**0 表示配置无效**，排在最前
         */
        fun of(
            enabled: Boolean,
            remainingMinutes: Int,
            limitMinutes: Int = UsageQuota.DEFAULT_LIMIT_MINUTES
        ): QuotaStatus = when {
            // ① 上限 == 0：配置本身不成立，与开关、与用量都无关
            limitMinutes < UsageQuota.MIN_CREATABLE_LIMIT_MINUTES -> INVALID
            // ② 停用优先于用尽（既有语义）
            !enabled -> DISABLED
            // ③ 用尽
            remainingMinutes <= 0 -> EXHAUSTED
            // ④ 剩下的才是真正在生效
            else -> ACTIVE
        }

        /** 从"额度 + 它的用量快照"算状态（界面走这条）。 */
        fun of(quota: UsageQuota, usage: QuotaUsage): QuotaStatus =
            of(
                enabled = quota.enabled,
                remainingMinutes = usage.remainingMinutes,
                limitMinutes = quota.limitMinutes
            )
    }
}

/**
 * 额度的**周期判定纯函数**。
 *
 * ⚠️ 这是本次功能的核心，刻意做成无状态、无 Android 依赖、无数据库依赖的
 * 纯函数对象：额度"什么时候刷新""还剩多少""算不算用尽"全部在这里，
 * 于是它们能被纯 JVM 单测精确钉死（见 `QuotaCalculatorTest`）。
 *
 * ## 时间口径统一为 [LocalDateTime]
 *
 * 周期边界是**本地日历**上的时刻（每天 0 点 / 周一 0 点 / 1 号 0 点），
 * 所以判定必须用本地时间。落库用的毫秒由调用方按同一个时区换算
 * （见 `UsageQuotaRepository`），本对象只碰 [LocalDateTime]。
 *
 * ## 「旧额度不可保留」的落地
 *
 * [usedMinutesInPeriod] 把每一段使用**按周期起点裁剪**（区间相交），
 * 只累加落在 `[periodStart, now]` 内的部分，**不做任何结转**：
 *  - 上个周期用超了 → 本周期从 0 开始
 *  - 上个周期没怎么用 → 本周期也只有 [UsageQuota.limitMinutes]，
 *    不会把上个周期省下来的攒进来
 *  - 一个会话横跨周期起点（比如 23:30 用到 00:30）→ 只算 00:00 之后那 30 分钟
 */
object QuotaCalculator {

    /**
     * 本周期起点。
     *
     * | 周期 | 起点 | 跨月 / 跨年时 |
     * |---|---|---|
     * | [ResetPeriod.DAILY] | 当天 00:00 | 不涉及 |
     * | [ResetPeriod.WEEKLY] | **本周一** 00:00 | `TemporalAdjusters.previousOrSame(MONDAY)` 直接按 ISO 周算，周一落在上个月/上一年都正确 |
     * | [ResetPeriod.MONTHLY] | 当月 1 号 00:00 | `withDayOfMonth(1)`，1 月 1 号的上一个周期是去年 12 月 1 号 |
     *
     * **为什么 WEEKLY 用 `previousOrSame(MONDAY)`**：
     *  - 它就是"本周一"的定义，`DayOfWeek` 是 ISO 语义（周一 = 1），与"每周一刷新"完全一致
     *  - 今天正好是周一时，`previousOrSame` 返回**今天 00:00**（而不是上周一）——
     *    这正是我们要的：周一 0 点刷新后，新周期从今天 0 点开始
     *  - 跨月 / 跨年由 `LocalDate` 的日历运算自动处理：
     *    2026-01-01（周四）→ 2025-12-29（周一），
     *    2026-03-01（周日）→ 2026-02-23（周一）
     *
     * **为什么 MONTHLY 用 `withDayOfMonth(1)`**：
     *  - 每个月的 1 号一定存在，不存在"31 号"那种缺日问题
     *  - 跨年由 `LocalDate` 自动处理：2026-01-01 → 2026-01-01（当天就是 1 号，
     *    起点就是自己 00:00，不是去年 12 月）
     */
    fun periodStart(period: ResetPeriod, now: LocalDateTime): LocalDateTime {
        val date = now.toLocalDate()
        val startDate = when (period) {
            ResetPeriod.DAILY -> date
            ResetPeriod.WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            ResetPeriod.MONTHLY -> date.withDayOfMonth(1)
        }
        return startDate.atStartOfDay()
    }

    /**
     * 剩余分钟数。
     *
     * 用 `coerceAtLeast(0)` 而不是 `maxOf`：超额（[usedMinutes] > [limitMinutes]）
     * 与刚好用完（相等）在界面上都显示 0，不会出现负数。
     *
     * @param usedMinutes 本周期**已经用掉**的分钟数（向上取整过的整数分钟）
     */
    fun remainingMinutes(limitMinutes: Int, usedMinutes: Int): Int =
        (limitMinutes - usedMinutes).coerceAtLeast(0)

    /**
     * 是否已经用尽。
     *
     * ⚠️ **`limitMinutes == 0` 时恒为 true** —— 这正是用户要的语义：
     * "时间额度为零时禁用指定软件"。0 分钟额度 = 指定应用一打开就被拦。
     *
     * 反过来，[usedMinutes] 为 0 而额度 > 0 时一定是 false（不能因为"还没用"
     * 就把 0 当成"没有配置"而误拦）。
     */
    fun isExhausted(usedMinutes: Int, limitMinutes: Int): Boolean = usedMinutes >= limitMinutes

    /** [isExhausted] 的毫秒版本，直接喂用量数据的单位。 */
    fun isExhausted(usedMillis: Long, limitMillis: Long): Boolean = usedMillis >= limitMillis

    /**
     * 周期内已用的**分钟数**（向上取整）。
     *
     * 向上取整而不是截断：用了 30 秒也算 1 分钟，否则"每天 1 分钟"的额度
     * 在 59 秒内可以无限次重开而永远用不完。
     */
    fun usedMinutes(usedMillis: Long): Int =
        ((usedMillis + 59_999L) / 60_000L).toInt()

    /**
     * 周期内总时长（原始毫秒）。
     *
     * 每一段按 `[periodStart, now]` **裁剪**后累加：
     * `overlap = min(段结束, now) - max(段开始, periodStart)`，只累加正的部分。
     *
     * 这样跨周期边界的会话只计入落在本周期内的那一段（"旧额度不可保留"），
     * 而完全落在周期外的会话贡献 0。
     *
     * ⚠️ 这里的 `[periodStart, now]` 是**闭区间**：`periodStart` 那一刻开始的会话
     * 算进本周期（本周期从此刻开始），`now` 那一刻结束的会话也算
     * （"到点刷新"意味着刷新前的最后一毫秒仍属旧周期）。
     *
     * 仓库层查库用的是 `sessionsInWindow(周期起点, now)`（**区间相交**），
     * 与本函数的裁剪口径逐字一致 —— 两者必须是同一套区间语义，
     * 否则"少算几分钟"这种偏差只会在周期边界附近偶发，极难复现。
     *
     * 名字里的 `Millis` 是刻意的：分钟是**展示与配置**的单位，
     * 判定用毫秒（见 [isExhausted] 的毫秒重载），避免反复取整带来的边界抖动。
     *
     * @param usage 每一条是"某应用在某段时间内处于前台"的原始段；
     *        单位是毫秒，与 `usage_sessions` 表一致
     * @param packageNames 参与统计的包名；空集合直接返回 0
     * @param period 刷新周期
     * @param now 当前时刻（周期起点由它推出）
     */
    fun usedMillisInPeriod(
        usage: List<UsageSegment>,
        packageNames: Set<String>,
        period: ResetPeriod,
        now: LocalDateTime
    ): Long = totalInWindow(
        usage = usage,
        packageNames = packageNames,
        windowStartMillis = epochMillis(periodStart(period, now)),
        windowEndMillis = epochMillis(now)
    )

    /**
     * [usedMillisInPeriod] 在早期草稿里的名字。
     *
     * 保留它是因为任务描述里点名要求"写一个 `usedMinutesInPeriod` 之类的纯函数"，
     * 而实现最终选择毫秒单位（见上）。两者语义完全相同，只是返回值单位是毫秒。
     */
    fun usedMinutesInPeriod(
        usage: List<UsageSegment>,
        packageNames: Set<String>,
        period: ResetPeriod,
        now: LocalDateTime
    ): Long = usedMillisInPeriod(usage, packageNames, period, now)

    /** [usedMinutesInPeriod] 的毫秒重载：直接给毫秒窗口，供仓库层用。 */
    fun totalInWindow(
        usage: List<UsageSegment>,
        packageNames: Set<String>,
        windowStartMillis: Long,
        windowEndMillis: Long
    ): Long {
        if (packageNames.isEmpty()) return 0L
        if (windowEndMillis <= windowStartMillis) return 0L
        var total = 0L
        usage.forEach { segment ->
            if (segment.packageName !in packageNames) return@forEach
            val overlapStart = maxOf(segment.startMillis, windowStartMillis)
            val overlapEnd = minOf(segment.endMillis, windowEndMillis)
            val overlap = overlapEnd - overlapStart
            if (overlap > 0L) total += overlap
        }
        return total
    }

    /**
     * 从一批额度与原始用量里算出"当前应当被禁用的应用"。
     *
     * 纯函数、无副作用：仓库层（[com.zilv.app.data.repository.UsageQuotaRepository]）
     * 只负责把库里的数据读出来喂给它，拦截服务只负责拿结果去拦。
     *
     * 语义细节：
     *  - 只有 [UsageQuota.enabled] 的额度参与判定；停用的额度完全不拦
     *  - **共用额度**：一个额度下所有绑定的包名用的是同一份 [UsageQuota.limitMinutes]，
     *    该额度内所有应用的用量**加在一起**与该额度比较
     *  - 一个包被多个额度绑定时，**任意一个**额度用尽即被禁用
     *    （口径与"白名单是并集"相反：禁用是"最严的那个说了算"）
     *  - [UsageQuota.isValid] 为假的脏数据整条跳过，不让它误拦
     *
     * @return 被禁用的包名 → 触发它的额度（用于诊断与提示文案）
     */
    fun exhaustedApps(
        quotas: List<UsageQuota>,
        usage: List<UsageSegment>,
        now: LocalDateTime
    ): Map<String, QuotaExhaustion> = exhaustedPackages(
        enforcement = quotas.filter { it.enabled && it.isValid }.map { QuotaEnforcer.from(it) },
        usage = usage,
        now = now
    )

    /**
     * [exhaustedApps] 的窄重载：直接吃 [QuotaEnforcer]。
     *
     * 拦截链路走这条 —— 它只需要"启用中的额度"的三个字段，
     * 不应该依赖完整的额度配置对象。
     *
     * ⚠️ 方法名与 [exhaustedApps] **刻意不同**：两者的参数在 JVM 上
     * 擦除后都是 `(List, List, LocalDateTime)`，同名会直接
     * "Platform declaration clash" 编译失败（Kotlin 不允许这种重载）。
     */
    fun exhaustedPackages(
        enforcement: List<QuotaEnforcer>,
        usage: List<UsageSegment>,
        now: LocalDateTime
    ): Map<String, QuotaExhaustion> {
        val result = mutableMapOf<String, QuotaExhaustion>()
        for (rule in enforcement) {
            val packages = rule.packageNames.filter { it.isNotBlank() }.toSet()
            if (packages.isEmpty()) continue

            val used = usedMillisInPeriod(usage, packages, rule.resetPeriod, now)
            if (!isExhausted(used, rule.limitMinutes * 60_000L)) continue

            val exhaustion = QuotaExhaustion(
                quotaId = rule.quotaId,
                quotaName = rule.quotaName,
                limitMinutes = rule.limitMinutes,
                usedMinutes = usedMinutes(used),
                resetPeriod = rule.resetPeriod
            )
            for (pkg in packages) {
                // 已有更严格（额度更小的）记录时不覆盖，让提示文案稳定
                val existing = result[pkg]
                if (existing == null || exhaustion.limitMinutes < existing.limitMinutes) {
                    result[pkg] = exhaustion
                }
            }
        }
        return result
    }

    /**
     * 把本地时间换算成毫秒。
     *
     * 公开出来是为了让仓库层用**同一个**换算（它要把周期起点变成
     * `usage_sessions.started_at` 的比较值）；两处各写一遍迟早会漂移，
     * 而这类漂移的症状是"周期边界附近偶尔多算/少算一分钟"，极难复现。
     */
    fun epochMillis(at: LocalDateTime): Long =
        at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/**
 * 一条**启用中的**额度规则：拦截判定只需要这些信息。
 *
 * 与 [UsageQuota] 分开是为了让"额度配置"与"额度执行"两件事各自可以演化：
 * 界面上要展示的是完整配置（含停用项），而拦截侧的契约只有三个字段。
 * 这也是 `PlanRuleSource` 那套"窄接口"思路在数据形状上的延伸。
 */
data class QuotaEnforcer(
    val quotaId: Long,
    val quotaName: String,
    val limitMinutes: Int,
    val resetPeriod: ResetPeriod,
    val packageNames: Set<String>
) {
    companion object {
        /**
         * 什么都不限制 —— 拦截判定的默认值。
         *
         * 用它当默认值可以让**既有调用点与测试一行都不用改**，
         * 同时把"这里没有额度限制"表达得比 `null` 更明确
         * （与 `PlanRuleSource.NONE` 同一手法）。
         *
         * [limitMinutes] 取 [UsageQuota.MAX_LIMIT_MINUTES] 而不是 `Int.MAX_VALUE`：
         * 判定时会做 `limitMinutes * 60_000L`，用 `Int.MAX_VALUE` 会让
         * 那个乘法溢出成负数，于是"什么额度都没有"反而变成"全都超额"——
         * 这正是最坏的一类 bug（会把用户所有应用全禁掉）。
         * 744 小时（= [UsageQuota.MAX_LIMIT_MINUTES]）配合空的 [packageNames]
         * 已经等价于"不限制"：判定时 `44640 * 60_000L = 2_678_400_000` 远小于
         * `Long.MAX_VALUE`，不会溢出。
         */
        val NONE = QuotaEnforcer(
            quotaId = 0L,
            quotaName = "",
            limitMinutes = UsageQuota.MAX_LIMIT_MINUTES,
            resetPeriod = ResetPeriod.DAILY,
            packageNames = emptySet()
        )

        fun from(quota: UsageQuota): QuotaEnforcer = QuotaEnforcer(
            quotaId = quota.id,
            quotaName = quota.name,
            limitMinutes = quota.limitMinutes,
            resetPeriod = quota.resetPeriod,
            packageNames = quota.effectivePackages
        )
    }
}

/**
 * 一段前台使用（原始数据），单位毫秒。
 *
 * 刻意不复用 `data.repository.UsageSession`：那是带 id 的领域视图，
 * 而这里只需要"包名 + 起止"，让 [QuotaCalculator] 完全独立于数据层，
 * 单测里直接构造即可。
 */
data class UsageSegment(
    val packageName: String,
    val startMillis: Long,
    val endMillis: Long
)

/**
 * 某个额度被用尽的说明。
 *
 * 带上额度名与用量，是为了让**提示文案与诊断页**能直接说清原因
 * （"抖音今天的 30 分钟已用完"），而不是只有一句"被拦截"。
 */
data class QuotaExhaustion(
    val quotaId: Long,
    val quotaName: String,
    val limitMinutes: Int,
    val usedMinutes: Int,
    val resetPeriod: ResetPeriod
) {
    /** 给用户看的一句话。 */
    val message: String
        get() = "「$quotaName」的 ${UsageQuota.formatMinutes(limitMinutes)}已用完" +
            "（${resetPeriod.shortLabel}刷新，已用 ${usedMinutes} 分钟）"
}
