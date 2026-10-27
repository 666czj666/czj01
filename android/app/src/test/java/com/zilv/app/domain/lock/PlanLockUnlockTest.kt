package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.plan.Plan
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「规划只能到点解锁」的调度测试（v2.4）。
 *
 * 用户要求："规划时间内锁机，**解锁条件仅为到点解锁**。"
 *
 * v2.2 起本项目是"打卡即解锁"（`WindowScheduler.isValidCheckIn` 里那道
 * `unlockMode` 的门被删掉了）。规划是这条规则的**唯一例外**，实现方式是
 * [LockRule.allowCheckInUnlock]（默认 true）。这个文件同时钉住两件事：
 *
 *  1. 规划规则（`allowCheckInUnlock = false`）**任何打卡都不解锁**
 *  2. **习惯规则的默认行为一字未变** —— 同一个场景下照样能被打卡解开
 *
 * 日期基准（与 [WindowSchedulerTest] 一致）：2026-10-14 是周三，
 * 2026-10-15 是周四。
 */
class PlanLockUnlockTest {

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    private val planDay = d("2026-10-15")

    /** 规划：2026-10-15 09:00–11:00，只能到点解锁。 */
    private val reading = Plan(
        id = 42L,
        name = "读书",
        date = planDay,
        startMinute = 9 * 60,
        endMinute = 11 * 60
    ).toLockRule()

    /** 对照组：窗口完全相同，但是习惯规则（默认允许打卡解锁）。 */
    private val habitRule = LockRule(
        id = 1L,
        name = "习惯锁机",
        windows = listOf(TimeWindow(9 * 60, 11 * 60))
    )

    private fun planInstances(date: LocalDate = planDay) =
        WindowScheduler.instancesStartingOn(reading, date)

    // ------------------------------------------------------- 打卡一律无效

    @Test
    fun `规划锁机 - 打卡对它完全无效`() {
        val instance = planInstances().single()
        assertThat(WindowScheduler.isValidCheckIn(instance, dt("2026-10-15T09:30"))).isFalse()
        assertThat(WindowScheduler.isValidCheckIn(instance, dt("2026-10-15T10:59"))).isFalse()
        // 提前打卡有效期也救不了它
        val withEarly = reading.copy(earlyCheckInMinutes = 120)
        val earlyInstance = WindowScheduler.instancesStartingOn(withEarly, planDay).single()
        assertThat(WindowScheduler.isValidCheckIn(earlyInstance, dt("2026-10-15T08:00"))).isFalse()
        // 没有打卡记录时当然也是无效
        assertThat(WindowScheduler.isValidCheckIn(instance, null)).isFalse()
    }

    @Test
    fun `规划锁机 - 窗口内打了卡也依然是 LOCKED`() {
        val status = WindowScheduler.statusFor(
            rules = listOf(reading),
            at = dt("2026-10-15T10:00"),
            checkInAtByRule = mapOf(0L to dt("2026-10-15T10:00"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.rule?.isPlanRule).isTrue()
        assertThat(status.rule?.name).isEqualTo("读书")
        // 还有多久解锁 = 到窗口结束
        assertThat(status.minutesUntilChange).isEqualTo(60L)
    }

    @Test
    fun `规划锁机 - 完全没打卡时当然也是 LOCKED`() {
        val status = WindowScheduler.statusFor(listOf(reading), dt("2026-10-15T09:30"))
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `规划锁机 - 窗口内打卡阶段是 LOCKED 而不是 UNLOCKED`() {
        val instance = planInstances().single()
        assertThat(
            WindowScheduler.phaseOf(instance, dt("2026-10-15T10:00"), dt("2026-10-15T10:00"))
        ).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------------- 打卡窗口对它只算提示

    @Test
    fun `规划锁机 - 打卡窗口对它只算提示（CHECKIN_DUE）`() {
        // 造一条"既有打卡窗口、又不允许打卡解锁"的规则来覆盖 checkInPhaseOf
        val withCheckIn = reading.copy(
            id = 7L,
            checkInWindows = listOf(TimeWindow(9 * 60, 11 * 60))
        )
        val instance = WindowScheduler.checkInInstancesStartingOn(withCheckIn, planDay).single()

        // 已经打过卡了，但这条规则不认打卡 -> 仍然只是"该打卡了"的提示
        assertThat(
            WindowScheduler.checkInPhaseOf(instance, dt("2026-10-15T10:00"), dt("2026-10-15T09:30"))
        ).isEqualTo(LockPhase.CHECKIN_DUE)
    }

    @Test
    fun `习惯规则 - 同样的打卡窗口里打卡仍然是 UNLOCKED`() {
        val habitWithCheckIn = habitRule.copy(checkInWindows = listOf(TimeWindow(9 * 60, 11 * 60)))
        val instance =
            WindowScheduler.checkInInstancesStartingOn(habitWithCheckIn, planDay).single()

        assertThat(
            WindowScheduler.checkInPhaseOf(instance, dt("2026-10-15T10:00"), dt("2026-10-15T09:30"))
        ).isEqualTo(LockPhase.UNLOCKED)
    }

    // --------------------------------------------------- 习惯行为完全不变

    @Test
    fun `习惯规则 - 打卡解锁的默认行为一字未变`() {
        val at = dt("2026-10-15T10:00")
        val status = WindowScheduler.statusFor(
            rules = listOf(habitRule),
            at = at,
            checkInAtByRule = mapOf(1L to dt("2026-10-15T09:30"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
        assertThat(habitRule.allowCheckInUnlock).isTrue()
        assertThat(habitRule.isPlanRule).isFalse()
    }

    @Test
    fun `多规则叠加 - 习惯解锁了但规划还锁着，以 LOCKED 为准`() {
        val at = dt("2026-10-15T10:00")
        val status = WindowScheduler.statusFor(
            rules = listOf(habitRule, reading),
            at = at,
            checkInAtByRule = mapOf(
                1L to at,   // 习惯：已打卡 -> UNLOCKED
                0L to at    // 规划：已打卡但无效 -> LOCKED
            )
        )
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.rule?.isPlanRule).isTrue()
    }

    // ---------------------------------------------------------- 起止与生效

    @Test
    fun `规划锁机 - 到点自动解锁`() {
        // 11:00 整（半开区间）已经结束
        assertThat(WindowScheduler.statusFor(listOf(reading), dt("2026-10-15T11:00")).phase)
            .isEqualTo(LockPhase.IDLE)
        assertThat(WindowScheduler.statusFor(listOf(reading), dt("2026-10-15T12:00")).phase)
            .isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `规划锁机 - 开始前 15 分钟内是预警`() {
        assertThat(WindowScheduler.statusFor(listOf(reading), dt("2026-10-15T08:50")).phase)
            .isEqualTo(LockPhase.WARN)
        // 再早就是空闲
        assertThat(WindowScheduler.statusFor(listOf(reading), dt("2026-10-15T08:00")).phase)
            .isEqualTo(LockPhase.IDLE)
        // 开始那一刻（09:00 整）就锁
        assertThat(WindowScheduler.statusFor(listOf(reading), dt("2026-10-15T09:00")).phase)
            .isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `规划锁机 - 只在规划当天生效`() {
        assertThat(planInstances(planDay)).hasSize(1)
        assertThat(planInstances(planDay.minusDays(1))).isEmpty()
        assertThat(planInstances(planDay.plusDays(1))).isEmpty()
        assertThat(WindowScheduler.statusFor(listOf(reading), dt("2026-10-16T10:00")).phase)
            .isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `规划锁机 - 宽限期对规划同样生效`() {
        val withGrace = reading.copy(graceMinutes = 10)
        // 09:05 在宽限期内 -> 预警（还没真正拦）
        assertThat(WindowScheduler.statusFor(listOf(withGrace), dt("2026-10-15T09:05")).phase)
            .isEqualTo(LockPhase.WARN)
        // 09:10 起真正锁机
        assertThat(WindowScheduler.statusFor(listOf(withGrace), dt("2026-10-15T09:10")).phase)
            .isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `规划锁机 - 窗口本身没有跨 0 点`() {
        val window = reading.effectiveLockWindows.single()
        assertThat(window).isEqualTo(TimeWindow(9 * 60, 11 * 60))
        assertThat(window.crossesMidnight).isFalse()
        assertThat(window.durationMinutes).isEqualTo(120)
    }
}
