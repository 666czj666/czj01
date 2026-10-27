package com.zilv.app.location

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.model.GeoPoint
import org.junit.Test

/**
 * 室内取点策略的单元测试（v2.10）。
 *
 * **为什么这组测试重要**：用户报「室内定位不了」，而我无法上真机。
 * 取点这条链路上，唯一能确定性地验证的就是"从一堆候选取点里挑哪一个" ——
 * 那正是原实现没有的一步（原来只有一条链路、一次机会）。
 *
 * 真机上真正保证室内可用的是 [LocationProvider] 的多来源并发采集
 * （Wi-Fi / 基站 / 缓存 / GPS 兜底），本文件锁住的是它**怎么选**。
 */
class LocationFixPolicyTest {

    private val now = 1_700_000_000_000L

    private fun geo(
        accuracyM: Float,
        ageMillis: Long = 0L,
        lat: Double = 30.0,
        lng: Double = 120.0
    ) = GeoPoint(lat = lat, lng = lng, accuracyM = accuracyM, atMillis = now - ageMillis)

    private fun fresh(accuracyM: Float, source: String = "fused-balanced", ageMillis: Long = 0L) =
        FixCandidate(point = geo(accuracyM, ageMillis), kind = FixKind.Fresh, fromSource = source)

    private fun cached(accuracyM: Float, ageMillis: Long = 0L, source: String = "platform-network-cache") =
        FixCandidate(point = geo(accuracyM, ageMillis), kind = FixKind.Cached, fromSource = source)

    // ------------------------------------------------------------ 时间预算

    @Test
    fun `时间预算先留给缓存，再留给实时定位`() {
        assertThat(LocationFixPolicy.CACHE_COLLECT_MILLIS).isEqualTo(800L)
        // PRD 的 15 秒总预算里，去掉 800ms 收缓存，其余给实时定位
        assertThat(LocationFixPolicy.freshRequestBudgetMillis(15_000L)).isEqualTo(14_200L)
    }

    @Test
    fun `预算小于缓存收集时间时实时预算为 0 而不是负数`() {
        assertThat(LocationFixPolicy.freshRequestBudgetMillis(500L)).isEqualTo(0L)
        assertThat(LocationFixPolicy.freshRequestBudgetMillis(800L)).isEqualTo(0L)
        assertThat(LocationFixPolicy.freshRequestBudgetMillis(0L)).isEqualTo(0L)
    }

    @Test
    fun `实时预算不会因为总预算为负而变成负数`() {
        assertThat(LocationFixPolicy.freshRequestBudgetMillis(-1_000L)).isEqualTo(0L)
    }

    // ------------------------------------------------------------ 新鲜度

    @Test
    fun `实时定位恒为新鲜，缓存看年龄`() {
        assertThat(LocationFixPolicy.isFresh(fresh(100f, ageMillis = 99_999_999L), now)).isTrue()
        // 默认 2 分钟内的缓存算新鲜
        assertThat(LocationFixPolicy.isFresh(cached(50f, ageMillis = 60_000L), now)).isTrue()
        assertThat(LocationFixPolicy.isFresh(cached(50f, ageMillis = 119_000L), now)).isTrue()
        assertThat(LocationFixPolicy.isFresh(cached(50f, ageMillis = 121_000L), now)).isFalse()
    }

    @Test
    fun `新鲜度阈值可以按参数覆盖`() {
        assertThat(LocationFixPolicy.isFresh(cached(50f, ageMillis = 10_000L), now, cacheFreshnessMillis = 5_000L))
            .isFalse()
    }

    // ------------------------------------------------------------ 精度可用性

    @Test
    fun `没报精度的 Float_MAX_VALUE 不算够用`() {
        // LocationProvider 在系统没报精度时会填 Float.MAX_VALUE
        assertThat(LocationFixPolicy.isAccuracyUsable(Float.MAX_VALUE)).isFalse()
    }

    @Test
    fun `异常解锁用的负数精度哨兵不算够用`() {
        assertThat(LocationFixPolicy.isAccuracyUsable(-1f)).isFalse()
    }

    @Test
    fun `够用分档线是 200 米`() {
        assertThat(LocationFixPolicy.GOOD_ENOUGH_ACCURACY_M).isEqualTo(200f)
        assertThat(LocationFixPolicy.isAccuracyUsable(0f)).isTrue()
        assertThat(LocationFixPolicy.isAccuracyUsable(30f)).isTrue()
        assertThat(LocationFixPolicy.isAccuracyUsable(200f)).isTrue()
        assertThat(LocationFixPolicy.isAccuracyUsable(201f)).isFalse()
    }

    // ------------------------------------------------------------ 挑选

    @Test
    fun `没有任何候选时返回 null`() {
        assertThat(LocationFixPolicy.pickBest(emptyList(), now)).isNull()
    }

    @Test
    fun `室内主力场景：Wi-Fi 给的 30 米胜过 GPS 硬撑的 400 米`() {
        // 这正是原实现会做错的地方：它只请求 PRIORITY_HIGH_ACCURACY，
        // 拿到的往往是那个 400 米的融合值，于是被打卡点的精度门槛挡掉
        val best = LocationFixPolicy.pickBest(
            listOf(
                fresh(400f, source = "fused-high"),
                fresh(30f, source = "fused-balanced")
            ),
            now
        )
        assertThat(best?.fromSource).isEqualTo("fused-balanced")
        assertThat(best?.point?.accuracyM).isEqualTo(30f)
    }

    @Test
    fun `新鲜且够用的缓存胜过不够用的实时结果`() {
        // 室内常见：实时定位只能给出 500 米（Wi-Fi 粗定位），
        // 而刚在室外定过位的缓存值是 20 米 —— 应当挑缓存
        val best = LocationFixPolicy.pickBest(
            listOf(
                fresh(500f, source = "fused-balanced"),
                cached(20f, ageMillis = 30_000L, source = "fused-cache")
            ),
            now
        )
        assertThat(best?.fromSource).isEqualTo("fused-cache")
        assertThat(best?.point?.accuracyM).isEqualTo(20f)
    }

    @Test
    fun `都是缓存时挑精度最好的那个`() {
        val best = LocationFixPolicy.pickBest(
            listOf(
                cached(120f, ageMillis = 10_000L, source = "platform-gps-cache"),
                cached(45f, ageMillis = 90_000L, source = "platform-network-cache"),
                cached(300f, ageMillis = 5_000L, source = "platform-passive-cache")
            ),
            now
        )
        assertThat(best?.fromSource).isEqualTo("platform-network-cache")
    }

    @Test
    fun `只有过期的缓存时也返回它，而不是 null`() {
        // 室内极端情况（飞行模式 / 无 Wi-Fi 无基站）：
        // 有总比没有好 —— 精度检查会挡住它，用户至少能看到"精度 xx 米"
        val best = LocationFixPolicy.pickBest(
            listOf(cached(80f, ageMillis = 6 * 60 * 60 * 1000L)),
            now
        )
        assertThat(best).isNotNull()
        assertThat(best?.point?.accuracyM).isEqualTo(80f)
    }

    @Test
    fun `实时与缓存都不够用时仍然挑出精度最好的一个`() {
        // 关键：取点层**不替打卡点做门槛判断**（否则"精度不达标"这条
        // 用户可见的提示永远不会出现，降级通道也就永远打不开）
        val best = LocationFixPolicy.pickBest(
            listOf(
                fresh(900f, source = "fused-balanced"),
                cached(600f, ageMillis = 10_000L, source = "platform-network-cache")
            ),
            now
        )
        assertThat(best?.point?.accuracyM).isEqualTo(600f)
    }

    @Test
    fun `没有精度的候选项不会被优先挑中`() {
        val best = LocationFixPolicy.pickBest(
            listOf(
                fresh(Float.MAX_VALUE, source = "fused-high"),
                fresh(150f, source = "fused-balanced")
            ),
            now
        )
        assertThat(best?.fromSource).isEqualTo("fused-balanced")
    }

    @Test
    fun `同精度时结果是确定的（不会随机挑）`() {
        val a = fresh(40f, source = "a")
        val b = fresh(40f, source = "b")
        assertThat(LocationFixPolicy.pickBest(listOf(a, b), now))
            .isEqualTo(LocationFixPolicy.pickBest(listOf(a, b), now))
    }
}
