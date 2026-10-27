package com.zilv.app.domain.quota

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [UsageQuota.allowsEnabledChange] 的单元测试（v2.12，修 Bug 2）。
 *
 * ## 用户报的 bug
 *
 * > 「时间限额关闭后无法再次打开（显示为灰色）。」
 *
 * 根因：v2.11 把 [QuotaStatus.DISABLED] 也纳入了 `allowsMutation`（本意是堵
 * "先关掉再删掉"），而界面用的是 `Switch(enabled = !mutationLocked)` —— 一刀切。
 * 于是「关闭」点得动、「打开」点不动，一份额度关掉之后再也开不回来。
 *
 * 修法：把开关的**方向**单独判 —— 打开恒允许、关闭才受冻结约束。
 * 这个文件就是那条判据的钉子（纯函数，不查库、不看时间）。
 */
class UsageQuotaEnabledDirectionTest {

    private fun quota(name: String = "短视频", limitMinutes: Int = 30, enabled: Boolean = true) =
        UsageQuota(
            name = name,
            limitMinutes = limitMinutes,
            enabled = enabled,
            packageNames = setOf("com.example.app")
        )

    // ------------------------------------------------ 打开方向：永远可点

    @Test
    fun `已关闭的额度永远可以再打开 —— 这就是本次要修的往返`() {
        val q = quota(enabled = false)
        // 关掉之后状态就是 DISABLED（而 DISABLED 的 allowsMutation 是 false）
        assertThat(q.allowsMutation(QuotaStatus.DISABLED)).isFalse()
        // 但"打开"方向必须放行，否则这份额度就永远回不来了
        assertThat(q.allowsEnabledChange(target = true, status = QuotaStatus.DISABLED)).isTrue()
        assertThat(q.enabledChangeRejectionReason(true, QuotaStatus.DISABLED)).isNull()
    }

    @Test
    fun `四种状态下打开方向一律放行`() {
        val q = quota()
        QuotaStatus.entries.forEach { status ->
            assertThat(q.allowsEnabledChange(target = true, status = status))
                .isTrue()
        }
    }

    @Test
    fun `已用尽也能打开 —— 那是唯一的出口（既有规格，不许因为本次改动放宽或收紧）`() {
        val q = quota()
        assertThat(q.allowsEnabledChange(target = true, status = QuotaStatus.EXHAUSTED)).isTrue()
        // 但"关掉"照旧被拒：用完就关掉正是要堵的绕过路径
        assertThat(q.allowsEnabledChange(target = false, status = QuotaStatus.EXHAUSTED)).isFalse()
    }

    // ------------------------------------------------ 关闭方向：照旧冻结

    @Test
    fun `关闭方向在生效中时放行`() {
        val q = quota()
        assertThat(q.allowsEnabledChange(target = false, status = QuotaStatus.ACTIVE)).isTrue()
        assertThat(q.enabledChangeRejectionReason(false, QuotaStatus.ACTIVE)).isNull()
    }

    @Test
    fun `关闭方向在已用尽时被拒 —— 防绕过的本意一点没放宽`() {
        val q = quota()
        assertThat(q.allowsEnabledChange(target = false, status = QuotaStatus.EXHAUSTED)).isFalse()
        val reason = q.enabledChangeRejectionReason(false, QuotaStatus.EXHAUSTED)
        assertThat(reason).contains("已用完")
        assertThat(reason).contains("刷新点")
    }

    @Test
    fun `关闭方向对配置无效的坏数据放行 —— 坏数据不该被冻死`() {
        val q = quota(limitMinutes = 0)
        assertThat(q.allowsEnabledChange(target = false, status = QuotaStatus.INVALID)).isTrue()
    }

    @Test
    fun `关闭方向对已经关闭的额度被拒 —— 与 allowsMutation 完全一致`() {
        val q = quota(enabled = false)
        // 再关一次本来就是空操作，拒绝它只是在保持一致：一份停用的额度
        // 改不了也删不掉（"先关掉再删掉"照旧堵着），但**开得回来**。
        assertThat(q.allowsEnabledChange(target = false, status = QuotaStatus.DISABLED)).isFalse()
        assertThat(q.allowsMutation(QuotaStatus.DISABLED)).isFalse()
    }

    // ------------------------------------------- 与既有两个判据的关系

    @Test
    fun `关闭方向的判据逐字等于 allowsMutation —— 不许分叉`() {
        val q = quota()
        QuotaStatus.entries.forEach { status ->
            assertThat(q.allowsEnabledChange(target = false, status = status))
                .isEqualTo(q.allowsMutation(status))
        }
    }

    @Test
    fun `编辑与删除照旧看 allowsMutation —— 不看方向`() {
        // 这两个动作没有"方向"可言，所以 DISABLED 下它们依然是禁止的。
        // 界面上那个"为什么点不动"的提示文案也照旧指向"先把它打开"，
        // 而现在"打开"真的点得动了（这就是本次修复）。
        val q = quota(enabled = false)
        assertThat(q.allowsMutation(QuotaStatus.DISABLED)).isFalse()
        assertThat(q.mutationRejectionReason(QuotaStatus.DISABLED)).contains("先把它打开")
    }
}
