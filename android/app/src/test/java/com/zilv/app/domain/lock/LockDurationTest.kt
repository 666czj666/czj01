package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * 「打卡截止 + 自选时长」模式（v2.1 新增）。
 *
 * 用户要求："把自选打卡时间保留，不自选锁机时间删除，改为超过打卡时间之后锁机，
 * 锁机的自选时间开始改为自选时长，最长为 24 小时"。
 *
 * 核心语义：**锁机起点 = 打卡截止时刻**，用户只能选持续多久。
 */
class LockDurationTest {

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    /** 打卡 07:00–08:00，锁机 2 小时 → 08:00–10:00。 */
    private fun ruleWith(durationMinutes: Int) = LockRule(
        id = 1L,
        name = "自习",
        checkInWindows = listOf(TimeWindow(7 * 60, 8 * 60)),
        lockDurationMinutes = durationMinutes,
        unlockMode = UnlockMode.SPOT_CHECKIN,
        spotId = 7L
    )

    // ------------------------------------------------------------ 推导

    @Test
    fun `锁机从打卡截止开始并持续所选时长`() {
        val rule = ruleWith(120)
        assertThat(rule.derivedLockWindows)
            .containsExactly(TimeWindow(8 * 60, 10 * 60))
    }

    @Test
    fun `一小时时长`() {
        assertThat(ruleWith(60).derivedLockWindows)
            .containsExactly(TimeWindow(8 * 60, 9 * 60))
    }

    @Test
    fun `时长跨零点时正确取模`() {
        // 打卡 22:00-23:00，锁机 3 小时 → 23:00 到次日 02:00
        val rule = LockRule(
            name = "早睡",
            checkInWindows = listOf(TimeWindow(22 * 60, 23 * 60)),
            lockDurationMinutes = 180
        )
        val window = rule.derivedLockWindows.single()
        assertThat(window.startMinute).isEqualTo(23 * 60)
        assertThat(window.endMinute).isEqualTo(2 * 60)
        assertThat(window.crossesMidnight).isTrue()
        assertThat(window.durationMinutes).isEqualTo(180)
    }

    @Test
    fun `24 小时时长等于全天`() {
        // 上限 1440 分钟：rawEnd - 1440 == start，恰好落到 TimeWindow 的
        // "起止相同＝全天"语义上
        val rule = ruleWith(LockRule.MAX_LOCK_DURATION_MINUTES)
        val window = rule.derivedLockWindows.single()
        assertThat(window.startMinute).isEqualTo(8 * 60)
        assertThat(window.endMinute).isEqualTo(8 * 60)
        assertThat(window.crossesMidnight).isTrue()
        assertThat(window.durationMinutes).isEqualTo(1440)
    }

    @Test
    fun `时长上限常量就是 24 小时`() {
        assertThat(LockRule.MAX_LOCK_DURATION_MINUTES).isEqualTo(1440)
    }

    @Test
    fun `打卡窗口恰好 24 点截止也不会崩`() {
        // endMinute 可以是 1440，而 TimeWindow 的 startMinute 上限是 1439
        val rule = LockRule(
            name = "全天",
            checkInWindows = listOf(TimeWindow(0, 1440)),
            lockDurationMinutes = 60
        )
        val window = rule.derivedLockWindows.single()
        assertThat(window.startMinute).isEqualTo(1439)
    }

    @Test
    fun `时长为零时推导为空`() {
        assertThat(ruleWith(0).derivedLockWindows).isEmpty()
    }

    @Test
    fun `没有打卡窗口时推导为空`() {
        val rule = ruleWith(120).copy(checkInWindows = emptyList())
        assertThat(rule.derivedLockWindows).isEmpty()
    }

    @Test
    fun `多个打卡窗口取最晚的截止`() {
        val rule = ruleWith(60).copy(
            checkInWindows = listOf(TimeWindow(7 * 60, 8 * 60), TimeWindow(12 * 60, 13 * 60))
        )
        // 截止取 13:00
        assertThat(rule.derivedLockWindows.single().startMinute).isEqualTo(13 * 60)
    }

    // -------------------------------------------------- effectiveLockWindows

    @Test
    fun `配了时长就用推导窗口`() {
        val rule = ruleWith(120).copy(windows = listOf(TimeWindow(20 * 60, 22 * 60)))
        assertThat(rule.usesDurationMode).isTrue()
        assertThat(rule.effectiveLockWindows).containsExactly(TimeWindow(8 * 60, 10 * 60))
    }

    @Test
    fun `旧式规则仍沿用自选窗口`() {
        val legacy = LockRule(
            name = "旧规则",
            windows = listOf(TimeWindow(20 * 60, 22 * 60))
        )
        assertThat(legacy.usesDurationMode).isFalse()
        assertThat(legacy.effectiveLockWindows).containsExactly(TimeWindow(20 * 60, 22 * 60))
    }

    @Test
    fun `有打卡窗口但没设时长时仍走旧式窗口`() {
        val mixed = ruleWith(0).copy(windows = listOf(TimeWindow(20 * 60, 22 * 60)))
        assertThat(mixed.usesDurationMode).isFalse()
        assertThat(mixed.effectiveLockWindows).containsExactly(TimeWindow(20 * 60, 22 * 60))
    }

    // ------------------------------------------------------------ 校验

    @Test
    fun `时长模式下锁机必然晚于打卡截止`() {
        // 由定义保证，不需要额外的约束校验
        assertThat(ruleWith(120).lockStartsAfterCheckIn).isTrue()
        assertThat(ruleWith(120).lockWindowProblem).isNull()
        assertThat(ruleWith(120).isValid).isTrue()
    }

    @Test
    fun `时长为零且无窗口时不合法`() {
        assertThat(ruleWith(0).isValid).isFalse()
    }

    @Test
    fun `时长可读文案`() {
        assertThat(ruleWith(0).lockDurationLabel).isEqualTo("未设置")
        assertThat(ruleWith(30).lockDurationLabel).isEqualTo("30 分钟")
        assertThat(ruleWith(60).lockDurationLabel).isEqualTo("1 小时")
        assertThat(ruleWith(120).lockDurationLabel).isEqualTo("2 小时")
        assertThat(ruleWith(150).lockDurationLabel).isEqualTo("2 小时 30 分")
        assertThat(ruleWith(1440).lockDurationLabel).isEqualTo("24 小时")
    }

    // ------------------------------------------------------ 与调度器联动

    @Test
    fun `打卡截止前是待打卡，截止后立刻锁机`() {
        val rules = listOf(ruleWith(120))

        // 07:30 还在打卡窗口内
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T07:30")).phase)
            .isEqualTo(LockPhase.CHECKIN_DUE)

        // 08:00 正好截止 → 已进入锁机
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T08:00")).phase)
            .isEqualTo(LockPhase.LOCKED)

        // 09:59 仍在锁
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T09:59")).phase)
            .isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `时长走完就不再锁机`() {
        val rules = listOf(ruleWith(120))
        // 10:00 窗口结束
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T10:00")).phase)
            .isNotEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `打卡截止后打的卡仍能解锁`() {
        val rules = listOf(ruleWith(120))
        val status = WindowScheduler.statusFor(
            rules, dt("2026-10-14T09:00"), mapOf(1L to dt("2026-10-14T08:30"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `打卡窗口内打卡则根本不进入锁机`() {
        val rules = listOf(ruleWith(120))
        val status = WindowScheduler.statusFor(
            rules, dt("2026-10-14T09:00"), mapOf(1L to dt("2026-10-14T07:30"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `24 小时时长会一直锁到第二天同一时刻`() {
        val rules = listOf(ruleWith(LockRule.MAX_LOCK_DURATION_MINUTES))
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T12:00")).phase)
            .isEqualTo(LockPhase.LOCKED)
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-15T03:00")).phase)
            .isEqualTo(LockPhase.LOCKED)
        // 10-15 08:00 前一刻仍属于前一天那个窗口
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-15T07:59")).phase)
            .isEqualTo(LockPhase.LOCKED)
        // 08:00 起下一天的窗口无缝接上 ——
        // 每天锁 24 小时的逻辑后果就是"一直锁着"（这是正确行为，不是 bug）
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-15T08:00")).phase)
            .isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `只在某一天生效时 24 小时窗口会在次日同一时刻结束`() {
        // 2026-10-14 是周三。限定只在周三生效，才能观察到窗口真的结束
        val rules = listOf(
            ruleWith(LockRule.MAX_LOCK_DURATION_MINUTES)
                .copy(daysOfWeek = setOf(DayOfWeek.WEDNESDAY))
        )
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T12:00")).phase)
            .isEqualTo(LockPhase.LOCKED)
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-15T07:59")).phase)
            .isEqualTo(LockPhase.LOCKED)
        // 周四 08:00 窗口结束，且周四不生效，所以接不上
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-15T08:00")).phase)
            .isNotEqualTo(LockPhase.LOCKED)
    }
}
