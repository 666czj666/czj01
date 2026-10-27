package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.AllowAppDao
import com.zilv.app.data.local.dao.CheckInDao
import com.zilv.app.data.local.dao.LockRuleDao
import com.zilv.app.data.local.dao.RuleAllowAppDao
import com.zilv.app.data.local.dao.RuleCheckInDao
import com.zilv.app.data.local.entity.AllowAppEntity
import com.zilv.app.data.local.entity.RuleAllowAppEntity
import com.zilv.app.data.mapper.AllowApp
import com.zilv.app.data.mapper.AllowScope
import com.zilv.app.data.mapper.toDomain
import com.zilv.app.data.mapper.toEntity
import com.zilv.app.domain.lock.InstantLock
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.WindowScheduler
import com.zilv.app.domain.lock.WindowScheduler.LockStatus
import com.zilv.app.domain.lock.isInstantLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDateTime

/**
 * 锁机规则与白名单的数据入口。
 *
 * 与 [HabitRepository] 一样注入 [Clock]，保证单元测试可确定性地验证。
 */
class LockRuleRepository(
    private val lockRuleDao: LockRuleDao,
    private val allowAppDao: AllowAppDao,
    private val ruleAllowAppDao: RuleAllowAppDao,
    private val ruleCheckInDao: RuleCheckInDao,
    /**
     * 习惯打卡表。
     *
     * v2.2 新增：用户要求"解锁方法 = 打卡（**任何打卡方法都可以**）"，
     * 所以习惯上点的打卡（`check_ins`）也必须能解锁锁机 ——
     * 见 [currentStatus]。
     */
    private val checkInDao: CheckInDao,
    private val clock: Clock = Clock.systemDefaultZone(),
    /**
     * 「明日规划」派生的规则来源（v2.5 接线）。
     *
     * 用户要求"规划时间内锁机"，而运行时读规则只有 [getEnabledRules] 与
     * [currentStatus] 两个入口；这两处**必须**把当天的规划并进来，否则
     * "领域层成立、运行时没接上"。
     *
     * ## 为什么这个参数在 [clock] **之后**
     *
     * 既有单测是**按位置**传参的（`..., habitCheckInDao, clock`）。把它放在
     * 中间会让那 8 个调用点全部编译失败，而它们的语义其实一字没变；
     * 放在末尾 + 默认值，既有的调用点因此**一行都不用改**。
     *
     * ## 为什么默认值是"什么都不提供"
     *
     * 这一层间接刻意做成可选的：
     *  - 应用里由 Koin 传 `PlanRepository`（它自己实现了本接口，真实来源）
     *  - 不传时行为与加这个参数之前**逐字节相同**，既有断言全部保持有效
     *    （新行为由 `LockRuleRepositoryTest` 里新增的规划用例单独覆盖）
     *
     * 选"在仓库层合并"而不是"每个调用点各合一次"的理由写在 [PlanRuleSource] 上。
     */
    private val planRuleSource: PlanRuleSource = PlanRuleSource.NONE
) {

    fun now(): LocalDateTime = LocalDateTime.now(clock)

    // ------------------------------------------------------------------ 规则

    fun observeRules(): Flow<List<LockRule>> =
        lockRuleDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getAllRules(): List<LockRule> = lockRuleDao.getAll().map { it.toDomain() }

    /**
     * 当前启用的锁机规则 = `lock_rules` 表里的启用项 **∪ 今明两天的规划**。
     *
     * ## 为什么是"今明两天"而不是"只有今天"
     *
     * 这个方法的调用点都在问同一件事："从现在起，还有哪些锁机计划要发生"：
     *  - [com.zilv.app.service.AlarmScheduler.reschedule] 要据此注册**下一个**触发点
     *  - [com.zilv.app.service.LockAccessibilityService] 要据此判断"现在是否该拦"
     *
     * 规划是**昨天做、今天生效**的，所以：
     *  - **今天**的规划必须在列表里 —— 它是"现在"的真相来源
     *  - **明天**的规划也必须提前进来 —— 它的窗口开始前 15 分钟要发预警、
     *    开始那一刻要注册精确闹钟。只给"此刻正在生效"的规划的话，一条
     *    09:00 的规划在 08:00 时还不存在，闹钟就没有任何理由在 08:45 醒来，
     *    到点也不会锁机。
     *
     * 传进来的规则**多**是安全的：调度器（[WindowScheduler]）与闹钟规划
     * （[com.zilv.app.domain.lock.AlarmPlanner]）都只按"窗口是否落在 [at] 之后
     * 或之内"自己筛一遍，多给的规则不会造成误锁。
     *
     * 规划规则**不落库**（`id = 0`，来源由 `planId` 标记），真相来源始终是
     * `plans` 表。调用点（前台服务、无障碍服务、闹钟接收器、开机接收器、
     * 通知监听、自检 Worker、MainActivity、各 ViewModel）因此不需要各自
     * 合并一次，理由见 [PlanRuleSource]。
     */
    suspend fun getEnabledRules(): List<LockRule> {
        val dbRules = lockRuleDao.getEnabled().map { it.toDomain() }
        val today = now().toLocalDate()
        return dbRules +
            planRuleSource.rulesFor(today) +
            planRuleSource.rulesFor(today.plusDays(1))
    }

    suspend fun getRule(id: Long): LockRule? = lockRuleDao.getById(id)?.toDomain()

    // ------------------------------------------------------ 按习惯操作（v2.0）

    /**
     * 某个习惯名下的锁机配置；没配置过返回 null。
     *
     * v2.0 起锁机归属于习惯（用户要求"把锁机功能和习惯功能结合，删除单独锁机"）。
     */
    suspend fun getRuleForHabit(habitId: Long): LockRule? =
        lockRuleDao.getByHabit(habitId)?.toDomain()

    /**
     * 新建或更新某个习惯的锁机配置。
     *
     * 用 habitId 反查已有规则并复用它的 id —— 这样在习惯编辑页反复保存
     * 不会堆积出多条配置。
     *
     * @param allowDuringLock **显式**放行锁机守卫（默认 false，即行为与以前完全一致）。
     *        只有「添加」页那条路径会传 `true`，详见 [ensureRuleEditable] 的 KDoc。
     *        ⚠️ 即使在 `true` 的前提下，"这条习惯的锁机此刻正在生效"时**仍然会被拒绝** ——
     *        否则用户在锁机时把这条配置改掉就等于直接解锁。
     */
    suspend fun saveForHabit(
        habitId: Long,
        rule: LockRule,
        allowDuringLock: Boolean = false
    ): Long {
        val existingId = lockRuleDao.getByHabit(habitId)?.id ?: 0L
        return saveRule(
            rule.copy(id = existingId, habitId = habitId),
            allowDuringLock = allowDuringLock
        )
    }

    /**
     * 关闭某个习惯的锁机：删掉它的配置与规则级白名单。
     *
     * 同样受"锁机期间禁止改动"约束 —— 否则用户在锁机时关掉习惯的锁机就等于解锁。
     * 没有配置时是空操作。
     *
     * @param allowDuringLock 同 [saveForHabit]。「添加」页里删掉一条**并此刻没有
     *        在锁机**的习惯配置是允许的（那跟正在生效的那把锁无关）；
     *        但只要这条习惯的锁正在生效，无论从哪进来都拒绝。
     */
    suspend fun deleteForHabit(habitId: Long, allowDuringLock: Boolean = false) {
        ensureRuleEditable("关闭锁机", allowDuringLock = allowDuringLock, habitId = habitId)
        val existing = lockRuleDao.getByHabit(habitId) ?: return
        ruleAllowAppDao.deleteForRule(existing.id)
        lockRuleDao.deleteByHabit(habitId)
    }

    /**
     * 锁机期间禁止改动规则与白名单。
     *
     * **这是产品上的硬要求**：如果锁机时还能编辑/停用规则，
     * 那用户在锁机时点一下"停用"就直接解锁了 —— 整套拦截形同虚设。
     *
     * 守卫放在**仓库层**而不是只挡界面：
     *  - 界面挡只能挡住看得见的入口，其余调用点（未来的快捷开关、自动化等）会绕过
     *  - 放在这里可以被单元测试直接覆盖
     *
     * 放行的时机：`IDLE`（本来就空闲）与 `WARN`（还没真正锁上，允许抓紧改）。
     *
     * ## `allowDuringLock`：为什么"添加"页需要一道显式放行
     *
     * 用户要求"锁机时间不能控制添加界面"—— 也就是锁机期间照样要能新建习惯、
     * 新建明日规划、新建时间限额。但**不能再退回到"锁机时改一下规则就解锁"**
     * （上一轮专门修过这个洞）。两者的分界线只有一条：
     *
     * > 这次改动会不会让**此刻正在生效的那把锁**消失？
     *
     * 于是这个参数是**显式**的、默认 `false` 的，而且只在 `forHabitId` 指向的
     * 那条习惯**并没有在锁机**时才放行（见 [habitLockInEffect]）：
     *
     *  - 新建（还没有配置）→ 不可能关掉任何既有的锁 → 放行
     *  - 改/删一条"没启用"或"此刻不在锁机窗口内"的配置 → 与正在生效的锁无关 → 放行
     *  - 改/删**此刻正在锁机**的那条配置 → **仍然抛 [RuleLockedException]**，
     *    并给出"锁机进行中，等本次锁机结束再改这条"这类可读提示
     *
     * 刻意做成参数而不是删掉守卫：
     *  - 锁机规则「详细设置」页、白名单页、`setEnabled`、`delete` 完全不传它，
     *    行为逐字节不变（既有断言全部继续有效）
     *  - 传 `true` 的只有「添加」界面这一条路径，一眼能数清有哪些入口被放开
     *
     * @param habitId 按习惯操作时传 habitId；不传（null）时 `allowDuringLock` 无效 ——
     *        因为"这条锁是不是正在生效"必须有习惯才能判断
     */
    private suspend fun ensureRuleEditable(
        action: String,
        allowDuringLock: Boolean = false,
        habitId: Long? = null
    ) {
        if (currentStatus().phase != LockPhase.LOCKED) return
        if (allowDuringLock && habitId != null && !habitLockInEffect(habitId)) return
        throw RuleLockedException(
            "锁机进行中，无法$action。请等本次锁机结束，或先打卡解锁。"
        )
    }

    /**
     * 这条习惯的锁机配置**此刻是否真的在锁机**。
     *
     * 判据与 [currentStatus] 完全同一套机器：把**这一条规则**单独喂给
     * [WindowScheduler.statusFor]（含它自己的打卡时刻），看它是否落在
     * [LockPhase.LOCKED]。这样"没启用""不在窗口内""已经打卡解锁"三种情况
     * 都会自然返回 false，不需要在这里再写一遍窗口判断。
     *
     * 为什么不直接用 `currentStatus().rule?.habitId == habitId`：
     * 综合状态只给出**最先命中的那一条**规则，多把锁同时生效时它可能是别人。
     * [ensureRuleEditable] 要回答的是"这条习惯的锁在不在其中"，
     * 所以必须按习惯单独算一遍。
     */
    private suspend fun habitLockInEffect(habitId: Long): Boolean {
        val rule = lockRuleDao.getByHabit(habitId)?.toDomain() ?: return false
        if (!rule.enabled) return false
        return WindowScheduler.statusFor(
            rules = listOf(rule),
            at = now(),
            checkInAtByRule = mapOf(rule.id to latestCheckInAt(rule))
        ).phase == LockPhase.LOCKED
    }

    /** 新增或更新；返回规则 id。锁机期间会抛 [RuleLockedException]。 */
    suspend fun save(rule: LockRule): Long = saveRule(rule, allowDuringLock = false)
    /** [save] 的实现；`allowDuringLock` 只由 [saveForHabit] 那条路径传得进来。 */
    private suspend fun saveRule(rule: LockRule, allowDuringLock: Boolean): Long {
        require(rule.isValid) {
            "规则不合法：需要名称、至少一个时间窗口；需地点打卡解锁时必须选择打卡点"
        }
        ensureRuleEditable(
            action = if (rule.id == 0L) "新增规则" else "修改规则",
            allowDuringLock = allowDuringLock,
            habitId = rule.habitId
        )
        return if (rule.id == 0L) {
            lockRuleDao.insert(rule.toEntity())
        } else {
            lockRuleDao.update(rule.toEntity())
            rule.id
        }
    }

    /** 启用/停用。锁机期间会抛 [RuleLockedException]（防"锁机时一键停用直接解锁"）。 */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        ensureRuleEditable(if (enabled) "启用规则" else "停用规则")
        lockRuleDao.setEnabled(id, enabled)
    }

    /** 删除规则。锁机期间会抛 [RuleLockedException]。 */
    suspend fun delete(id: Long) {
        ensureRuleEditable("删除规则")
        ruleAllowAppDao.deleteForRule(id)
        lockRuleDao.delete(id)
    }

    // ------------------------------------------------------------ 即时锁机（v2.12）

    /**
     * **立刻锁机**，持续 [minutes] 分钟（0 = 到下一个整分钟解除）。
     *
     * ## 为什么可以绕过 [ensureRuleEditable] 那道守卫
     *
     * 那道守卫要防的是"**锁机期间把正在生效的那把锁改掉 / 关掉**"，
     * 而这里做的是**新建**一把即时锁：
     *  - 它一个既有规则都不碰（不读、不改、不删）
     *  - 它只会让限制**更紧**（再多一个必须熬过去的窗口）
     *
     * 所以"正在锁机时点了即时锁机"必须能成功 —— 报错才是错的。
     * 这也让"在已有锁机上再加一把"变成合法操作（两把锁各算各的窗口，
     * 后结束的那把说了算，这正是用户想要的"再锁久一点"）。
     *
     * ## 顺手清理
     *
     * 每次新建之前先清掉**已经结束**的即时锁规则（见 [purgeEndedInstantLocks]），
     * 否则每锁一次就在 `lock_rules` 里留一行，越攒越多。
     *
     * @return 新规则的 id
     */
    suspend fun startInstantLock(minutes: Int, at: LocalDateTime = now()): Long {
        purgeEndedInstantLocks(at)
        val rule = InstantLock.buildRule(at, minutes)
        require(rule.isValid) {
            "即时锁机规则不合法（这是程序错误，不是用户输入问题）：${rule.effectiveLockWindows}"
        }
        return lockRuleDao.insert(rule.toEntity())
    }

    /**
     * 清掉**已经结束**的即时锁机规则；返回清掉了几条。
     *
     * 判据是"这条规则的窗口结束时刻已经过去了"，而不是"当前阶段不是 LOCKED"——
     * 后者对一条**还没开始**的窗口（理论上不该存在）也会返回 IDLE，
     * 那就把一条有效的锁给删了。窗口结束时刻用 [WindowScheduler.instancesStartingOn]
     * 展开（归属日 = `startDate`），所以跨零点的窗口也算得对。
     */
    suspend fun purgeEndedInstantLocks(at: LocalDateTime = now()): Int {
        val ended = lockRuleDao.getAll().map { it.toDomain() }
            .filter { it.isInstantLock }
            .filter { it.instantLockEndedAt(at) }
        ended.forEach { lockRuleDao.delete(it.id) }
        return ended.size
    }

    /**
     * 即时锁机**当前是否正在生效**；以及它还剩多久（分钟）。
     *
     * 只用于给用户一句明确的回执（"已锁 N 分钟"），真相仍然是
     * [currentStatus] —— 界面上的卡片读的是它。
     */
    suspend fun instantLockStatus(at: LocalDateTime = now()): WindowScheduler.LockStatus {
        val instant = lockRuleDao.getEnabled().map { it.toDomain() }
            .filter { it.isInstantLock }
        if (instant.isEmpty()) return WindowScheduler.LockStatus.IDLE
        return WindowScheduler.statusFor(
            rules = instant,
            at = at,
            checkInAtByRule = instant.associate { it.id to latestCheckInAt(it) }
        )
    }

    /** 这条即时锁规则的窗口是不是已经走完了。 */
    private fun LockRule.instantLockEndedAt(at: LocalDateTime): Boolean {
        val date = startDate ?: return true
        val instance = WindowScheduler.instancesStartingOn(this, date).firstOrNull() ?: return true
        return !at.isBefore(instance.end)
    }

    // ---------------------------------------------------------------- 白名单

    suspend fun getAllowedApps(): List<AllowApp> =
        allowAppDao.getAll().map { it.toDomain() }

    /** 预置内置强制白名单（首次启动 / 版本升级时调用，幂等）。 */
    suspend fun ensureBuiltinAllowList(packages: List<Pair<String, String>>) {
        allowAppDao.insertAll(
            packages.map { (pkg, label) ->
                AllowAppEntity(
                    packageName = pkg,
                    label = label,
                    scope = AllowScope.BUILTIN,
                    enabled = true
                )
            }
        )
    }

    // 白名单同样受"锁机期间禁止改动"约束 ——
    // 否则用户在锁机时把要用的应用加进白名单，等于绕开拦截。
    // 注意：ensureBuiltinAllowList 刻意不加这道守卫，
    // 它由无障碍服务在连接时调用，被拦下会导致系统必需包进不了白名单。

    suspend fun setAppEnabled(packageName: String, enabled: Boolean) {
        ensureRuleEditable(if (enabled) "启用白名单项" else "停用白名单项")
        allowAppDao.setEnabled(packageName, enabled)
    }

    suspend fun addUserApp(packageName: String, label: String) {
        ensureRuleEditable("添加白名单")
        allowAppDao.upsert(
            AllowAppEntity(
                packageName = packageName,
                label = label,
                scope = AllowScope.USER,
                enabled = true
            )
        )
    }

    /**
     * 移除用户白名单项。
     *
     * @return true 表示确实删掉了一条用户项；false 表示不存在，或是**内置强制项**
     *         （内置项删不掉，这是不把手机搞坏的安全底线，见 PRD §3.4）
     */
    suspend fun removeUserApp(packageName: String): Boolean {
        ensureRuleEditable("移除白名单")
        return allowAppDao.deleteUserApp(packageName) > 0
    }

    suspend fun setRuleAllowApps(ruleId: Long, packageNames: List<String>) {
        ruleAllowAppDao.deleteForRule(ruleId)
        packageNames.forEach { ruleAllowAppDao.insert(RuleAllowAppEntity(ruleId, it)) }
    }

    /**
     * 有效白名单 = 全局启用的白名单项 ∪ 该规则级白名单项。
     *
     * PRD §3.2：「多规则同时生效时白名单取**并集**（对用户更宽松）」。
     */
    suspend fun effectiveAllowList(ruleIds: List<Long> = emptyList()): Set<String> {
        val global = allowAppDao.getEnabled().map { it.packageName }.toSet()
        val perRule = ruleIds.flatMap { ruleAllowAppDao.packageNamesFor(it) }.toSet()
        return global + perRule
    }

    // ------------------------------------------------------------------ 状态

    /**
     * 当前锁机状态。
     *
     * 「打卡解锁」的时刻会**自动从两张表读取**：
     *  - `rule_check_ins`：从锁机规则 / 打卡点流程打的卡
     *  - `check_ins`：**习惯本身**的打卡（用户在今日列表点的那一下、或地点打卡）
     *
     * 用户要求（v2.2）："锁机后的解锁方法 = 打卡（**任何打卡方法都可以**），
     * 或者过完锁机时间"。所以对一条挂在习惯上的规则，取两者中**较晚**的那个
     * 当作解锁时刻；没有 `habitId` 的旧规则只读 `rule_check_ins`。
     *
     * 这样 M5 的打卡流程与习惯打卡一落库，拦截状态就会立刻跟着变 ——
     * 无需调用方手工传参。
     *
     * ## 规划也在这里并进来（v2.5 接线）
     *
     * [at] 时刻**正在进行中**的规划会被并进这一份规则：
     *  - 进行中的规划派生出 `allowCheckInUnlock = false` 的规则，于是打卡
     *    （习惯打卡 / 规则打卡都不例外）**对它完全无效**，只能等窗口走完 ——
     *    用户要求"解锁条件仅为到点解锁"
     *  - [ensureRuleEditable] 用的是本方法，所以规划生效期间会抛出
     *    [RuleLockedException]：改规则、改白名单、删规划都做不到。
     *    否则用户在规划锁机时点一下就等于提前解锁
     *
     * @param checkInAtByRule 显式覆盖某个规则的打卡时刻（测试或特殊场景用）
     */
    suspend fun currentStatus(
        at: LocalDateTime = now(),
        checkInAtByRule: Map<Long, LocalDateTime?>? = null
    ): LockStatus {
        val rules = rulesInEffectAt(at)
        val checkIns = checkInAtByRule ?: rules.associate { rule ->
            rule.id to latestCheckInAt(rule)
        }
        return WindowScheduler.statusFor(rules, at, checkIns)
    }

    /**
     * [at] 时刻**真正参与判定**的规则集合。
     *
     * 与 [getEnabledRules] 的区别只有一处：这里只取 [at] 那一天里
     * **正在进行中**或是**即将开始（T-15 预警区间内）**的规划，
     * 而不是今明两天的全部规划。
     *
     * 为什么状态判定要用这个更小的子集，而不是 [getEnabledRules] 的全集：
     *  - 语义上"当前状态"只该由此刻生效或马上要生效的窗口决定，
     *    把明天的规划塞进判定会让 `statusFor` 的"下一个窗口"提示指错日期
     *  - 状态被 [ensureRuleEditable] 直接用于"锁机期间禁止改动"的门禁，
     *    它的输入必须尽可能小、尽可能只包含"此刻真的有约束力"的规则
     *
     * ## 为什么预警区间内的规划也必须算进来
     *
     * 这是被测试抓出来的一个真实缺陷：规划是"昨天做、今天生效"的，
     * 如果只把**已经在窗口里**的规划并进状态，那么一条 17:00 的规划
     * 在 16:50 时还不存在，[LockPhase.WARN]（用户要的"提前 15 分钟提醒"）
     * 与前台服务那条"⏳ 即将锁机"的通知都不会出现，
     * 而闹钟又恰恰要在这个时刻醒来 —— 链路是断的。
     *
     * 用"该规划下是否有 T-15 内的窗口"做判据，而不是自己再算一遍
     * 15 分钟的常量：`WARN_LEAD_MINUTES` 只有一个来源。
     */
    private suspend fun rulesInEffectAt(at: LocalDateTime): List<LockRule> {
        val active = planRuleSource.activeRules(at).map { it.id to it }.toMap()
        val warning = planRuleSource.rulesFor(at.toLocalDate())
            .filter { rule ->
                rule.id !in active &&
                    WindowScheduler.warnings(listOf(rule), at).isNotEmpty()
            }
        return lockRuleDao.getEnabled().map { it.toDomain() } + active.values + warning
    }

    /**
     * 某条规则**最近一次有效来源的打卡时刻**（本地时间）。
     *
     * 与 [currentStatus] 用同一套口径：`rule_check_ins` 与 `check_ins` 取较晚的一个。
     * 报告页判断"这次锁机是被打卡解开的、还是时间到了自己解除的"要用它。
     */
    suspend fun latestCheckInAt(rule: LockRule): LocalDateTime? {
        val fromRule = ruleCheckInDao.latestFor(rule.id)?.checkedAtMillis
        val fromHabit = rule.habitId?.let { checkInDao.latestForHabit(it)?.checkedAtMillis }
        return listOfNotNull(fromRule, fromHabit).maxOrNull()?.let { millisToLocal(it) }
    }

    /** 落库用的是 epoch 毫秒，判定用的是本地时刻 —— 统一走时钟的时区换算。 */
    private fun millisToLocal(millis: Long): LocalDateTime =
        java.time.Instant.ofEpochMilli(millis).atZone(clock.zone).toLocalDateTime()
}

/**
 * 锁机期间试图改动规则。
 *
 * 单独定义一个异常类型而不是复用 [IllegalStateException]，
 * 是为了让上层能精确区分"业务规则拒绝"与"程序出错"，
 * 前者应当给用户一句人话，后者才需要上报。
 */
class RuleLockedException(message: String) : IllegalStateException(message)