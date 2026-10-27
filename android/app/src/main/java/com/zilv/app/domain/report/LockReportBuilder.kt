package com.zilv.app.domain.report

import com.zilv.app.data.mapper.RuleCheckIn
import com.zilv.app.data.mapper.RuleCheckInStatus
import java.time.Duration
import java.time.LocalDateTime

/**
 * 锁机结束报告的数据构建（PRD §3.8）。
 *
 * 纯函数：给定一个窗口的原始数据，产出报告视图模型。
 * PRD 特别强调**数据口径必须写清楚**（否则数字没意义），
 * 所以这里的每个字段都在注释里注明它到底在算什么。
 */
object LockReportBuilder {

    /** 报告里的一个「关键应用使用时长」条目。 */
    data class AppUsage(
        val packageName: String,
        val label: String,
        val durationMillis: Long
    )

    /** 报告里的一个「被拦住的」条目。 */
    data class BlockedApp(
        val packageName: String,
        val label: String,
        val count: Int
    )

    data class Report(
        val ruleName: String,
        val windowStart: LocalDateTime,
        val windowEnd: LocalDateTime,
        /** 窗口开始 → 结束，**含已解锁的时段** */
        val lockDurationMillis: Long,
        /**
         * 「用手机」= 窗口内所有前台应用时长之和。
         *
         * ⚠️ 与 PRD 的说明略有不同：PRD 提醒「用手机 ≠ 各应用时长之和」，
         * 因为系统 API 统计的"屏幕点亮时长"含桌面、设置、通知栏。
         * 而我们的自采集是**按前台包名**统计的，桌面与通知栏本身就是包
         * （launcher / systemui），已经在总和里了 —— 所以这里两者相等。
         */
        val phoneUsedMillis: Long,
        val keyApps: List<AppUsage>,
        val blockedApps: List<BlockedApp>,
        val checkIn: CheckInSummary?,
        val anomalies: List<String>
    ) {
        val hasAnyUsage: Boolean get() = phoneUsedMillis > 0L
    }

    data class CheckInSummary(
        val at: LocalDateTime,
        val status: RuleCheckInStatus,
        /** 相对窗口开始的迟到分钟数；提前打卡为负数 */
        val minutesFromStart: Long
    ) {
        val isLate: Boolean get() = minutesFromStart > 0
        val isEarly: Boolean get() = minutesFromStart < 0
        val lateMinutes: Long get() = minutesFromStart.coerceAtLeast(0L)
    }

    /**
     * @param usageMillis 窗口内各包名的前台时长
     * @param labels 包名 → 显示名（由 PackageManager 解析；解析不到时回退包名）
     * @param blockedTotals 窗口内各被拦应用的次数（合并后口径）
     * @param anomalies 异常事件文案（紧急放行、无障碍被关等）
     * @param maxKeyApps 「关键应用使用时长」最多显示几个（PRD 默认 8）
     */
    fun build(
        ruleName: String,
        windowStart: LocalDateTime,
        windowEnd: LocalDateTime,
        checkIn: RuleCheckIn?,
        usageMillis: Map<String, Long>,
        labels: Map<String, String> = emptyMap(),
        blockedTotals: Map<String, Int> = emptyMap(),
        anomalies: List<String> = emptyList(),
        maxKeyApps: Int = DEFAULT_MAX_KEY_APPS
    ): Report {
        require(maxKeyApps > 0) { "maxKeyApps 必须为正数，实际 $maxKeyApps" }

        val lockDuration = Duration.between(windowStart, windowEnd).toMillis().coerceAtLeast(0L)

        val keyApps = usageMillis.entries
            .filter { it.value > 0L }
            .sortedByDescending { it.value }
            .take(maxKeyApps)
            .map { (pkg, ms) -> AppUsage(pkg, labels[pkg] ?: pkg, ms) }

        // 「用手机」统计**全部**前台时长，不只是 keyApps 那几条
        val phoneUsed = usageMillis.values.filter { it > 0L }.sum()

        val blocked = blockedTotals.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { (pkg, count) -> BlockedApp(pkg, labels[pkg] ?: pkg, count) }

        val checkInSummary = checkIn?.let {
            CheckInSummary(
                at = it.localDateTime(),
                status = it.status,
                minutesFromStart = Duration.between(windowStart, it.localDateTime()).toMinutes()
            )
        }

        return Report(
            ruleName = ruleName,
            windowStart = windowStart,
            windowEnd = windowEnd,
            lockDurationMillis = lockDuration,
            phoneUsedMillis = phoneUsed,
            keyApps = keyApps,
            blockedApps = blocked,
            checkIn = checkInSummary,
            anomalies = anomalies
        )
    }

    /** PRD §2.2 默认值表：报告内应用列表最多 8 个。 */
    const val DEFAULT_MAX_KEY_APPS = 8
}
