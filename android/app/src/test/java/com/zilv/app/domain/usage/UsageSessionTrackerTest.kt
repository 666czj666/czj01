package com.zilv.app.domain.usage

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 使用时长会话跟踪的单元测试（PRD §3.7）。
 *
 * 最重要的一组断言围绕**按包名去重**（坑清单 #11）：
 * App 内部切 Activity 会连发窗口事件，不去重会把一次连续使用切碎。
 */
class UsageSessionTrackerTest {

    private val t0 = 1_700_000_000_000L

    private fun at(secondsLater: Long) = t0 + secondsLater * 1000L

    // ------------------------------------------------------------ 去重（核心）

    @Test
    fun `同一包名的连续事件不产生新段且不重置计时`() {
        val tracker = UsageSessionTracker()

        assertThat(tracker.onForeground("com.tencent.mm", at(0))).isNull()
        // 微信内部切 Activity —— 包名没变，必须去重
        assertThat(tracker.onForeground("com.tencent.mm", at(5))).isNull()
        assertThat(tracker.onForeground("com.tencent.mm", at(10))).isNull()
        assertThat(tracker.onForeground("com.tencent.mm", at(60))).isNull()

        // 计时起点仍是 0 秒，没有被后面的切页重置
        assertThat(tracker.currentPackage).isEqualTo("com.tencent.mm")
    }

    @Test
    fun `切换包名时结算上一段且时长从原始起点算起`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(0))
        tracker.onForeground("com.tencent.mm", at(5))   // 去重

        val finished = tracker.onForeground("com.android.chrome", at(40))

        assertThat(finished).isNotNull()
        assertThat(finished!!.packageName).isEqualTo("com.tencent.mm")
        assertThat(finished.startMillis).isEqualTo(at(0))
        assertThat(finished.endMillis).isEqualTo(at(40))
        assertThat(finished.durationMillis).isEqualTo(40_000L)
    }

    @Test
    fun `大量内部切页事件不会切碎一段使用`() {
        val tracker = UsageSessionTracker()
        val settled = mutableListOf<UsageSessionTracker.Segment>()

        tracker.onForeground("com.tencent.mm", at(0))
        // 模拟 40 分钟内微信内部反复切页
        (1..200).forEach { i ->
            tracker.onForeground("com.tencent.mm", at(i * 12L))?.let { settled += it }
        }
        tracker.onForeground("com.android.chrome", at(2400))?.let { settled += it }

        assertThat(settled).hasSize(1)
        assertThat(settled.single().durationMillis).isEqualTo(2400 * 1000L)
    }

    // ---------------------------------------------------------------- 息屏

    @Test
    fun `息屏会结算当前段并暂停计时`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(0))

        val finished = tracker.onScreenOff(at(30))

        assertThat(finished!!.durationMillis).isEqualTo(30_000L)
        assertThat(tracker.isTracking).isFalse()
        assertThat(tracker.currentPackage).isNull()
    }

    @Test
    fun `息屏期间的前台事件不产生时长`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(0))
        tracker.onScreenOff(at(30))

        // 息屏期间系统仍会报事件，但不应计时
        tracker.onForeground("com.tencent.mm", at(60))
        assertThat(tracker.onScreenOff(at(90))).isNull()
        assertThat(tracker.currentSegmentAt(at(120))).isNull()
    }

    @Test
    fun `亮屏后从此刻重新开始计时`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(0))
        tracker.onScreenOff(at(30))
        tracker.onScreenOn(at(300))

        val seg = tracker.currentSegmentAt(at(360))
        assertThat(seg).isNotNull()
        assertThat(seg!!.startMillis).isEqualTo(at(300))
        assertThat(seg.durationMillis).isEqualTo(60_000L)
    }

    // ------------------------------------------------------------ 其他边界

    @Test
    fun `flush 结算当前段并清空状态`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(0))

        val finished = tracker.flush(at(50))
        assertThat(finished!!.durationMillis).isEqualTo(50_000L)
        assertThat(tracker.currentPackage).isNull()
        assertThat(tracker.currentSegmentAt(at(60))).isNull()
    }

    @Test
    fun `没有前台应用时 flush 返回 null`() {
        assertThat(UsageSessionTracker().flush(at(10))).isNull()
    }

    @Test
    fun `空包名被忽略`() {
        val tracker = UsageSessionTracker()
        assertThat(tracker.onForeground("", at(0))).isNull()
        assertThat(tracker.onForeground("   ", at(10))).isNull()
        assertThat(tracker.currentPackage).isNull()
    }

    @Test
    fun `包名前后空格被忽略且视为同一个包`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(0))
        assertThat(tracker.onForeground("  com.tencent.mm  ", at(10))).isNull()
        assertThat(tracker.currentPackage).isEqualTo("com.tencent.mm")
    }

    @Test
    fun `时钟回拨不产生负时长`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(100))
        val finished = tracker.onForeground("com.android.chrome", at(50)) // 时间倒流

        assertThat(finished!!.durationMillis).isEqualTo(0L)
    }

    @Test
    fun `currentSegmentAt 对时钟回拨也返回零长度`() {
        val tracker = UsageSessionTracker()
        tracker.onForeground("com.tencent.mm", at(100))
        val seg = tracker.currentSegmentAt(at(50))!!
        assertThat(seg.durationMillis).isEqualTo(0L)
    }

    // ---------------------------------------------------------------- 聚合

    @Test
    fun `聚合按包名累加`() {
        val segments = listOf(
            UsageSessionTracker.Segment("a", 0, 60_000),
            UsageSessionTracker.Segment("b", 60_000, 90_000),
            UsageSessionTracker.Segment("a", 90_000, 120_000)
        )
        val totals = UsageSessionTracker.aggregate(segments)
        assertThat(totals["a"]).isEqualTo(90_000L)
        assertThat(totals["b"]).isEqualTo(30_000L)
    }

    @Test
    fun `聚合忽略零长度与负长度段`() {
        val segments = listOf(
            UsageSessionTracker.Segment("a", 0, 0),
            UsageSessionTracker.Segment("b", 100, 50),
            UsageSessionTracker.Segment("c", 0, 1_000)
        )
        val totals = UsageSessionTracker.aggregate(segments)
        assertThat(totals).containsExactly("c", 1_000L)
    }

    @Test
    fun `空输入聚合为空`() {
        assertThat(UsageSessionTracker.aggregate(emptyList())).isEmpty()
    }
}
