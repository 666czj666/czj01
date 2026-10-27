package com.zilv.app.data.mapper

import com.zilv.app.data.local.entity.CheckInEntity
import com.zilv.app.data.local.entity.HabitEntity
import com.zilv.app.domain.habit.HabitFrequencyCodec
import com.zilv.app.domain.model.CheckIn
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.Habit
import com.zilv.app.domain.model.HabitType
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 提醒时间的编解码：`HH:mm` 逗号分隔。
 *
 * 与 [HabitFrequencyCodec] 一样刻意不用 JSON —— 纯字符串便于单元测试。
 */
object RemindTimesCodec {

    private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun encode(times: List<LocalTime>): String =
        times.sorted().joinToString(",") { it.format(FORMATTER) }

    /** 无法解析的项直接丢弃，不抛异常。 */
    fun decode(raw: String): List<LocalTime> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { part ->
                val t = part.trim()
                if (t.isEmpty()) return@mapNotNull null
                runCatching { LocalTime.parse(t, FORMATTER) }.getOrNull()
            }
            .distinct()
            .sorted()
    }
}

/** 习惯类型的编解码。未知值退化为 [HabitType.SIMPLE]。 */
object HabitTypeCodec {
    fun encode(type: HabitType): String = type.name

    fun decode(raw: String): HabitType =
        runCatching { HabitType.valueOf(raw.trim().uppercase()) }.getOrDefault(HabitType.SIMPLE)
}

/** 打卡状态的编解码。未知值退化为 [CheckInStatus.MANUAL]。 */
object CheckInStatusCodec {
    fun encode(status: CheckInStatus): String = status.name

    fun decode(raw: String): CheckInStatus =
        runCatching { CheckInStatus.valueOf(raw.trim().uppercase()) }.getOrDefault(CheckInStatus.MANUAL)
}

// ------------------------------------------------------------------ 实体 <-> 领域

fun HabitEntity.toDomain(): Habit = Habit(
    id = id,
    name = name,
    icon = icon,
    color = color,
    type = HabitTypeCodec.decode(type),
    frequency = HabitFrequencyCodec.decode(frequencyType, frequencyPayload),
    remindTimes = RemindTimesCodec.decode(remindTimes),
    spotId = spotId,
    sortOrder = sortOrder,
    archived = archived,
    createdAt = createdAt
)

fun Habit.toEntity(): HabitEntity = HabitEntity(
    id = id,
    name = name,
    icon = icon,
    color = color,
    type = HabitTypeCodec.encode(type),
    frequencyType = HabitFrequencyCodec.typeOf(frequency),
    frequencyPayload = HabitFrequencyCodec.payloadOf(frequency),
    remindTimes = RemindTimesCodec.encode(remindTimes),
    spotId = spotId,
    sortOrder = sortOrder,
    archived = archived,
    createdAt = createdAt
)

fun CheckInEntity.toDomain(): CheckIn = CheckIn(
    id = id,
    habitId = habitId,
    ruleId = ruleId,
    sessionId = sessionId,
    checkedAtMillis = checkedAtMillis,
    epochDay = epochDay,
    status = CheckInStatusCodec.decode(status),
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    distanceM = distanceM,
    spotId = spotId,
    photoPath = photoPath,
    note = note
)

fun CheckIn.toEntity(): CheckInEntity = CheckInEntity(
    id = id,
    habitId = habitId,
    ruleId = ruleId,
    sessionId = sessionId,
    checkedAtMillis = checkedAtMillis,
    epochDay = epochDay,
    status = CheckInStatusCodec.encode(status),
    lat = lat,
    lng = lng,
    accuracyM = accuracyM,
    distanceM = distanceM,
    spotId = spotId,
    photoPath = photoPath,
    note = note
)
