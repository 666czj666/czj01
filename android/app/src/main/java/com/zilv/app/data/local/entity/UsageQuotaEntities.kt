package com.zilv.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「软件使用时间限时」额度表（`usage_quotas`）。
 *
 * 用户要求："自定义一个使用时间额度，自定义一个或多个（共用）软件使用该时间额度。
 * 自定义时间额度刷新间隔（每天 0 点，每周一，每月 1 号）到点刷新（旧额度不可保留）。
 * 时间额度为零时禁用指定软件。"
 *
 * 设计取舍：
 *  - `reset_period` 存枚举名（`DAILY` / `WEEKLY` / `MONTHLY`），与
 *    `lock_rules` 的 `unlock_mode` 同一口径：**不用序号**，避免枚举顺序一变
 *    旧库就读错。
 *  - `limit_minutes` 存**分钟**而不是毫秒：用户输入的就是分钟，
 *    存分钟让"库里的值"和"界面上的值"逐字对应，排查问题时不用换算。
 *  - `enabled` 与用户要求之外的用途无关，只表示"这条额度是否生效"：
 *    停用的额度完全不参与拦截，但它的配置与绑定关系都保留。
 *  - 绑定关系在 [UsageQuotaAppEntity]（关联表），不在这里塞一个逗号分隔的字符串 ——
 *    那样既没法建索引，删单个应用也要整行重写。
 */
@Entity(
    tableName = "usage_quotas",
    indices = [Index("enabled")]
)
data class UsageQuotaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "limit_minutes") val limitMinutes: Int,
    /** `DAILY` / `WEEKLY` / `MONTHLY` */
    @ColumnInfo(name = "reset_period") val resetPeriod: String,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long
)

/**
 * 额度 ↔ 应用绑定（`usage_quota_apps`）。
 *
 * 复合主键 `(quota_id, package_name)` 保证"同一个应用不会在同一个额度里出现两次"，
 * 也让"给额度加一个应用"是幂等的（`INSERT OR REPLACE`）。
 *
 * 不建外键：本项目全部表都不建外键（见其它实体），删除额度时由
 * `UsageQuotaDao.deleteAppsOf` 显式清理绑定，理由与 `rule_allow_apps` 一致 ——
 * Room 的外键会强制开启 `foreign_keys` pragma 并在迁移时带来额外约束，
 * 而这里的关系简单到用两条 DELETE 就能保证一致。
 */
@Entity(
    tableName = "usage_quota_apps",
    primaryKeys = ["quota_id", "package_name"],
    indices = [Index("package_name")]
)
data class UsageQuotaAppEntity(
    @ColumnInfo(name = "quota_id") val quotaId: Long,
    @ColumnInfo(name = "package_name") val packageName: String
)
