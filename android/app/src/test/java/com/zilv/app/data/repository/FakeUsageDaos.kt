package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.EventDao
import com.zilv.app.data.local.dao.LockSessionDao
import com.zilv.app.data.local.dao.PackageTotal
import com.zilv.app.data.local.dao.UsageDao
import com.zilv.app.data.local.entity.AppUsageEntity
import com.zilv.app.data.local.entity.EventEntity
import com.zilv.app.data.local.entity.LockSessionEntity
import com.zilv.app.data.local.entity.UsageSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 使用时长 DAO 的内存实现（纯 JVM 单元测试用）。 */
class FakeUsageDao : UsageDao {

    private val sessions = mutableListOf<UsageSessionEntity>()
    private val aggregates = mutableMapOf<Pair<Long, String>, AppUsageEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    override suspend fun insertSession(session: UsageSessionEntity): Long {
        val id = nextId++
        sessions += session.copy(id = id)
        bump()
        return id
    }

    override suspend fun aggregateRow(epochDay: Long, packageName: String): AppUsageEntity? =
        aggregates[epochDay to packageName]

    override suspend fun upsertAggregate(row: AppUsageEntity) {
        aggregates[row.epochDay to row.packageName] = row
        bump()
    }

    override suspend fun totalsForDay(epochDay: Long): List<AppUsageEntity> =
        aggregates.values.filter { it.epochDay == epochDay }.sortedByDescending { it.foregroundMillis }

    override fun observeTotalsForDay(epochDay: Long): Flow<List<AppUsageEntity>> =
        revision.map { totalsForDay(epochDay) }

    override suspend fun allTimeTotals(limit: Int): List<PackageTotal> =
        aggregates.values.groupBy { it.packageName }
            .map { (pkg, rows) -> PackageTotal(pkg, rows.sumOf { it.foregroundMillis }.toInt()) }
            .sortedByDescending { it.total }
            .take(limit)

    override suspend fun sessionsInWindow(windowStart: Long, windowEnd: Long): List<UsageSessionEntity> =
        sessions.filter { it.endedAtMillis > windowStart && it.startedAtMillis < windowEnd }
            .sortedBy { it.startedAtMillis }

    override suspend fun sessionsSince(fromMillis: Long): List<UsageSessionEntity> =
        sessions.filter { it.startedAtMillis >= fromMillis }.sortedBy { it.startedAtMillis }

    override suspend fun longestSessionInWindow(windowStart: Long, windowEnd: Long): Long? =
        sessionsInWindow(windowStart, windowEnd).maxOfOrNull { it.durationMillis }

    override suspend fun pruneSessionsBefore(beforeMillis: Long) {
        sessions.removeAll { it.startedAtMillis < beforeMillis }
        bump()
    }
}

/** 锁机窗口 DAO 的内存实现。 */
class FakeLockSessionDao : LockSessionDao {

    private val items = mutableListOf<LockSessionEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    override suspend fun insert(session: LockSessionEntity): Long {
        val id = nextId++
        items += session.copy(id = id)
        bump()
        return id
    }

    override suspend fun update(session: LockSessionEntity) {
        val idx = items.indexOfFirst { it.id == session.id }
        require(idx >= 0) { "update 目标不存在: id=${session.id}" }
        items[idx] = session
        bump()
    }

    override suspend fun getById(id: Long): LockSessionEntity? = items.find { it.id == id }

    override suspend fun findByRuleAndStart(ruleId: Long, windowStart: Long): LockSessionEntity? =
        items.find { it.ruleId == ruleId && it.windowStartMillis == windowStart }

    override suspend fun recent(limit: Int): List<LockSessionEntity> =
        items.sortedByDescending { it.windowStartMillis }.take(limit)

    override fun observeAll(): Flow<List<LockSessionEntity>> =
        revision.map { items.sortedByDescending { it.windowStartMillis } }

    override suspend fun latestUnseenFinished(nowMillis: Long): LockSessionEntity? =
        items.filter { !it.reportSeen && it.windowEndMillis <= nowMillis }
            .maxByOrNull { it.windowEndMillis }

    override suspend fun markReportSeen(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(reportSeen = true)
            bump()
        }
    }

    override suspend fun count(): Int = items.size
}

/** 事件 DAO 的内存实现。 */
class FakeEventDao : EventDao {

    private val items = mutableListOf<EventEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    override suspend fun insert(event: EventEntity): Long {
        val id = nextId++
        items += event.copy(id = id)
        bump()
        return id
    }

    override suspend fun forSession(sessionId: Long): List<EventEntity> =
        items.filter { it.sessionId == sessionId }.sortedBy { it.detectedAtMillis }

    override fun observeRecent(limit: Int): Flow<List<EventEntity>> =
        revision.map { items.sortedByDescending { it.detectedAtMillis }.take(limit) }

    override suspend fun since(fromMillis: Long): List<EventEntity> =
        items.filter { it.detectedAtMillis >= fromMillis }.sortedByDescending { it.detectedAtMillis }

    override suspend fun count(): Int = items.size
}
