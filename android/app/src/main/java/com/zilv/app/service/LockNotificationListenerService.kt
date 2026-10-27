package com.zilv.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zilv.app.MainActivity
import com.zilv.app.R
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.notify.NotificationMaskPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 通知脱敏（PRD §3.6）。
 *
 * 机制：Android **不允许**修改他人的通知内容，所以只能「撤回 + 自己重发」。
 * 决策逻辑在 [NotificationMaskPolicy]（纯函数，12 个单元测试覆盖）。
 *
 * ⚠️ 已在策略里固化两条平台硬约束：
 *  1. `setOngoing(true)` 的通知**监听器撤不掉**（官方 javadoc 逐字核实）
 *  2. 撤回前必须判 `isClearable()`，否则会陷入无效重试
 *
 * 降级策略（PRD）：没有通知使用权时静默停用，**不影响拦截主流程**。
 */
class LockNotificationListenerService : NotificationListenerService(), KoinComponent {

    private val lockRuleRepository: LockRuleRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedLocked: Boolean = false

    @Volatile
    private var cacheAt: Long = 0L

    /** 每个目标应用的待展示消息数，用于聚合文案。 */
    private val pendingCounts = mutableMapOf<String, Int>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接")
        createChannel()
        refreshLockState(force = true)
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "通知监听已断开，请求重新绑定")
        // 官方文档：只有 requestRebind 在断开后是安全的（API 24+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            runCatching {
                requestRebind(
                    android.content.ComponentName(this, LockNotificationListenerService::class.java)
                )
            }.onFailure { Log.w(TAG, "请求重新绑定失败", it) }
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return

        refreshLockState(force = false)

        val decision = NotificationMaskPolicy.decide(
            NotificationMaskPolicy.Input(
                packageName = notification.packageName.orEmpty(),
                isOngoing = notification.isOngoing,
                isClearable = notification.isClearable,
                targetPackages = NotificationMaskPolicy.DEFAULT_TARGETS,
                locked = cachedLocked,
                hasListenerAccess = true
            )
        )

        when (decision) {
            is NotificationMaskPolicy.Decision.Ignore -> Unit

            is NotificationMaskPolicy.Decision.CannotMask -> {
                // 撤不掉就放着 —— 不重试、不崩溃（PRD：降级而非对抗）
                Log.d(TAG, "跳过不可撤回的通知: ${decision.reason}")
            }

            is NotificationMaskPolicy.Decision.Mask -> {
                val count = (pendingCounts[decision.packageName] ?: 0) + 1
                pendingCounts[decision.packageName] = count
                mask(decision.packageName, notification.key, count)
            }
        }
    }

    private fun mask(packageName: String, key: String, count: Int) {
        // 1) 撤回原通知（这会顺带撤掉 SystemUI 渲染的"直接回复"框，
        //    也正因如此重发的通知不再带快捷回复 —— PRD 已接受这个代价）
        runCatching { cancelNotification(key) }
            .onFailure { Log.w(TAG, "撤回通知失败", it) }

        // 2) 重发一条不含内容的通知
        val label = appLabel(packageName)
        val text = NotificationMaskPolicy.maskedText(label, count)

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val masked = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label)
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(packageName.hashCode(), masked)
    }

    private fun appLabel(packageName: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse {
        when (packageName) {
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            else -> packageName
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "消息提醒", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "锁机期间提示有新消息（不显示内容）" }
        )
    }

    private fun refreshLockState(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - cacheAt < CACHE_TTL_MILLIS) return
        cacheAt = now
        scope.launch {
            runCatching {
                cachedLocked = lockRuleRepository.currentStatus().phase == LockPhase.LOCKED
            }.onFailure { Log.e(TAG, "刷新锁机状态失败", it) }
        }
    }

    companion object {
        private const val TAG = "LockNotificationListener"
        private const val CHANNEL_ID = "zilv_masked_messages"
        private const val CACHE_TTL_MILLIS = 2_000L
    }
}
