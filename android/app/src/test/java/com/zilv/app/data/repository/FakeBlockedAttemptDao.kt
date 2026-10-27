package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.BlockedAttemptDao
import com.zilv.app.data.local.dao.PackageTotal
import com.zilv.app.data.local.entity.BlockedAttemptEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 拦截记录 DAO 的内存实现（纯 JVM 单元测试用）。 */
class FakeBlockedAttemptDao : BlockedAttemptDao {

    private val items = mutableListOf<BlockedAttemptEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    override suspend fun insert(entity: BlockedAttemptEntity): Long {
        val id = nextId++
        items += entity.copy(id = id)
        bump()
        return id
    }

    override suspend fun latestFor(packageName: String): BlockedAttemptEntity? =
        items.filter { it.packageName == packageName }.maxByOrNull { it.lastAtMillis }

    override suspend fun updateCount(id: Long, count: Int, lastAt: Long) {
        val idx = items.indexOfFirst { it.id == id }
        require(idx >= 0) { "updateCount 目标不存在: id=$id" }
        items[idx] = items[idx].copy(count = count, lastAtMillis = lastAt)
        bump()
    }

    override fun observeRecent(limit: Int): Flow<List<BlockedAttemptEntity>> =
        revision.map { items.sortedByDescending { it.lastAtMillis }.take(limit) }

    override suspend fun getAll(): List<BlockedAttemptEntity> =
        items.sortedByDescending { it.lastAtMillis }

    override suspend fun totalsByPackage(): List<PackageTotal> =
        items.groupBy { it.packageName }
            .map { (pkg, rows) -> PackageTotal(pkg, rows.sumOf { it.count }) }
            .sortedByDescending { it.total }

    override suspend fun totalsByPackageBetween(windowStart: Long, windowEnd: Long): List<PackageTotal> =
        items.filter { it.lastAtMillis >= windowStart && it.lastAtMillis < windowEnd }
            .groupBy { it.packageName }
            .map { (pkg, rows) -> PackageTotal(pkg, rows.sumOf { it.count }) }
            .sortedByDescending { it.total }

    override suspend fun totalCount(): Int? = items.sumOf { it.count }

    override suspend fun rowCount(): Int = items.size

    override suspend fun clear() {
        items.clear()
        bump()
    }
}
