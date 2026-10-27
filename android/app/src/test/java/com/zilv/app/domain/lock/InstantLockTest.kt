package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [InstantLock] 的单元测试（v2.12）。
 *
 * 用户要求：「添加即时锁机功能，锁机**可选时间从 0 分到 12 小时**。
 * 入口放在今天界面的最上方。」
 *
 * 这个文件把三件最容易写错的事钉死（全是纯函数，不碰数据库、不碰时钟）：
 *  1. **时长区间**：0..720 分钟，越界夹取
 *  2. **0 分的语义**：立刻锁、到下一个整分钟解除（而不是退化成"全天"）
 *  3. **跨零点**：20:00 锁 12 小时必须到次日 08:00，而不是被截到今天 24:00
 */
class InstantLockTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 14)

    private fun at(hour: Int, minute: Int): LocalDateTime = today.atTime(hour, minute)

    private fun minuteOf(hour: Int, minute: Int): Int = hour * 60 + minute

    // ---------------------------------------------------------------- 区间

    @Test
    fun `时长上限是 12 小时`() {
        assertThat(InstantLock.MIN_MINUTES).isEqualTo(0)
        assertThat(InstantLock.MAX_MINUTES).isEqualTo(12 * 60)
        assertThat(InstantLock.MAX_MINUTES).isEqualTo(720)
    }

    @Test
    fun `越界时长被夹进区间`() {
        assertThat(InstantLock.clampMinutes(-30)).isEqualTo(0)
        assertThat(InstantLock.clampMinutes(0)).isEqualTo(0)
        assertThat(InstantLock.clampMinutes(30)).isEqualTo(30)
        assertThat(InstantLock.clampMinutes(720)).isEqualTo(720)
        assertThat(InstantLock.clampMinutes(99999)).isEqualTo(720)
    }

    @Test
    fun `快捷档位覆盖用户点名的七档`() {
        // 用户建议："15 分 / 30 分 / 1 小时 / 2 小时 / 4 小时 / 8 小时 / 12 小时"
        assertThat(InstantLock.PRESETS)
            .containsExactly(15, 30, 60, 120, 240, 480, 720).inOrder()
        // 每一档都在合法区间里
        assertThat(InstantLock.PRESETS.all { it in InstantLock.MIN_MINUTES..InstantLock.MAX_MINUTES })
            .isTrue()
    }

    @Test
    fun `时长文案`() {
        assertThat(InstantLock.label(30)).isEqualTo("30 分钟")
        assertThat(InstantLock.label(60)).isEqualTo("1 小时")
        assertThat(InstantLock.label(120)).isEqualTo("2 小时")
        assertThat(InstantLock.label(90)).isEqualTo("1 小时 30 分")
        assertThat(InstantLock.label(720)).isEqualTo("12 小时")
        // 0 分必须把它的含义说出来，否则用户不知道"锁 0 分钟"是什么
        assertThat(InstantLock.label(0)).contains("立刻锁")
    }

    // ------------------------------------------------------- 窗口：不越界

    @Test
    fun `普通时长就是从现在开始的窗口`() {
        val w = InstantLock.windowAt(minuteOf(16, 0), 30)
        assertThat(w.startMinute).isEqualTo(minuteOf(16, 0))
        assertThat(w.endMinute).isEqualTo(minuteOf(16, 30))
        assertThat(w.durationMinutes).isEqualTo(30)
        assertThat(w.crossesMidnight).isFalse()
    }

    /**
     * **0 分的边界**：`TimeWindow(start, start)` 会被当成"全天"（跨零点语义），
     * 那是最危险的形态 —— 本想锁 0 分钟却锁了一整天。
     * 所以这里必须取 `start + 1`。
     */
    @Test
    fun `0 分不会退化成全天，而是锁到下一个整分钟`() {
        val w = InstantLock.windowAt(minuteOf(16, 0), 0)
        assertThat(w.crossesMidnight).isFalse()
        assertThat(w.durationMinutes).isEqualTo(1)
        assertThat(w.startMinute).isEqualTo(minuteOf(16, 0))
        assertThat(w.endMinute).isEqualTo(minuteOf(16, 1))
    }

    @Test
    fun `23点59分起锁 0 分也落在当天之内`() {
        val w = InstantLock.windowAt(minuteOf(23, 59), 0)
        assertThat(w.startMinute).isEqualTo(1439)
        assertThat(w.endMinute).isEqualTo(1440)
        assertThat(w.durationMinutes).isEqualTo(1)
        assertThat(w.crossesMidnight).isFalse()
    }

    @Test
    fun `时刻超出取值范围时被夹到当天最后一分钟`() {
        val w = InstantLock.windowAt(99999, 30)
        assertThat(w.startMinute).isEqualTo(1439)
    }

    // ---------------------------------------------------------- 跨零点

    @Test
    fun `20点锁 12 小时到次日 8 点 —— 不许被截断到今天 24 点`() {
        val w = InstantLock.windowAt(minuteOf(20, 0), 720)
        assertThat(w.crossesMidnight).isTrue()
        assertThat(w.durationMinutes).isEqualTo(720)
        assertThat(w.startMinute).isEqualTo(minuteOf(20, 0))
        assertThat(w.endMinute).isEqualTo(minuteOf(8, 0))
    }

    @Test
    fun `跨零点的窗口在 TimeWindow 眼里就是跨零点`() {
        val w = InstantLock.windowAt(minuteOf(23, 0), 120)
        // 23:00 + 2h = 01:00 次日
        assertThat(w.startMinute).isEqualTo(1380)
        assertThat(w.endMinute).isEqualTo(60)
        assertThat(w.crossesMidnight).isTrue()
        assertThat(w.durationMinutes).isEqualTo(120)
    }

    @Test
    fun `恰好到 24 点的窗口不跨零点`() {
        val w = InstantLock.windowAt(minuteOf(22, 0), 120)
        assertThat(w.endMinute).isEqualTo(1440)
        assertThat(w.crossesMidnight).isFalse()
        assertThat(w.durationMinutes).isEqualTo(120)
    }

    // ------------------------------------------------------------- 规则

    @Test
    fun `生成的规则立刻生效、且是合法的`() {
        val rule = InstantLock.buildRule(at(16, 0), 30)
        assertThat(rule.name).isEqualTo(InstantLock.NAME)
        assertThat(rule.enabled).isTrue()
        assertThat(rule.isValid).isTrue()
        assertThat(rule.effectiveLockWindows).hasSize(1)
        assertThat(rule.startDate).isEqualTo(today)
    }

    @Test
    fun `生成的规则只锁当天`() {
        val rule = InstantLock.buildRule(at(16, 0), 30)
        assertThat(rule.startDate).isEqualTo(today)
        assertThat(rule.endDate).isEqualTo(today)
        assertThat(rule.isActiveOn(today)).isTrue()
        assertThat(rule.isActiveOn(today.plusDays(1))).isFalse()
        assertThat(rule.daysOfWeek).isEmpty()
    }

    /**
     * **解锁方式：仅到点解锁**（用户倾向，本实现的选择）。
     *
     * 即时锁机是"我主动锁的" —— 打卡就能解开的话，它随手就能绕过，
     * 锁着没有任何意义。这条与「明日规划」用的是同一个开关、同一条语义。
     */
    @Test
    fun `解锁方式仅为到点解锁 —— 打卡对它无效`() {
        val rule = InstantLock.buildRule(at(16, 0), 60)
        assertThat(rule.allowCheckInUnlock).isFalse()

        // 真的试一次：窗口内任何时刻的打卡都不解锁
        val status = WindowScheduler.statusFor(
            rules = listOf(rule),
            at = at(16, 30),
            checkInAtByRule = mapOf(rule.id to at(16, 10))
        )
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `刚创建的那一刻就是锁机中`() {
        val rule = InstantLock.buildRule(at(16, 0), 30)
        val status = WindowScheduler.statusFor(listOf(rule), at(16, 0))
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `窗口结束后自动解除`() {
        val rule = InstantLock.buildRule(at(16, 0), 30)
        val status = WindowScheduler.statusFor(listOf(rule), at(16, 30))
        assertThat(status.phase).isNotEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `跨零点的即时锁在第二天凌晨仍然锁着`() {
        val rule = InstantLock.buildRule(at(20, 0), 720)
        // 次日 03:00 仍在窗口内（20:00 -> 次日 08:00）
        val status = WindowScheduler.statusFor(listOf(rule), today.plusDays(1).atTime(3, 0))
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        // 次日 09:00 已经解除
        val after = WindowScheduler.statusFor(listOf(rule), today.plusDays(1).atTime(9, 0))
        assertThat(after.phase).isNotEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `即时锁机不挂在任何习惯或规划上`() {
        val rule = InstantLock.buildRule(at(16, 0), 30)
        assertThat(rule.habitId).isNull()
        assertThat(rule.planId).isNull()
        assertThat(rule.isPlanRule).isFalse()
        // 没有"必须先打卡"的窗口：它跟打卡完全无关
        assertThat(rule.checkInWindows).isEmpty()
        assertThat(rule.usesDurationMode).isFalse()
    }

    // --------------------------------------------------------- 身份识别

    @Test
    fun `生成的规则能被认出来是即时锁机`() {
        assertThat(InstantLock.buildRule(at(16, 0), 30).isInstantLock).isTrue()
    }

    @Test
    fun `习惯规则与规划规则都不会被误认成即时锁机`() {
        // 普通习惯锁机：sortOrder 默认 0
        val habitRule = LockRule(name = "早课", habitId = 7L, windows = listOf(TimeWindow(600, 660)))
        assertThat(habitRule.isInstantLock).isFalse()

        // 规划派生出来的规则：planId 非空
        val planRule = com.zilv.app.domain.plan.Plan(
            id = 3L,
            name = "读书",
            date = today,
            startMinute = 9 * 60,
            endMinute = 11 * 60
        ).toLockRule()
        assertThat(planRule.isInstantLock).isFalse()
    }

    // ------------------------------------------------------- 自填时长合成

    @Test
    fun `小时与分钟合成总分钟数`() {
        assertThat(InstantLock.minutesOf("1", "30")).isEqualTo(90)
        assertThat(InstantLock.minutesOf("0", "15")).isEqualTo(15)
        assertThat(InstantLock.minutesOf("12", "0")).isEqualTo(720)
    }

    @Test
    fun `空输入就是 0 分 —— 这一档必须留得住`() {
        assertThat(InstantLock.minutesOf("", "")).isEqualTo(0)
        assertThat(InstantLock.minutesOf("abc", "xyz")).isEqualTo(0)
    }

    @Test
    fun `分钟栏超过 59 按 59 算`() {
        assertThat(InstantLock.minutesOf("1", "90")).isEqualTo(119)
    }

    @Test
    fun `合成结果被夹到 12 小时上限`() {
        assertThat(InstantLock.minutesOf("99", "30")).isEqualTo(720)
        assertThat(InstantLock.minutesOf("99999999999", "0")).isEqualTo(720)
    }
}
