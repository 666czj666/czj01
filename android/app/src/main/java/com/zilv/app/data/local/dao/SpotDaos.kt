package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zilv.app.data.local.entity.RuleCheckInEntity
import com.zilv.app.data.local.entity.SpotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotDao {

    @Query("SELECT * FROM spots ORDER BY created_at ASC")
    fun observeAll(): Flow<List<SpotEntity>>

    @Query("SELECT * FROM spots ORDER BY created_at ASC")
    suspend fun getAll(): List<SpotEntity>

    @Query("SELECT * FROM spots WHERE id = :id")
    suspend fun getById(id: Long): SpotEntity?

    @Insert
    suspend fun insert(spot: SpotEntity): Long

    @Update
    suspend fun update(spot: SpotEntity)

    @Query("DELETE FROM spots WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM spots")
    suspend fun count(): Int
}

@Dao
interface RuleCheckInDao {

    @Insert
    suspend fun insert(entity: RuleCheckInEntity): Long

    /** 某规则最近一次解锁打卡（用于判断窗口内是否已打卡）。 */
    @Query("SELECT * FROM rule_check_ins WHERE rule_id = :ruleId ORDER BY checked_at DESC LIMIT 1")
    suspend fun latestFor(ruleId: Long): RuleCheckInEntity?

    @Query("SELECT * FROM rule_check_ins WHERE rule_id = :ruleId ORDER BY checked_at DESC")
    suspend fun allFor(ruleId: Long): List<RuleCheckInEntity>

    @Query("SELECT * FROM rule_check_ins ORDER BY checked_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RuleCheckInEntity>>

    @Query("SELECT COUNT(*) FROM rule_check_ins")
    suspend fun count(): Int

    @Query("DELETE FROM rule_check_ins WHERE rule_id = :ruleId")
    suspend fun deleteForRule(ruleId: Long)
}
