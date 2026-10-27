package com.zilv.app.data.repository

import com.zilv.app.data.local.dao.UsageQuotaDao
import com.zilv.app.data.mapper.quotaApp
import com.zilv.app.data.mapper.toDomain
import com.zilv.app.data.mapper.toEntity
import com.zilv.app.domain.quota.QuotaCalculator
import com.zilv.app.domain.quota.QuotaEnforcementSource
import com.zilv.app.domain.quota.QuotaEnforcer
import com.zilv.app.domain.quota.QuotaExhaustion
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.QuotaUsage
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import com.zilv.app.domain.quota.UsageSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDateTime

/**
 * 「软件使用时间限时」的数据入口。
 *
 * 用户要求："自定义一个使用时间额度，自定义一个或多个（共用）软件使用该时间额度。
 * 自定义时间额度刷新间隔（每天 0 点，每周一，每月 1 号）到点刷新（旧额度不可保留）。
 * 时间额度为零时禁用指定软件。"
 *
 * ⚠️ 最后那句"时间额度为零时禁用指定软件"是**早期规格**，v2.11 本轮已被用户改掉：
 * 用户看到"0 分钟额度会被永久冻结（关不掉、改不了、删不掉）"的风险说明后
 * 答复"**不予许设置成 0 分钟**" —— 0 从此不是合法配置，而是坏数据。
 * 它不参与拦截（见 [disabledPackages] 里的 `isConfigured` 过滤），
 * 想"一打开就拦"请设 1 分钟。
 *
 * ## 职责边界
 *
 * 本类只做三件事：
 *  1. **存取**额度与绑定关系（配置）
 *  2. 把 `usage_sessions` 里的原始段**换算成周期内的用量**
 *  3. 把 (额度, 用量) 喂给纯函数 [QuotaCalculator]，拿到"哪些应用该被禁用"
 *
 * "什么时候刷新""还剩多少""算不算用尽"**一行都不在这里** ——
 * 它们全在 [QuotaCalculator]，于是能被纯 JVM 单测精确钉死。
 *
 * ## 旧额度不可保留
 *
 * 统计只看 `started_at >= 本周期起点` 的会话段，**不做任何结转**。
 * 用毫秒窗口（[QuotaCalculator.usedMillisInPeriod]）而不是"按天聚合表"，
 * 是因为 `app_usage` 是按天聚合的 —— 一周/一个月的额度要按周期裁剪，
 * 用按天聚合表会引入"周期跨了几天、每天各自算多少"的二次换算，
 * 而原始段本身就是精确的起止，裁剪一次即可。
 *
 * ## 为什么实现 [QuotaEnforcementSource]
 *
 * 与 [PlanRepository] 实现 [PlanRuleSource] 同一手法：拦截服务通过一个
 * **只有一个方法的窄接口**取"当前该禁哪些包"，于是服务不需要知道
 * "额度"这个业务概念，也不会被拖进一串仓库依赖里。
 * 依赖方向保持单向：service → 接口 ← repository → dao + UsageRepository。
 */
class UsageQuotaRepository(
    private val quotaDao: UsageQuotaDao,
    /**
     * 用量的真相来源。
     *
     * 只读它的 `sessionsSince`，**不反向依赖**：`UsageRepository` 完全不知道
     * "额度"的存在，所以这里不存在任何循环。
     */
    private val usageRepository: UsageRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) : QuotaEnforcementSource {

    fun now(): LocalDateTime = LocalDateTime.now(clock)

    // ------------------------------------------------------------------ 读

    fun observeAll(): Flow<List<UsageQuota>> = quotaDao.observeAll().map { rows ->
        val apps = quotaDao.allApps().groupBy({ it.quotaId }, { it.packageName })
        rows.map { it.toDomain(apps[it.id].orEmpty().toSet()) }
    }

    suspend fun getAll(): List<UsageQuota> = assemble(quotaDao.getAll())

    suspend fun getById(id: Long): UsageQuota? =
        quotaDao.getById(id)?.toDomain(quotaDao.packagesOf(id).toSet())

    /** 启用中的额度 —— 拦截链路只关心这些。 */
    suspend fun getEnabled(): List<UsageQuota> = assemble(quotaDao.getEnabled())

    suspend fun count(): Int = quotaDao.count()

    // ------------------------------------------------------------------ 写

    /**
     * 新增一个额度，返回新 id。
     *
     * @param allowZeroLimit 允许写一条 0 分钟的额度。
     *
     *        ⚠️ **只给测试夹具用，生产代码里没有任何调用点传 true**
     *        （`UsageQuotaRepositoryTest.seedLegacyZeroQuota` 用它铺
     *        "库里本来就有的 0 分钟行"）。备份恢复**不再**走这条路 ——
     *        `QuotaImport.restore` 现在把快照里的 0 夹到 1
     *        （用户决定："不予许设置成 0 分钟"）。
     *        保留这个参数是为了让"存量脏数据"这个**数据形态**仍然可造、
     *        可测：读路径必须一直读得出来。
     * @throws QuotaRejectedException 名称为空 / 时长越界 / 没选应用 /
     *         新建 0 分钟额度（除非 [allowZeroLimit]）
     */
    suspend fun add(
        name: String,
        limitMinutes: Int,
        resetPeriod: ResetPeriod,
        packageNames: Set<String>,
        enabled: Boolean = true,
        allowZeroLimit: Boolean = false
    ): Long {
        val draft = UsageQuota(
            name = name.trim(),
            limitMinutes = limitMinutes,
            resetPeriod = resetPeriod,
            enabled = enabled,
            packageNames = packageNames.filter { it.isNotBlank() }.toSet()
        )
        ensureValid(draft)
        // v2.11（用户第 3 条）：新建 0 分钟额度会立刻把自己锁成"永久置灰"，
        // 所以从源头拦住它。放在 ensureValid 之后：先把"名称/时长越界/没选应用"
        // 这些更基础的问题报出来，用户一次只看到最该改的那一条。
        //
        // 本轮用户进一步明确"**不予许设置成 0 分钟**"：0 不是合法配置。
        // 于是这条守卫覆盖**所有写入口**（新建 / 编辑保存 / 备份恢复），
        // 只有测试夹具会显式传 allowZeroLimit 来铺存量脏数据。
        if (limitMinutes < UsageQuota.MIN_CREATABLE_LIMIT_MINUTES && !allowZeroLimit) {
            throw QuotaRejectedException(
                "时长至少 ${UsageQuota.MIN_CREATABLE_LIMIT_MINUTES} 分钟。" +
                    "0 分钟不是一个合法的额度配置 —— " +
                    "想要「一打开就拦」，请设 ${UsageQuota.MIN_CREATABLE_LIMIT_MINUTES} 分钟。"
            )
        }

        val id = quotaDao.insert(draft.copy(createdAt = clock.millis()).toEntity())
        draft.packageNames.forEach { quotaDao.insertApp(quotaApp(id, it)) }
        return id
    }

    /**
     * 修改一个额度。
     *
     * 绑定关系**整组替换**：先删后插。理由与"额度面板改应用多选"的语义一致 ——
     * 用户在界面上看到的就是最终集合，逐条 diff 只会让"取消勾选没生效"
     * 这类 bug 有机会存在。
     *
     * @throws QuotaRejectedException 额度不存在 / 不合法 /
     *         **本周期额度已用完（或已关闭）** —— 见 [ensureMutable]
     */
    suspend fun update(quota: UsageQuota) {
        if (quota.id == 0L) throw QuotaRejectedException("要修改的额度必须有有效的 id。")
        val existing = quotaDao.getById(quota.id)
            ?: throw QuotaRejectedException("要修改的额度不存在（id=${quota.id}）。")
        ensureMutable(existing.toDomain(quotaDao.packagesOf(quota.id).toSet()), action = "修改")

        val cleaned = quota.copy(
            name = quota.name.trim(),
            packageNames = quota.packageNames.filter { it.isNotBlank() }.toSet()
        )
        ensureValid(cleaned)

        quotaDao.update(cleaned.toEntity())
        quotaDao.deleteAppsOf(cleaned.id)
        cleaned.packageNames.forEach { quotaDao.insertApp(quotaApp(cleaned.id, it)) }
    }

    /**
     * 启用 / 停用。停用的额度不参与拦截，但配置与绑定关系都留着。
     *
     * ## v2.11（用户第 3 条规则 2）：**停用**也要过守卫
     *
     * "目的是防止'额度用完就把额度关掉 / 删掉'来绕过限制。" ——
     * 关掉（停用）正是那条绕过的第一步，所以它必须和编辑/删除一起挡。
     *
     * ## v2.12（Bug 2）：守卫必须**按方向**区分，不能一刀切
     *
     * 用户原话：「**「打开（启用）」方向必须永远可点**，无论当前是什么状态；
     * 「关闭」方向在冻结时仍应被拒（那才是防绕过的本意）。
     * 仓库层 `setEnabled` 的守卫也要按这个方向区分，不要一刀切。」
     *
     * 判据就是领域层的纯函数 [UsageQuota.allowsEnabledChange]：
     *  - `enabled == true`（打开）→ **恒放行**。往"更严"的方向走，
     *    而且它是"已关闭的额度怎么改回来"的**唯一出口** ——
     *    堵死它就会让一份被关掉的额度永远开不回来（v2.11 的真实 bug）。
     *  - `enabled == false`（关闭）→ 走 [ensureMutable] 那道冻结守卫。
     *
     * 打开方向**先短路**（不查用量）：既省一次统计，也保证它不可能因为
     * "用量算不出来"这类无关原因而被拒。
     *
     * @throws QuotaRejectedException 额度已用尽时试图**停用**它
     */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val existing = quotaDao.getById(id) ?: return
        if (!enabled) {
            // 只有"关掉"这个方向受守卫；"打开"永远允许（见上面 KDoc）
            val domain = existing.toDomain(quotaDao.packagesOf(id).toSet())
            ensureMutable(domain, action = "关闭")
        }
        quotaDao.update(existing.copy(enabled = enabled))
    }

    /**
     * 删除一个额度，连同它的绑定关系。
     *
     * ## v2.11（用户第 3 条规则 2）：已用尽的额度**删不掉**
     *
     * 删掉一份用尽的额度 = 重新获得那份时长，这正是要堵的绕过路径。
     *
     * ⚠️ 与旧行为的差异：以前 id 不存在时是空操作（`DELETE` 语义），
     * 现在**存在但不允许删**时会抛 [QuotaRejectedException]。
     * id 不存在**仍然**是空操作 —— 重复点删除不该报错。
     */
    suspend fun delete(id: Long) {
        val existing = quotaDao.getById(id) ?: return
        ensureMutable(
            existing.toDomain(quotaDao.packagesOf(id).toSet()),
            action = "删除"
        )
        quotaDao.delete(id)
        quotaDao.deleteAppsOf(id)
    }

    // ------------------------------------------------------------------ 用量

    /** 某个额度在**本周期内**的已用毫秒（原始精度，判定用它）。 */
    suspend fun usedMillisInPeriod(quota: UsageQuota, at: LocalDateTime = now()): Long =
        usedMillisInPeriod(
            packages = quota.effectivePackages,
            period = quota.resetPeriod,
            at = at
        )

    /** [usedMillisInPeriod] 的按包名重载（拦截侧走这条）。 */
    suspend fun usedMillisInPeriod(
        packages: Set<String>,
        period: ResetPeriod,
        at: LocalDateTime = now()
    ): Long {
        if (packages.isEmpty()) return 0L
        return QuotaCalculator.totalInWindow(
            usage = sessionsInPeriod(packages, period, at),
            packageNames = packages,
            windowStartMillis = QuotaCalculator.epochMillis(QuotaCalculator.periodStart(period, at)),
            windowEndMillis = QuotaCalculator.epochMillis(at)
        )
    }

    /**
     * 某额度本周期内**所有涉及应用**的原始使用段。
     *
     * ⚠️ 用 `sessionsInWindow(周期起点, now)`（**区间相交**）而不是
     * `started_at >= 周期起点`：
     * 一段使用可能**开始于**周期起点之前（23:30 用到 00:30），
     * 只按开始时间过滤会整整漏掉它落在本周期内的那 30 分钟 ——
     * 而这正是"旧额度不可保留"最需要算对的边界场景。
     * `usage_sessions` 上有 `started_at` 索引，这个区间查询走索引。
     */
    private suspend fun sessionsInPeriod(
        packages: Set<String>,
        period: ResetPeriod,
        at: LocalDateTime
    ): List<UsageSegment> {
        if (packages.isEmpty()) return emptyList()
        val fromMillis = QuotaCalculator.epochMillis(QuotaCalculator.periodStart(period, at))
        val toMillis = QuotaCalculator.epochMillis(at)
        return usageRepository.sessionsInWindow(fromMillis, toMillis)
            .map { UsageSegment(it.packageName, it.startedAtMillis, it.endedAtMillis) }
    }

    /**
     * 一个额度的用量汇总，界面上显示"已用多久 / 还剩多久"。
     *
     * 已用分钟数**向上取整**（见 [QuotaCalculator.usedMinutes]）：
     * 用了 30 秒显示"已用 1 分钟"，与判定口径一致，不会出现
     * "界面说还剩 1 分钟，实际已经用尽"这种最让人困惑的偏差。
     */
    suspend fun usageOf(quota: UsageQuota, at: LocalDateTime = now()): QuotaUsage {
        // 上限为 0 的存量坏数据不需要查库：它的结果与用量无关。
        // ⚠️ 这里返回 exhausted = true 是**算术事实**（可用 0 分钟，怎么算都用满），
        // 不是"它该被冻结"的结论 —— 本轮起"0 分钟"的对外结论是
        // QuotaStatus.INVALID（【配置无效】）、不冻结、也不参与拦截，
        // 由 QuotaStatus.of 与 disabledPackages 的 isConfigured 过滤负责表达。
        if (quota.limitMinutes <= 0) {
            return QuotaUsage(
                quotaId = quota.id,
                usedMinutes = 0,
                remainingMinutes = 0,
                exhausted = true
            )
        }
        val usedMillis = usedMillisInPeriod(quota, at)
        val usedMinutes = QuotaCalculator.usedMinutes(usedMillis)
        return QuotaUsage(
            quotaId = quota.id,
            usedMinutes = usedMinutes,
            remainingMinutes = QuotaCalculator.remainingMinutes(quota.limitMinutes, usedMinutes),
            exhausted = QuotaCalculator.isExhausted(usedMillis, quota.limitMillis)
        )
    }

    /** 一次算完所有额度（列表页用，避免 N 次查库）。 */
    suspend fun usageOfAll(at: LocalDateTime = now()): Map<Long, QuotaUsage> {
        val quotas = getAll()
        if (quotas.isEmpty()) return emptyMap()

        // 一次查库覆盖全部额度：窗口取"所有额度里最早的周期起点 → now"。
        // 各额度再用自己的周期起点裁剪（totalInWindow），结果与逐个查库相同
        // （对单个额度而言，`[最早起点, now]` 里的段与 `[它自己的起点, now]`
        //  里的段裁剪后完全一致 —— 多出来的部分都被裁掉了）。
        val windowStart = quotas.minOf {
            QuotaCalculator.epochMillis(QuotaCalculator.periodStart(it.resetPeriod, at))
        }
        val windowEnd = QuotaCalculator.epochMillis(at)
        val usage = usageRepository.sessionsInWindow(windowStart, windowEnd)
            .map { UsageSegment(it.packageName, it.startedAtMillis, it.endedAtMillis) }

        return quotas.associate { quota ->
            // 上限为 0 的坏数据不算用量（同 usageOf：exhausted 只是算术事实，
            // "配置无效"这个结论由 QuotaStatus.of 给）
            val usedMillis = if (quota.limitMinutes <= 0) {
                0L
            } else {
                QuotaCalculator.totalInWindow(
                    usage = usage,
                    packageNames = quota.effectivePackages,
                    windowStartMillis = QuotaCalculator.epochMillis(
                        QuotaCalculator.periodStart(quota.resetPeriod, at)
                    ),
                    windowEndMillis = windowEnd
                )
            }
            val usedMinutes = QuotaCalculator.usedMinutes(usedMillis)
            quota.id to QuotaUsage(
                quotaId = quota.id,
                usedMinutes = usedMinutes,
                remainingMinutes = QuotaCalculator.remainingMinutes(
                    quota.limitMinutes,
                    usedMinutes
                ),
                exhausted = QuotaCalculator.isExhausted(usedMillis, quota.limitMillis)
            )
        }
    }

    // --------------------------------------------------------- 拦截（窄接口）

    /**
     * [QuotaEnforcementSource] 的实现：当前因额度用完而该被禁用的包集合。
     *
     * ⚠️ **只返回"有启用额度、且确实用尽"的包**。任何异常（库读不到、
     * 时间换算失败）都退化为**空集合** —— 额度判定失败绝不能变成
     * "把用户所有应用全拦下来"，那比漏拦严重得多。
     * 这与 `InterceptDecider` 的"拿不到包名就不拦"是同一条安全取向。
     *
     * ## 本轮（v2.11）新增的一层过滤：上限 == 0 的坏数据不参与拦截
     *
     * 判据是 [UsageQuota.isConfigured]（`limitMinutes >= 1`）。
     * 0 分钟额度**不再是**"一打开就拦"的合法配置 —— 用户已经决定
     * "不予许设置成 0 分钟"，而存量里那些 0 分钟行是坏数据：
     *  - 它的状态是 [QuotaStatus.INVALID]（【配置无效】），不是"已耗尽"
     *  - 因此它**不冻结**（用户能把它改成 ≥1 分钟或者删掉）
     *  - 也不该拿它去锁应用：拿一份"没配好"的额度拦人是没有依据的，
     *    而且那会立刻和上一条矛盾（不冻结却还在拦，用户改完才发现白改）
     *
     * ⚠️ **真正的耗尽场景一个字都没放宽**：上限 ≥ 1 且用满时照旧禁用，
     * 见 `UsageQuotaRepositoryTest` 的
     * `上限 5 分钟的本周期用满后，三个动作仍然冻结且应用真的被拦`。
     */
    override suspend fun disabledPackages(atMillis: Long): Map<String, QuotaExhaustion> {
        // 兜底失败方向：判定不出就**不拦**。
        // 与 InterceptDecider 的"拿不到包名就放行"同一条安全取向 ——
        // 额度判定出问题绝不能变成"把用户所有应用全拦下来"。
        val quotas = runCatching { getEnabled() }.getOrElse { return emptyMap() }
        // 两道过滤各管一件事：isConfigured = "上限配好了"（本轮），
        // isValid = "名称/时长区间/绑定关系都没问题"（既有）。
        val enforcers = quotas.filter { it.isValid && it.isConfigured }
            .map { QuotaEnforcer.from(it) }
        if (enforcers.isEmpty()) return emptyMap()

        val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(atMillis), clock.zone)
        val windowStart = runCatching {
            enforcers.minOf {
                QuotaCalculator.epochMillis(QuotaCalculator.periodStart(it.resetPeriod, at))
            }
        }.getOrElse { return emptyMap() }

        val usage = runCatching {
            usageRepository.sessionsInWindow(windowStart, atMillis)
                .map { UsageSegment(it.packageName, it.startedAtMillis, it.endedAtMillis) }
        }.getOrElse { return emptyMap() }

        return QuotaCalculator.exhaustedPackages(enforcers, usage, at)
    }

    /**
     * [QuotaEnforcementSource] 的实现：被**启用额度**绑定的所有包名。
     *
     * 与 [disabledPackages] 一样，任何异常都退化为空集合 ——
     * 读不到就当作"没有额度"，绝不误拦。
     *
     * 也同样按 [UsageQuota.isConfigured] 过滤：上限 == 0 的坏数据既不会
     * 出现在禁用集合里，也不该让看门狗为它启动（那只会白耗电）。
     */
    override suspend fun boundPackages(atMillis: Long): Set<String> =
        runCatching {
            getEnabled()
                .filter { it.isValid && it.isConfigured }
                .flatMap { it.effectivePackages }
                .toSet()
        }.getOrElse { emptySet() }

    // ---------------------------------------------------------------- 内部

    /** 把额度行与它们的绑定关系拼成领域对象（一次读全表，避免 N+1）。 */
    private suspend fun assemble(
        rows: List<com.zilv.app.data.local.entity.UsageQuotaEntity>
    ): List<UsageQuota> {
        if (rows.isEmpty()) return emptyList()
        val apps = quotaDao.allApps().groupBy({ it.quotaId }, { it.packageName })
        return rows.map { it.toDomain(apps[it.id].orEmpty().toSet()) }
    }

    /** 写入口的统一校验 —— 与 `PlanRepository` 同一口径：**拒绝而不是猜**。 */
    private fun ensureValid(quota: UsageQuota) {
        quota.invalidReason?.let { throw QuotaRejectedException(it) }
    }

    /**
     * 「额度用完就不许改/删/关」的守卫（v2.11，用户第 3 条规则 2）。
     *
     * ## 为什么放在仓库层，而不是只挡界面
     *
     * 用户的原话是"**不能只挡界面**：请在 ViewModel（乃至仓库）也拒绝，
     * 并给出可读原因（本项目一贯做法：守卫放在能被单测覆盖的层）"。
     *
     * 三层各司其职，但**判据只有一个**（[UsageQuota.allowsMutation]）：
     *  1. 界面：按钮置灰，用户根本点不到（第一道，见 `QuotaScreen`）
     *  2. ViewModel：`runCatching` 把这里抛的原因变成一条可读消息
     *  3. **仓库层（这里）**：真正的权威。任何绕过界面的调用点都拦得住，
     *     而且这里的每一条分支都能被纯 JVM 单测直接覆盖
     *     （见 `UsageQuotaRepositoryTest`）。
     *
     * ## 判据是"此刻算不算正在生效"，所以必须真算一次用量
     *
     * [UsageQuota.allowsMutation] 吃的是一个 [QuotaStatus]，而状态里的
     * "剩余 == 0"是时间的函数。这里就地问 [usageOf] 要一份**当下**的用量 ——
     * 不能信界面上那份可能已经过时的快照（用户可以在列表停留几分钟，
     * 期间额度正好用尽）。
     *
     * ⚠️ 上限也必须一起喂进去（本轮）：上限 == 0 的存量坏数据判成
     * [QuotaStatus.INVALID]（【配置无效】），于是 [UsageQuota.allowsMutation]
     * 放行它 —— 用户能把它改成 ≥1 分钟或者删掉。**这不是放宽**
     * "用完就关掉"那条守卫：上限 ≥ 1 且剩余 == 0 仍然是
     * [QuotaStatus.EXHAUSTED]，照样拒。理由见 [UsageQuota.allowsMutation]
     * 的 KDoc（"上限为 0 的配置永远不可能被用完，它是坏数据"）。
     *
     * ## 失败方向
     *
     * 用量算不出来（库读不到）时**照旧放行**：宁可漏挡一次，
     * 也不能因为"算不出来"就把用户锁在"什么都改不了"里 ——
     * 与 `disabledPackages` 的"拿不到就当作没有额度"同一条安全取向。
     */
    private suspend fun ensureMutable(quota: UsageQuota, action: String) {
        val status = runCatching {
            QuotaStatus.of(
                enabled = quota.enabled,
                remainingMinutes = usageOf(quota).remainingMinutes,
                limitMinutes = quota.limitMinutes
            )
        }.getOrElse {
            // 读用量失败：不拦（见上面 KDoc 的失败方向）
            return
        }
        val reason = quota.mutationRejectionReason(status) ?: return
        throw QuotaRejectedException("无法$action：$reason")
    }
}

/**
 * 额度被业务规则拒绝（名称为空 / 时长越界 / 没选应用 …）。
 *
 * 单独定义异常类型而不是复用 [IllegalStateException]，与
 * [PlanRejectedException] / [RuleLockedException] 的理由相同：上层要能精确
 * 区分"业务规则拒绝"（给用户一句人话）与"程序出错"（才需要上报）。
 */
class QuotaRejectedException(message: String) : IllegalStateException(message)
