package com.zilv.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.zilv.app.domain.model.GeoPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定位封装（PRD §3.5 / 定位方案 A + v2.10 室内取点优化）。
 *
 * ## 不变量（红线，不许改）
 *
 *  - **只在点击「立即打卡」时取一次位置**，不申请后台定位
 *  - 只用前台定位权限；**不新增任何权限**
 *  - 不声明 `ACCESS_BACKGROUND_LOCATION`（`tools/verify-apk.ps1` 会断言它不存在）
 *
 * ## v2.10：室内也能拿到位置
 *
 * 原实现只有一条链路：`getCurrentLocation(PRIORITY_HIGH_ACCURACY)` + 15 秒超时。
 * 那条链路**优先要 GPS 卫星**，而室内收不到卫星 —— 这是用户报
 * 「室内定位不了」的直接原因。现在改成**多来源并发 + 统一挑点**：
 *
 * | # | 链路 | 室内为什么有用 |
 * |---|---|---|
 * | 1 | Fused `PRIORITY_BALANCED_POWER_ACCURACY` | **主链路**：明确允许走 Wi-Fi 与基站，室内通常几秒内出结果 |
 * | 2 | Fused `lastLocation` | 本地缓存，**毫秒级**返回；用户刚在室外定过位就一定有值 |
 * | 3 | 平台 `NETWORK_PROVIDER` 的 `lastKnownLocation` | 不依赖 Google Play 服务；Wi-Fi / 基站缓存 |
 * | 4 | 平台 `GPS_PROVIDER` 的 `lastKnownLocation` | 室内的最后已知 GPS 值（从室外走进来的场景） |
 * | 5 | 平台 `NETWORK` / `GPS` / `PASSIVE` 的实时请求 | Google Play 服务不可用（部分国产 ROM / 无 GMS）时的兜底 |
 *
 * 所有链路的结果交给 [LocationFixPolicy.pickBest] 统一挑出最好的一个
 * （先挑"新鲜且精度够用"的，再挑新鲜的，最后才用缓存）。挑点规则是纯函数，
 * 有单元测试覆盖 —— 这是本项目"没有真机也要能验证"的一贯做法。
 *
 * ⚠️ 全部链路都失败时**仍然返回 null**，由打卡页给出"室内定位失败"提示，
 * 并且「改用无条件打卡」入口**照旧可达**（见 `CheckInViewModel.refreshLocation`：
 * 该入口只取决于"是不是从一条「指定地点」的习惯进来"，与定位结果无关）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationProvider(private val context: Context) : LocationSource {

    private val client by lazy { LocationServices.getFusedLocationProviderClient(context) }

    private val locationManager: LocationManager?
        get() = runCatching {
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        }.getOrNull()

    /** 是否已获得前台精确定位权限。 */
    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 取一次当前位置。
     *
     * @param timeoutMs 总预算（PRD 默认 15 秒）
     * @return null 表示无权限、超时或所有来源都拿不到位置
     */
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(timeoutMs: Long): GeoPoint? {
        if (!hasPermission()) {
            Log.w(TAG, "没有定位权限")
            return null
        }

        val startedAt = System.currentTimeMillis()
        val candidates = mutableListOf<FixCandidate>()

        // ---- 第一阶段：本地缓存（毫秒级，不等任何人）----
        //
        // 这一段是室内可用性的关键：室内没有卫星信号，但"最后已知位置"
        // 是本地缓存值，Wi-Fi / 基站更新过就一定有。
        candidates += collectCachedFixes()

        // ---- 第二阶段：实时定位（并发多来源，等预算）----
        val freshBudget = LocationFixPolicy.freshRequestBudgetMillis(timeoutMs)
        if (freshBudget > 0L) {
            candidates += collectFreshFixes(freshBudget)
        }

        val best = LocationFixPolicy.pickBest(candidates, System.currentTimeMillis())
        Log.i(
            TAG,
            "取点完成：候选 ${candidates.size} 个" +
                candidates.joinToString(prefix = " [", postfix = "]") {
                    "${it.fromSource}/${it.kind}±${it.point.accuracyM.toInt()}m"
                } +
                " → 选中 ${best?.let { "${it.fromSource}±${it.point.accuracyM.toInt()}m" } ?: "无"}" +
                "（用时 ${System.currentTimeMillis() - startedAt}ms）"
        )
        return best?.point
    }

    // ------------------------------------------------------------------ 采集

    /** 所有**本地缓存**来源：Fused + 平台 provider 的 lastKnownLocation。 */
    @SuppressLint("MissingPermission")
    private suspend fun collectCachedFixes(): List<FixCandidate> {
        val results = mutableListOf<FixCandidate>()

        // 1) Fused lastLocation（有 Google Play 服务时最省事的缓存）
        runCatching {
            withTimeoutOrNull(CACHED_CALL_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    client.lastLocation
                        .addOnSuccessListener { loc ->
                            if (cont.isActive) cont.resume(loc?.toCandidate(FixKind.Cached, "fused-cache"))
                        }
                        .addOnFailureListener {
                            if (cont.isActive) cont.resume(null)
                        }
                    cont.invokeOnCancellation { }
                }
            }
        }.getOrNull()?.let { results += it }

        // 2) 平台 provider 的 lastKnownLocation —— 不依赖 Google Play 服务。
        //    室内主要靠 NETWORK_PROVIDER（Wi-Fi / 基站）。
        PLATFORM_PROVIDERS.forEach { provider ->
            val enabled = runCatching { locationManager?.isProviderEnabled(provider) }
                .getOrDefault(false)
            if (enabled != true) return@forEach

            val loc = runCatching {
                withTimeoutOrNull(CACHED_CALL_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        val known = runCatching { locationManager?.getLastKnownLocation(provider) }
                            .getOrNull()
                        if (cont.isActive) cont.resume(known)
                        cont.invokeOnCancellation { }
                    }
                }
            }.getOrNull()
            if (loc != null) {
                results += loc.toCandidate(FixKind.Cached, "platform-$provider-cache")
            }
        }

        return results
    }

    /**
     * 所有**实时**来源：并发发出，各自带超时，最后把拿到的都交出来。
     *
     * 并发而不是串行是刻意的：串行时"先等 GPS 15 秒超时再看 Wi-Fi"
     * 会让室内用户白等 15 秒 —— 而那正是原实现的问题。
     *
     * 用 `supervisorScope`：任何一条链路抛异常都不能连累其它链路
     * （踩过的坑：未捕获异常会终止 App 进程，仓库层/服务里一律 runCatching）。
     */
    @SuppressLint("MissingPermission")
    private suspend fun collectFreshFixes(budgetMillis: Long): List<FixCandidate> =
        try {
            supervisorScope {
                val jobs = buildList {
                    // 主链路：明确允许 Wi-Fi / 基站，室内出结果最快
                    add(
                        async {
                            fusedCurrentLocation(
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                budgetMillis,
                                "fused-balanced"
                            )
                        }
                    )
                    // 次链路：GPS 优先。只在真的拿到值时才算候选 ——
                    // 室内它会超时，但因为是并发，不会拖慢别的链路
                    add(
                        async {
                            fusedCurrentLocation(
                                Priority.PRIORITY_HIGH_ACCURACY,
                                budgetMillis,
                                "fused-high"
                            )
                        }
                    )
                    // 平台兜底：Google Play 服务不可用时的唯一路径
                    PLATFORM_PROVIDERS.forEach { provider ->
                        add(async { platformCurrentLocation(provider, budgetMillis) })
                    }
                }
                jobs.awaitAll().filterNotNull()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "实时定位采集失败", t)
            emptyList()
        }

    /** Fused `getCurrentLocation`：一次请求，拿到就返回。 */
    @SuppressLint("MissingPermission")
    private suspend fun fusedCurrentLocation(
        priority: Int,
        budgetMillis: Long,
        source: String
    ): FixCandidate? = runCatching {
        withTimeoutOrNull(budgetMillis) {
            suspendCancellableCoroutine { cont ->
                val token = com.google.android.gms.tasks.CancellationTokenSource()
                client.getCurrentLocation(priority, token.token)
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc?.toCandidate(FixKind.Fresh, source))
                    }
                    .addOnFailureListener { e ->
                        Log.d(TAG, "$source 失败：${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                cont.invokeOnCancellation { runCatching { token.cancel() } }
            }
        }
    }.onFailure { Log.d(TAG, "$source 异常：${it.message}") }.getOrNull()

    /** 平台 provider 的实时单次请求（不依赖 Google Play 服务）。 */
    @SuppressLint("MissingPermission")
    private suspend fun platformCurrentLocation(provider: String, budgetMillis: Long): FixCandidate? {
        val manager = locationManager ?: return null
        if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) != true) {
            return null
        }
        return runCatching {
            withTimeoutOrNull(budgetMillis) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Android 平台接口，保留以兼容旧版本")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                    // ⚠️ 必须配对 removeUpdates，否则监听会一直挂着（耗电 + 事件泄漏）。
                    // 取消路径有两处：withTimeoutOrNull 到期、调用方取消协程 ——
                    // 两者都会触发 invokeOnCancellation。
                    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                }
            }
        }.onFailure { Log.d(TAG, "platform-$provider 异常：${it.message}") }
            .getOrNull()
            ?.toCandidate(FixKind.Fresh, "platform-$provider")
    }

    private fun Location.toCandidate(kind: FixKind, source: String): FixCandidate =
        FixCandidate(point = toGeoPoint(), kind = kind, fromSource = source)

    private fun Location.toGeoPoint(): GeoPoint = GeoPoint(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy else Float.MAX_VALUE,
        atMillis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            time
        } else {
            System.currentTimeMillis()
        }
    )

    companion object {
        private const val TAG = "LocationProvider"

        /** PRD §2.2 默认值表：定位超时 15 秒。 */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** 平台 provider 列表。`NETWORK_PROVIDER` 放前面 —— 室内的主力。 */
        private val PLATFORM_PROVIDERS = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        /** 缓存类调用各自的超时（本地调用，给足余量但不拖时间）。 */
        private const val CACHED_CALL_TIMEOUT_MS = 1_500L
    }
}
