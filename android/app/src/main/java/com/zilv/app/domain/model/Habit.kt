package com.zilv.app.domain.model

import com.zilv.app.domain.habit.HabitFrequency
import java.time.LocalTime

/** 习惯类型（PRD §3.1）。 */
enum class HabitType {
    /** 纯打卡：点一下就算完成 */
    SIMPLE,

    /** 需到地点打卡：要走 GPS 校验 */
    LOCATION,

    /** 需计时 */
    TIMED
}

/**
 * **打卡条件**（习惯级，只有两个取值）。
 *
 * 用户亲自纠正过的术语：
 *  - 「打卡」= 一次完成、连续天数 +1
 *  - 「打卡条件」= 这条习惯要求怎么打卡：**指定地点** 或 **无**
 *
 * 原先代码把"打卡条件"混在 [HabitType] 与锁机规则的 `unlockMode` 里叫"打卡"，
 * 现在统一到这里。
 */
enum class CheckInCondition {
    /** 无：什么时候都能打卡，点一下就算（**室内也能用**）。 */
    NONE,

    /** 指定地点：必须到选定的打卡点才能打卡（**室内可能定位不到**）。 */
    LOCATION
}

/**
 * 习惯领域模型。
 *
 * 对应 PRD §5 的 `habits` 表。
 */
data class Habit(
    val id: Long = 0L,
    val name: String,
    val icon: String = DEFAULT_ICON,
    val color: Long = DEFAULT_COLOR,
    val type: HabitType = HabitType.SIMPLE,
    val frequency: HabitFrequency = HabitFrequency.Daily,
    /** 提醒时间，如 07:30；可多个 */
    val remindTimes: List<LocalTime> = emptyList(),
    /** 关联打卡点（[HabitType.LOCATION] 时必填） */
    val spotId: Long? = null,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    /**
     * 是否满足保存条件。
     *
     * PRD 验收标准：「到点类型未选打卡点时禁止保存」。
     */
    val isValid: Boolean
        get() = name.isNotBlank() && (type != HabitType.LOCATION || spotId != null)

    /**
     * **打卡条件**：这条习惯是"指定地点"还是"无"。
     *
     * 派生规则（刻意做成派生属性，不新增数据库列 —— 用户数据正在使用，
     * 只改代码不建新列）：
     *  - `type == LOCATION && spotId != null` → [CheckInCondition.LOCATION]
     *  - 其余（含 `SIMPLE` / `TIMED`，以及 `LOCATION` 但没选点）→ [CheckInCondition.NONE]
     */
    val checkInCondition: CheckInCondition
        get() = if (type == HabitType.LOCATION && spotId != null) {
            CheckInCondition.LOCATION
        } else {
            CheckInCondition.NONE
        }

    /** 打卡条件的可读文案：「指定地点」/「无条件」。 */
    val checkInConditionLabel: String
        get() = when (checkInCondition) {
            CheckInCondition.LOCATION -> "指定地点"
            CheckInCondition.NONE -> "无条件"
        }

    /** 打卡条件的补充说明，界面直接用它讲清取舍。 */
    val checkInConditionHint: String
        get() = when (checkInCondition) {
            CheckInCondition.LOCATION -> "必须到打卡点才能打卡（室内可能定位不到）"
            CheckInCondition.NONE -> "什么时候都能打卡（室内也能用）"
        }

    /** 改成「无条件打卡」：类型回到 [HabitType.SIMPLE] 并清掉打卡点。 */
    fun withUnconditionalCheckIn(): Habit =
        copy(type = HabitType.SIMPLE, spotId = null)

    companion object {
        const val DEFAULT_ICON = "check_circle"
        const val DEFAULT_COLOR = 0xFF1B2A4A
        const val MAX_NAME_LENGTH = 20
    }
}
