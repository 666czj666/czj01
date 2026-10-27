package com.zilv.app.data.mapper

import com.zilv.app.data.local.entity.UsageQuotaAppEntity
import com.zilv.app.data.local.entity.UsageQuotaEntity
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota

/**
 * 刷新周期的编解码。
 *
 * 与 [HabitTypeCodec] / [UnlockModeCodec] 同一口径：**存枚举名，不存序号** ——
 * 序号会在枚举顺序调整时把旧库读成另一个周期（这类错误极难发现，
 * 因为"每天"变"每周"在界面上看起来只是行为不对）。
 *
 * 未知值退化为 [ResetPeriod.DAILY]：宁可刷得勤一点，
 * 也不要因为一行脏数据把额度变成"永不刷新"（那会让用户的应用被永久禁用）。
 */
object ResetPeriodCodec {
    fun encode(period: ResetPeriod): String = period.name

    fun decode(raw: String): ResetPeriod =
        runCatching { ResetPeriod.valueOf(raw.trim().uppercase()) }
            .getOrDefault(ResetPeriod.DAILY)
}

/**
 * 额度实体 <-> 领域映射。
 *
 * 只做**机械换算**，不做业务判断（"至少要绑一个应用"等规则在
 * [com.zilv.app.data.repository.UsageQuotaRepository]）。
 */
fun UsageQuotaEntity.toDomain(packageNames: Set<String> = emptySet()): UsageQuota = UsageQuota(
    id = id,
    name = name,
    limitMinutes = limitMinutes,
    resetPeriod = ResetPeriodCodec.decode(resetPeriod),
    enabled = enabled,
    createdAt = createdAt,
    packageNames = packageNames
)

fun UsageQuota.toEntity(): UsageQuotaEntity = UsageQuotaEntity(
    id = id,
    name = name,
    limitMinutes = limitMinutes,
    resetPeriod = ResetPeriodCodec.encode(resetPeriod),
    enabled = enabled,
    createdAt = createdAt
)

/** 由 (额度 id, 包名) 造一条绑定关系。 */
fun quotaApp(quotaId: Long, packageName: String): UsageQuotaAppEntity =
    UsageQuotaAppEntity(quotaId = quotaId, packageName = packageName)
