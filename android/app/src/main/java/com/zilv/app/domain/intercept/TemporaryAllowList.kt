package com.zilv.app.domain.intercept

/**
 * 「紧急放行 5 分钟」的运行时白名单（PRD §4.5）。
 *
 * 抽成纯类是为了可测试：放行的**到期语义**容易写错
 * （应该用「到期时刻」比较，而不是「剩余秒数」累加）。
 */
class TemporaryAllowList {

    private val expiries = mutableMapOf<String, Long>()

    /**
     * 放行某包名一段时间。
     *
     * @param nowMillis 当前时刻
     * @param durationMillis 放行时长
     * @return 到期时刻
     */
    fun grant(packageName: String, nowMillis: Long, durationMillis: Long = DEFAULT_DURATION_MILLIS): Long {
        require(durationMillis > 0) { "放行时长必须为正数，实际 $durationMillis" }
        val expiry = nowMillis + durationMillis
        // 若已有更晚的到期时刻，不缩短它
        expiries[packageName] = maxOf(expiries[packageName] ?: 0L, expiry)
        return expiries[packageName]!!
    }

    fun isAllowed(packageName: String, nowMillis: Long): Boolean {
        val expiry = expiries[packageName] ?: return false
        return nowMillis < expiry
    }

    /** 剩余毫秒；未放行或已过期返回 0。 */
    fun remainingMillis(packageName: String, nowMillis: Long): Long {
        val expiry = expiries[packageName] ?: return 0L
        return (expiry - nowMillis).coerceAtLeast(0L)
    }

    /** 清理已过期的条目，返回清理数量。 */
    fun purgeExpired(nowMillis: Long): Int {
        val before = expiries.size
        expiries.entries.removeAll { nowMillis >= it.value }
        return before - expiries.size
    }

    fun clear() = expiries.clear()

    val size: Int get() = expiries.size

    companion object {
        /** PRD 默认值：5 分钟。 */
        const val DEFAULT_DURATION_MILLIS = 5 * 60 * 1000L
    }
}
