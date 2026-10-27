package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.RuleCheckInDao
import com.zilv.app.data.local.entity.RuleCheckInEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 解锁打卡 DAO 的内存实现（纯 JVM 单元测试用）。 */
class FakeRuleCheckInDao : RuleCheckInDao {

    private val items = mutableListOf<RuleCheckInEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    override suspend fun insert(entity: RuleCheckInEntity): Long {
        val id = nextId++
        items += entity.copy(id = id)
        bump()
        return id
    }

    override suspend fun latestFor(ruleId: Long): RuleCheckInEntity? =
        items.filter { it.ruleId == ruleId }.maxByOrNull { it.checkedAtMillis }

    override suspend fun allFor(ruleId: Long): List<RuleCheckInEntity> =
        items.filter { it.ruleId == ruleId }.sortedByDescending { it.checkedAtMillis }

    override fun observeRecent(limit: Int): Flow<List<RuleCheckInEntity>> =
        revision.map { items.sortedByDescending { it.checkedAtMillis }.take(limit) }

    override suspend fun count(): Int = items.size

    override suspend fun deleteForRule(ruleId: Long) {
        items.removeAll { it.ruleId == ruleId }
        bump()
    }
}
