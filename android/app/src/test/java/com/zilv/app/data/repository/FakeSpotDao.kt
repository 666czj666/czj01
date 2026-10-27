package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.SpotDao
import com.zilv.app.data.local.entity.SpotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 打卡点 DAO 的内存实现。 */
class FakeSpotDao : SpotDao {

    private val items = mutableListOf<SpotEntity>()
    private val revision = MutableStateFlow(0)
    private var nextId = 1L

    private fun bump() {
        revision.value++
    }

    override fun observeAll(): Flow<List<SpotEntity>> =
        revision.map { items.sortedBy { it.createdAt } }

    override suspend fun getAll(): List<SpotEntity> = items.sortedBy { it.createdAt }

    override suspend fun getById(id: Long): SpotEntity? = items.find { it.id == id }

    override suspend fun insert(spot: SpotEntity): Long {
        val id = nextId++
        items += spot.copy(id = id)
        bump()
        return id
    }

    override suspend fun update(spot: SpotEntity) {
        val idx = items.indexOfFirst { it.id == spot.id }
        require(idx >= 0) { "update 目标不存在: id=${spot.id}" }
        items[idx] = spot
        bump()
    }

    override suspend fun delete(id: Long) {
        items.removeAll { it.id == id }
        bump()
    }

    override suspend fun count(): Int = items.size
}
