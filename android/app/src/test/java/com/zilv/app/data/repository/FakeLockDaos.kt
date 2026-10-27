package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.AllowAppDao
import com.zilv.app.data.local.dao.LockRuleDao
import com.zilv.app.data.local.dao.RuleAllowAppDao
import com.zilv.app.data.local.entity.AllowAppEntity
import com.zilv.app.data.local.entity.LockRuleEntity
import com.zilv.app.data.local.entity.RuleAllowAppEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 锁机相关 DAO 的内存实现（纯 JVM 单元测试用）。
 *
 * 语义与真实 Room 实现保持一致：排序、内置项不可删、规则级白名单清理。
 */

private val ruleOrder = compareBy<LockRuleEntity>({ it.sortOrder }, { it.id })

class FakeLockRuleDao : LockRuleDao {

    private val items = mutableListOf<LockRuleEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    private fun snapshot() = items.sortedWith(ruleOrder)

    override fun observeAll(): Flow<List<LockRuleEntity>> = revision.map { snapshot() }

    override suspend fun getAll(): List<LockRuleEntity> = snapshot()

    override suspend fun getEnabled(): List<LockRuleEntity> = snapshot().filter { it.enabled }

    override suspend fun getById(id: Long): LockRuleEntity? = items.find { it.id == id }

    override suspend fun getByHabit(habitId: Long): LockRuleEntity? =
        items.firstOrNull { it.habitId == habitId }

    override suspend fun deleteByHabit(habitId: Long) {
        items.removeAll { it.habitId == habitId }
        bump()
    }

    override suspend fun insert(rule: LockRuleEntity): Long {
        val id = nextId++
        items += rule.copy(id = id)
        bump()
        return id
    }

    override suspend fun update(rule: LockRuleEntity) {
        val idx = items.indexOfFirst { it.id == rule.id }
        require(idx >= 0) { "update 目标不存在: id=${rule.id}" }
        items[idx] = rule
        bump()
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(enabled = enabled)
            bump()
        }
    }

    override suspend fun delete(id: Long) {
        items.removeAll { it.id == id }
        bump()
    }

    override suspend fun count(): Int = items.size
}

class FakeAllowAppDao : AllowAppDao {

    private val items = linkedMapOf<String, AllowAppEntity>()
    private val revision = MutableStateFlow(0)

    private fun bump() {
        revision.value++
    }

    private fun snapshot() = items.values.sortedWith(compareBy({ it.scope }, { it.label }))

    override fun observeAll(): Flow<List<AllowAppEntity>> = revision.map { snapshot() }

    override suspend fun getAll(): List<AllowAppEntity> = snapshot()

    override suspend fun getEnabled(): List<AllowAppEntity> = snapshot().filter { it.enabled }

    override suspend fun getByPackage(packageName: String): AllowAppEntity? = items[packageName]

    override suspend fun upsert(app: AllowAppEntity) {
        items[app.packageName] = app
        bump()
    }

    override suspend fun insertAll(apps: List<AllowAppEntity>) {
        apps.forEach { if (!items.containsKey(it.packageName)) items[it.packageName] = it }
        bump()
    }

    override suspend fun setEnabled(packageName: String, enabled: Boolean) {
        items[packageName]?.let {
            items[packageName] = it.copy(enabled = enabled)
            bump()
        }
    }

    /** 与 SQL 中的 `AND scope = 'user'` 等价：内置项删不掉。 */
    override suspend fun deleteUserApp(packageName: String): Int {
        val existing = items[packageName] ?: return 0
        if (existing.scope != "user") return 0
        items.remove(packageName)
        bump()
        return 1
    }

    override suspend fun count(): Int = items.size
}

class FakeRuleAllowAppDao : RuleAllowAppDao {

    private val items = mutableListOf<RuleAllowAppEntity>()

    override suspend fun packageNamesFor(ruleId: Long): List<String> =
        items.filter { it.ruleId == ruleId }.map { it.packageName }

    override suspend fun insert(entry: RuleAllowAppEntity) {
        if (items.none { it.ruleId == entry.ruleId && it.packageName == entry.packageName }) {
            items += entry
        }
    }

    override suspend fun deleteForRule(ruleId: Long) {
        items.removeAll { it.ruleId == ruleId }
    }
}
