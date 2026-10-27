package com.zilv.app.domain.quota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 额度**文字状态**的纯函数测试（v2.11，用户第 3 条规则 1；本轮扩到四态）。
 *
 * ## 用户原话
 *
 * "**不展示**还剩多少分钟、也不展示已用多少分钟（已用 + 上限等于剩余，
 * 等于变相暴露），只展示文字状态：**【限额生效中】** / **【额度已耗尽】** …
 * 停用 → 【已关闭】。建议把'状态文案'做成**纯函数**（输入 enabled + 剩余分钟
 * → 输出状态文案），便于单测。"
 *
 * ## 本轮（用户决定"不予许设置成 0 分钟"）新增的第四条
 *
 * 用户对"0 分钟额度会被永久冻结"的答复是"0 从根本上是非法配置"，
 * 并要求把 0 判成**"配置非法"而不是"额度耗尽"**、**不冻结**。于是：
 *  - 判据多一个输入：**上限**（[QuotaStatus.of] 的 `limitMinutes`）
 *  - 优先级：`上限 == 0` > `停用` > `用尽` > `生效中`（四种情形逐一钉死）
 *
 * ⚠️ "停用优先于用尽"这条**既有语义一个字都没改**；
 * 而"上限 5 分钟用满 5 分钟 → 仍然冻结"那条钉子不在这里，
 * 它在 `QuotaViewModelTest` 与 `UsageQuotaRepositoryTest` 里
 * （那两条同时验证状态与"三个动作被拒"）。
 *
 * 界面（`QuotaScreen` / `AddScreen`）只负责画 [QuotaStatus.label]。
 */
class QuotaStatusTest {

    // ============================================================ 四种取值

    @Test
    fun `启用且还有剩余 → 限额生效中`() {
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 30))
            .isEqualTo(QuotaStatus.ACTIVE)
        // 剩 1 分钟也算生效中（只要 > 0）
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 1))
            .isEqualTo(QuotaStatus.ACTIVE)
    }

    @Test
    fun `启用但剩余为 0 → 额度已耗尽`() {
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 0))
            .isEqualTo(QuotaStatus.EXHAUSTED)
    }

    @Test
    fun `剩余为负数也归到已耗尽 —— 界面上不该出现负的剩余`() {
        // QuotaCalculator.remainingMinutes 本身就会 coerceAtLeast(0)，
        // 但这里也兜一下：万一将来有别的调用点直接喂差值进来。
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = -5))
            .isEqualTo(QuotaStatus.EXHAUSTED)
    }

    @Test
    fun `停用 → 已关闭`() {
        assertThat(QuotaStatus.of(enabled = false, remainingMinutes = 30))
            .isEqualTo(QuotaStatus.DISABLED)
    }

    @Test
    fun `停用优先于用尽 —— 停用的额度不该被说成额度已耗尽`() {
        // 一份被停用的额度此刻**并没有**在生效（不参与拦截），
        // 说成"额度已耗尽"会让用户以为应用被锁住了。
        // 这与 QuotaCalculator.exhaustedPackages 只吃启用额度是同一个口径。
        assertThat(QuotaStatus.of(enabled = false, remainingMinutes = 0))
            .isEqualTo(QuotaStatus.DISABLED)
    }

    @Test
    fun `上限为 0 → 配置无效，而不是额度已耗尽`() {
        // ⚠️ 本轮新规格（用户决定"不予许设置成 0 分钟"）。
        // 旧规格里"上限 0 + 剩余 0"会落到 EXHAUSTED，于是这条坏数据被冻住、
        // 用户再也改不了删不掉。用户明确要求把它判成"配置非法"、不冻结：
        // "冻结是为了防'用完就关掉'，而一个上限为 0 的配置永远不可能被'用完'，
        //  它是坏数据；把它冻住只会让用户无路可走。"
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 0, limitMinutes = 0))
            .isEqualTo(QuotaStatus.INVALID)
    }

    // ============================================================ 优先级

    @Test
    fun `上限为 0 排在最前 —— 无论停用与否、剩余多少`() {
        // 配置本身不成立这件事，与开关、与用量都无关。
        // 说成"已关闭"会误导用户"打开就好"，说成"已耗尽"会误导他"等刷新就好"——
        // 两种误导都让他修不好这份坏数据。
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 30, limitMinutes = 0))
            .isEqualTo(QuotaStatus.INVALID)
        assertThat(QuotaStatus.of(enabled = false, remainingMinutes = 30, limitMinutes = 0))
            .isEqualTo(QuotaStatus.INVALID)
        assertThat(QuotaStatus.of(enabled = false, remainingMinutes = 0, limitMinutes = 0))
            .isEqualTo(QuotaStatus.INVALID)
    }

    @Test
    fun `四态的优先级逐层下降 —— 无效 高于 停用 高于 用尽 高于 生效中`() {
        // 一张表把优先级顺序一次钉死（判据的唯一入口就是 QuotaStatus.of）
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 10, limitMinutes = 0))
            .isEqualTo(QuotaStatus.INVALID)   // ① 上限 0：不管别的
        assertThat(QuotaStatus.of(enabled = false, remainingMinutes = 10, limitMinutes = 5))
            .isEqualTo(QuotaStatus.DISABLED)  // ② 停用：优先于"还有剩余"
        assertThat(QuotaStatus.of(enabled = false, remainingMinutes = 0, limitMinutes = 5))
            .isEqualTo(QuotaStatus.DISABLED)  // ② 停用：也优先于"用尽"
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 0, limitMinutes = 5))
            .isEqualTo(QuotaStatus.EXHAUSTED) // ③ 用尽：优先于"生效中"
        assertThat(QuotaStatus.of(enabled = true, remainingMinutes = 1, limitMinutes = 5))
            .isEqualTo(QuotaStatus.ACTIVE)    // ④ 剩下的才是生效中
    }

    @Test
    fun `与拦截判定同口径 —— 停用的额度无论剩多少都不该显示成生效中`() {
        listOf(0, 1, 30, 44640).forEach { remaining ->
            assertThat(QuotaStatus.of(enabled = false, remainingMinutes = remaining))
                .isEqualTo(QuotaStatus.DISABLED)
        }
    }

    // ============================================================ 文案

    @Test
    fun `四种状态的文案互不相同且一眼能分辨`() {
        val labels = QuotaStatus.entries.map { it.label }
        assertThat(labels).containsExactly(
            "【配置无效】",
            "【已关闭】",
            "【额度已耗尽】",
            "【限额生效中】"
        )
        // 四个措辞必须两两不同 —— 否则用户分不出四种情况
        assertThat(labels.toSet()).hasSize(QuotaStatus.entries.size)
        assertThat(labels.toSet()).hasSize(4)
    }

    @Test
    fun `短文案也互不相同`() {
        val shorts = QuotaStatus.entries.map { it.shortLabel }
        assertThat(shorts).containsExactly("配置无效", "已关闭", "已耗尽", "生效中")
        assertThat(shorts.toSet()).hasSize(4)
    }

    @Test
    fun `文案用【】包起来 —— 用户点名的写法`() {
        QuotaStatus.entries.forEach { status ->
            assertThat(status.label).startsWith("【")
            assertThat(status.label).endsWith("】")
        }
    }

    @Test
    fun `任何一条文案里都不许出现数字 —— 规则 1 对新状态同样成立`() {
        // 用户第 3 条规则 1 的有效范围是"界面能说什么"，新加的"配置无效"是
        // 最容易漏的一个口子："配置无效（0 分钟）"会把分钟数从这个新口子放回来。
        QuotaStatus.entries.forEach { status ->
            assertThat(status.label.none { it.isDigit() }).isTrue()
            assertThat(status.shortLabel.none { it.isDigit() }).isTrue()
        }
    }

    // ============================================================ 从额度 + 用量算

    @Test
    fun `能从额度与用量快照直接算状态`() {
        val quota = UsageQuota(name = "短视频", limitMinutes = 30, enabled = true, packageNames = setOf("a"))
        assertThat(
            QuotaStatus.of(
                quota,
                QuotaUsage(quotaId = 1L, usedMinutes = 10, remainingMinutes = 20, exhausted = false)
            )
        ).isEqualTo(QuotaStatus.ACTIVE)

        assertThat(
            QuotaStatus.of(
                quota,
                QuotaUsage(quotaId = 1L, usedMinutes = 30, remainingMinutes = 0, exhausted = true)
            )
        ).isEqualTo(QuotaStatus.EXHAUSTED)
    }

    @Test
    fun `上限为 0 的额度行算出来是配置无效，且与它此刻是否停用无关`() {
        // 库里存量坏数据的真实形态：limitMinutes == 0，usage 会算出
        // remaining == 0 / exhausted == true（见 UsageQuotaRepository.usageOf）。
        // 只看那份 usage 会得到"已耗尽"——加上上限这个输入之后才是正确的"配置无效"。
        val legacy = UsageQuota(
            id = 9L,
            name = "直接禁用",
            limitMinutes = 0,
            enabled = true,
            packageNames = setOf("a")
        )
        val exhaustedLooking = QuotaUsage(
            quotaId = 9L,
            usedMinutes = 0,
            remainingMinutes = 0,
            exhausted = true
        )
        assertThat(QuotaStatus.of(legacy, exhaustedLooking)).isEqualTo(QuotaStatus.INVALID)
        assertThat(QuotaStatus.of(legacy.copy(enabled = false), exhaustedLooking))
            .isEqualTo(QuotaStatus.INVALID)
    }

    @Test
    fun `还没算出用量时按已耗尽处理 —— 宁可少让人点，也不谎报生效中`() {
        // QuotaUsage.UNKNOWN 是 exhausted = false / remaining = 0（一个"还没算"的占位）。
        // 它落到 EXHAUSTED 是**刻意**的：界面上"三个按钮置灰"的判据就是它，
        // 宁可先灰一下（下一次 refreshUsage 就会纠正），也不能因为
        // "还没算出来"就谎报"限额生效中"、让用户以为额度还在。
        //
        // 实际使用时不会碰到这个组合：`QuotaViewModel` 的行是先拿到
        // `rows`（usage 已经在同一次快照里算好）才渲染的。
        val quota = UsageQuota(name = "x", limitMinutes = 30, packageNames = setOf("a"))
        assertThat(QuotaStatus.of(quota, QuotaUsage.UNKNOWN))
            .isEqualTo(QuotaStatus.EXHAUSTED)
    }
}
