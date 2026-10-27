package com.zilv.app.ui.backup

import com.zilv.app.data.repository.PlanRepository
import com.zilv.app.domain.plan.Plan

/**
 * 「明日规划」的备份恢复（v2.5 的 A4 接线）。
 *
 * ## 为什么这段逻辑要单独拎出来
 *
 * 恢复规划这件事有几条**必须一起成立**的约束，而它们全部由
 * [PlanRepository] 把关：
 *  - 规划只能添加**明天**的
 *  - 不能跨 0 点（`endMinute > startMinute`）
 *  - 起止不能相同（`TimeWindow(x, x)` 是**全天 1440 分钟**而不是 0）
 *
 * 所以这里**绝不能**直接往 `plans` 表塞数据（那会绕过全部三条），
 * 只能逐条调 `addPlan`。而备份快照里天然带着过去的与更远的规划 ——
 * "被拒绝"因此是**可预期的正常结果**，必须逐条 `runCatching` 并计数，
 * 而不是让整次恢复失败（用户会以为整个备份坏了）。
 *
 * 放在这个独立对象里而不是塞在 [BackupViewModel] 的方法体里，是为了让它
 * 能在**纯 JVM 单测**里被直接覆盖：`BackupViewModel` 还依赖
 * `SettingsRepository`（需要 Android 的 DataStore / Context），
 * 而这段逻辑本身一点也不需要。
 */
object PlanImport {

    /**
     * 把快照里的规划尽力恢复到 [planRepository]。
     *
     * @return **真正落库**的条数（被拒绝的不计入）
     *
     * 逐条独立的理由：一份备份里通常既有历史规划（过去的日期，按产品规则
     * 永远加不回来）也有"明天"的那一条。前者被拒绝不该影响后者落库，
     * 更不该让整次恢复报失败 —— 用户要的是"能救回来的都救回来"。
     */
    suspend fun restore(plans: List<Plan>, planRepository: PlanRepository): Int {
        var imported = 0
        plans.forEach { plan ->
            runCatching {
                planRepository.addPlan(
                    name = plan.name,
                    date = plan.date,
                    startMinute = plan.startMinute,
                    endMinute = plan.endMinute
                )
            }.onSuccess { imported++ }
        }
        return imported
    }
}
