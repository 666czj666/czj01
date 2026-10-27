package com.zilv.app.location

import com.zilv.app.domain.model.GeoPoint

/**
 * 一次候选取点的来源。
 *
 * 分开记录来源是为了让"室内到底走通了哪条路"可查：
 * 真机排查时 `adb logcat -s LocationProvider` 会打出最终选中了哪一个。
 */
enum class FixKind {
    /** 刚刚从系统拿到的实时定位（GPS 或 Wi-Fi/基站融合）。 */
    Fresh,

    /** 系统缓存的"最后已知位置"—— 通常**室内可秒回**，但可能偏旧。 */
    Cached
}

/**
 * 一个候选取点。
 *
 * @param fromSource 哪条链路给的（调试用，不参与决策）
 */
data class FixCandidate(
    val point: GeoPoint,
    val kind: FixKind,
    val fromSource: String
)

/**
 * 室内取点策略（v2.10）—— **纯函数，可单元测试**。
 *
 * ## 为什么需要它
 *
 * 原实现只做一件事：`getCurrentLocation(PRIORITY_HIGH_ACCURACY)`，超时 15 秒，
 * 超时才退回一次 `lastLocation`。用户报"室内定位不了"，根因有两个：
 *
 *  1. **`PRIORITY_HIGH_ACCURACY` 优先要 GPS 卫星定位。** 室内收不到卫星信号，
 *     于是要么等满超时、要么派生出一个精度极差（几十~几百米）的融合结果。
 *     而室内真正能用的是 Wi-Fi 与基站 —— 那属于
 *     `PRIORITY_BALANCED_POWER_ACCURACY` 的主场。
 *  2. **只有一条链路、一次机会。** 拿不到就返回 null，用户只看到"室内定位失败"，
 *     既不知道还能重试，也没有更省事的出口。
 *
 * 本类只负责"怎么从一堆候选取点里挑出最好的那一个"，
 * 采集（走哪些 provider、超时多久）在 [LocationProvider]。
 * 这样"挑点"这条最容易写错的规则可以完全靠单元测试钉死，无需真机。
 *
 * ## 挑选规则（按优先级）
 *
 * | 序 | 条件 | 选择 |
 * |---|---|---|
 * | 1 | 有"新鲜且精度够用（≤ [GOOD_ENOUGH_ACCURACY_M]）"的候选 | 其中**精度最好**的那个 |
 * | 2 | 有"新鲜"的候选 | 其中**精度最好**的那个（哪怕只有 200 米） |
 * | 3 | 只有缓存候选 | 其中**精度最好**的那个 |
 * | 4 | 一个都没有 | null（由调用方给出"室内定位失败"与降级入口） |
 *
 * 第 1 条的意义：室内常见的"Wi-Fi 精度 30 米 + GPS 精度 200 米"里，
 * 一定要挑到 30 米那个，否则会被打卡点的精度门槛（默认 ≤ 50 米）挡掉。
 * 这里刻意**不**用打卡点的门槛做过滤 —— 门槛是打卡点自己的事
 * （见 `CheckInValidator`），取点层不该替它做决定，否则
 * "精度不达标"这一条用户可见的提示就永远不会出现。
 */
object LocationFixPolicy {

    /**
     * 时间预算的分界线（毫秒）：先花这么久收**缓存**候选，再开始等实时定位。
     *
     * 800ms 的依据：`getLastLocation()` 在室内通常几十毫秒就返回（Wi-Fi 定位结算是
     * 异步的，但缓存值本身是本地的）。留 800ms 是给"多个 provider 的缓存都要问一遍"
     * 留余量，同时不至于让用户多等。
     */
    const val CACHE_COLLECT_MILLIS = 800L

    /** 缓存候选的"新鲜度"上限：默认 2 分钟内的缓存值仍然算新鲜。 */
    const val DEFAULT_CACHE_FRESHNESS_MILLIS = 2 * 60 * 1000L

    /**
     * "够用"的精度上限（米）。
     *
     * 200 米：比任何打卡点门的精度门槛（默认 50 米，可调到更宽）都宽，
     * 因此它不是一个"验收标准"，只是"优先挑哪个"的分档线。
     * 定这个值的目的是：在"Wi-Fi 给的 30 米"和"GPS 硬撑出来的 500 米"之间，
     * 一定要先挑到前者。
     */
    const val GOOD_ENOUGH_ACCURACY_M = 200f

    /**
     * 时间预算分配：给实时定位留多少毫秒。
     *
     * 至少 [MIN_FRESH_BUDGET_MILLIS]，否则室内刚起振就超时了；
     * 也不超过总预算（总预算本身由 PRD 的 15 秒决定）。
     */
    fun freshRequestBudgetMillis(
        totalTimeoutMillis: Long,
        cacheCollectMillis: Long = CACHE_COLLECT_MILLIS
    ): Long {
        if (totalTimeoutMillis <= 0L) return 0L
        return (totalTimeoutMillis - cacheCollectMillis).coerceAtLeast(0L)
    }

    /** 候选是否"新鲜"：实时定位恒为新鲜；缓存要看它有多旧。 */
    fun isFresh(
        candidate: FixCandidate,
        nowMillis: Long,
        cacheFreshnessMillis: Long = DEFAULT_CACHE_FRESHNESS_MILLIS
    ): Boolean = when (candidate.kind) {
        FixKind.Fresh -> true
        FixKind.Cached -> (nowMillis - candidate.point.atMillis) <= cacheFreshnessMillis
    }

    /**
     * 精度是否可信：非负数且在"够用"分档线内。
     *
     * `GeoPoint.accuracyM` 允许出现 `Float.MAX_VALUE`（系统没报精度时，
     * 见 `LocationProvider.toGeoPoint`）与负数（异常解锁用的哨兵值），
     * 这两类都不能当作"够用"。
     */
    fun isAccuracyUsable(accuracyM: Float): Boolean =
        accuracyM >= 0f && accuracyM <= GOOD_ENOUGH_ACCURACY_M

    /** 从候选中挑出最好的一个；一个都没有时返回 null。 */
    fun pickBest(
        candidates: List<FixCandidate>,
        nowMillis: Long,
        cacheFreshnessMillis: Long = DEFAULT_CACHE_FRESHNESS_MILLIS
    ): FixCandidate? {
        if (candidates.isEmpty()) return null

        val fresh = candidates.filter { isFresh(it, nowMillis, cacheFreshnessMillis) }
        val freshGood = fresh.filter { isAccuracyUsable(it.point.accuracyM) }
        val pickFrom = when {
            freshGood.isNotEmpty() -> freshGood
            fresh.isNotEmpty() -> fresh
            else -> candidates
        }
        return pickFrom.minByOrNull { it.point.accuracyM }
    }
}
