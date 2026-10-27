package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.BlockedAttemptDao
import com.zilv.app.data.local.dao.PackageTotal
import com.zilv.app.data.local.entity.BlockedAttemptEntity
import com.zilv.app.domain.intercept.AttemptMerger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/** 拦截记录的领域视图。 */
data class BlockedAttempt(
    val id: Long,
    val packageName: String,
    val ruleId: Long?,
    val sessionId: Long?,
    val firstAtMillis: Long,
    val lastAtMillis: Long,
    val count: Int
)

/**
 * 拦截记录仓库。
 *
 * 合并语义由 [AttemptMerger] 决定（纯函数，已有单元测试）；
 * 这里负责把它落到数据库：窗口内并入上一条，窗口外新建一条。
 */
class BlockedAttemptRepository(
    private val dao: BlockedAttemptDao,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val mergeWindowMillis: Long = AttemptMerger.MERGE_WINDOW_MILLIS
) {

    /**
     * 记录一次拦截尝试。
     *
     * @return true 表示新建了一条记录；false 表示并入了上一条（计数 +1）
     */
    suspend fun recordAttempt(
        packageName: String,
        ruleId: Long? = null,
        sessionId: Long? = null,
        nowMillis: Long = clock.millis()
    ): Boolean {
        val latest = dao.latestFor(packageName)
        val startsNew = AttemptMerger.startsNewRecord(
            lastAttemptAtMillis = latest?.lastAtMillis,
            nowMillis = nowMillis,
            windowMillis = mergeWindowMillis
        )

        return if (startsNew || latest == null) {
            dao.insert(
                BlockedAttemptEntity(
                    packageName = packageName,
                    ruleId = ruleId,
                    sessionId = sessionId,
                    firstAtMillis = nowMillis,
                    lastAtMillis = nowMillis,
                    count = 1
                )
            )
            true
        } else {
            dao.updateCount(latest.id, latest.count + 1, nowMillis)
            false
        }
    }

    fun observeRecent(limit: Int = 100): Flow<List<BlockedAttempt>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    suspend fun totalsByPackage(): List<PackageTotal> = dao.totalsByPackage()

    /** 指定时间窗口内的按包名汇总（锁机结束报告用）。 */
    suspend fun totalsByPackageBetween(windowStart: Long, windowEnd: Long): Map<String, Int> =
        dao.totalsByPackageBetween(windowStart, windowEnd).associate { it.packageName to it.total }

    /** 总拦截次数（合并后的口径）。 */
    suspend fun totalCount(): Int = dao.totalCount() ?: 0

    suspend fun clear() = dao.clear()

    private fun BlockedAttemptEntity.toDomain() = BlockedAttempt(
        id = id,
        packageName = packageName,
        ruleId = ruleId,
        sessionId = sessionId,
        firstAtMillis = firstAtMillis,
        lastAtMillis = lastAtMillis,
        count = count
    )
}
