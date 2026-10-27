package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「打卡窗口 × 锁机窗口」组合语义的单元测试（v2.0 新增）。
 *
 * 用户要求："把锁机时间和打卡时间分开设置，在打卡时间未能打卡才进行锁机，
 * 锁机时间也能打卡解锁"。
 *
 * 这三条语义分别对应下面三组断言：
 *  1. 打卡窗口内没打卡 → `CHECKIN_DUE`（**此时还没锁**）
 *  2. 在打卡窗口打的卡，到锁机时间仍算数 → 不锁
 *  3. 锁机窗口内补打卡 → 解锁
 */
class CheckInWindowSchedulerTest {

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    /**
     * 典型配置：
     *  - 打卡时间 07:00–08:00
     *  - 锁机时间 08:00–18:00
     */
    private val studyRule = LockRule(
        id = 1L,
        name = "自习",
        checkInWindows = listOf(TimeWindow(7 * 60, 8 * 60)),
        windows = listOf(TimeWindow(8 * 60, 18 * 60)),
        unlockMode = UnlockMode.SPOT_CHECKIN,
        spotId = 7L
    )

    private val rules = listOf(studyRule)

    private fun statusAt(
        time: String,
        checkInAt: LocalDateTime? = null
    ) = WindowScheduler.statusFor(rules, dt(time), mapOf(1L to checkInAt))

    // ------------------------------------------------ 1. 打卡窗口内：待打卡

    @Test
    fun `打卡窗口内未打卡是待打卡而不是锁机`() {
        val status = statusAt("2026-10-14T07:30")
        assertThat(status.phase).isEqualTo(LockPhase.CHECKIN_DUE)
        assertThat(status.rule?.name).isEqualTo("自习")
    }

    @Test
    fun `打卡窗口内已打卡就是已解锁`() {
        val status = statusAt("2026-10-14T07:30", checkInAt = dt("2026-10-14T07:10"))
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `打卡窗口刚开始的整点也算在窗口内`() {
        assertThat(statusAt("2026-10-14T07:00").phase).isEqualTo(LockPhase.CHECKIN_DUE)
    }

    @Test
    fun `打卡窗口结束的那一刻已经不在窗口内`() {
        // 08:00 是锁机窗口的起点，所以应该已经是锁机而不是待打卡
        assertThat(statusAt("2026-10-14T08:00").phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------ 2. 在打卡窗口打的卡，锁机时仍算数

    @Test
    fun `在打卡窗口打的卡到锁机时间仍然有效`() {
        // 用户语义："在打卡时间未能打卡才进行锁机" —— 打了卡就不该被锁
        val status = statusAt("2026-10-14T10:00", checkInAt = dt("2026-10-14T07:30"))
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `打卡窗口内没打卡则锁机时间真的锁上`() {
        val status = statusAt("2026-10-14T10:00")
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    // -------------------------------------- 3. 锁机窗口内补打卡也能解锁

    @Test
    fun `锁机窗口内补打卡可以解锁`() {
        val status = statusAt("2026-10-14T10:00", checkInAt = dt("2026-10-14T09:30"))
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `锁机窗口末尾补打卡同样能解锁`() {
        val status = statusAt("2026-10-14T17:59", checkInAt = dt("2026-10-14T17:58"))
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    // ------------------------------------------------------ 提前打卡有效期

    @Test
    fun `打卡早于提前有效期上限时不算数`() {
        // 有效期 0：07:00 之前打的卡不算
        val status = statusAt("2026-10-14T07:30", checkInAt = dt("2026-10-14T06:30"))
        assertThat(status.phase).isEqualTo(LockPhase.CHECKIN_DUE)
    }

    @Test
    fun `设置提前有效期后早到的打卡也算数`() {
        val rule = studyRule.copy(earlyCheckInMinutes = 60)
        val status = WindowScheduler.statusFor(
            listOf(rule), dt("2026-10-14T07:30"), mapOf(1L to dt("2026-10-14T06:30"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `锁机窗口内打卡也受提前有效期约束`() {
        // 上一行 06:30 打的卡，有效期 0 → 到 10:00 仍应视为未打卡
        val status = statusAt("2026-10-14T10:00", checkInAt = dt("2026-10-14T06:30"))
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------------------------ 宽限期

    @Test
    fun `锁机窗口的宽限期内表现为预警`() {
        val rule = studyRule.copy(graceMinutes = 10)
        val status = WindowScheduler.statusFor(listOf(rule), dt("2026-10-14T08:05"), emptyMap())
        assertThat(status.phase).isEqualTo(LockPhase.WARN)
    }

    @Test
    fun `过了宽限期就真的锁上`() {
        val rule = studyRule.copy(graceMinutes = 10)
        val status = WindowScheduler.statusFor(listOf(rule), dt("2026-10-14T08:11"), emptyMap())
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------------------ 窗口之间的空档

    @Test
    fun `打卡截止后锁机开始前应该是空闲或预警`() {
        // 打卡 07:00-07:30，锁机 10:00-18:00；09:50 处在锁机前 10 分钟 → 预警
        val rule = studyRule.copy(
            checkInWindows = listOf(TimeWindow(7 * 60, 7 * 60 + 30)),
            windows = listOf(TimeWindow(10 * 60, 18 * 60))
        )
        val status = WindowScheduler.statusFor(listOf(rule), dt("2026-10-14T09:50"), emptyMap())
        assertThat(status.phase).isEqualTo(LockPhase.WARN)
    }

    @Test
    fun `不在任何窗口内且离锁机还远就是空闲`() {
        val rule = studyRule.copy(
            checkInWindows = listOf(TimeWindow(7 * 60, 7 * 60 + 30)),
            windows = listOf(TimeWindow(10 * 60, 18 * 60))
        )
        val status = WindowScheduler.statusFor(listOf(rule), dt("2026-10-14T09:00"), emptyMap())
        assertThat(status.phase).isEqualTo(LockPhase.IDLE)
    }

    // ------------------------------------------------------------ 约束校验

    @Test
    fun `锁机晚于打卡截止时合法`() {
        assertThat(studyRule.lockStartsAfterCheckIn).isTrue()
        assertThat(studyRule.lockWindowProblem).isNull()
        assertThat(studyRule.isValid).isTrue()
    }

    @Test
    fun `锁机早于打卡截止时非法`() {
        val bad = studyRule.copy(
            checkInWindows = listOf(TimeWindow(9 * 60, 10 * 60)),
            windows = listOf(TimeWindow(8 * 60, 18 * 60))
        )
        assertThat(bad.lockStartsAfterCheckIn).isFalse()
        assertThat(bad.isValid).isFalse()
    }

    @Test
    fun `非法时给出可读的原因，包含两个时刻`() {
        val bad = studyRule.copy(
            checkInWindows = listOf(TimeWindow(9 * 60, 10 * 60)),
            windows = listOf(TimeWindow(8 * 60, 18 * 60))
        )
        val problem = bad.lockWindowProblem!!
        assertThat(problem).contains("10:00") // 打卡截止
        assertThat(problem).contains("08:00") // 锁机开始
    }

    @Test
    fun `锁机与打卡截止恰好相接是合法的`() {
        // 打卡 07:00-08:00，锁机 08:00 开始 —— 正好"打卡截止即锁机开始"
        assertThat(studyRule.lockStartsAfterCheckIn).isTrue()
    }

    @Test
    fun `没有打卡窗口时不施加该约束`() {
        val noCheckIn = studyRule.copy(checkInWindows = emptyList())
        assertThat(noCheckIn.lockStartsAfterCheckIn).isTrue()
        assertThat(noCheckIn.hasCheckInWindow).isFalse()
    }

    // ------------------------------------------------------ 辅助属性

    @Test
    fun `checkInOpenMinute 在两种配置下都给出正确值`() {
        assertThat(studyRule.checkInOpenMinute).isEqualTo(7 * 60)
        // 没有打卡窗口时退回锁机窗口开始
        assertThat(studyRule.copy(checkInWindows = emptyList()).checkInOpenMinute).isEqualTo(8 * 60)
    }

    @Test
    fun `lockWindows 别名与 windows 一致`() {
        assertThat(studyRule.lockWindows).isEqualTo(studyRule.windows)
    }

    @Test
    fun `打卡截止取所有打卡窗口里最晚的`() {
        val rule = studyRule.copy(
            checkInWindows = listOf(TimeWindow(7 * 60, 8 * 60), TimeWindow(12 * 60, 13 * 60))
        )
        assertThat(rule.checkInDeadlineMinute).isEqualTo(13 * 60)
    }

    // ------------------------------------------ 已废弃的解锁方式不再影响行为
    //
    // v2.2 用户要求："锁机后的解锁方法 = 打卡（任何打卡方法都可以），
    // 或者过完锁机时间" —— 于是 `unlockMode` 只是一个不再生效的历史记录字段。
    // 下面两条把"它确实不再影响行为"钉死（此前它们断言的是旧行为）。

    @Test
    fun `已废弃的仅定时解锁取值下，锁机窗口内打卡同样能解锁`() {
        val rule = studyRule.copy(unlockMode = UnlockMode.TIME_ONLY, spotId = null)
        val status = WindowScheduler.statusFor(
            listOf(rule), dt("2026-10-14T10:00"), mapOf(1L to dt("2026-10-14T07:30"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `已废弃的仅定时解锁取值下，打卡窗口内打卡同样算已解锁`() {
        val rule = studyRule.copy(unlockMode = UnlockMode.TIME_ONLY, spotId = null)
        val status = WindowScheduler.statusFor(
            listOf(rule), dt("2026-10-14T07:30"), mapOf(1L to dt("2026-10-14T07:10"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `已废弃的仅定时解锁取值下，没打卡仍然老老实实锁机`() {
        // 反面钉死：解锁方式不再有开关，但"没打卡"当然还是锁着 ——
        // 否则上面两条就变成"怎么都不锁"了
        val rule = studyRule.copy(unlockMode = UnlockMode.TIME_ONLY, spotId = null)
        val status = WindowScheduler.statusFor(
            listOf(rule), dt("2026-10-14T10:00"), mapOf(1L to null)
        )
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }
}
