package com.zilv.app.domain.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [CheckInWindowRules] 的单元测试（本轮改造）。
 *
 * 用户要求："把打卡时间选择改成和明日规划那样（开始时间 + 结束时间
 * 两个滚轮选择器）"。两个界面（习惯编辑页/新建习惯弹窗、锁机详细设置）
 * 加上两处 ViewModel 的 `addCheckInWindow` 现在都走这一个纯函数，
 * 所以这里逐条钉住语义。
 *
 * 重点是这条**踩过多次**的坑：
 * `TimeWindow(x, x)` 的 `durationMinutes` 是 **1440 分钟（全天）** 而不是 0
 * （起止相同被 `crossesMidnight` 判成跨零点）。放过去就等于把
 * "09:00 到 09:00" 变成"整天都得打卡"。
 */
class CheckInWindowRulesTest {

    /** 小时 → 当天第几分钟，让下面的断言读起来就是"09:00"而不是 540。 */
    private fun h(hour: Int): Int = hour * 60

    // ------------------------------------------------------------ 合法输入

    @Test
    fun `普通的起止区间是合法的`() {
        assertThat(CheckInWindowRules.problem(h(8), h(9))).isNull()
        assertThat(CheckInWindowRules.problem(0, 1)).isNull()
        assertThat(CheckInWindowRules.problem(23 * 60 + 58, 23 * 60 + 59)).isNull()
    }

    @Test
    fun `跨零点是合法的（结束早于开始）`() {
        // 23:00-01:00 是合法的跨零点窗口，不是错误 ——
        // 界面以前也不拦它，本轮保持一致
        assertThat(CheckInWindowRules.problem(h(23), h(1))).isNull()
        assertThat(CheckInWindowRules.toWindow(h(23), h(1)))
            .isEqualTo(TimeWindow(h(23), h(1)))
        assertThat(CheckInWindowRules.toWindow(h(23), h(1))!!.crossesMidnight).isTrue()
    }

    @Test
    fun `结束取 00 00 表示 24 点而不是 0 分钟`() {
        // 与 TimeWindow.of(..., endMeansMidnight = true) 同一口径
        val window = CheckInWindowRules.toWindow(h(8), 0)!!
        assertThat(window).isEqualTo(TimeWindow(h(8), 1440))
        assertThat(window.durationMinutes).isEqualTo(960)
        assertThat(window.crossesMidnight).isFalse()
    }

    // ------------------------------------------------------------ 起止相同

    @Test
    fun `起止相同必须被显式拒绝（TimeWindow(x,x) 是全天而不是 0 分钟）`() {
        val problem = CheckInWindowRules.problem(h(9), h(9))!!
        assertThat(problem).contains("结束时间")
        assertThat(problem).contains("相同")
        // 顺手钉住那条陷阱本身：TimeWindow(540, 540) 的时长是 1440 而不是 0
        assertThat(TimeWindow(h(9), h(9)).durationMinutes).isEqualTo(1440)
    }

    @Test
    fun `起止相同时不产出窗口`() {
        assertThat(CheckInWindowRules.toWindow(0, 0)).isNull()
        assertThat(CheckInWindowRules.toWindow(h(15), h(15))).isNull()
    }

    // ------------------------------------------------------------ 越界

    @Test
    fun `越界的分钟数被拒绝且不产出窗口`() {
        assertThat(CheckInWindowRules.problem(-1, h(9))).contains("00:00..23:59")
        assertThat(CheckInWindowRules.problem(h(9), 1440)).contains("00:00..23:59")
        assertThat(CheckInWindowRules.problem(1440, 1440)).isNotNull()
        assertThat(CheckInWindowRules.toWindow(-1, h(9))).isNull()
        assertThat(CheckInWindowRules.toWindow(h(9), 1440)).isNull()
    }

    // ------------------------------------------------------------ 默认值

    @Test
    fun `界面预填的默认时段本身必须合法`() {
        // 默认值会被直接放进两个滚轮里，绝不能是 x-x（那会被当成全天窗口）
        assertThat(CheckInWindowRules.DEFAULT_START_MINUTE).isEqualTo(h(8))
        assertThat(CheckInWindowRules.DEFAULT_END_MINUTE).isEqualTo(h(9))
        assertThat(
            CheckInWindowRules.problem(
                CheckInWindowRules.DEFAULT_START_MINUTE,
                CheckInWindowRules.DEFAULT_END_MINUTE
            )
        ).isNull()
    }
}
