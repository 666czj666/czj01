package com.zilv.app.domain.intercept

/**
 * 拦截尝试的合并（PRD §3.8 数据口径）。
 *
 * 用户被挡住后往往会连着点好几次同一个应用。PRD 明确规定：
 * 「同一 App 连续尝试 5 秒内合并为 1 次」，否则报告里的次数会虚高得毫无意义。
 *
 * 这里只做"该新建还是该并入"的判定，真正的计数与落库由仓库负责。
 */
object AttemptMerger {

    /** 合并窗口（PRD 默认值 5 秒）。 */
    const val MERGE_WINDOW_MILLIS = 5_000L

    /**
     * @param lastAttemptAtMillis 该应用上一次被记录的尝试时刻；null 表示还没有记录
     * @param nowMillis 本次尝试时刻
     * @param windowMillis 合并窗口
     * @return true = 需要新建一条记录；false = 并入上一条（计数 +1）
     */
    fun startsNewRecord(
        lastAttemptAtMillis: Long?,
        nowMillis: Long,
        windowMillis: Long = MERGE_WINDOW_MILLIS
    ): Boolean {
        require(windowMillis >= 0) { "windowMillis 不能为负，实际 $windowMillis" }
        if (lastAttemptAtMillis == null) return true
        // 时钟回拨（用户改系统时间）时也当作新记录，避免负数窗口
        if (nowMillis < lastAttemptAtMillis) return true
        return nowMillis - lastAttemptAtMillis >= windowMillis
    }
}
