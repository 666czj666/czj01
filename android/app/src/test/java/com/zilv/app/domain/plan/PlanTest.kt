package com.zilv.app.domain.plan

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.lock.TimeWindow
import kotlin.test.assertFailsWith
import org.junit.Test
import java.time.LocalDate

/**
 * [Plan] 领域模型的单元测试。
 *
 * 用户要求："自定义规划名称，自定义规划的开始和结束时间，规划时间内锁机，
 * 解锁条件仅为到点解锁，规划不能跨 0 点。"
 *
 * 这里钉死三件事：
 *  1. **不变式**（起止同天、不跨 0 点、名称非空）能被查询到，且原因可读
 *  2. 半开区间 `[start, end)` 的边界（含开始、不含结束）
 *  3. [Plan.toLockRule] 产出的规则字段**逐条固定** —— 尤其是
 *     `allowCheckInUnlock = false`（解锁条件仅为到点解锁）与
 *     `habitId = null`（不会混进习惯的锁机配置）
 */
class PlanTest {

    private val day: LocalDate = LocalDate.of(2026, 10, 15)

    private fun plan(
        name: String = "读书",
        date: LocalDate = day,
        startMinute: Int = 9 * 60,
        endMinute: Int = 11 * 60,
        id: Long = 7L
    ) = Plan(
        id = id,
        name = name,
        date = date,
        startMinute = startMinute,
        endMinute = endMinute,
        createdAt = 1000L
    )

    // ---------------------------------------------------------------- 不变式

    @Test
    fun `合法规划 - 有效`() {
        val p = plan()
        assertThat(p.isValid).isTrue()
        assertThat(p.invalidReason).isNull()
        assertThat(p.durationMinutes).isEqualTo(120)
    }

    @Test
    fun `跨0点 - 结束早于开始时不合法`() {
        // 22:00 -> 06:00 属于跨 0 点，用户明确要求不允许
        val p = plan(startMinute = 22 * 60, endMinute = 6 * 60)
        assertThat(p.hasValidWindow).isFalse()
        assertThat(p.isValid).isFalse()
        assertThat(p.invalidReason).contains("不能跨 0 点")
    }

    @Test
    fun `起止相同 - 不合法（不能退化成全天锁机）`() {
        // TimeWindow(x, x) 表示全天 1440 分钟，所以 10:00-10:00 必须被拦下
        val p = plan(startMinute = 10 * 60, endMinute = 10 * 60)
        assertThat(p.hasValidWindow).isFalse()
        assertThat(p.invalidReason).contains("不能相同")
        // 顺手钉住那条"陷阱"本身：TimeWindow(600, 600) 的时长是 1440 而不是 0
        assertThat(TimeWindow(600, 600).durationMinutes).isEqualTo(1440)
    }

    @Test
    fun `越界分钟 - 不合法`() {
        assertThat(plan(startMinute = -1, endMinute = 60).hasValidWindow).isFalse()
        // 1439 是允许的最后一分钟（23:59）；1440 属于"次日 0 点"，规划不允许跨 0 点
        assertThat(plan(startMinute = 0, endMinute = 1440).hasValidWindow).isFalse()
        assertThat(plan(startMinute = 0, endMinute = 1440).invalidReason).contains("00:00..23:59")
    }

    @Test
    fun `23点到23点59 是合法的最后一分钟`() {
        val p = plan(startMinute = 23 * 60, endMinute = Plan.MINUTE_MAX)
        assertThat(p.hasValidWindow).isTrue()
        assertThat(p.durationMinutes).isEqualTo(59)
    }

    @Test
    fun `名称为空 - 不合法`() {
        val p = plan(name = "   ")
        assertThat(p.isValid).isFalse()
        assertThat(p.invalidReason).contains("名称")
    }

    @Test
    fun `不合法时窗口属性抛异常而不是返回全天`() {
        val p = plan(startMinute = 10 * 60, endMinute = 10 * 60)
        val error = assertFailsWith<IllegalArgumentException> { p.window }
        assertThat(error.message).contains("不能相同")
    }

    // ---------------------------------------------------------------- 区间

    @Test
    fun `covers - 半开区间含开始不含结束`() {
        val p = plan(startMinute = 9 * 60, endMinute = 11 * 60)
        assertThat(p.covers(day, 9 * 60)).isTrue()
        assertThat(p.covers(day, 10 * 60 + 59)).isTrue()
        // 11:00 整已经结束 —— 与锁机窗口的半开区间语义一致
        assertThat(p.covers(day, 11 * 60)).isFalse()
        assertThat(p.covers(day, 8 * 60 + 59)).isFalse()
    }

    @Test
    fun `covers - 只看自己那一天`() {
        val p = plan()
        assertThat(p.covers(day.plusDays(1), 10 * 60)).isFalse()
        assertThat(p.covers(day.minusDays(1), 10 * 60)).isFalse()
    }

    @Test
    fun `window 与起止分钟一一对应`() {
        val p = plan(startMinute = 8 * 60 + 30, endMinute = 12 * 60)
        assertThat(p.window).isEqualTo(TimeWindow(510, 720))
        assertThat(p.window.toString()).isEqualTo("08:30-12:00")
    }

    // ---------------------------------------------------------------- 锁机规则

    @Test
    fun `toLockRule - 锁机窗口就是规划时间`() {
        val rule = plan(startMinute = 9 * 60, endMinute = 11 * 60).toLockRule()
        assertThat(rule.windows).containsExactly(TimeWindow(9 * 60, 11 * 60))
        assertThat(rule.effectiveLockWindows).containsExactly(TimeWindow(9 * 60, 11 * 60))
        // 走旧式自选窗口，不参与"打卡截止 + 时长"推导
        assertThat(rule.lockDurationMinutes).isEqualTo(0)
        assertThat(rule.usesDurationMode).isFalse()
    }

    @Test
    fun `toLockRule - 解锁条件仅为到点解锁`() {
        val rule = plan().toLockRule()
        assertThat(rule.allowCheckInUnlock).isFalse()
        // 规划没有"必须先打卡"的概念
        assertThat(rule.checkInWindows).isEmpty()
        assertThat(rule.hasCheckInWindow).isFalse()
    }

    @Test
    fun `toLockRule - 规划规则不会被当成习惯规则`() {
        val rule = plan(id = 42L).toLockRule()
        assertThat(rule.habitId).isNull()
        assertThat(rule.planId).isEqualTo(42L)
        assertThat(rule.isPlanRule).isTrue()
        assertThat(rule.name).isEqualTo("读书")
        // 不落库：id 固定 0（真相来源是 plans 表）
        assertThat(rule.id).isEqualTo(0L)
    }

    @Test
    fun `toLockRule - 只在规划当天生效`() {
        val rule = plan().toLockRule()
        assertThat(rule.isActiveOn(day)).isTrue()
        assertThat(rule.isActiveOn(day.minusDays(1))).isFalse()
        assertThat(rule.isActiveOn(day.plusDays(1))).isFalse()
    }

    @Test
    fun `toLockRule - 规则本身自洽`() {
        assertThat(plan().toLockRule().isValid).isTrue()
    }

    @Test
    fun `toLockRule - 非法规划被拒绝`() {
        val bad = plan(startMinute = 22 * 60, endMinute = 6 * 60)
        assertFailsWith<IllegalArgumentException> { bad.toLockRule() }
    }

    @Test
    fun `习惯规则默认允许打卡解锁且不是规划规则`() {
        val habitRule = com.zilv.app.domain.lock.LockRule(
            name = "习惯锁机",
            windows = listOf(TimeWindow(9 * 60, 11 * 60))
        )
        // 默认值就是向后兼容的关键：习惯的行为一字未变
        assertThat(habitRule.allowCheckInUnlock).isTrue()
        assertThat(habitRule.planId).isNull()
        assertThat(habitRule.isPlanRule).isFalse()
    }
}
