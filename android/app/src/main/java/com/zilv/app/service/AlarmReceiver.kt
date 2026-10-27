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
 * 精确闹钟到点后的入口（PRD §3.3 L2）。
 *
 * 收到触发后：重新评估状态 → 通知前台服务 → 注册下一个触发点。
 * 实际的重算逻辑放在 [LockForegroundService] 里，广播接收器只做转发，
 * 避免在 BroadcastReceiver 里做耗时工作（系统限制 10 秒）。
 */
class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val lockRuleRepository: LockRuleRepository by inject()
    private val alarmScheduler: AlarmScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM) return
        val kind = intent.getStringExtra(EXTRA_TRIGGER_KIND)
        Log.i(TAG, "闹钟触发: kind=$kind")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 只在确实处于锁机 / 预警态时才拉起前台服务 ——
                // 否则窗口结束的闹钟也会弹一下通知（用户要求非锁机时间不显示通知）
                val status = lockRuleRepository.currentStatus()
                if (status.phase == LockPhase.LOCKED || status.phase == LockPhase.WARN) {
                    LockForegroundService.start(context)
                } else {
                    // 服务可能还在运行（刚解锁）——让它重算并收起通知
                    LockForegroundService.notifyStateChanged(context)
                }
                // 触发后立刻重算下一个，保证链路不断
                alarmScheduler.reschedule(lockRuleRepository.getEnabledRules())
            } catch (t: Throwable) {
                Log.e(TAG, "闹钟触发处理失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_ALARM = "com.zilv.app.action.ALARM"
        const val EXTRA_TRIGGER_KIND = "trigger_kind"
    }
}
