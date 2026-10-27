package com.zilv.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.zilv.app.domain.lock.AlarmPlanner
import com.zilv.app.domain.lock.LockRule
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 精确闹钟的注册与取消（PRD §3.3 L2）。
 *
 * 设计：**每次只注册最近的一个触发点**，触发后重算下一个。
 * 这样不受系统对每应用待定闹钟数量的限制，也不会因为规则改动而留下僵尸闹钟。
 *
 * 触发时刻的计算逻辑在 [AlarmPlanner] 里，已用单元测试覆盖；
 * 这里只负责把它翻译成 Android 的 [AlarmManager] 调用。
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 精确闹钟是否可用（Android 12+ 需要用户授权，Android 14 起默认被拒）。 */
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() ?: false
    }

    /**
     * 依据规则重算并注册下一个触发点。
     *
     * @param now 当前时刻（注入以便测试与保持与仓库一致的时钟）
     * @return 注册成功的触发点；没有可注册的触发点、或无权限时为 null
     */
    fun reschedule(rules: List<LockRule>, now: LocalDateTime = LocalDateTime.now()): AlarmPlanner.Trigger? {
        cancel()
        val next = AlarmPlanner.next(rules, now) ?: return null

        val am = alarmManager ?: return null
        val millis = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(next, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (canScheduleExact()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            // 降级：不精确闹钟会晚几分钟，但总比完全不响好。
            // 权限体检页会提示用户去开启精确闹钟。
            Log.w(TAG, "精确闹钟不可用，降级为 setAndAllowWhileIdle（可能延迟）")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        return next
    }

    fun cancel() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager?.cancel(pi)
            pi.cancel()
        }
    }

    private fun pendingIntent(
        trigger: AlarmPlanner.Trigger,
        flags: Int
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_ALARM
            putExtra(AlarmReceiver.EXTRA_TRIGGER_KIND, trigger::class.java.simpleName)
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val REQUEST_CODE = 1001
    }
}
