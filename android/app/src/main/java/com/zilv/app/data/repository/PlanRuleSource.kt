package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.PlanDao
import com.zilv.app.data.mapper.toDomain
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.plan.Plan
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「明日规划」派生的锁机规则来源。
 *
 * ## 为什么需要这层间接（A1 的决策）
 *
 * 用户要求"规划时间内锁机"必须在**运行时**真正生效，而运行时读规则只有两个入口：
 * [LockRuleRepository.getEnabledRules] 与 [LockRuleRepository.currentStatus]。
 * 把它们改成"规则表 ∪ 当天的规划"有两种做法：
 *
 *  1. **在仓库层合并**（本文件走的路）
 *  2. 在每个调用点各合一次（前台服务、无障碍服务、闹钟接收器、开机接收器、
 *     通知监听、自检 Worker、MainActivity、各 ViewModel —— 一共 8 处）
 *
 * 选 1 的理由：
 *  - **不可能漏**。做法 2 里漏掉任何一处，就会出现"界面说锁机了但拦截没生效"
 *    或者"锁机了但闹钟没设"这类只在特定路径上复现的 bug；而这 8 处分布在
 *    Service / BroadcastReceiver / Worker / Activity / ViewModel 五种组件里，
 *    没有任何编译期手段能保证它们口径一致。
 *  - **白名单与编辑守卫自动跟着走**。[LockRuleRepository] 用
 *    `currentStatus().phase` 判断"锁机期间禁止改动"，规划锁机因此**天然**
 *    获得同一条保护：规划进行中改不了规则、也改不了白名单 —— 这正是
 *    "规划只能到点解锁"在运行时的落地。
 *  - **可测**。合并逻辑落在仓库层，纯 JVM 单测直接覆盖（见
 *    `LockRuleRepositoryTest` 里新增的规划用例）。
 *
 * ## 为什么是接口，而不是直接注入 `PlanRepository`
 *
 * 接口把依赖收窄到一个只有两个方法的契约上：
 *  - [LockRuleRepository] 不再需要知道"规划"这个业务概念，只认"还有一批规则来自别处"
 *  - 单测里可以给一条**什么都不返回**的实现（[NONE]），既有测试因此完全不受影响
 *  - 依赖图保持单向：[LockRuleRepository] → [PlanRuleSource] → `plans` 表
 *
 * ## 两处实现为什么是"同源"的
 *
 * 应用里 Koin 注入的是 `PlanRepository`（它自己 `implement` 了本接口，
 * 复用同一套"读 `plans` 表 → 过滤非法 → `toLockRule()`"的逻辑）；
 * [fromPlanDao] 则是同语义的轻量适配器，供只依赖 [PlanDao] 的场合使用。
 */
interface PlanRuleSource {

    /** [date] 这一天要参与锁机调度的规划规则（不落库，真相来源始终是 `plans` 表）。 */
    suspend fun rulesFor(date: LocalDate): List<LockRule>

    /** [at] 时刻**正在进行中**的规划规则（半开区间 `[start, end)`）。 */
    suspend fun activeRules(at: LocalDateTime): List<LockRule>

    companion object {

        /**
         * 什么规划都不提供 —— [LockRuleRepository] 构造参数的默认值。
         *
         * 用它当默认值可以让**既有测试一行都不用改**（详见该参数的 KDoc），
         * 同时把"这里没有规划"表达得比 `null` 更明确。
         */
        val NONE: PlanRuleSource = object : PlanRuleSource {
            override suspend fun rulesFor(date: LocalDate): List<LockRule> = emptyList()
            override suspend fun activeRules(at: LocalDateTime): List<LockRule> = emptyList()
        }

        /**
         * 由 [PlanDao] 造一个真实来源。
         *
         * 只包一个 DAO、**不经过** `PlanRepository`，所以用在"不想引入整个仓库"
         * 的地方也不会带来任何循环依赖。
         */
        fun fromPlanDao(planDao: PlanDao): PlanRuleSource = PlanDaoRuleSource(planDao)
    }
}

/**
 * 把一条规划安全地转成运行时规则；不合法就返回 `null`。
 *
 * 本项目对"从库里读出来的脏数据"的统一策略是**不抛异常、退化处理**
 * （见 `Plan.isValid` 的 KDoc）：`plans` 表是历史数据的落点，
 * 一条手改出来的"起止相同"记录不该让整个拦截链路挂掉 ——
 * 与 `HabitFrequencyCodec` 的解码容错同一条口径。
 *
 * 正常情况下 `isValid` 为假的规划在上游就被过滤掉了，这里是**兜底**：
 * 只要有任何一条规划能让这里返回 null，那就宁可少锁一次，
 * 也不要让前台服务/无障碍服务里的 `getEnabledRules()` 抛异常。
 */
private fun Plan.toLockRuleOrNull(): LockRule? = runCatching { toLockRule() }.getOrNull()

/**
 * [PlanRuleSource] 的 DAO 适配实现。
 *
 * 与 `PlanRepository` 的规则一致：
 *  - 只取 `Plan.isValid` 的规划（脏数据不进调度）
 *  - 时间窗口是**半开区间** `[startMinute, endMinute)`
 *  - 生成的规则 `allowCheckInUnlock = false` —— 用户要求"解锁条件仅为到点解锁"
 */
private class PlanDaoRuleSource(private val planDao: PlanDao) : PlanRuleSource {

    override suspend fun rulesFor(date: LocalDate): List<LockRule> =
        plansOn(date).mapNotNull { it.toLockRuleOrNull() }

    override suspend fun activeRules(at: LocalDateTime): List<LockRule> {
        val minuteOfDay = at.hour * 60 + at.minute
        val date = at.toLocalDate()
        return plansOn(date)
            .filter { it.covers(date, minuteOfDay) }
            .mapNotNull { it.toLockRuleOrNull() }
    }

    private suspend fun plansOn(date: LocalDate) =
        planDao.getForDay(date.toEpochDay())
            .map { it.toDomain() }
            .filter { it.isValid }
}
