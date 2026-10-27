package com.zilv.app.data.mapper

import com.zilv.app.data.local.entity.AllowAppEntity
import com.zilv.app.data.local.entity.LockRuleEntity
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 时间窗口列表的编解码：`start-end` 逗号分隔，单位为"当天第几分钟"。
 * 例：`480-1080,1350-420` 表示 08:00-18:00 与 22:30-07:00（跨零点）。
 *
 * 与其它编解码器一样：**非法项丢弃而不是抛异常**，避免脏数据让整个规则页崩溃。
 */
object TimeWindowCodec {

    fun encode(windows: List<TimeWindow>): String =
        windows.joinToString(",") { "${it.startMinute}-${it.endMinute}" }

    fun decode(raw: String): List<TimeWindow> {
        if (raw.isBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { part ->
                val t = part.trim()
                val dash = t.indexOf('-')
                if (dash <= 0) return@mapNotNull null
                val s = t.substring(0, dash).trim().toIntOrNull() ?: return@mapNotNull null
                val e = t.substring(dash + 1).trim().toIntOrNull() ?: return@mapNotNull null
                if (s !in 0..1439 || e !in 0..1440) return@mapNotNull null
                TimeWindow(s, e)
            }
            .distinct()
    }
}

/** 星期集合的编解码：ISO 序号（周一=1），空串表示每天。 */
object DaysOfWeekCodec {

    fun encode(days: Set<DayOfWeek>): String =
        days.sortedBy { it.value }.joinToString(",") { it.value.toString() }

    fun decode(raw: String): Set<DayOfWeek> {
        if (raw.isBlank()) return emptySet()
        return raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .map { DayOfWeek.of(it) }
            .toSet()
    }
}

/**
 * 解锁条件的编解码，未知值退化为 [UnlockMode.TIME_ONLY]。
 *
 * ⚠️ **历史兼容**：早期版本存在过 `EITHER`（两者任一），已删除。
 * 数据库里可能仍有以此为值的旧规则，这里显式映射为 [UnlockMode.SPOT_CHECKIN] ——
 * 因为 EITHER 当时的实际行为就与它一致。若直接走 `valueOf` 失败回退到
 * [UnlockMode.TIME_ONLY]，用户原本"打卡可解锁"的规则会悄悄变成"只能等时间到"，
 * 属于静默的行为退化。
 */
object UnlockModeCodec {
    private const val LEGACY_EITHER = "EITHER"

    fun encode(mode: UnlockMode): String = mode.name

    fun decode(raw: String): UnlockMode {
        val key = raw.trim().uppercase()
        if (key == LEGACY_EITHER) return UnlockMode.SPOT_CHECKIN
        return runCatching { UnlockMode.valueOf(key) }.getOrDefault(UnlockMode.TIME_ONLY)
    }
}

/** 白名单 scope 常量。 */
object AllowScope {
    const val BUILTIN = "builtin"
    const val USER = "user"
}

// ------------------------------------------------------------ 实体 <-> 领域

fun LockRuleEntity.toDomain(): LockRule = LockRule(
    id = id,
    habitId = habitId,
    planId = planId,
    name = name,
    enabled = enabled,
    startDate = startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    endDate = endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    daysOfWeek = DaysOfWeekCodec.decode(daysOfWeek),
    checkInWindows = TimeWindowCodec.decode(checkInWindows),
    windows = TimeWindowCodec.decode(windows),
    lockDurationMinutes = lockDurationMinutes,
    unlockMode = UnlockModeCodec.decode(unlockMode),
    allowCheckInUnlock = allowCheckInUnlock,
    spotId = spotId,
    earlyCheckInMinutes = earlyCheckInMinutes,
    graceMinutes = graceMinutes,
    strictMode = strictMode,
    strictLeaveMinutes = strictLeaveMinutes,
    message = message,
    sortOrder = sortOrder
)

fun LockRule.toEntity(): LockRuleEntity = LockRuleEntity(
    id = id,
    habitId = habitId,
    planId = planId,
    name = name,
    enabled = enabled,
    startDate = startDate?.toString(),
    endDate = endDate?.toString(),
    daysOfWeek = DaysOfWeekCodec.encode(daysOfWeek),
    windows = TimeWindowCodec.encode(windows),
    checkInWindows = TimeWindowCodec.encode(checkInWindows),
    lockDurationMinutes = lockDurationMinutes,
    unlockMode = UnlockModeCodec.encode(unlockMode),
    allowCheckInUnlock = allowCheckInUnlock,
    spotId = spotId,
    earlyCheckInMinutes = earlyCheckInMinutes,
    graceMinutes = graceMinutes,
    strictMode = strictMode,
    strictLeaveMinutes = strictLeaveMinutes,
    message = message,
    sortOrder = sortOrder
)

fun AllowAppEntity.toDomain(): AllowApp = AllowApp(
    packageName = packageName,
    label = label,
    builtin = scope == AllowScope.BUILTIN,
    enabled = enabled
)

/** 白名单条目的领域模型。 */
data class AllowApp(
    val packageName: String,
    val label: String,
    val builtin: Boolean,
    val enabled: Boolean
)
