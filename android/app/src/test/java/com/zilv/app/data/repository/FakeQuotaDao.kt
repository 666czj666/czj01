package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.UsageQuotaDao
import com.zilv.app.data.local.entity.UsageQuotaAppEntity
import com.zilv.app.data.local.entity.UsageQuotaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 「软件使用时间限时」DAO 的内存实现（纯 JVM 单元测试用）。
 *
 * 与其它 Fake 一样严格按 DAO 接口的语义实现：
 *  - [insert] 自增 id
 *  - [insertApp] 是 `INSERT OR REPLACE`（复合主键，同一个应用不会出现两次）
 *  - [deleteAppsOf] 是**级联清理**对应的那一步（本项目不建外键，删除额度时
 *    必须显式清绑定关系 —— 这个 Fake 也要如实反映，否则测不出漏清理）
 *
 * `open` 是为了让测试能造一个"坏掉的 DAO"（见
 * `UsageQuotaRepositoryTest` 里"DAO 抛异常时退化为空集合"那条）——
 * 那正是 [com.zilv.app.domain.quota.QuotaEnforcementSource] 最重要的安全属性。
 */
open class FakeQuotaDao : UsageQuotaDao {

    private val quotas = mutableListOf<UsageQuotaEntity>()
    private val apps = mutableListOf<UsageQuotaAppEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value = revision.value + 1
    }

    override fun observeAll(): Flow<List<UsageQuotaEntity>> =
        revision.map { quotas.sortedBy { it.id } }

    override suspend fun getAll(): List<UsageQuotaEntity> = quotas.sortedBy { it.id }

    override suspend fun getEnabled(): List<UsageQuotaEntity> =
        quotas.filter { it.enabled }.sortedBy { it.id }

    override suspend fun getById(id: Long): UsageQuotaEntity? = quotas.find { it.id == id }

    override suspend fun insert(quota: UsageQuotaEntity): Long {
        val id = nextId++
        quotas += quota.copy(id = id)
        bump()
        return id
    }

    override suspend fun update(quota: UsageQuotaEntity) {
        val idx = quotas.indexOfFirst { it.id == quota.id }
        require(idx >= 0) { "update 的目标不存在: id=${quota.id}" }
        quotas[idx] = quota
        bump()
    }

    override suspend fun delete(id: Long) {
        quotas.removeAll { it.id == id }
        bump()
    }

    override suspend fun count(): Int = quotas.size

    override suspend fun packagesOf(quotaId: Long): List<String> =
        apps.filter { it.quotaId == quotaId }.map { it.packageName }.sorted()

    override suspend fun allApps(): List<UsageQuotaAppEntity> =
        apps.sortedWith(compareBy({ it.quotaId }, { it.packageName }))

    override suspend fun insertApp(row: UsageQuotaAppEntity) {
        // INSERT OR REPLACE 的语义：复合主键相同就替换，不会出现重复行
        apps.removeAll { it.quotaId == row.quotaId && it.packageName == row.packageName }
        apps += row
        bump()
    }

    override suspend fun deleteAppsOf(quotaId: Long) {
        apps.removeAll { it.quotaId == quotaId }
        bump()
    }

    override suspend fun deleteApp(quotaId: Long, packageName: String) {
        apps.removeAll { it.quotaId == quotaId && it.packageName == packageName }
        bump()
    }

    override suspend fun countEnabledBindingsFor(packageName: String): Int {
        val enabledIds = quotas.filter { it.enabled }.map { it.id }.toSet()
        return apps.count { it.packageName == packageName && it.quotaId in enabledIds }
    }

    /** 仅供测试：当前绑定关系条数（用于验证"删除额度时清干净了"）。 */
    val appRowCount: Int get() = apps.size
}
