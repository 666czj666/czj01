package com.zilv.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.usage.UsageSessionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 亮屏 / 息屏监听（PRD §3.7）。
 *
 * `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` **不能**在 manifest 里静态注册，
 * 只能在运行时动态注册。这里由无障碍服务在连接时注册、断开时注销。
 *
 * 作用：息屏时暂停使用时长计时，否则挂后台的时间会被算进去。
 */
class ScreenStateReceiver(
    private val scope: CoroutineScope,
    private val onSegment: (UsageSessionTracker.Segment) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                UsageTrackingHub.tracker.onScreenOff(now)?.let(onSegment)
                Log.d(TAG, "息屏，暂停计时")
            }

            Intent.ACTION_SCREEN_ON -> {
                UsageTrackingHub.tracker.onScreenOn(now)
                Log.d(TAG, "亮屏，恢复计时")
            }
        }
    }

    companion object {
        private const val TAG = "ScreenStateReceiver"

        fun register(context: Context, receiver: ScreenStateReceiver) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            context.registerReceiver(receiver, filter)
        }

        fun unregister(context: Context, receiver: ScreenStateReceiver) {
            runCatching { context.unregisterReceiver(receiver) }
        }

        /** 兜底：某些 ROM 不广播亮屏，用 PowerManager 查询当前状态。 */
        fun isInteractive(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isInteractive ?: true
        }
    }
}

/**
 * 使用时长跟踪的全局单例持有者。
 *
 * 为什么用单例而不是 Koin：无障碍服务的生命周期由系统控制，
 * 可能在 Koin 未初始化时就被绑定（例如用户刚开机），单例更稳妥。
 * Tracker 本身是纯逻辑类，不持有 Context。
 */
object UsageTrackingHub : KoinComponent {

    val tracker: UsageSessionTracker = UsageSessionTracker()

    private val usageRepository: UsageRepository by inject()

    /** 把一段使用落到数据库（会话段 + 当日聚合）。 */
    fun persist(scope: CoroutineScope, segment: UsageSessionTracker.Segment) {
        if (segment.durationMillis <= 0L) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                usageRepository.recordSegment(
                    packageName = segment.packageName,
                    startMillis = segment.startMillis,
                    endMillis = segment.endMillis
                )
            }.onFailure { Log.e("UsageTrackingHub", "记录使用时长失败", it) }
        }
    }
}
