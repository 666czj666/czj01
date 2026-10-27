package com.zilv.app.domain.spot

import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.Spot

/**
 * 打卡校验（PRD §3.5「校验流程」）。
 *
 * 顺序严格按 PRD：
 *  1. 拿到定位（超时由调用方处理）
 *  2. **精度检查** —— 不达标**不计失败**，可以无限重试
 *  3. 距离计算（Haversine ≤ 半径）
 *  4. 通过
 *
 * 第 2 步的语义很关键：精度差是环境问题不是用户的错，
 * 若算作失败会把人逼到放弃使用（PRD 明确要求"不计失败，可重试"）。
 */
object CheckInValidator {

    sealed interface Result {
        /** 校验通过 */
        data object Pass : Result

        /** 精度不达标：请到空旷处重试（**不计失败**） */
        data class AccuracyTooLow(
            val accuracyM: Float,
            val thresholdM: Float
        ) : Result {
            val message: String
                get() = "信号弱（精度 ${accuracyM.toInt()} 米，需要 ≤ ${thresholdM.toInt()} 米），请到空旷处重试"
        }

        /** 距离过远 */
        data class TooFar(
            val distanceM: Double,
            val radiusM: Int,
            val bearingDeg: Double
        ) : Result {
            val message: String get() = "还差 ${distanceM.toInt()} 米"
        }

        /** 没有定位结果 */
        data object NoLocation : Result
    }

    /**
     * 校验一次打卡尝试。
     *
     * @param location 当前定位；null 表示定位失败/超时
     * @param spot 目标打卡点
     */
    fun validate(location: GeoPoint?, spot: Spot): Result {
        if (location == null) return Result.NoLocation

        // 1) 精度门槛：不达标直接返回，且**不消耗失败次数**
        if (location.accuracyM > spot.minAccuracyM) {
            return Result.AccuracyTooLow(location.accuracyM, spot.minAccuracyM)
        }

        // 2) 距离判定
        val distance = GeoUtils.haversineMeters(
            location.lat, location.lng,
            spot.lat, spot.lng
        )
        if (distance > spot.radiusM) {
            val bearing = GeoUtils.bearingDegrees(
                location.lat, location.lng,
                spot.lat, spot.lng
            )
            return Result.TooFar(distance, spot.radiusM, bearing)
        }

        return Result.Pass
    }

    /**
     * 是否应当开放"室内定位降级通道"（PRD §3.5）。
     *
     * 连续 [failureThreshold] 次精度不达标后，允许「拍照存证 + 临时解锁 5 分钟」。
     * 理由写在 PRD 里：死守精度会让用户索性把整个 App 关掉。
     */
    fun shouldOfferFallback(
        consecutiveAccuracyFailures: Int,
        failureThreshold: Int = DEFAULT_FALLBACK_THRESHOLD
    ): Boolean {
        require(failureThreshold > 0) { "failureThreshold 必须为正数" }
        return consecutiveAccuracyFailures >= failureThreshold
    }

    /**
     * 是否应当给出**「改用无条件打卡」**入口（v2.11；v2.12 收紧了输入的口径）。
     *
     * ## 这是**另一条**门槛，与 [shouldOfferFallback] 不是一回事
     *
     * 两个门槛的数值恰好都是 3，但适用路径完全不同，**绝不能合并成一个判断**：
     *
     * | | 拍照存证 + 临时解锁 | 改用无条件打卡 |
     * |---|---|---|
     * | 解的是什么 | **这一次**锁机 | 这条习惯**以后**都不再要求地点 |
     * | 什么时候出现 | 从**锁机规则**进来（`ruleId > 0`）| 从**指定地点的习惯**进来 |
     * | 入口为什么必须有规则 | 没有锁机就没有"临时解锁"可言 | 没有可改的习惯就没得改 |
     * | 计数来源 | [com.zilv.app.ui.checkin.CheckInUiState.consecutiveAccuracyFailures]（拿不到定位 **或** 精度不达标） | [com.zilv.app.ui.checkin.CheckInUiState.consecutiveLocationFailures]（**只有**拿不到定位） |
     *
     * ⚠️ **v2.12 的关键区分（用户第 4 条要求）**：
     *
     * > 「只有『定位失败』（拿不到定位）才累加这个门槛。
     * > 『定位成功但不在打卡范围内』不算失败，不能触发无条件打卡。」
     *
     * 所以喂给本函数的计数**只能**来自"拿不到定位"那条路径（见
     * [locationFailureCountAfterFix] / [nextLocationFailureCount]）。
     * 精度不达标与距离过远都算"定位成功"，一律清零。
     * 这**不**影响上面那条"拍照存证"的门槛 —— 它的口径一个字都没改。
     *
     * @param consecutiveLocationFailures 连续**拿不到定位**的次数
     */
    fun shouldOfferUnconditionalSwitch(
        consecutiveLocationFailures: Int,
        failureThreshold: Int = DEFAULT_FALLBACK_THRESHOLD
    ): Boolean {
        require(failureThreshold > 0) { "failureThreshold 必须为正数" }
        return consecutiveLocationFailures >= failureThreshold
    }

    /**
     * 「拿不到定位」计数 +1（**纯函数**，v2.12）。
     *
     * 只有 [com.zilv.app.location.LocationSource.currentLocation] 返回 `null`
     * 时才该调用它。做成纯函数是为了让"什么算一次失败"这条规则能被单测逐条钉死，
     * 而不是散在 ViewModel 的 `if` 里。
     */
    fun nextLocationFailureCount(previous: Int): Int = (previous + 1).coerceAtLeast(0)

    /**
     * 拿到定位之后的新计数：**清零**（**纯函数**，v2.12）。
     *
     * 用户要求："定位成功但不在打卡范围之内不触发无条件打卡" ——
     * 精度不达标同样是"定位成功"，一样清零。
     * 于是"连续"这个词才有意义：中间只要成功过一次，链条就断了。
     */
    fun locationFailureCountAfterFix(): Int = 0

    /**
     * 距离开放上面那两个降级通道**还差几次失败**。
     *
     * 0 表示已经达到门槛（入口该出现了）。
     *
     * 为什么单独做一个纯函数：v2.11 时用户要求"提示文案要说清还差几次"，
     * 那句文案里的数字必须是**算出来的**，而不是界面里再写一遍 `3 - failures`。
     *
     * ⚠️ v2.12 起用户改主意了：**「把无条件打卡的提示隐藏」** ——
     * 界面上不再显示"再失败 N 次可以改用无条件打卡"。这个函数因此从
     * "文案的唯一来源"退化成"门槛的唯一来源"（阈值只有一个地方，
     * 它仍然被 `CheckInUiState.unconditionalPending` 与若干单测使用）。
     * 阈值本身一个字没改，改的只是它有没有被画到屏幕上。
     */
    fun failuresUntilOffer(
        consecutiveFailures: Int,
        failureThreshold: Int = DEFAULT_FALLBACK_THRESHOLD
    ): Int {
        require(failureThreshold > 0) { "failureThreshold 必须为正数" }
        return (failureThreshold - consecutiveFailures).coerceAtLeast(0)
    }

    /** PRD 默认值：连续 3 次精度不达标后给出降级通道。 */
    const val DEFAULT_FALLBACK_THRESHOLD = 3
}
