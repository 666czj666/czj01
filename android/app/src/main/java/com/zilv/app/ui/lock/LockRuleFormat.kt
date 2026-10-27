package com.zilv.app.ui.lock

import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow

/**
 * 锁机文案工具。
 *
 * 原先这几个函数住在 `LockRuleListScreen.kt` 里。v2.0 按用户要求
 * **删除了独立的锁机列表入口**，但习惯编辑页仍要展示锁机配置摘要 ——
 * 所以把它们挪到这个独立文件，而不是跟着列表页一起消失。
 */

/** `07:30-09:00`；`24:00` 单独处理，因为 1440 分钟不是合法 LocalTime。 */
fun formatWindow(w: TimeWindow): String {
    val end = if (w.endMinute == 1440) "24:00" else "%02d:%02d".format(w.endMinute / 60, w.endMinute % 60)
    return "%02d:%02d-%s".format(w.startMinute / 60, w.startMinute % 60, end)
}

/** 多个窗口拼成一行文案；空列表给一句明确的"没配置"。 */
fun formatWindows(windows: List<TimeWindow>, emptyText: String = "未设置"): String =
    if (windows.isEmpty()) emptyText else windows.joinToString("、") { formatWindow(it) }

/** 重复星期 + 锁机窗口，如 `每天  15:00-18:00`。 */
fun describeSchedule(rule: LockRule): String {
    val days = if (rule.daysOfWeek.isEmpty()) {
        "每天"
    } else {
        val names = mapOf(
            1 to "一", 2 to "二", 3 to "三", 4 to "四", 5 to "五", 6 to "六", 7 to "日"
        )
        "周" + rule.daysOfWeek.sortedBy { it.value }.joinToString("") { names[it.value] ?: "" }
    }
    return "$days  ${formatWindows(rule.windows)}"
}

/**
 * 锁机时长的可读文案：`30 分钟` / `2 小时` / `2 小时 30 分` / `24 小时` / `未设置`。
 *
 * 直接复用 [LockRule.lockDurationLabel]（域对象上的那一套口径），
 * 界面层不另写一份格式化 —— 两处文案一旦分叉，用户看到的和真正生效的就会对不上。
 */
fun formatLockDuration(minutes: Int): String =
    LockRule(name = "锁机", lockDurationMinutes = minutes).lockDurationLabel

/**
 * 解锁方式一句话。
 *
 * v2.2 起解锁方式**不再可配**：用户要求"解锁方法 = 打卡（任何打卡方法都可以），
 * 或者过完锁机时间"，所以这里不再看已废弃的 `unlockMode`
 * （旧代码会显示成"仅定时解锁"，与新的真实行为正好相反）。
 */
fun describeUnlock(rule: LockRule): String {
    val extra = buildString {
        if (rule.graceMinutes > 0) append(" · 宽限 ${rule.graceMinutes} 分钟")
        append(" · 提前 ${rule.earlyCheckInMinutes} 分钟有效")
    }
    return "打卡解锁 或 等锁机时间走完" + extra
}
