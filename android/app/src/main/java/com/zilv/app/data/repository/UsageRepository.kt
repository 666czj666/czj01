package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.PackageTotal
import com.zilv.app.data.local.dao.UsageDao
import com.zilv.app.data.local.entity.AppUsageEntity
import com.zilv.app.data.local.entity.UsageSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate

/** 一段前台使用的领域视图。 */
data class UsageSession(
    val id: Long,
    val packageName: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val durationMillis: Long
)

/**
 * 使用时长仓库（PRD §3.7）。
 *
 * 两条写入路径：
 *  1. 写**原始段**到 `usage_sessions`（供窗口内切片）
 *  2. 同时累加到 `app_usage` 的当日聚合（供时长页快速查询）
 *
 * 窗口内统计采用**区间裁剪**：一段使用可能横跨窗口边界，
 * 只应计入落在窗口内的那部分（见 [windowTotals]）。
 */
class UsageRepository(
    private val dao: UsageDao,
    private val clock: Clock = Clock.systemDefaultZone()
) {

    fun today(): LocalDate = LocalDate.now(clock)

    /**
     * 记录一段前台使用。
     *
     * @return 写入的会话 id；时长为 0 时返回 null 且不写库
     */
    suspend fun recordSegment(
        packageName: String,
        startMillis: Long,
        endMillis: Long
    ): Long? {
        val duration = (endMillis - startMillis).coerceAtLeast(0L)
        if (duration <= 0L || packageName.isBlank()) return null

        val epochDay = java.time.Instant.ofEpochMilli(startMillis)
            .atZone(clock.zone).toLocalDate().toEpochDay()

        val id = dao.insertSession(
            UsageSessionEntity(
                packageName = packageName,
                startedAtMillis = startMillis,
                endedAtMillis = endMillis,
                durationMillis = duration,
                epochDay = epochDay
            )
        )

        // 同步累加当日聚合
        val existing = dao.aggregateRow(epochDay, packageName)
        dao.upsertAggregate(
            AppUsageEntity(
                epochDay = epochDay,
                packageName = packageName,
                foregroundMillis = (existing?.foregroundMillis ?: 0L) + duration
            )
        )
        return id
    }

    // ---------------------------------------------------------------- 按天

    suspend fun dailyTotals(epochDay: Long): Map<String, Long> =
        dao.totalsForDay(epochDay).associate { it.packageName to it.foregroundMillis }

    suspend fun dailyTotals(date: LocalDate): Map<String, Long> = dailyTotals(date.toEpochDay())

    fun observeDailyTotals(date: LocalDate): Flow<Map<String, Long>> =
        dao.observeTotalsForDay(date.toEpochDay())
            .map { rows -> rows.associate { it.packageName to it.foregroundMillis } }

    suspend fun allTimeTotals(limit: Int = 50): List<PackageTotal> = dao.allTimeTotals(limit)

    // ---------------------------------------------------------------- 窗口内

    suspend fun sessionsInWindow(windowStart: Long, windowEnd: Long): List<UsageSession> =
        dao.sessionsInWindow(windowStart, windowEnd).map { it.toDomain() }

    /**
     * 窗口内各应用的前台时长。
     *
     * 对每段做**区间裁剪**：`overlap = min(sessionEnd, windowEnd) - max(sessionStart, windowStart)`，
     * 只累加正的重叠部分。这样跨窗口边界的使用不会被整段计入。
     */
    suspend fun windowTotals(windowStart: Long, windowEnd: Long): Map<String, Long> {
        if (windowEnd <= windowStart) return emptyMap()
        val result = mutableMapOf<String, Long>()
        dao.sessionsInWindow(windowStart, windowEnd).forEach { row ->
            val overlapStart = maxOf(row.startedAtMillis, windowStart)
            val overlapEnd = minOf(row.endedAtMillis, windowEnd)
            val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
            if (overlap > 0L) {
                result[row.packageName] = (result[row.packageName] ?: 0L) + overlap
            }
        }
        return result
    }

    /** 窗口内单次使用的最大时长（「最长连续使用」指标）。 */
    suspend fun longestSessionInWindow(windowStart: Long, windowEnd: Long): Long =
        dao.longestSessionInWindow(windowStart, windowEnd) ?: 0L

    /** 清理过老的原始段，避免无限增长（按天聚合会被保留）。 */
    suspend fun pruneSessionsOlderThan(days: Int): Int {
        require(days > 0) { "days 必须为正数，实际 $days" }
        val cutoff = clock.millis() - days * 24L * 60 * 60 * 1000
        dao.pruneSessionsBefore(cutoff)
        return 0
    }

    private fun UsageSessionEntity.toDomain() = UsageSession(
        id = id,
        packageName = packageName,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        durationMillis = durationMillis
    )
}
