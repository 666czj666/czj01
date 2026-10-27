package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 锁机窗口调度的单元测试。
 *
 * 日期基准（已核对）：2026-10-14 是**周三**，2026-10-12 是周一，2026-10-11 是周日。
 */
class WindowSchedulerTest {

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    // 早课：07:00–09:00，需到点打卡，提前 15 分钟有效
    private val morningClass = LockRule(
        id = 1L,
        name = "早课",
        windows = listOf(TimeWindow(7 * 60, 9 * 60)),
        unlockMode = UnlockMode.SPOT_CHECKIN,
        spotId = 10L,
        earlyCheckInMinutes = 15,
        graceMinutes = 0
    )

    // 睡前：22:30–07:00（跨零点），仅定时解锁
    private val sleep = LockRule(
        id = 2L,
        name = "睡前",
        windows = listOf(TimeWindow(22 * 60 + 30, 7 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    // ------------------------------------------------------------ TimeWindow

    @Test
    fun `TimeWindow - 普通窗口不跨零点且时长为差`() {
        val w = TimeWindow(480, 1080) // 08:00-18:00
        assertThat(w.crossesMidnight).isFalse()
        assertThat(w.durationMinutes).isEqualTo(600)
        assertThat(w.start).isEqualTo(LocalTime.of(8, 0))
        assertThat(w.end).isEqualTo(LocalTime.of(18, 0))
    }

    @Test
    fun `TimeWindow - 跨零点识别与时长跨天计算`() {
        val w = TimeWindow(1350, 420) // 22:30-07:00
        assertThat(w.crossesMidnight).isTrue()
        assertThat(w.durationMinutes).isEqualTo(90 + 420)
    }

    @Test
    fun `TimeWindow - 半开区间：含起点不含终点`() {
        val w = TimeWindow(480, 1080)
        assertThat(w.containsMinuteOfDay(480)).isTrue()   // 08:00 含
        assertThat(w.containsMinuteOfDay(1079)).isTrue()  // 17:59 含
        assertThat(w.containsMinuteOfDay(1080)).isFalse() // 18:00 不含
        assertThat(w.containsMinuteOfDay(479)).isFalse()
    }

    @Test
    fun `TimeWindow - 跨零点窗口的包含判定`() {
        val w = TimeWindow(1350, 420)
        assertThat(w.containsMinuteOfDay(1350)).isTrue() // 22:30
        assertThat(w.containsMinuteOfDay(1439)).isTrue() // 23:59
        assertThat(w.containsMinuteOfDay(0)).isTrue()    // 00:00
        assertThat(w.containsMinuteOfDay(419)).isTrue()  // 06:59
        assertThat(w.containsMinuteOfDay(420)).isFalse() // 07:00 不含
        assertThat(w.containsMinuteOfDay(600)).isFalse() // 10:00
    }

    @Test
    fun `TimeWindow - 全天窗口 0 到 1440`() {
        val w = TimeWindow(0, 1440)
        assertThat(w.crossesMidnight).isFalse()
        assertThat(w.durationMinutes).isEqualTo(1440)
        assertThat(w.containsMinuteOfDay(0)).isTrue()
        assertThat(w.containsMinuteOfDay(1439)).isTrue()
        assertThat(w.end).isEqualTo(LocalTime.MIDNIGHT)
    }

    @Test
    fun `TimeWindow - 非法分钟数抛异常`() {
        assertThat(runCatching { TimeWindow(-1, 100) }.isFailure).isTrue()
        assertThat(runCatching { TimeWindow(0, 1441) }.isFailure).isTrue()
        assertThat(runCatching { TimeWindow(1440, 10) }.isFailure).isTrue()
    }

    @Test
    fun `TimeWindow - of 工厂方法`() {
        assertThat(TimeWindow.of(LocalTime.of(7, 30), LocalTime.of(9, 0)))
            .isEqualTo(TimeWindow(450, 540))
        // 24:00 需要用 endMeansMidnight 表示
        assertThat(TimeWindow.of(LocalTime.of(0, 0), LocalTime.MIDNIGHT, endMeansMidnight = true))
            .isEqualTo(TimeWindow(0, 1440))
    }

    // ------------------------------------------------------ instancesStartingOn

    @Test
    fun `展开 - 普通窗口的起止时刻`() {
        val inst = WindowScheduler.instancesStartingOn(morningClass, d("2026-10-14")).single()
        assertThat(inst.start).isEqualTo(dt("2026-10-14T07:00"))
        assertThat(inst.end).isEqualTo(dt("2026-10-14T09:00"))
        assertThat(inst.ownerDate).isEqualTo(d("2026-10-14"))
    }

    @Test
    fun `展开 - 跨零点窗口结束在次日且归属日为开始日`() {
        val inst = WindowScheduler.instancesStartingOn(sleep, d("2026-10-14")).single()
        assertThat(inst.start).isEqualTo(dt("2026-10-14T22:30"))
        assertThat(inst.end).isEqualTo(dt("2026-10-15T07:00"))
        assertThat(inst.ownerDate).isEqualTo(d("2026-10-14"))
    }

    @Test
    fun `展开 - 停用的规则不产生窗口`() {
        assertThat(WindowScheduler.instancesStartingOn(morningClass.copy(enabled = false), d("2026-10-14")))
            .isEmpty()
    }

    @Test
    fun `展开 - 星期不符不产生窗口`() {
        val mondayOnly = morningClass.copy(daysOfWeek = setOf(DayOfWeek.MONDAY))
        assertThat(WindowScheduler.instancesStartingOn(mondayOnly, d("2026-10-14"))).isEmpty() // 周三
        assertThat(WindowScheduler.instancesStartingOn(mondayOnly, d("2026-10-12"))).hasSize(1) // 周一
    }

    @Test
    fun `展开 - 空星期集合视为每天`() {
        assertThat(WindowScheduler.instancesStartingOn(morningClass.copy(daysOfWeek = emptySet()), d("2026-10-14")))
            .hasSize(1)
    }

    @Test
    fun `展开 - 日期区间生效`() {
        val bounded = morningClass.copy(
            startDate = d("2026-10-10"),
            endDate = d("2026-10-20")
        )
        assertThat(WindowScheduler.instancesStartingOn(bounded, d("2026-10-09"))).isEmpty()
        assertThat(WindowScheduler.instancesStartingOn(bounded, d("2026-10-10"))).hasSize(1)
        assertThat(WindowScheduler.instancesStartingOn(bounded, d("2026-10-20"))).hasSize(1)
        assertThat(WindowScheduler.instancesStartingOn(bounded, d("2026-10-21"))).isEmpty()
    }

    @Test
    fun `展开 - 一条规则多个窗口`() {
        val multi = morningClass.copy(
            windows = listOf(TimeWindow(9 * 60, 12 * 60), TimeWindow(14 * 60, 18 * 60))
        )
        val list = WindowScheduler.instancesStartingOn(multi, d("2026-10-14"))
        assertThat(list).hasSize(2)
        assertThat(list[0].start).isEqualTo(dt("2026-10-14T09:00"))
        assertThat(list[1].start).isEqualTo(dt("2026-10-14T14:00"))
    }

    // ------------------------------------------------------------ activeWindows

    @Test
    fun `生效 - 窗口内被识别为生效`() {
        val active = WindowScheduler.activeWindows(listOf(morningClass), dt("2026-10-14T08:00"))
        assertThat(active).hasSize(1)
    }

    @Test
    fun `生效 - 窗口外不生效`() {
        assertThat(WindowScheduler.activeWindows(listOf(morningClass), dt("2026-10-14T06:59"))).isEmpty()
        assertThat(WindowScheduler.activeWindows(listOf(morningClass), dt("2026-10-14T09:00"))).isEmpty()
    }

    @Test
    fun `生效 - 跨零点窗口在次日凌晨仍然生效`() {
        // 10-14 22:30 开始，持续到 10-15 07:00
        assertThat(WindowScheduler.activeWindows(listOf(sleep), dt("2026-10-14T23:00"))).hasSize(1)
        assertThat(WindowScheduler.activeWindows(listOf(sleep), dt("2026-10-15T03:00"))).hasSize(1)
        assertThat(WindowScheduler.activeWindows(listOf(sleep), dt("2026-10-15T06:59"))).hasSize(1)
    }

    @Test
    fun `生效 - 跨零点窗口在结束时刻不再生效`() {
        assertThat(WindowScheduler.activeWindows(listOf(sleep), dt("2026-10-15T07:00"))).isEmpty()
    }

    @Test
    fun `生效 - 跨零点窗口在开始前不生效`() {
        assertThat(WindowScheduler.activeWindows(listOf(sleep), dt("2026-10-14T22:29"))).isEmpty()
    }

    @Test
    fun `生效 - 子夜时刻不会把前一天的窗口算错`() {
        // 00:30 应该匹配"前一天 22:30 开始"的窗口，而不是"今天 22:30"的
        val active = WindowScheduler.activeWindows(listOf(sleep), dt("2026-10-15T00:30"))
        assertThat(active).hasSize(1)
        assertThat(active.single().start).isEqualTo(dt("2026-10-14T22:30"))
    }

    @Test
    fun `生效 - 多条规则同时生效时全部返回`() {
        val rules = listOf(morningClass, sleep)
        assertThat(WindowScheduler.activeWindows(rules, dt("2026-10-14T23:00"))).hasSize(1)
        assertThat(WindowScheduler.activeWindows(rules, dt("2026-10-14T08:00"))).hasSize(1)
    }

    // ---------------------------------------------------------------- 预警 / 下一个

    @Test
    fun `预警 - T-15 分钟内进入预警`() {
        assertThat(WindowScheduler.warnings(listOf(morningClass), dt("2026-10-14T06:45"))).hasSize(1)
        assertThat(WindowScheduler.warnings(listOf(morningClass), dt("2026-10-14T06:44"))).isEmpty()
        assertThat(WindowScheduler.warnings(listOf(morningClass), dt("2026-10-14T07:00"))).isEmpty()
    }

    @Test
    fun `下一个 - 返回最近一次即将开始的窗口`() {
        val next = WindowScheduler.next(listOf(morningClass), dt("2026-10-14T09:00"))
        assertThat(next!!.start).isEqualTo(dt("2026-10-15T07:00"))
    }

    @Test
    fun `下一个 - 在窗口内时指向下一个窗口`() {
        val next = WindowScheduler.next(listOf(morningClass), dt("2026-10-14T08:00"))
        assertThat(next!!.start).isEqualTo(dt("2026-10-15T07:00"))
    }

    @Test
    fun `下一个 - 停用规则不参与`() {
        assertThat(
            WindowScheduler.next(listOf(morningClass.copy(enabled = false)), dt("2026-10-14T09:00"))
        ).isNull()
    }

    // ---------------------------------------------------------------- 阶段判定

    @Test
    fun `阶段 - 窗口内未打卡为锁机`() {
        val inst = WindowScheduler.instancesStartingOn(morningClass, d("2026-10-14")).single()
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T08:00"), null))
            .isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `阶段 - 窗口内有效打卡为已解锁`() {
        val inst = WindowScheduler.instancesStartingOn(morningClass, d("2026-10-14")).single()
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T08:00"), dt("2026-10-14T07:30")))
            .isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `阶段 - 提前打卡有效期边界：第15分钟有效第16分钟无效`() {
        val inst = WindowScheduler.instancesStartingOn(morningClass, d("2026-10-14")).single()
        // 窗口 07:00，提前 15 分钟 -> 06:45 起有效
        assertThat(WindowScheduler.isValidCheckIn(inst, dt("2026-10-14T06:45"))).isTrue()
        assertThat(WindowScheduler.isValidCheckIn(inst, dt("2026-10-14T06:44"))).isFalse()
        // 窗口结束时刻不算有效
        assertThat(WindowScheduler.isValidCheckIn(inst, dt("2026-10-14T09:00"))).isFalse()
        assertThat(WindowScheduler.isValidCheckIn(inst, dt("2026-10-14T08:59"))).isTrue()
    }

    @Test
    fun `阶段 - 已废弃的仅定时解锁取值不再让打卡失效`() {
        // v2.2：用户要求"解锁方法 = 打卡（任何打卡方法都可以）"。
        // `sleep` 上写着历史值 TIME_ONLY，但它现在**只是记录**：
        // 22:50 打的卡对 22:30–07:00 这个窗口同样有效。
        val inst = WindowScheduler.instancesStartingOn(sleep, d("2026-10-14")).single()
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T23:00"), dt("2026-10-14T22:50")))
            .isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `阶段 - 宽限期内为预警而非锁机`() {
        val g = morningClass.copy(graceMinutes = 10)
        val inst = WindowScheduler.instancesStartingOn(g, d("2026-10-14")).single()
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T07:05"), null)).isEqualTo(LockPhase.WARN)
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T07:10"), null)).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `阶段 - 窗口外为空闲`() {
        val inst = WindowScheduler.instancesStartingOn(morningClass, d("2026-10-14")).single()
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T06:00"), null)).isEqualTo(LockPhase.IDLE)
        assertThat(WindowScheduler.phaseOf(inst, dt("2026-10-14T10:00"), null)).isEqualTo(LockPhase.IDLE)
    }

    // ---------------------------------------------------------------- 综合状态

    @Test
    fun `综合 - 无规则为空闲`() {
        val s = WindowScheduler.statusFor(emptyList(), dt("2026-10-14T08:00"))
        assertThat(s.phase).isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `综合 - 窗口内未打卡为锁机且带剩余时长`() {
        val s = WindowScheduler.statusFor(listOf(morningClass), dt("2026-10-14T08:00"))
        assertThat(s.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(s.rule!!.id).isEqualTo(1L)
        assertThat(s.minutesUntilChange).isEqualTo(60) // 到 09:00
    }

    @Test
    fun `综合 - 窗口前15分钟内为预警`() {
        val s = WindowScheduler.statusFor(listOf(morningClass), dt("2026-10-14T06:50"))
        assertThat(s.phase).isEqualTo(LockPhase.WARN)
        assertThat(s.minutesUntilChange).isEqualTo(10)
    }

    @Test
    fun `综合 - 窗口开始前已完成有效提前打卡则为已解锁`() {
        val s = WindowScheduler.statusFor(
            listOf(morningClass),
            dt("2026-10-14T06:50"),
            mapOf(1L to dt("2026-10-14T06:46"))
        )
        assertThat(s.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `综合 - 提前打卡太早则仍是预警`() {
        val s = WindowScheduler.statusFor(
            listOf(morningClass),
            dt("2026-10-14T06:50"),
            mapOf(1L to dt("2026-10-14T06:00")) // 早于 06:45
        )
        assertThat(s.phase).isEqualTo(LockPhase.WARN)
    }

    @Test
    fun `综合 - 空档期为空闲并给出下一个窗口`() {
        val s = WindowScheduler.statusFor(listOf(morningClass), dt("2026-10-14T12:00"))
        assertThat(s.phase).isEqualTo(LockPhase.IDLE)
        assertThat(s.instance!!.start).isEqualTo(dt("2026-10-15T07:00"))
    }

    @Test
    fun `综合 - 多规则冲突时锁机优先`() {
        // 规则 A 已打卡解锁，规则 B 未打卡锁机 -> 应判为 LOCKED
        val unlocked = morningClass.copy(id = 3L, name = "已打卡的")
        val locked = morningClass.copy(id = 4L, name = "没打卡的")
        val s = WindowScheduler.statusFor(
            listOf(unlocked, locked),
            dt("2026-10-14T08:00"),
            mapOf(3L to dt("2026-10-14T07:30"))
        )
        assertThat(s.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(s.rule!!.id).isEqualTo(4L)
    }

    @Test
    fun `综合 - 全部解锁时为已解锁`() {
        val a = morningClass.copy(id = 3L)
        val b = morningClass.copy(id = 4L)
        val s = WindowScheduler.statusFor(
            listOf(a, b),
            dt("2026-10-14T08:00"),
            mapOf(3L to dt("2026-10-14T07:30"), 4L to dt("2026-10-14T07:40"))
        )
        assertThat(s.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `综合 - 跨零点窗口在次日凌晨仍为锁机`() {
        val s = WindowScheduler.statusFor(listOf(sleep), dt("2026-10-15T03:00"))
        assertThat(s.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(s.instance!!.ownerDate).isEqualTo(d("2026-10-14"))
        assertThat(s.minutesUntilChange).isEqualTo(240) // 到 07:00
    }

    // ---------------------------------------------------------------- 规则校验

    @Test
    fun `规则校验 - 不再要求打卡点（打卡点归习惯的打卡条件管）`() {
        // v2.2：规则上的 spotId 已废弃，isValid 不再检查它 ——
        // 老规则（SPOT_CHECKIN 却没存点）也必须还能保存，否则用户一改名字就被拦下
        assertThat(morningClass.copy(spotId = null).isValid).isTrue()
        assertThat(morningClass.copy(spotId = 10L).isValid).isTrue()
    }

    @Test
    fun `规则校验 - 仅定时解锁不需要打卡点`() {
        assertThat(sleep.copy(spotId = null).isValid).isTrue()
    }

    @Test
    fun `规则校验 - 无窗口或空名称为非法`() {
        assertThat(morningClass.copy(windows = emptyList()).isValid).isFalse()
        assertThat(morningClass.copy(name = "  ").isValid).isFalse()
    }

    @Test
    fun `规则校验 - requiresSpot 已随解锁方式的废弃一起删除`() {
        // v2.2：任何打卡都能解锁，解锁方式不再可配 ——
        // 于是"要不要打卡点"这件事整个离开了 LockRule（改由习惯的打卡条件管）。
        // 旧的 requiresSpot 属性已被删除，这里改为钉住它派生自的那条规则：
        // **打卡点完全不参与 isValid**。
        assertThat(morningClass.copy(unlockMode = UnlockMode.TIME_ONLY, spotId = null).isValid).isTrue()
        assertThat(morningClass.copy(unlockMode = UnlockMode.SPOT_CHECKIN, spotId = null).isValid).isTrue()
    }
}
