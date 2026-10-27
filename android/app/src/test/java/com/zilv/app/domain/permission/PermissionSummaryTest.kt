package com.zilv.app.domain.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 权限向导模型的单元测试（PRD §3.10）。
 *
 * 重点验证两件事：
 * 1. **顺序依赖**：受限设置必须排在前置位置（它决定后面几个开关能不能点）
 * 2. **必需 vs 可选**：通知使用权是唯一可跳过的一步，不应阻塞就绪判定
 */
class PermissionSummaryTest {

    @Test
    fun `步骤顺序 - 受限设置必须排在第一组前置位置`() {
        val ordered = PermissionStep.ordered
        assertThat(ordered.toList()).containsExactly(
            PermissionStep.NOTIFICATIONS,
            PermissionStep.RESTRICTED_SETTINGS,
            PermissionStep.ACCESSIBILITY,
            PermissionStep.OVERLAY,
            PermissionStep.EXACT_ALARM,
            PermissionStep.BATTERY_OPTIMIZATION,
            PermissionStep.AUTO_START,
            PermissionStep.LOCATION,
            PermissionStep.NOTIFICATION_ACCESS
        ).inOrder()

        // 「允许受限设置」必须出现在无障碍 / 悬浮窗 / 通知使用权之前
        val idxRestricted = ordered.indexOf(PermissionStep.RESTRICTED_SETTINGS)
        assertThat(idxRestricted).isLessThan(ordered.indexOf(PermissionStep.ACCESSIBILITY))
        assertThat(idxRestricted).isLessThan(ordered.indexOf(PermissionStep.OVERLAY))
        assertThat(idxRestricted).isLessThan(ordered.indexOf(PermissionStep.NOTIFICATION_ACCESS))
    }

    @Test
    fun `步骤数量 - PRD 定义的 9 步`() {
        assertThat(PermissionStep.ordered).hasSize(9)
    }

    @Test
    fun `必需性 - 只有通知使用权等少数几步是可选的`() {
        assertThat(PermissionStep.ordered.filter { it.required }).containsExactly(
            PermissionStep.NOTIFICATIONS,
            PermissionStep.RESTRICTED_SETTINGS,
            PermissionStep.ACCESSIBILITY,
            PermissionStep.OVERLAY,
            PermissionStep.EXACT_ALARM,
            PermissionStep.LOCATION
        )
        assertThat(PermissionStep.ordered.filter { !it.required }).containsExactly(
            PermissionStep.BATTERY_OPTIMIZATION,
            PermissionStep.AUTO_START,
            PermissionStep.NOTIFICATION_ACCESS
        )
    }

    @Test
    fun `进度 - 空状态为0`() {
        assertThat(PermissionSummary.from(emptySet()).progress).isEqualTo(0f)
    }

    @Test
    fun `进度 - 全部必需完成即为1，与可选步骤无关`() {
        val requiredOnly = PermissionStep.ordered.filter { it.required }.toSet()
        assertThat(PermissionSummary.from(requiredOnly).progress).isEqualTo(1f)
    }

    @Test
    fun `进度 - 只计入必需步骤`() {
        // 6 个必需步骤，完成 3 个 -> 0.5
        val half = setOf(
            PermissionStep.NOTIFICATIONS,
            PermissionStep.RESTRICTED_SETTINGS,
            PermissionStep.ACCESSIBILITY
        )
        assertThat(PermissionSummary.from(half).progress).isEqualTo(0.5f)

        // 再加两个可选的，进度不变
        val withOptional = half + setOf(
            PermissionStep.BATTERY_OPTIMIZATION,
            PermissionStep.AUTO_START
        )
        assertThat(PermissionSummary.from(withOptional).progress).isEqualTo(0.5f)
    }

    @Test
    fun `就绪判定 - 缺任一必需步骤都未就绪`() {
        val allRequired = PermissionStep.ordered.filter { it.required }.toSet()
        assertThat(PermissionSummary.from(allRequired).ready).isTrue()

        allRequired.forEach { missing ->
            val s = PermissionSummary.from(allRequired - missing)
            assertThat(s.ready).isFalse()
            assertThat(s.missingRequired).containsExactly(missing)
        }
    }

    @Test
    fun `就绪判定 - 缺少可选步骤不影响就绪`() {
        val allRequired = PermissionStep.ordered.filter { it.required }.toSet()
        val s = PermissionSummary.from(allRequired) // 可选全缺
        assertThat(s.ready).isTrue()
        assertThat(s.missingOptional).hasSize(3)
    }

    @Test
    fun `nextStep - 优先返回第一个缺失的必需步骤`() {
        val s = PermissionSummary.from(setOf(PermissionStep.NOTIFICATIONS))
        assertThat(s.nextStep).isEqualTo(PermissionStep.RESTRICTED_SETTINGS)
    }

    @Test
    fun `nextStep - 必需全完成时返回第一个缺失的可选步骤`() {
        val allRequired = PermissionStep.ordered.filter { it.required }.toSet()
        val s = PermissionSummary.from(allRequired)
        assertThat(s.nextStep).isEqualTo(PermissionStep.BATTERY_OPTIMIZATION)
    }

    @Test
    fun `nextStep - 全部完成时为 null`() {
        val all = PermissionStep.ordered.toSet()
        val s = PermissionSummary.from(all)
        assertThat(s.nextStep).isNull()
        assertThat(s.ready).isTrue()
        assertThat(s.progress).isEqualTo(1f)
        assertThat(s.missingRequired).isEmpty()
        assertThat(s.missingOptional).isEmpty()
    }

    @Test
    fun `isGranted - 按步骤查询`() {
        val s = PermissionSummary.from(setOf(PermissionStep.ACCESSIBILITY))
        assertThat(s.isGranted(PermissionStep.ACCESSIBILITY)).isTrue()
        assertThat(s.isGranted(PermissionStep.OVERLAY)).isFalse()
    }

    @Test
    fun `每步都有标题与说明文案`() {
        PermissionStep.ordered.forEach { step ->
            assertThat(step.title).isNotEmpty()
            assertThat(step.why).isNotEmpty()
        }
    }
}
