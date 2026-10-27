package com.zilv.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zilv.app.data.local.entity.UsageQuotaAppEntity
import com.zilv.app.data.local.entity.UsageQuotaEntity
import kotlinx.coroutines.flow.Flow

/**
 * 「软件使用时间限时」的 DAO。
 *
 * 与其它 DAO 一样只做存取：**业务判断一律留在仓库层**
 * （"至少要绑一个应用""时长范围""旧额度不可保留"都在
 * [com.zilv.app.data.repository.UsageQuotaRepository] 与
 * [com.zilv.app.domain.quota.QuotaCalculator]），这样纯 JVM 单测可以用内存
 * Fake 完整覆盖，而这里的 SQL 由 Room 注解处理器在编译期校验。
 *
 * 排序统一按 `id` 升序（= 创建顺序）：额度列表没有天然的时间序，
 * 稳定顺序才不会让界面抖动。
 */
@Dao
interface UsageQuotaDao {

    @Query("SELECT * FROM usage_quotas ORDER BY id ASC")
    fun observeAll(): Flow<List<UsageQuotaEntity>>

    @Query("SELECT * FROM usage_quotas ORDER BY id ASC")
    suspend fun getAll(): List<UsageQuotaEntity>

    /** 只有启用的额度参与拦截 —— 拦截热路径上不该把停用的也读回来。 */
    @Query("SELECT * FROM usage_quotas WHERE enabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<UsageQuotaEntity>

    @Query("SELECT * FROM usage_quotas WHERE id = :id")
    suspend fun getById(id: Long): UsageQuotaEntity?

    @Insert
    suspend fun insert(quota: UsageQuotaEntity): Long

    @Update
    suspend fun update(quota: UsageQuotaEntity)

    @Query("DELETE FROM usage_quotas WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM usage_quotas")
    suspend fun count(): Int

    // ------------------------------------------------------------ 绑定关系

    /**
     * 某个额度绑定的包名。
     *
     * ⚠️ 返回 `List` 而不是 `Flow`：绑定关系的变化一定伴随额度行的写入，
     * 而界面观察的是 [observeAll]（额度表）—— 一次绑定变更 = 一次额度行
     * `update`，Flow 会重新发射，所以再观察一张表是多余的。
     */
    @Query("SELECT package_name FROM usage_quota_apps WHERE quota_id = :quotaId ORDER BY package_name ASC")
    suspend fun packagesOf(quotaId: Long): List<String>

    /** 一次拿全部绑定关系，供仓库层批量组装（避免 N+1 查询）。 */
    @Query("SELECT * FROM usage_quota_apps ORDER BY quota_id ASC, package_name ASC")
    suspend fun allApps(): List<UsageQuotaAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(row: UsageQuotaAppEntity)

    @Query("DELETE FROM usage_quota_apps WHERE quota_id = :quotaId")
    suspend fun deleteAppsOf(quotaId: Long)

    @Query("DELETE FROM usage_quota_apps WHERE quota_id = :quotaId AND package_name = :packageName")
    suspend fun deleteApp(quotaId: Long, packageName: String)

    /** 某个应用是否被任何**启用中**的额度绑定（用于界面提示"这个应用已被限时"）。 */
    @Query(
        """
        SELECT COUNT(*) FROM usage_quota_apps a
        INNER JOIN usage_quotas q ON q.id = a.quota_id
        WHERE a.package_name = :packageName AND q.enabled = 1
        """
    )
    suspend fun countEnabledBindingsFor(packageName: String): Int
}
