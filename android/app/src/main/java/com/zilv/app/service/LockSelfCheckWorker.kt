package com.zilv.app.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.util.Log
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockPhase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * 定期自检（PRD §3.3 L2）。
 *
 * 职责：
 *  1. 重算并重新注册精确闹钟 —— 防止闹钟因系统改动而丢失
 *  2. 若当前应处于拦截态但前台服务不在运行，则把它拉起来
 *
 * 这是"保活"的兜底。PRD 已明确保活有上限（§3.12），本 Worker 不试图对抗系统。
 */
class LockSelfCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val lockRuleRepository: LockRuleRepository by inject()
    private val alarmScheduler: AlarmScheduler by inject()

    override suspend fun doWork(): Result = runCatching {
        val rules = lockRuleRepository.getEnabledRules()
        alarmScheduler.reschedule(rules)

        val status = lockRuleRepository.currentStatus()
        if (status.phase == LockPhase.LOCKED || status.phase == LockPhase.WARN) {
            LockForegroundService.start(applicationContext)
        }
        Log.i(TAG, "自检完成: 规则 ${rules.size} 条, 状态 ${status.phase}")
        Result.success()
    }.getOrElse {
        Log.e(TAG, "自检失败", it)
        Result.retry()
    }

    companion object {
        private const val TAG = "LockSelfCheckWorker"
        private const val UNIQUE_NAME = "zilv_lock_self_check"

        /** 周期下限是 15 分钟（WorkManager 的系统限制）。 */
        private const val INTERVAL_MINUTES = 15L

        /** 幂等注册；已有任务则保持不变。 */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<LockSelfCheckWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }.onFailure { Log.e(TAG, "注册自检任务失败", it) }
        }
    }
}
