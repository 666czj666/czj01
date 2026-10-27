package com.zilv.app.ui.backup

import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.domain.quota.UsageQuota

/**
 * 「软件使用时间限时」额度的备份恢复（v2.7）。
 *
 * ## 为什么这段逻辑要单独拎出来
 *
 * 与 [PlanImport] 完全同一理由：
 *  - 恢复额度必须走 [UsageQuotaRepository.add]（它会做"名称非空 /
 *    时长在 0..[UsageQuota.MAX_LIMIT_MINUTES]（744 小时）/ 至少绑一个应用"的校验）。
 *    直接往 `usage_quotas`
 *    与 `usage_quota_apps` 塞数据会绕过全部三条；
 *  - 快照里可能带着**不合法**的额度（手改过的备份文件、
 *    或者由更高版本写出的新形态），"被拒绝"是**可预期的正常结果**，
 *    必须逐条 `runCatching` 并计数，而不是让整次恢复失败
 *   （用户会以为整个备份坏了）。
 *
 * 放在独立对象里而不是塞在 `BackupViewModel` 的方法体里，是为了让它能在
 * **纯 JVM 单测**里被直接覆盖（`BackupViewModel` 还依赖 `SettingsRepository`，
 * 那需要 Android 的 DataStore / Context）。
 *
 * ## 为什么一律 `id = 0`
 *
 * 恢复是**追加**（见备份页的说明）：额度必须作为新记录插入。
 * 沿用备份里的 id 会让"同一个备份恢复两次"撞主键，
 * 也会和用户当前库里已有的额度打架。
 */
object QuotaImport {

    /**
     * 一次恢复的结果。
     *
     * ## 为什么不是只返回一个 Int
     *
     * 上一版只返回"真正落库的条数"，而本轮起 0 分钟额度会被**修好**
     * 而不是**拒绝**（见 [restore] 的说明）—— 于是"落库了几条"这个数字
     * 再也说不清发生了什么：一条被改过时长的额度也是"落库了一条"。
     * 把 [repaired] 单独报出来，界面才可能告诉用户
     * "其中 N 个 0 分钟额度被改成 1 分钟"。
     *
     * @property imported **真正落库**的条数（被拒绝的不计入）
     * @property repaired 其中有多少条是因为时长是 0 而被夹到 1 分钟的
     */
    data class Result(
        val imported: Int,
        val repaired: Int
    )

    /**
     * 把快照里的额度尽力恢复到 [quotaRepository]。
     *
     * ## v2.11 本轮：0 分钟额度**夹到 1 分钟**，而不是原样搬回来
     *
     * 上一版这里显式传 `allowZeroLimit = true`，把旧备份里的 0 分钟额度
     * 原样搬回来 —— 理由是"恢复是把用户本来就有的配置搬回来，不是新建"。
     * 那个理由在用户本轮的决定面前不再成立：用户看过"0 分钟额度会被永久
     * 冻结"的风险说明后明确答复"**不予许设置成 0 分钟**"，即 0 从根本上
     * 就不是一个合法配置。留一条恢复路径能把 0 搬回来，等于给这个
     * 非法配置留了唯一一个后门，恢复出来的还是一条僵尸额度。
     *
     * 所以这里改成 **`coerceAtLeast(MIN_CREATABLE_LIMIT_MINUTES)`**：
     *  - **夹到 1，而不是跳过整条**：用户的数据不该因为一个字段非法就整条
     *    丢掉。额度行上还挂着名称、刷新周期、启用状态、绑定的一串应用 ——
     *    那些都是用户亲手配的，丢掉它们比"把 0 改成 1 分钟"的损失大得多
     *    （而且和本项目"旧备份必须仍能读"的一贯取向一致）
     *  - 1 分钟是**最接近 0 的合法值**：用户当初设 0 的意图是"一打开就拦"，
     *    1 分钟是能表达这个意图的最严格合法值（用满 1 分钟就拦）
     *  - 夹过的条数记进 [Result.repaired]，界面会把这件事说出来，
     *    不做**静默**改动
     *
     * 真正的非法值（负数 / 超过 744 小时 / 名称为空 / 没绑应用）**照旧被拒绝**：
     * 那些值要么没有"最接近的合法值"可言（越界的方向是开放的），
     * 要么缺的是必需信息（名称、绑定关系），夹一下反而是在替用户编数据。
     *
     * @return [Result]：落库条数与其中被修好的条数
     */
    suspend fun restore(
        quotas: List<UsageQuota>,
        quotaRepository: UsageQuotaRepository
    ): Result {
        var imported = 0
        var repaired = 0
        quotas.forEach { quota ->
            // 0（以及任何非正的）时长在这里就被夹成 1 —— 于是下面那次 add
            // 走的是"新建至少 1 分钟"的正门，不需要任何放行开关。
            val limit = quota.limitMinutes.coerceAtLeast(UsageQuota.MIN_CREATABLE_LIMIT_MINUTES)
            if (limit != quota.limitMinutes) repaired++
            runCatching {
                quotaRepository.add(
                    name = quota.name,
                    limitMinutes = limit,
                    resetPeriod = quota.resetPeriod,
                    packageNames = quota.effectivePackages,
                    enabled = quota.enabled
                )
            }.onSuccess { imported++ }
        }
        return Result(imported = imported, repaired = repaired)
    }
}
