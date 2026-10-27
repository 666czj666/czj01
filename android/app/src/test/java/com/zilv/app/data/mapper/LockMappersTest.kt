package com.zilv.app.data.mapper

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.local.entity.LockRuleEntity
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class LockMappersTest {

    // ---------------------------------------------------------------- 时间窗口

    @Test
    fun `时间窗口 - 往返一致`() {
        val windows = listOf(
            TimeWindow(480, 1080),   // 08:00-18:00
            TimeWindow(1350, 420),   // 22:30-07:00 跨零点
            TimeWindow(0, 1440)      // 全天
        )
        assertThat(TimeWindowCodec.decode(TimeWindowCodec.encode(windows))).isEqualTo(windows)
    }

    @Test
    fun `时间窗口 - 编码格式为 start-end 逗号分隔`() {
        assertThat(TimeWindowCodec.encode(listOf(TimeWindow(480, 1080), TimeWindow(1350, 420))))
            .isEqualTo("480-1080,1350-420")
    }

    @Test
    fun `时间窗口 - 空串与空列表`() {
        assertThat(TimeWindowCodec.decode("")).isEmpty()
        assertThat(TimeWindowCodec.decode("   ")).isEmpty()
        assertThat(TimeWindowCodec.encode(emptyList())).isEmpty()
    }

    @Test
    fun `时间窗口 - 非法项被丢弃而不抛异常`() {
        assertThat(TimeWindowCodec.decode("480-1080,abc,12-xx,1440-100,100-1441,-5-10,500-"))
            .containsExactly(TimeWindow(480, 1080))
    }

    @Test
    fun `时间窗口 - 边界值被接受`() {
        assertThat(TimeWindowCodec.decode("0-1440")).containsExactly(TimeWindow(0, 1440))
        assertThat(TimeWindowCodec.decode("0-0")).containsExactly(TimeWindow(0, 0))
        assertThat(TimeWindowCodec.decode("1439-1439")).containsExactly(TimeWindow(1439, 1439))
    }

    @Test
    fun `时间窗口 - 去重`() {
        assertThat(TimeWindowCodec.decode("480-1080,480-1080")).hasSize(1)
    }

    // ------------------------------------------------------------------ 星期

    @Test
    fun `星期 - 往返一致且按序号升序`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)
        assertThat(DaysOfWeekCodec.encode(days)).isEqualTo("1,3,5")
        assertThat(DaysOfWeekCodec.decode("1,3,5"))
            .containsExactly(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
    }

    @Test
    fun `星期 - 空串表示每天（空集合）`() {
        assertThat(DaysOfWeekCodec.decode("")).isEmpty()
        assertThat(DaysOfWeekCodec.encode(emptySet())).isEmpty()
    }

    @Test
    fun `星期 - 非法序号被过滤`() {
        assertThat(DaysOfWeekCodec.decode("1,9,0,abc,7"))
            .containsExactly(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
    }

    // -------------------------------------------------------------- 解锁条件

    @Test
    fun `解锁条件 - 往返一致且未知值退化为仅定时`() {
        UnlockMode.entries.forEach {
            assertThat(UnlockModeCodec.decode(UnlockModeCodec.encode(it))).isEqualTo(it)
        }
        assertThat(UnlockModeCodec.decode("BOGUS")).isEqualTo(UnlockMode.TIME_ONLY)
        assertThat(UnlockModeCodec.decode(" spot_checkin ")).isEqualTo(UnlockMode.SPOT_CHECKIN)
    }

    @Test
    fun `解锁条件 - 已废弃的旧值仍能原样往返，不影响新行为`() {
        // v2.2：解锁方式不再由用户选（打卡即解锁，或等锁机时间走完）。
        // 枚举留着只是为了**旧数据能读出来**，所以这里钉住一件事：
        // 旧规则上的 unlockMode 会原样往返，不会被悄悄改写成别的值。
        val legacy = LockRule(
            name = "旧规则",
            windows = listOf(TimeWindow(480, 1080)),
            unlockMode = UnlockMode.TIME_ONLY
        )
        val loaded = legacy.toEntity().toDomain()
        assertThat(loaded.unlockMode).isEqualTo(UnlockMode.TIME_ONLY)
        assertThat(loaded.copy(unlockMode = UnlockMode.SPOT_CHECKIN).toEntity().toDomain().unlockMode)
            .isEqualTo(UnlockMode.SPOT_CHECKIN)
        // 而且它再也管不着"打卡能不能解锁"了：两种取值的规则校验结果完全一样
        assertThat(
            legacy.isValid == legacy.copy(unlockMode = UnlockMode.SPOT_CHECKIN).isValid
        ).isTrue()
    }

    @Test
    fun `解锁条件 - 只剩两种，两者任一已删除`() {
        // 「两者任一」的第二个条件 PRD 从未定义，行为与「到点打卡」完全一致，
        // 留着只会误导用户。已按用户要求删除。
        assertThat(UnlockMode.entries).hasSize(2)
        assertThat(UnlockMode.entries.map { it.name })
            .containsExactly("TIME_ONLY", "SPOT_CHECKIN")
    }

    @Test
    fun `解锁条件 - 历史 EITHER 数据映射为到点打卡而不是仅定时`() {
        // 关键：若这里退化成 TIME_ONLY，用户原本"打卡可解锁"的旧规则
        // 会悄悄变成"只能等时间到" —— 静默的行为退化。
        assertThat(UnlockModeCodec.decode("EITHER")).isEqualTo(UnlockMode.SPOT_CHECKIN)
        assertThat(UnlockModeCodec.decode("either")).isEqualTo(UnlockMode.SPOT_CHECKIN)
        assertThat(UnlockModeCodec.decode(" EITHER ")).isEqualTo(UnlockMode.SPOT_CHECKIN)
    }

    @Test
    fun `提前打卡有效期 - 默认是 0 而不是 15`() {
        assertThat(LockRule(name = "x", windows = listOf(TimeWindow(480, 540))).earlyCheckInMinutes)
            .isEqualTo(0)
    }

    @Test
    fun `提前打卡有效期 - 实体往返保留非零值`() {
        val rule = LockRule(
            name = "x",
            windows = listOf(TimeWindow(480, 540)),
            earlyCheckInMinutes = 30
        )
        assertThat(rule.toEntity().toDomain().earlyCheckInMinutes).isEqualTo(30)
    }

    // -------------------------------------------------------------- 实体往返

    @Test
    fun `规则 - 实体往返无损`() {
        val rule = LockRule(
            id = 5L,
            name = "图书馆自习",
            enabled = true,
            startDate = LocalDate.of(2026, 10, 1),
            endDate = LocalDate.of(2026, 12, 31),
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            windows = listOf(TimeWindow(480, 1080), TimeWindow(1350, 420)),
            unlockMode = UnlockMode.SPOT_CHECKIN,
            spotId = 42L,
            earlyCheckInMinutes = 20,
            graceMinutes = 5,
            strictMode = true,
            strictLeaveMinutes = 15,
            message = "去图书馆，别在这刷手机",
            sortOrder = 2
        )
        assertThat(rule.toEntity().toDomain()).isEqualTo(rule)
    }

    @Test
    fun `规则 - null 日期与空字段往返无损`() {
        val rule = LockRule(
            id = 1L,
            name = "最小规则",
            windows = listOf(TimeWindow(0, 60))
        )
        assertThat(rule.toEntity().toDomain()).isEqualTo(rule)
    }

    @Test
    fun `规则 - 损坏的日期字符串退化为 null 而不是崩溃`() {
        val entity = LockRule(
            id = 1L,
            name = "x",
            windows = listOf(TimeWindow(0, 60)),
            startDate = LocalDate.of(2026, 1, 1)
        ).toEntity().copy(startDate = "not-a-date")
        assertThat(entity.toDomain().startDate).isNull()
    }

    // -------------------------------------------- 仅到点解锁 / 规划来源（v2.4）

    @Test
    fun `规则 - 仅到点解锁与规划来源往返无损`() {
        val rule = LockRule(
            id = 2L,
            name = "规划锁机",
            windows = listOf(TimeWindow(9 * 60, 11 * 60)),
            allowCheckInUnlock = false,
            planId = 9L
        )
        val back = rule.toEntity().toDomain()
        assertThat(back).isEqualTo(rule)
        assertThat(back.allowCheckInUnlock).isFalse()
        assertThat(back.planId).isEqualTo(9L)
        assertThat(back.isPlanRule).isTrue()
    }

    @Test
    fun `规则 - 默认允许打卡解锁且不是规划规则（向后兼容）`() {
        assertThat(LockRule(name = "x", windows = listOf(TimeWindow(0, 60))).allowCheckInUnlock)
            .isTrue()

        // 直接构造"列上没有这一列/为默认值"的实体，模拟历史数据
        val legacyRow = LockRuleEntity(
            id = 1L,
            name = "旧规则",
            enabled = true,
            startDate = null,
            endDate = null,
            daysOfWeek = "",
            windows = "480-540",
            checkInWindows = "",
            lockDurationMinutes = 0,
            unlockMode = "TIME_ONLY",
            allowCheckInUnlock = true,
            planId = null,
            spotId = null,
            earlyCheckInMinutes = 0,
            graceMinutes = 0,
            strictMode = false,
            strictLeaveMinutes = 10,
            message = "",
            sortOrder = 0
        )
        val rule = legacyRow.toDomain()
        assertThat(rule.allowCheckInUnlock).isTrue()
        assertThat(rule.planId).isNull()
        assertThat(rule.isPlanRule).isFalse()
    }
}
