package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.PlanDao
import com.zilv.app.data.mapper.toDomain
import com.zilv.app.data.mapper.toEntity
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.plan.Plan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「明日规划」的数据入口。
 *
 * 用户要求："添加明日规划界面，该界面**只能添加明天的规划**。自定义规划名称，
 * 自定义规划的开始和结束时间，规划时间内锁机，**解锁条件仅为到点解锁**，
 * 规划**不能跨 0 点**。日历上可以看见当天的规划。"
 *
 * ## 校验为什么必须在这里，而不是只靠界面
 *
 * 界面挡只能挡住看得见的入口（将来的快捷添加、备份导入、自动化都会绕过它），
 * 而且界面挡不住"今天过了 0 点、昨天填好的表单被提交"这类时序问题。
 * 放在仓库层的第三个理由最实在：**它能被单元测试直接覆盖**
 * （见 `PlanRepositoryTest`）。这与 [LockRuleRepository.ensureRuleEditable]
 * 的做法一致 —— 业务规则拒绝时抛**独立的异常类型**，
 * 让上层能区分"业务拒绝"（给用户一句人话）与"程序出错"（才需要上报）。
 *
 * ## 时间从注入了 [Clock] 的"今天/明天"来
 *
 * 与 [HabitRepository] / [DayNoteRepository] 口径一致：单测里把时钟固定住，
 * "明天"就是确定的，校验结果因此完全可复现。
 */
class PlanRepository(
    private val planDao: PlanDao,
    private val clock: Clock = Clock.systemDefaultZone()
) : PlanRuleSource {

    fun now(): LocalDateTime = LocalDateTime.now(clock)

    fun today(): LocalDate = LocalDate.now(clock)

    /** 唯一被允许新增规划的那一天。 */
    fun tomorrow(): LocalDate = today().plusDays(1)

    // ------------------------------------------------------------------ 读

    fun observeForDay(date: LocalDate): Flow<List<Plan>> =
        planDao.observeForDay(date.toEpochDay()).map { list -> list.map { it.toDomain() } }

    suspend fun getForDay(date: LocalDate): List<Plan> =
        planDao.getForDay(date.toEpochDay()).map { it.toDomain() }

    fun observeAll(): Flow<List<Plan>> =
        planDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAll(): List<Plan> = planDao.getAll().map { it.toDomain() }

    suspend fun getById(id: Long): Plan? = planDao.getById(id)?.toDomain()

    /**
     * 有规划的日期集合 —— 日历标记只需要这个集合
     * （见 [com.zilv.app.domain.records.MonthCalendar.build] 的 `planDates`）。
     */
    fun observePlanDates(): Flow<Set<LocalDate>> =
        planDao.observeEpochDays().map { days -> days.mapTo(HashSet()) { LocalDate.ofEpochDay(it) } }

    suspend fun planDates(): Set<LocalDate> =
        planDao.epochDays().mapTo(HashSet()) { LocalDate.ofEpochDay(it) }

    // ------------------------------------------------------------------ 写

    /**
     * 新增一条规划，返回新 id。
     *
     * @param epochDay 规划生效的那一天。**必须正好是"明天"**，否则抛
     *        [PlanRejectedException] —— 这是"只能添加明天的规划"在仓库层的落地。
     * @param startMinute 当天第几分钟，含
     * @param endMinute 当天第几分钟，不含（半开区间，所以 [endMinute] > [startMinute]）
     * @throws PlanRejectedException 不是明天 / 名称为空 / 时间不合法
     */
    suspend fun addPlan(
        name: String,
        epochDay: Long,
        startMinute: Int,
        endMinute: Int
    ): Long {
        val trimmed = name.trim()
        val tomorrow = tomorrow()
        if (epochDay != tomorrow.toEpochDay()) {
            throw PlanRejectedException(
                "只能添加明天的规划：今天是 ${today()}，明天是 $tomorrow，" +
                    "但收到的是 ${describeDay(epochDay)}。"
            )
        }
        if (trimmed.isEmpty()) {
            throw PlanRejectedException("规划名称不能为空。")
        }
        ensureSameDayWindow(startMinute, endMinute)

        return planDao.insert(
            Plan(
                name = trimmed,
                date = tomorrow,
                startMinute = startMinute,
                endMinute = endMinute,
                createdAt = clock.millis()
            ).toEntity()
        )
    }

    /** [Plan.date] 形态的便捷重载，规则与 [addPlan] 完全相同。 */
    suspend fun addPlan(
        name: String,
        date: LocalDate,
        startMinute: Int,
        endMinute: Int
    ): Long = addPlan(name, date.toEpochDay(), startMinute, endMinute)

    /**
     * 修改一条规划。
     *
     * 除了时间/名称校验，还有两条守卫：
     *  - **不能改到已经过去的日期**（过去的规划只该被查看或删除）
     *  - **正在进行中的规划不能改**：否则用户在锁机时把结束时间改到一分钟后就等于解锁，
     *    这与 [LockRuleRepository] 的"锁机期间禁止改动规则"是同一条产品硬要求
     *
     * @throws PlanRejectedException 规划不存在 / 不合法 / 正在生效
     */
    suspend fun update(plan: Plan) {
        if (plan.id == 0L) throw PlanRejectedException("要修改的规划必须有有效的 id。")
        val existing = planDao.getById(plan.id)
            ?: throw PlanRejectedException("要修改的规划不存在（id=${plan.id}）。")
        ensureEditable(existing.toDomain(), action = "修改")

        val trimmed = plan.name.trim()
        if (trimmed.isEmpty()) throw PlanRejectedException("规划名称不能为空。")
        ensureSameDayWindow(plan.startMinute, plan.endMinute)
        if (plan.date.isBefore(today())) {
            throw PlanRejectedException("不能把规划改到已经过去的日期（${plan.date}）。")
        }

        planDao.update(plan.copy(name = trimmed).toEntity())
    }

    /**
     * 删除一条规划。
     *
     * 正在进行中的规划**不能删** —— 删掉它等于提前解锁（见 [update]）。
     * id 不存在时是空操作（与 `DELETE` 的语义一致，重复点删除不该报错）。
     *
     * @throws PlanRejectedException 规划正在生效
     */
    suspend fun delete(id: Long) {
        val existing = planDao.getById(id) ?: return
        ensureEditable(existing.toDomain(), action = "删除")
        planDao.delete(id)
    }

    // ---------------------------------------------------------- 锁机（运行时）

    /**
     * 某一天要参与锁机调度的规则 —— 由当天的规划派生（**不落库**）。
     *
     * 锁机链路统一读 [LockRule]，所以拦截/调度侧只要把这里返回的规则
     * 并进规则列表，规划时间内就会自动锁机（见 `Plan.toLockRule()`：
     * `allowCheckInUnlock = false`，只能到点解锁）。
     *
     * 本方法同时是 [PlanRuleSource] 的实现 —— [LockRuleRepository] 通过那个
     * **只有两个方法的接口**取规划规则，因此不会与这里互引。
     */
    override suspend fun rulesFor(date: LocalDate): List<LockRule> =
        getForDay(date).filter { it.isValid }.mapNotNull { plan ->
            // 兜底：读路径**不抛异常**（见 Plan.isValid 的 KDoc）。
            // 正常情况下上游的 isValid 已经把非法规划挡掉了，
            // 但 plans 表是历史数据的落点，一条手改出来的脏记录
            // 不该让 getEnabledRules() 整条链路挂掉。
            runCatching { plan.toLockRule() }.getOrNull()
        }

    /**
     * [at] 时刻**正在进行中**的规划规则。
     *
     * 供前台服务/拦截服务判断"现在该不该因为规划锁机"，也供
     * [LockRuleRepository.currentStatus] 把规划并进状态判定。
     */
    override suspend fun activeRules(at: LocalDateTime): List<LockRule> {
        val date = at.toLocalDate()
        val minuteOfDay = at.hour * 60 + at.minute
        return getForDay(date)
            .filter { it.isValid && it.covers(date, minuteOfDay) }
            .mapNotNull { runCatching { it.toLockRule() }.getOrNull() }
    }

    /**
     * [lockRulesFor] 的旧名字，保留给既有调用点与测试。
     *
     * 语义与 [rulesFor] 完全相同；[PlanRuleSource] 里的方法名是接口契约，
     * 这里这个名字是历史包袱，重命名不改变任何行为。
     */
    suspend fun lockRulesFor(date: LocalDate): List<LockRule> = rulesFor(date)

    /** [activeRules] 的旧名字，保留给既有调用点与测试。 */
    suspend fun activeLockRules(at: LocalDateTime = now()): List<LockRule> = activeRules(at)

    // ---------------------------------------------------------------- 内部

    /**
     * 起止时间必须落在同一天内、且结束晚于开始。
     *
     * 起止相同被单独拦下并给出专门的原因：`TimeWindow(x, x)` 表示**全天 1440 分钟**，
     * 而不是 0 分钟 —— 放过去就等于把"10:00-10:00 的一次会议"变成整天锁机。
     */
    private fun ensureSameDayWindow(startMinute: Int, endMinute: Int) {
        if (startMinute !in Plan.MINUTE_MIN..Plan.MINUTE_MAX ||
            endMinute !in Plan.MINUTE_MIN..Plan.MINUTE_MAX
        ) {
            throw PlanRejectedException(
                "规划的起止时间必须在 00:00..23:59 之间（当天第 0..${Plan.MINUTE_MAX} 分钟），" +
                    "实际 ${startMinute}..${endMinute}。"
            )
        }
        if (startMinute == endMinute) {
            throw PlanRejectedException(
                "规划的开始和结束时间不能相同（${hhmm(startMinute)}）：" +
                    "本项目里「起止相同」表示全天锁机，而不是 0 分钟。"
            )
        }
        if (endMinute < startMinute) {
            throw PlanRejectedException(
                "规划不能跨 0 点：结束时间必须晚于开始时间（当前 ${hhmm(startMinute)}-${hhmm(endMinute)}）。"
            )
        }
    }

    /**
     * 进行中的规划禁止改动。
     *
     * 与 [LockRuleRepository] 的 `ensureRuleEditable` 同一条理由：
     * 规划的锁机"解锁条件仅为到点解锁"，如果在生效期间能改时间或删掉它，
     * 那用户在锁机时点一下就等于解锁 —— 整套拦截形同虚设。
     */
    private fun ensureEditable(plan: Plan, action: String) {
        val at = now()
        if (plan.covers(at.toLocalDate(), at.hour * 60 + at.minute)) {
            throw PlanRejectedException(
                "规划「${plan.name}」正在进行中，无法$action。它的解锁条件只有到点解锁，" +
                    "请等本次规划结束。"
            )
        }
    }

    private fun describeDay(epochDay: Long): String =
        runCatching { LocalDate.ofEpochDay(epochDay).toString() }
            .getOrElse { "无法识别的日期（epochDay=$epochDay）" }

    private fun hhmm(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

/**
 * 规划被业务规则拒绝（不是明天 / 跨 0 点 / 正在进行中 …）。
 *
 * 单独定义异常类型而不是复用 [IllegalStateException] / [IllegalArgumentException]，
 * 与 [RuleLockedException] 的理由相同：上层要能精确区分
 * "业务规则拒绝"（给用户一句人话）与"程序出错"（才需要上报）。
 */
class PlanRejectedException(message: String) : IllegalStateException(message)
