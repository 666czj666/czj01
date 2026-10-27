package com.zilv.app.domain.notify

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 通知脱敏决策的单元测试（PRD §3.6）。
 *
 * 最重要的一组断言围绕平台硬约束：**常驻通知撤不掉**（官方 javadoc 逐字核实），
 * 因此必须能被识别为 CannotMask 而不是盲目重试。
 */
class NotificationMaskPolicyTest {

    private val wechat = "com.tencent.mm"

    private fun input(
        packageName: String = wechat,
        isOngoing: Boolean = false,
        isClearable: Boolean = true,
        targetPackages: Set<String> = NotificationMaskPolicy.DEFAULT_TARGETS,
        locked: Boolean = true,
        hasListenerAccess: Boolean = true
    ) = NotificationMaskPolicy.Input(
        packageName = packageName,
        isOngoing = isOngoing,
        isClearable = isClearable,
        targetPackages = targetPackages,
        locked = locked,
        hasListenerAccess = hasListenerAccess
    )

    @Test
    fun `正常目标通知在锁机态被脱敏`() {
        val d = NotificationMaskPolicy.decide(input())
        assertThat(d).isInstanceOf(NotificationMaskPolicy.Decision.Mask::class.java)
        assertThat((d as NotificationMaskPolicy.Decision.Mask).packageName).isEqualTo(wechat)
    }

    @Test
    fun `没有通知使用权时静默停用`() {
        assertThat(NotificationMaskPolicy.decide(input(hasListenerAccess = false)))
            .isEqualTo(NotificationMaskPolicy.Decision.Ignore)
    }

    @Test
    fun `非锁机态不脱敏`() {
        assertThat(NotificationMaskPolicy.decide(input(locked = false)))
            .isEqualTo(NotificationMaskPolicy.Decision.Ignore)
    }

    @Test
    fun `非目标应用放行`() {
        assertThat(NotificationMaskPolicy.decide(input(packageName = "com.android.chrome")))
            .isEqualTo(NotificationMaskPolicy.Decision.Ignore)
    }

    @Test
    fun `空包名放行`() {
        assertThat(NotificationMaskPolicy.decide(input(packageName = "")))
            .isEqualTo(NotificationMaskPolicy.Decision.Ignore)
        assertThat(NotificationMaskPolicy.decide(input(packageName = "   ")))
            .isEqualTo(NotificationMaskPolicy.Decision.Ignore)
    }

    // -------------------------------------------------- 平台硬约束（最关键）

    @Test
    fun `常驻通知无法撤回，返回 CannotMask`() {
        val d = NotificationMaskPolicy.decide(input(isOngoing = true))
        assertThat(d).isInstanceOf(NotificationMaskPolicy.Decision.CannotMask::class.java)
        val c = d as NotificationMaskPolicy.Decision.CannotMask
        assertThat(c.packageName).isEqualTo(wechat)
        assertThat(c.reason).contains("常驻")
    }

    @Test
    fun `不可清除的通知返回 CannotMask`() {
        val d = NotificationMaskPolicy.decide(input(isClearable = false))
        assertThat(d).isInstanceOf(NotificationMaskPolicy.Decision.CannotMask::class.java)
        assertThat((d as NotificationMaskPolicy.Decision.CannotMask).reason).contains("isClearable")
    }

    @Test
    fun `常驻判定优先于可清除判定`() {
        val d = NotificationMaskPolicy.decide(input(isOngoing = true, isClearable = false))
        assertThat((d as NotificationMaskPolicy.Decision.CannotMask).reason).contains("常驻")
    }

    @Test
    fun `无权限优先于锁机态判定`() {
        // 无权限 + 未锁机，仍应走"无权限"这条最早的分支
        assertThat(
            NotificationMaskPolicy.decide(input(hasListenerAccess = false, locked = false))
        ).isEqualTo(NotificationMaskPolicy.Decision.Ignore)
    }

    // ---------------------------------------------------------------- 文案

    @Test
    fun `单条消息的脱敏文案`() {
        assertThat(NotificationMaskPolicy.maskedText("微信", 1)).isEqualTo("微信 · 有新消息")
        assertThat(NotificationMaskPolicy.maskedText("微信", 0)).isEqualTo("微信 · 有新消息")
    }

    @Test
    fun `多条消息聚合成计数`() {
        assertThat(NotificationMaskPolicy.maskedText("微信", 3)).isEqualTo("微信 · 3 条新消息")
        assertThat(NotificationMaskPolicy.maskedText("支付宝", 12)).isEqualTo("支付宝 · 12 条新消息")
    }

    @Test
    fun `默认脱敏目标包含微信与支付宝`() {
        assertThat(NotificationMaskPolicy.DEFAULT_TARGETS)
            .containsAtLeast("com.tencent.mm", "com.eg.android.AlipayGphone")
    }
}
