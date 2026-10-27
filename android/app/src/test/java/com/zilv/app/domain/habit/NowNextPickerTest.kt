package com.zilv.app.domain.habit

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.TodayHabit
import com.zilv.app.domain.model.Habit
import org.junit.Test
import java.time.LocalTime

/**
 * [NowNextPicker] 的单元测试。
 *
 * 用户要求："今天界面保留今日习惯列表，在列表上方显示当前习惯或距离当前最近的习惯"。
 *
 * 这里的用例把**选择语义整个钉死**（语义见 [NowNextPicker.pick] 的 KDoc）：
 *  1. 只在未打卡的习惯里选
 *  2. 有提醒时间、已过且在 60 分钟内 → NOW，取其中最晚的
 *  3. 否则取将来最早的 → NEXT
 *  4. 都没有提醒时间 → 列表最靠前的未打卡习惯，NEXT
 */
class NowNextPickerTest {

    private fun item(
        id: Long,
        name: String,
        remindTimes: List<LocalTime> = emptyList(),
        checked: Boolean = false
    ) = TodayHabit(
        habit = Habit(id = id, name = name, remindTimes = remindTimes),
        checkedToday = checked,
        stats = StreakStats(current = 0, longest = 0, total = 0)
    )

    /**
     * 单条提醒时间的简写，用例里用得最多。
     *
     * 刻意换个名字而不是重载 `item` —— 重载的 JVM 签名会与
     * "提醒时间列表 + 布尔参数"那个撞在一起。
     */
    private fun itemAt(
        id: Long,
        name: String,
        time: LocalTime,
        checked: Boolean = false
    ) = item(id, name, listOf(time), checked)

    // ------------------------------------------------------------------ 第 1 条

    @Test
    fun `全部打完时返回 null`() {
        val items = listOf(
            itemAt(1, "早起", LocalTime.of(7, 0), checked = true),
            item(2, "跑步", checked = true)
        )
        assertThat(NowNextPicker.pick(items, setOf(1L, 2L), LocalTime.of(12, 0))).isNull()
    }

    @Test
    fun `没有习惯时返回 null`() {
        assertThat(NowNextPicker.pick(emptyList(), emptySet(), LocalTime.of(12, 0))).isNull()
    }

    @Test
    fun `已打卡的习惯不会被选中`() {
        val items = listOf(
            itemAt(1, "早起", LocalTime.of(9, 0), checked = true),
            itemAt(2, "跑步", LocalTime.of(18, 0))
        )
        val picked = NowNextPicker.pick(items, setOf(1L), LocalTime.of(9, 5))!!
        // 9:00 那条虽然刚过 5 分钟，但已经打卡了 —— 不该再提示"现在该做"
        assertThat(picked.habit.name).isEqualTo("跑步")
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NEXT)
    }

    @Test
    fun `checkedHabitIds 也会被当成已打卡`() {
        // 两个判据都表示"打过了"。这里刻意只让 checkedHabitIds 命中，
        // 验证它不是被忽略的参数
        val items = listOf(
            itemAt(1, "早起", LocalTime.of(9, 0)),
            itemAt(2, "跑步", LocalTime.of(18, 0))
        )
        val picked = NowNextPicker.pick(items, setOf(1L), LocalTime.of(9, 5))!!
        assertThat(picked.habit.name).isEqualTo("跑步")
    }

    // ------------------------------------------------------------------ 第 2 条

    @Test
    fun `提醒刚过 12 分钟时是 NOW`() {
        val items = listOf(itemAt(1, "背单词", LocalTime.of(7, 30)))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(7, 42))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NOW)
        assertThat(picked.habit.name).isEqualTo("背单词")
        assertThat(picked.minutesUntil).isEqualTo(-12)
        assertThat(picked.distanceLabel).isEqualTo("已经过了 12 分钟")
    }

    @Test
    fun `提醒时间就是现在时是 NOW 且距离为 0`() {
        val items = listOf(itemAt(1, "背单词", LocalTime.of(7, 30)))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(7, 30))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NOW)
        assertThat(picked.minutesUntil).isEqualTo(0)
        assertThat(picked.distanceLabel).isEqualTo("就是现在")
    }

    @Test
    fun `多个已过的提醒时间取最晚的那个`() {
        val items = listOf(
            itemAt(1, "很久以前", LocalTime.of(6, 30)),
            itemAt(2, "刚刚", LocalTime.of(7, 55))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(8, 0))!!
        // 6:30 过了 90 分钟（超出窗口），7:55 过了 5 分钟 → 选 7:55
        assertThat(picked.habit.name).isEqualTo("刚刚")
        assertThat(picked.minutesUntil).isEqualTo(-5)
    }

    @Test
    fun `超出 60 分钟窗口的提醒不算 NOW`() {
        val items = listOf(itemAt(1, "两小时前", LocalTime.of(14, 0)))
        // 16:00 —— 已经过了 120 分钟
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NEXT)
        // 该习惯没有将来的提醒时间，于是落到第 4 条：仍然是它，但没有距离
        assertThat(picked.habit.name).isEqualTo("两小时前")
        assertThat(picked.minutesUntil).isNull()
    }

    @Test
    fun `窗口边界 60 分钟整仍算 NOW`() {
        val items = listOf(itemAt(1, "边界", LocalTime.of(15, 0)))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NOW)
        assertThat(picked.minutesUntil).isEqualTo(-60)
    }

    @Test
    fun `窗口边界 61 分钟不算 NOW`() {
        val items = listOf(itemAt(1, "边界外", LocalTime.of(14, 59)))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NEXT)
        assertThat(picked.minutesUntil).isNull()
    }

    @Test
    fun `NOW 优先于 NEXT`() {
        val items = listOf(
            itemAt(1, "接下来", LocalTime.of(17, 0)),
            itemAt(2, "现在", LocalTime.of(15, 55))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.habit.name).isEqualTo("现在")
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NOW)
    }

    @Test
    fun `一个习惯有多个提醒时间时取窗口内最晚的那个`() {
        val items = listOf(
            item(1, "喝水", listOf(LocalTime.of(15, 10), LocalTime.of(15, 55)))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NOW)
        assertThat(picked.minutesUntil).isEqualTo(-5)
    }

    // ------------------------------------------------------------------ 第 3 条

    @Test
    fun `将来最早的提醒是 NEXT`() {
        val items = listOf(
            itemAt(1, "跑步", LocalTime.of(19, 0)),
            itemAt(2, "开会", LocalTime.of(17, 20))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.habit.name).isEqualTo("开会")
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NEXT)
        assertThat(picked.minutesUntil).isEqualTo(80)
        assertThat(picked.distanceLabel).isEqualTo("还有 1 小时 20 分钟")
    }

    @Test
    fun `刚好一小时的将来提醒文案不带分钟`() {
        val items = listOf(itemAt(1, "跑步", LocalTime.of(17, 0)))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.distanceLabel).isEqualTo("还有 1 小时")
    }

    @Test
    fun `不足一小时的将来提醒只报分钟`() {
        val items = listOf(itemAt(1, "跑步", LocalTime.of(16, 45)))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.distanceLabel).isEqualTo("还有 45 分钟")
    }

    @Test
    fun `一个习惯的多个将来提醒时间取最早的那个`() {
        val items = listOf(
            item(1, "喝水", listOf(LocalTime.of(18, 0), LocalTime.of(16, 30)))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.minutesUntil).isEqualTo(30)
    }

    @Test
    fun `将来时间并列时取列表靠前的那条`() {
        val items = listOf(
            itemAt(1, "第一条", LocalTime.of(18, 0)),
            itemAt(2, "第二条", LocalTime.of(18, 0))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.habit.name).isEqualTo("第一条")
    }

    @Test
    fun `NOW 并列时也取列表靠前的那条`() {
        // 两条的提醒时间都在窗口内、且时刻相同 —— 平手判据是列表顺序。
        // 注意"取最晚"里的次键是列表**升序**，所以这里该拿到第一条
        val items = listOf(
            itemAt(1, "第一条", LocalTime.of(15, 0)),
            itemAt(2, "第二条", LocalTime.of(15, 0))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NOW)
        assertThat(picked.habit.name).isEqualTo("第一条")
    }

    // ------------------------------------------------------------------ 第 4 条

    @Test
    fun `都没有提醒时间时取列表最靠前的未打卡习惯`() {
        val items = listOf(
            item(1, "早起", checked = true),
            item(2, "读书"),
            item(3, "写日记")
        )
        val picked = NowNextPicker.pick(items, setOf(1L), LocalTime.of(16, 0))!!
        assertThat(picked.habit.name).isEqualTo("读书")
        assertThat(picked.mode).isEqualTo(NowNextPicker.Mode.NEXT)
    }

    @Test
    fun `没有提醒时间时不编造距离`() {
        val items = listOf(item(1, "读书"))
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.hasRemindTime).isFalse()
        assertThat(picked.minutesUntil).isNull()
        assertThat(picked.distanceLabel).isEmpty()
    }

    @Test
    fun `没有提醒时间的习惯在有将来提醒时让位`() {
        val items = listOf(
            item(1, "读书"),
            itemAt(2, "跑步", LocalTime.of(18, 0))
        )
        val picked = NowNextPicker.pick(items, emptySet(), LocalTime.of(16, 0))!!
        assertThat(picked.habit.name).isEqualTo("跑步")
        assertThat(picked.minutesUntil).isEqualTo(120)
    }

    // -------------------------------------------------------------- 纯工具方法

    @Test
    fun `minutesBetween 带符号`() {
        assertThat(NowNextPicker.minutesBetween(LocalTime.of(16, 0), LocalTime.of(17, 20)))
            .isEqualTo(80)
        assertThat(NowNextPicker.minutesBetween(LocalTime.of(16, 0), LocalTime.of(15, 48)))
            .isEqualTo(-12)
        assertThat(NowNextPicker.minutesBetween(LocalTime.of(16, 0), LocalTime.of(16, 0)))
            .isEqualTo(0)
    }

    @Test
    fun `formatDistance 三种形态`() {
        assertThat(NowNextPicker.formatDistance(0)).isEqualTo("就是现在")
        assertThat(NowNextPicker.formatDistance(80)).isEqualTo("还有 1 小时 20 分钟")
        assertThat(NowNextPicker.formatDistance(-12)).isEqualTo("已经过了 12 分钟")
    }

    @Test
    fun `NOW 窗口常量是 60 分钟`() {
        // 这个常量直接决定"刚错过多久还算现在"，写死防止被顺手改掉
        assertThat(NowNextPicker.NOW_WINDOW_MINUTES).isEqualTo(60)
    }
}
