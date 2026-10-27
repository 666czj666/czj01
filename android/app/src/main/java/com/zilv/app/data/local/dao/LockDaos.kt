package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zilv.app.data.local.entity.AllowAppEntity
import com.zilv.app.data.local.entity.LockRuleEntity
import com.zilv.app.data.local.entity.RuleAllowAppEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LockRuleDao {

    @Query("SELECT * FROM lock_rules ORDER BY sort_order ASC, id ASC")
    fun observeAll(): Flow<List<LockRuleEntity>>

    @Query("SELECT * FROM lock_rules ORDER BY sort_order ASC, id ASC")
    suspend fun getAll(): List<LockRuleEntity>

    @Query("SELECT * FROM lock_rules WHERE enabled = 1 ORDER BY sort_order ASC, id ASC")
    suspend fun getEnabled(): List<LockRuleEntity>

    @Query("SELECT * FROM lock_rules WHERE id = :id")
    suspend fun getById(id: Long): LockRuleEntity?

    /**
     * 某个习惯名下的锁机配置（至多一条）。
     *
     * v2.0 起锁机归属于习惯，一个习惯只应有一条配置 ——
     * 用 LIMIT 1 而不是断言唯一，避免历史脏数据导致查询抛异常。
     */
    @Query("SELECT * FROM lock_rules WHERE habit_id = :habitId LIMIT 1")
    suspend fun getByHabit(habitId: Long): LockRuleEntity?

    @Query("DELETE FROM lock_rules WHERE habit_id = :habitId")
    suspend fun deleteByHabit(habitId: Long)

    @Insert
    suspend fun insert(rule: LockRuleEntity): Long

    @Update
    suspend fun update(rule: LockRuleEntity)

    @Query("UPDATE lock_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM lock_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM lock_rules")
    suspend fun count(): Int
}

@Dao
interface AllowAppDao {

    @Query("SELECT * FROM allow_apps ORDER BY scope ASC, label ASC")
    fun observeAll(): Flow<List<AllowAppEntity>>

    @Query("SELECT * FROM allow_apps ORDER BY scope ASC, label ASC")
    suspend fun getAll(): List<AllowAppEntity>

    @Query("SELECT * FROM allow_apps WHERE enabled = 1")
    suspend fun getEnabled(): List<AllowAppEntity>

    @Query("SELECT * FROM allow_apps WHERE package_name = :packageName")
    suspend fun getByPackage(packageName: String): AllowAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: AllowAppEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(apps: List<AllowAppEntity>)

    @Query("UPDATE allow_apps SET enabled = :enabled WHERE package_name = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)

    /** 只允许删除用户自定义项，内置强制项删不掉（安全底线，见 PRD §3.4）。 */
    @Query("DELETE FROM allow_apps WHERE package_name = :packageName AND scope = 'user'")
    suspend fun deleteUserApp(packageName: String): Int

    @Query("SELECT COUNT(*) FROM allow_apps")
    suspend fun count(): Int
}

@Dao
interface RuleAllowAppDao {

    @Query("SELECT package_name FROM rule_allow_apps WHERE rule_id = :ruleId")
    suspend fun packageNamesFor(ruleId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: RuleAllowAppEntity)

    @Query("DELETE FROM rule_allow_apps WHERE rule_id = :ruleId")
    suspend fun deleteForRule(ruleId: Long)
}
