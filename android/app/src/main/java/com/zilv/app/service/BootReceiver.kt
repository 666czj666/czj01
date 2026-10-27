package com.zilv.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 开机与升级恢复（PRD §3.3 L2 / 流程 8.4）。
 *
 * 行为：开机后立即评估一次 —— 若当前处于某个窗口内且未打卡，
 * 应当立即回到拦截态（M4 接遮挡层后生效），并重新注册精确闹钟。
 *
 * ⚠️ Android 14 起，从 BOOT_COMPLETED 启动某些类型的前台服务会被系统拒绝
 * （`ForegroundServiceStartNotAllowedException`）。因此这里的启动失败是**预期内**的，
 * 已做降级处理：服务起不来，至少保证闹钟链路恢复。
 */
class BootReceiver : BroadcastReceiver(), KoinComponent {

    private val lockRuleRepository: LockRuleRepository by inject()
    private val alarmScheduler: AlarmScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        Log.i(TAG, "收到 $action，开始恢复")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rules = lockRuleRepository.getEnabledRules()
                val status = lockRuleRepository.currentStatus()
                Log.i(TAG, "开机时状态: ${status.phase} (${status.rule?.name ?: "-"})")

                // 先恢复闹钟链路，这一步不依赖前台服务能否启动
                alarmScheduler.reschedule(rules)

                // 仅在真正需要拦截时才拉起前台服务，减少无谓的常驻
                if (status.phase == LockPhase.LOCKED || status.phase == LockPhase.WARN) {
                    LockForegroundService.start(context)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "开机恢复失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
