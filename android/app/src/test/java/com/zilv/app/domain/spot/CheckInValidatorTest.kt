package com.zilv.app.domain.spot

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.Spot
import org.junit.Test

/**
 * 打卡校验的单元测试（PRD §3.5 校验流程）。
 *
 * 最需要钉死的两条语义：
 *  1. 精度不达标**不计失败**，可以无限重试
 *  2. 距离判定是闭区间（distance ≤ radius 即通过）
 */
class CheckInValidatorTest {

    private val spot = Spot(
        id = 1L,
        name = "三教一楼",
        lat = 30.0,
        lng = 120.0,
        radiusM = 100,
        minAccuracyM = 50f
    )

    private fun point(lat: Double, lng: Double, accuracy: Float) =
        GeoPoint(lat = lat, lng = lng, accuracyM = accuracy, atMillis = 0L)

    /**
     * 纬度上「向北 [meters] 米」对应的度数增量。
     *
     * 用精确反函数（而不是四舍五入的常数）：沿经线移动时
     * haversine = R · Δφ，因此 Δφ = meters / R 是精确解，
     * 否则 100 米会被算成 100.0001 米而越界。
     */
    private fun metersNorth(meters: Double): Double =
        Math.toDegrees(meters / GeoUtils.EARTH_RADIUS_M)

    // ---------------------------------------------------------------- 无定位

    @Test
    fun `没有定位结果时返回 NoLocation`() {
        assertThat(CheckInValidator.validate(null, spot))
            .isEqualTo(CheckInValidator.Result.NoLocation)
    }

    // ---------------------------------------------------------------- 精度检查

    @Test
    fun `精度不达标时返回 AccuracyTooLow 并带提示文案`() {
        val r = CheckInValidator.validate(point(30.0, 120.0, 86f), spot)
        assertThat(r).isInstanceOf(CheckInValidator.Result.AccuracyTooLow::class.java)
        val low = r as CheckInValidator.Result.AccuracyTooLow
        assertThat(low.accuracyM).isEqualTo(86f)
        assertThat(low.thresholdM).isEqualTo(50f)
        assertThat(low.message).contains("信号弱")
        assertThat(low.message).contains("86")
    }

    @Test
    fun `精度恰好等于门槛时通过精度检查`() {
        // 判定是 accuracy > threshold 才算不达标
        val r = CheckInValidator.validate(point(30.0, 120.0, 50f), spot)
        assertThat(r).isEqualTo(CheckInValidator.Result.Pass)
    }

    @Test
    fun `精度不达标优先于距离判定`() {
        // 即使人就在打卡点正上方，精度差也不放行（但也不算失败）
        val r = CheckInValidator.validate(point(30.0, 120.0, 500f), spot)
        assertThat(r).isInstanceOf(CheckInValidator.Result.AccuracyTooLow::class.java)
    }

    // ---------------------------------------------------------------- 距离判定

    @Test
    fun `在半径内通过`() {
        val r = CheckInValidator.validate(point(30.0 + metersNorth(30.0), 120.0, 10f), spot)
        assertThat(r).isEqualTo(CheckInValidator.Result.Pass)
    }

    @Test
    fun `距离等于半径时通过、超过1米即失败（闭区间语义）`() {
        // 用精确反函数构造一个固定距离的点，再把半径设成它的向上取整，
        // 从而确定性地验证「distance <= radius 才算通过」。
        val lat = 30.0 + metersNorth(100.0)
        val exactDistance = GeoUtils.haversineMeters(30.0, 120.0, lat, 120.0)
        val radius = kotlin.math.ceil(exactDistance).toInt()

        // distance <= radius -> 通过
        val atBoundary = spot.copy(radiusM = radius)
        assertThat(CheckInValidator.validate(point(lat, 120.0, 10f), atBoundary))
            .isEqualTo(CheckInValidator.Result.Pass)

        // 半径收紧 1 米 -> 距离 > 半径 -> 失败
        val tighter = spot.copy(radiusM = radius - 1)
        assertThat(CheckInValidator.validate(point(lat, 120.0, 10f), tighter))
            .isInstanceOf(CheckInValidator.Result.TooFar::class.java)
    }

    @Test
    fun `超出半径时返回 TooFar`() {
        val r = CheckInValidator.validate(point(30.0 + metersNorth(340.0), 120.0, 10f), spot)
        assertThat(r).isInstanceOf(CheckInValidator.Result.TooFar::class.java)
        val far = r as CheckInValidator.Result.TooFar
        assertThat(far.distanceM).isWithin(0.01).of(340.0)
        assertThat(far.radiusM).isEqualTo(100)
        assertThat(far.message).contains("还差")
    }

    @Test
    fun `用户在打卡点正南方时方位角指向正北 0 度`() {
        // 方位角是「从用户当前位置指向打卡点」——这正是罗盘箭头需要的方向
        val userLat = 30.0 - metersNorth(500.0)
        val r = CheckInValidator.validate(point(userLat, 120.0, 10f), spot)
        val far = r as CheckInValidator.Result.TooFar
        assertThat(far.bearingDeg).isWithin(0.5).of(0.0)
    }

    @Test
    fun `用户在打卡点正西方时方位角指向正东 90 度`() {
        val equatorSpot = spot.copy(lat = 0.0, lng = 0.0)
        val dm = metersNorth(500.0)
        val r = CheckInValidator.validate(point(0.0, -dm, 10f), equatorSpot)
        val far = r as CheckInValidator.Result.TooFar
        assertThat(far.bearingDeg).isWithin(1.0).of(90.0)
    }

    // ---------------------------------------------------------- 室内降级通道

    @Test
    fun `连续三次精度失败后开放降级通道`() {
        assertThat(CheckInValidator.shouldOfferFallback(0)).isFalse()
        assertThat(CheckInValidator.shouldOfferFallback(1)).isFalse()
        assertThat(CheckInValidator.shouldOfferFallback(2)).isFalse()
        assertThat(CheckInValidator.shouldOfferFallback(3)).isTrue()
        assertThat(CheckInValidator.shouldOfferFallback(10)).isTrue()
    }

    @Test
    fun `降级阈值可配置`() {
        assertThat(CheckInValidator.shouldOfferFallback(2, failureThreshold = 2)).isTrue()
        assertThat(CheckInValidator.shouldOfferFallback(2, failureThreshold = 5)).isFalse()
    }

    @Test
    fun `降级阈值非正数时抛异常`() {
        assertThat(runCatching { CheckInValidator.shouldOfferFallback(1, 0) }.isFailure).isTrue()
    }

    @Test
    fun `PRD 约定的默认阈值为3次`() {
        assertThat(CheckInValidator.DEFAULT_FALLBACK_THRESHOLD).isEqualTo(3)
    }

    // ---------------- v2.11：「改用无条件打卡」的**另一条** 3 次门槛

    @Test
    fun `改用无条件打卡同样要连续失败三次才开放`() {
        // 用户第 2 条："「改用无条件打卡」原来一失败就出现，要连续失败 3 次之后才出现。"
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(0)).isFalse()
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(1)).isFalse()
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(2)).isFalse()
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(3)).isTrue()
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(10)).isTrue()
    }

    @Test
    fun `两条门槛的阈值数值相同但判据是各自独立的两个函数`() {
        // "两者门槛都是 3 次，但适用路径不同" —— 界面上靠调用点各自判断
        // canFallback（ruleId > 0）/ canSwitch（habitId > 0 且要求地点）来区分。
        // 这里钉住：两个函数在同一个输入下**恰好**同真同假（数值一致），
        // 一旦有人只改了一个，这条会立刻红。
        (0..6).forEach { failures ->
            assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(failures))
                .isEqualTo(CheckInValidator.shouldOfferFallback(failures))
        }
    }

    @Test
    fun `还差几次失败是可算的 —— 提示文案里的数字只能来自这里`() {
        assertThat(CheckInValidator.failuresUntilOffer(0)).isEqualTo(3)
        assertThat(CheckInValidator.failuresUntilOffer(1)).isEqualTo(2)
        assertThat(CheckInValidator.failuresUntilOffer(2)).isEqualTo(1)
        // 到门槛之后是 0，不会变成负数
        assertThat(CheckInValidator.failuresUntilOffer(3)).isEqualTo(0)
        assertThat(CheckInValidator.failuresUntilOffer(99)).isEqualTo(0)
    }

    @Test
    fun `还差几次跟着阈值走`() {
        assertThat(CheckInValidator.failuresUntilOffer(0, failureThreshold = 5)).isEqualTo(5)
        assertThat(CheckInValidator.failuresUntilOffer(3, failureThreshold = 5)).isEqualTo(2)
        assertThat(CheckInValidator.failuresUntilOffer(4, failureThreshold = 2)).isEqualTo(0)
    }

    @Test
    fun `无条件入口的阈值非正数时也抛异常`() {
        assertThat(
            runCatching { CheckInValidator.shouldOfferUnconditionalSwitch(1, 0) }.isFailure
        ).isTrue()
        assertThat(
            runCatching { CheckInValidator.failuresUntilOffer(1, 0) }.isFailure
        ).isTrue()
    }

    // ---------------- v2.12：\(用户第 4 条\) "什么算一次失败"的纯函数

    @Test
    fun `拿不到定位才累加一次`() {
        // 用户第 4 条："只有『定位失败』（拿不到定位）才累加这个门槛。
        // 『定位成功但不在打卡范围内』不算失败。"
        assertThat(CheckInValidator.nextLocationFailureCount(0)).isEqualTo(1)
        assertThat(CheckInValidator.nextLocationFailureCount(1)).isEqualTo(2)
        assertThat(CheckInValidator.nextLocationFailureCount(2)).isEqualTo(3)
        // 到了门槛之后继续失败也照常往上走（计数本身不做上限）
        assertThat(CheckInValidator.nextLocationFailureCount(3)).isEqualTo(4)
    }

    @Test
    fun `拿到定位（哪怕太远或精度不达标）就清零`() {
        // 清零用的是同一个常量，确保"通过 / 太远 / 精度不达标"三条路径
        // 不可能各写一个零（那正是"口径分叉"最容易发生的地方）
        assertThat(CheckInValidator.locationFailureCountAfterFix()).isEqualTo(0)
    }

    @Test
    fun `连续失败的链条只能由拿不到定位接起来`() {
        // 手动走一遍"失败两次 -> 成功一次 -> 再失败两次"：
        // 到手仍然不够门槛。这条就是"连续"两个字的可执行定义。
        var count = CheckInValidator.nextLocationFailureCount(0)
        count = CheckInValidator.nextLocationFailureCount(count)
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(count)).isFalse()

        count = CheckInValidator.locationFailureCountAfterFix()
        assertThat(count).isEqualTo(0)

        count = CheckInValidator.nextLocationFailureCount(count)
        count = CheckInValidator.nextLocationFailureCount(count)
        assertThat(count).isEqualTo(2)
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(count)).isFalse()

        count = CheckInValidator.nextLocationFailureCount(count)
        assertThat(CheckInValidator.shouldOfferUnconditionalSwitch(count)).isTrue()
    }

    // ---------------------------------------------------------------- 打卡点校验

    @Test
    fun `打卡点参数边界`() {
        assertThat(Spot(name = "x", lat = 0.0, lng = 0.0).isValid).isTrue()
        assertThat(Spot(name = " ", lat = 0.0, lng = 0.0).isValid).isFalse()
        assertThat(Spot(name = "x", lat = 0.0, lng = 0.0, radiusM = 49).isValid).isFalse()
        assertThat(Spot(name = "x", lat = 0.0, lng = 0.0, radiusM = 50).isValid).isTrue()
        assertThat(Spot(name = "x", lat = 0.0, lng = 0.0, radiusM = 1000).isValid).isTrue()
        assertThat(Spot(name = "x", lat = 0.0, lng = 0.0, radiusM = 1001).isValid).isFalse()
    }

    @Test
    fun `打卡点坐标非法时构造即抛异常`() {
        assertThat(runCatching { Spot(name = "x", lat = 91.0, lng = 0.0) }.isFailure).isTrue()
        assertThat(runCatching { Spot(name = "x", lat = 0.0, lng = 181.0) }.isFailure).isTrue()
    }

    @Test
    fun `PRD 默认值`() {
        val s = Spot(name = "x", lat = 0.0, lng = 0.0)
        assertThat(s.radiusM).isEqualTo(100)
        assertThat(s.minAccuracyM).isEqualTo(50f)
    }
}
