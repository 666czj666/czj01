package com.zilv.app.data.mapper

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.habit.HabitFrequency
import com.zilv.app.domain.model.CheckIn
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class MappersTest {

    // ---------------------------------------------------------- 提醒时间编解码

    @Test
    fun `提醒时间 - 编码按时间升序`() {
        assertThat(
            RemindTimesCodec.encode(listOf(LocalTime.of(12, 0), LocalTime.of(7, 30)))
        ).isEqualTo("07:30,12:00")
    }

    @Test
    fun `提醒时间 - 往返一致且解码结果按升序排列`() {
        val unsorted = listOf(LocalTime.of(7, 30), LocalTime.of(22, 0), LocalTime.of(0, 5))
        val expectedSorted = listOf(LocalTime.of(0, 5), LocalTime.of(7, 30), LocalTime.of(22, 0))
        assertThat(RemindTimesCodec.decode(RemindTimesCodec.encode(unsorted)))
            .isEqualTo(expectedSorted)
    }

    @Test
    fun `提醒时间 - 空串与空列表`() {
        assertThat(RemindTimesCodec.decode("")).isEmpty()
        assertThat(RemindTimesCodec.decode("   ")).isEmpty()
        assertThat(RemindTimesCodec.encode(emptyList())).isEmpty()
    }

    @Test
    fun `提醒时间 - 非法项被丢弃而不是抛异常`() {
        assertThat(RemindTimesCodec.decode("07:30,abc,,25:99,12:00"))
            .containsExactly(LocalTime.of(7, 30), LocalTime.of(12, 0))
            .inOrder()
    }

    @Test
    fun `提醒时间 - 去重`() {
        assertThat(RemindTimesCodec.decode("07:30,07:30")).hasSize(1)
    }

    // ---------------------------------------------------------- 类型 / 状态编解码

    @Test
    fun `习惯类型 - 往返一致且未知值退化为纯打卡`() {
        HabitType.entries.forEach { type ->
            assertThat(HabitTypeCodec.decode(HabitTypeCodec.encode(type))).isEqualTo(type)
        }
        assertThat(HabitTypeCodec.decode("WHATEVER")).isEqualTo(HabitType.SIMPLE)
        assertThat(HabitTypeCodec.decode(" location ")).isEqualTo(HabitType.LOCATION)
    }

    @Test
    fun `打卡状态 - 往返一致且未知值退化为手动`() {
        CheckInStatus.entries.forEach { status ->
            assertThat(CheckInStatusCodec.decode(CheckInStatusCodec.encode(status))).isEqualTo(status)
        }
        assertThat(CheckInStatusCodec.decode("BOGUS")).isEqualTo(CheckInStatus.MANUAL)
    }

    // ---------------------------------------------------------- 实体往返

    @Test
    fun `习惯 - 实体往返无损`() {
        val habit = Habit(
            id = 7L,
            name = "到图书馆",
            icon = "menu_book",
            color = 0xFF112233,
            type = HabitType.LOCATION,
            frequency = HabitFrequency.SpecificDays(
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            ),
            remindTimes = listOf(LocalTime.of(7, 30), LocalTime.of(13, 0)),
            spotId = 42L,
            sortOrder = 3,
            archived = false,
            createdAt = 1_700_000_000_000L
        )
        assertThat(habit.toEntity().toDomain()).isEqualTo(habit)
    }

    @Test
    fun `打卡 - 实体往返无损`() {
        val checkIn = CheckIn(
            id = 9L,
            habitId = 7L,
            ruleId = 1L,
            sessionId = 2L,
            checkedAtMillis = 1_700_000_000_000L,
            epochDay = 19_700L,
            status = CheckInStatus.LATE,
            lat = 30.1234,
            lng = 120.5678,
            accuracyM = 12.5f,
            distanceM = 34.0,
            spotId = 42L,
            photoPath = "/tmp/a.jpg",
            note = "备注"
        )
        assertThat(checkIn.toEntity().toDomain()).isEqualTo(checkIn)
    }

    @Test
    fun `打卡 - localDate 与 epochDay 一致`() {
        val c = CheckIn(habitId = 1L, checkedAtMillis = 0L, epochDay = 19_700L)
        assertThat(c.localDate.toEpochDay()).isEqualTo(19_700L)
    }
}
