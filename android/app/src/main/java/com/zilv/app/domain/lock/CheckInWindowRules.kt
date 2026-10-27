package com.zilv.app.domain.lock

/**
 * 「必须打卡的时间段」在**界面侧**的输入规则。
 *
 * 两个界面都要让用户选一段"开始时间 + 结束时间"来当打卡窗口：
 *  - 习惯编辑页 / 新建习惯弹窗的「惩罚 → 打卡时间」
 *  - 锁机「详细设置」的「打卡时间」
 *
 * 它们以前各自在 ViewModel 里写一遍 `start == end` 的判断（而且都是**两步式**
 * 对话框，用户在第一步看不出第二步会撞上什么）。现在两处都改成
 * "开始时间 + 结束时间两个滚轮"（与「明日规划」同一种交互），
 * 于是需要一条**能在用户点「添加」之前就显示出来**的校验。
 *
 * 做成本对象而不是写进界面，是因为它必须能被纯 JVM 单测直接钉死 ——
 * 这里的每一条都对应一个真实踩过的坑（见下）。
 *
 * ## 为什么 `start == end` 必须**显式**拒绝
 *
 * `TimeWindow(x, x)` 的 [TimeWindow.durationMinutes] 是 **1440 分钟（全天）**
 * 而不是 0：`crossesMidnight` 的判据是 `endMinute <= startMinute`，
 * 所以"起止相同"被判成"跨零点"＝全天。放过去就等于把
 * "09:00 到 09:00" 变成"整天都得打卡"（这个坑本项目踩过多次）。
 *
 * ## 为什么结束时间允许早于开始时间
 *
 * 那是**合法的跨零点窗口**（23:00–01:00），不是错误 —— 判断跨零点、
 * 展开成窗口实例都由 [TimeWindow] / `WindowScheduler` 负责，这里不拦。
 *
 * ⚠️ 结束时间取 `00:00` 有特殊含义：`TimeWindow.of(..., endMeansMidnight = true)`
 * 把它读成 **24:00**（当天最后一刻），所以"08:00–00:00"是一段 16 小时的窗口，
 * 而"00:00–00:00"是起止相同 —— 仍然被本对象拒绝。
 */
object CheckInWindowRules {

    /** 允许的分钟范围：与 [TimeWindow] 的 `startMinute` 上限（1439）一致。 */
    const val MIN_MINUTE = 0
    const val MAX_MINUTE = 1439

    /** 新建窗口时界面预填的时段：08:00–09:00（本身合法，绝不是 `x-x`）。 */
    const val DEFAULT_START_MINUTE = 8 * 60
    const val DEFAULT_END_MINUTE = 9 * 60

    /**
     * 校验用户选好的一对起止分钟。
     *
     * @return 可以直接显示给用户的原因；合法时返回 `null`
     */
    fun problem(startMinute: Int, endMinute: Int): String? = when {
        startMinute !in MIN_MINUTE..MAX_MINUTE || endMinute !in MIN_MINUTE..MAX_MINUTE ->
            "打卡时间必须在 00:00..23:59 之间"

        // ⚠️ 这一条是重点：TimeWindow(x, x) 是全天 1440 分钟，不是 0
        startMinute == endMinute ->
            "打卡时间的结束时间不能与开始时间相同（会被当成全天窗口）"

        else -> null
    }

    /**
     * 把一对起止分钟转成 [TimeWindow]；非法组合返回 `null`。
     *
     * 转换规则与两个界面完全一致：[endMeansMidnight] 传 `true`，
     * 于是"结束 00:00"被读成 24:00 —— 而不是 0 分钟的空窗口。
     * 转换失败（越界 / 起止相同）时**不抛异常**：界面提前拦一道，
     * 这里只是兜底，读路径与界面都不该因此崩掉。
     */
    fun toWindow(startMinute: Int, endMinute: Int): TimeWindow? {
        if (problem(startMinute, endMinute) != null) return null
        return runCatching {
            TimeWindow.of(
                java.time.LocalTime.of(startMinute / 60, startMinute % 60),
                java.time.LocalTime.of(endMinute / 60, endMinute % 60),
                endMeansMidnight = true
            )
        }.getOrNull()
    }
}
