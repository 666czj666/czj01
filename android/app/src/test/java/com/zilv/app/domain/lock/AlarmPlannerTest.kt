package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * [AlarmPlanner] 的单元测试。
 *
 * 日期基准：2026-10-14 是周三。
 */
class AlarmPlannerTest {

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    /** 08:00–18:00，宽限 10 分钟 */
    private val workday = LockRule(
        id = 1L,
        name = "上班",
        windows = listOf(TimeWindow(8 * 60, 18 * 60)),
        unlockMode = UnlockMode.TIME_ONLY,
        graceMinutes = 10
    )

    /** 22:30–07:00 跨零点，无宽限 */
    private val sleep = LockRule(
        id = 2L,
        name = "睡前",
        windows = listOf(TimeWindow(22 * 60 + 30, 7 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    @Test
    fun `单窗口产生四种触发点且带宽限时含宽限结束`() {
        val inst = WindowScheduler.instancesStartingOn(workday, java.time.LocalDate.of(2026, 10, 14)).single()
        val triggers = AlarmPlanner.triggersFor(inst)

        assertThat(triggers.map { it.at }).containsExactly(
            dt("2026-10-14T07:45"), // T-15 预警
            dt("2026-10-14T08:00"), // 窗口开始
            dt("2026-10-14T08:10"), // 宽限结束
            dt("2026-10-14T18:00")  // 窗口结束
        ).inOrder()
    }

    @Test
    fun `无宽限时不产生宽限触发点`() {
        val inst = WindowScheduler.instancesStartingOn(sleep, java.time.LocalDate.of(2026, 10, 14)).single()
        val triggers = AlarmPlanner.triggersFor(inst)
        assertThat(triggers.filterIsInstance<AlarmPlanner.Trigger.GraceEnd>()).isEmpty()
        assertThat(triggers).hasSize(3)
    }

    @Test
    fun `触发点类型正确`() {
        val inst = WindowScheduler.instancesStartingOn(workday, java.time.LocalDate.of(2026, 10, 14)).single()
        val triggers = AlarmPlanner.triggersFor(inst)

        assertThat(triggers[0]).isInstanceOf(AlarmPlanner.Trigger.Warn::class.java)
        assertThat(triggers[1]).isInstanceOf(AlarmPlanner.Trigger.WindowStart::class.java)
        assertThat(triggers[2]).isInstanceOf(AlarmPlanner.Trigger.GraceEnd::class.java)
        assertThat(triggers[3]).isInstanceOf(AlarmPlanner.Trigger.WindowEnd::class.java)
    }

    @Test
    fun `每个触发点都携带所属窗口实例`() {
        val inst = WindowScheduler.instancesStartingOn(workday, java.time.LocalDate.of(2026, 10, 14)).single()
        AlarmPlanner.triggersFor(inst).forEach {
            assertThat(it.instance).isEqualTo(inst)
        }
    }

    @Test
    fun `next - 窗口之前返回预警时刻`() {
        val next = AlarmPlanner.next(listOf(workday), dt("2026-10-14T07:00"))
        assertThat(next).isInstanceOf(AlarmPlanner.Trigger.Warn::class.java)
        assertThat(next!!.at).isEqualTo(dt("2026-10-14T07:45"))
    }

    @Test
    fun `next - 预警时刻之后返回窗口开始`() {
        val next = AlarmPlanner.next(listOf(workday), dt("2026-10-14T07:50"))
        assertThat(next).isInstanceOf(AlarmPlanner.Trigger.WindowStart::class.java)
        assertThat(next!!.at).isEqualTo(dt("2026-10-14T08:00"))
    }

    @Test
    fun `next - 窗口内返回宽限结束或窗口结束`() {
        // 08:05 时下一个是 08:10 宽限结束
        val a = AlarmPlanner.next(listOf(workday), dt("2026-10-14T08:05"))
        assertThat(a).isInstanceOf(AlarmPlanner.Trigger.GraceEnd::class.java)

        // 08:15 时下一个是 18:00 窗口结束
        val b = AlarmPlanner.next(listOf(workday), dt("2026-10-14T08:15"))
        assertThat(b).isInstanceOf(AlarmPlanner.Trigger.WindowEnd::class.java)
    }

    @Test
    fun `next - 窗口结束后指向次日预警`() {
        val next = AlarmPlanner.next(listOf(workday), dt("2026-10-14T18:30"))
        assertThat(next!!.at).isEqualTo(dt("2026-10-15T07:45"))
    }

    @Test
    fun `next - 严格晚于当前时刻（等于当前时刻不算）`() {
        val next = AlarmPlanner.next(listOf(workday), dt("2026-10-14T07:45"))
        assertThat(next!!.at).isEqualTo(dt("2026-10-14T08:00"))
    }

    @Test
    fun `next - 跨零点窗口的触发点跨天正确`() {
        val next = AlarmPlanner.next(listOf(sleep), dt("2026-10-14T22:00"))
        assertThat(next!!.at).isEqualTo(dt("2026-10-14T22:15")) // T-15
        assertThat(next).isInstanceOf(AlarmPlanner.Trigger.Warn::class.java)

        val end = AlarmPlanner.next(listOf(sleep), dt("2026-10-14T23:00"))
        assertThat(end!!.at).isEqualTo(dt("2026-10-15T07:00"))
        assertThat(end).isInstanceOf(AlarmPlanner.Trigger.WindowEnd::class.java)
    }

    @Test
    fun `upcoming - 多规则合并并按时间升序`() {
        val list = AlarmPlanner.upcoming(listOf(workday, sleep), dt("2026-10-14T07:00"))
        val times = list.map { it.at }
        assertThat(times).isInOrder()
        // 第一条应是 07:45 预警
        assertThat(times.first()).isEqualTo(dt("2026-10-14T07:45"))
        // 睡前规则的 22:15 预警应出现在同一天
        assertThat(times).contains(dt("2026-10-14T22:15"))
    }

    @Test
    fun `upcoming - 停用规则不产生触发点`() {
        assertThat(AlarmPlanner.upcoming(listOf(workday.copy(enabled = false)), dt("2026-10-14T07:00")))
            .isEmpty()
    }

    @Test
    fun `upcoming - 无规则时为空`() {
        assertThat(AlarmPlanner.upcoming(emptyList(), dt("2026-10-14T07:00"))).isEmpty()
        assertThat(AlarmPlanner.next(emptyList(), dt("2026-10-14T07:00"))).isNull()
    }

    @Test
    fun `upcoming - 受 horizonDays 限制`() {
        val list = AlarmPlanner.upcoming(listOf(workday), dt("2026-10-14T19:00"), horizonDays = 0)
        assertThat(list).isEmpty() // 当天剩余已无触发点
    }

    @Test
    fun `upcoming - 多窗口规则产生多组触发点`() {
        val multi = workday.copy(
            windows = listOf(TimeWindow(9 * 60, 12 * 60), TimeWindow(14 * 60, 18 * 60)),
            graceMinutes = 0
        )
        val list = AlarmPlanner.upcoming(listOf(multi), dt("2026-10-14T08:00"), horizonDays = 0)
        assertThat(list.map { it.at }).containsExactly(
            dt("2026-10-14T08:45"), dt("2026-10-14T09:00"), dt("2026-10-14T12:00"),
            dt("2026-10-14T13:45"), dt("2026-10-14T14:00"), dt("2026-10-14T18:00")
        ).inOrder()
    }
}
