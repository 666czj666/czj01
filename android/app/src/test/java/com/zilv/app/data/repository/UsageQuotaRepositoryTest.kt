package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.mapper.ResetPeriodCodec
import com.zilv.app.domain.quota.QuotaEnforcementSource
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [UsageQuotaRepository] 的单元测试（v2.7）。
 *
 * 覆盖四件事：
 *  1. **写入口的拒绝**（名称为空 / 时长越界 / 没选应用）—— 与 `PlanRepository`
 *     同一口径："拒绝而不是猜"，而且拒绝时**不落库**
 *  2. **绑定关系整组替换**：编辑时取消勾选的包必须真的被删掉，
 *     删除额度时绑定关系必须被级联清掉（本项目不建外键，漏清会留下悬空行）
 *  3. **周期内用量**：`usageOf` / `usageOfAll` 的数字必须与
 *     `QuotaCalculator` 的纯函数一致（含跨周期边界的会话）
 *  4. **窄接口 `disabledPackages` / `boundPackages`**：拦截服务拿到的集合
 *     必须与界面显示的"已用尽"完全一致，且任何异常都退化为空集合
 *
 * 时间固定为 2026-10-14 20:00 (+08:00)，以便断言"今天/本周/本月"的周期起点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsageQuotaRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-14 20:00 (+08:00) —— 周三。本周一 = 10-12，本月 1 号 = 10-01。 */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), zone)

    private lateinit var quotaDao: FakeQuotaDao
    private lateinit var usageDao: FakeUsageDao
    private lateinit var usageRepository: UsageRepository
    private lateinit var repository: UsageQuotaRepository

    private val douyin = "com.ss.android.ugc.aweme"
    private val kuaishou = "com.smile.gifmaker"
    private val wechat = "com.tencent.mm"

    @Before
    fun setUp() {
        quotaDao = FakeQuotaDao()
        usageDao = FakeUsageDao()
        usageRepository = UsageRepository(usageDao, clock)
        repository = UsageQuotaRepository(quotaDao, usageRepository, clock)
    }

    // ---------------------------------------------------------------- 工具

    private suspend fun addQuota(
        name: String = "短视频",
        limit: Int = 30,
        period: ResetPeriod = ResetPeriod.DAILY,
        packages: Set<String> = setOf(douyin),
        enabled: Boolean = true
    ): Long = repository.add(name, limit, period, packages, enabled)

    /**
     * 造一条**库里本来就有的** 0 分钟额度（v2.11）。
     *
     * 新建入口至少 1 分钟（见 [UsageQuota.MIN_CREATABLE_LIMIT_MINUTES]），
     * 备份恢复也会把 0 夹到 1 —— 也就是说**生产代码里没有任何路径**能写出
     * 一条 0 分钟额度。但这个**数据形态**仍然必须被支持：旧库里可能有
     * （v2.11 之前建的）。所以本文件用这个助手直接铺那种行 ——
     * `allowZeroLimit` 这个参数现在**只给测试夹具用**（见它的 KDoc）。
     *
     * ⚠️ 这些既有断言考的从来不是"新建允许 0 分钟"，而是
     * "0 分钟行**读出来之后**该被怎么对待"：
     *  - `usageOf` 照旧算得出来（读路径不收紧）
     *  - 状态是【配置无效】而不是【额度已耗尽】（见 `QuotaStatusTest`）
     *  - 它**不参与拦截**、也**不冻结**（本轮用户决定，见下面的用例）
     */
    private suspend fun seedLegacyZeroQuota(
        name: String = "直接禁用",
        packages: Set<String> = setOf(douyin),
        enabled: Boolean = true
    ): Long = repository.add(
        name = name,
        limitMinutes = 0,
        resetPeriod = ResetPeriod.DAILY,
        packageNames = packages,
        enabled = enabled,
        allowZeroLimit = true
    )

    /** 记录一段使用。[hour] 是本机时区的小时数，[day] 是 10 月的第几天。 */
    private suspend fun use(
        pkg: String,
        day: Int,
        fromHour: Int,
        toHour: Int,
        fromMinute: Int = 0,
        toMinute: Int = 0,
        month: Int = 10,
        year: Int = 2026
    ) {
        val start = LocalDateTime.of(year, month, day, fromHour, fromMinute)
            .atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(year, month, day, toHour, toMinute)
            .atZone(zone).toInstant().toEpochMilli()
        usageRepository.recordSegment(pkg, start, end)
    }

    private fun millisAt(
        day: Int,
        hour: Int,
        minute: Int = 0,
        month: Int = 10,
        year: Int = 2026
    ): Long = LocalDateTime.of(year, month, day, hour, minute)
        .atZone(zone).toInstant().toEpochMilli()

    // ============================================================ 增删改

    @Test
    fun `新增额度后能读回来，绑定关系一起落库`() = runTest {
        val id = addQuota(name = "短视频", limit = 45, packages = setOf(douyin, kuaishou))

        val loaded = repository.getById(id)!!
        assertThat(loaded.name).isEqualTo("短视频")
        assertThat(loaded.limitMinutes).isEqualTo(45)
        assertThat(loaded.resetPeriod).isEqualTo(ResetPeriod.DAILY)
        assertThat(loaded.enabled).isTrue()
        assertThat(loaded.packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `名称前后空格被去掉`() = runTest {
        val id = addQuota(name = "  短视频  ")
        assertThat(repository.getById(id)!!.name).isEqualTo("短视频")
    }

    @Test
    fun `名称为空的额度被拒绝且不落库`() = runTest {
        val error = runCatching { addQuota(name = "   ") }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("名称")
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `时长越界的额度被拒绝且不落库`() = runTest {
        // ⚠️ 规格变更：上限从 1440 分钟提到 44640 分钟（744 小时 = 31 天）。
        // 旧断言给的越界样本是 1441，它现在落在合法区间里，必须挪到 44641。
        assertThat(runCatching { addQuota(limit = -1) }.exceptionOrNull())
            .isInstanceOf(QuotaRejectedException::class.java)
        assertThat(runCatching { addQuota(limit = UsageQuota.MAX_LIMIT_MINUTES + 1) }
            .exceptionOrNull())
            .isInstanceOf(QuotaRejectedException::class.java)
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `上限于 744 小时，恰好等于上限的额度可以落库`() = runTest {
        val id = addQuota(limit = UsageQuota.MAX_LIMIT_MINUTES)
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(44640)
        assertThat(UsageQuota.formatMinutes(44640)).isEqualTo("744 小时")
    }

    @Test
    fun `新建 0 分钟额度被拒绝 —— 本轮起 0 不是合法配置`() = runTest {
        // ⚠️ v2.11 规格变更（用户第 3 条规则 2 的边界处理）：
        // 旧规格是"时长为 0 是合法的 —— 用户要求额度为零时禁用指定软件"。
        // 但规则 2 让"剩余 == 0"的额度永远关不掉也删不掉，于是新建一个
        // 0 分钟额度就等于当场把自己锁死；用户点名要求
        // "保证新建时就不能创建 0 分钟额度"。所以这条断言被改写了。
        //
        // 本轮用户把话说到底："**不予许设置成 0 分钟**" ——
        // 于是报错文案也从"会立刻用尽、自己也改不了"改成
        // "0 分钟不是一个合法的额度配置"（**测试跟着新规格改**，不是代码错）。
        val error = runCatching { addQuota(limit = 0) }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("至少 1 分钟")
        assertThat(error.message).contains("不是一个合法的额度配置")
        assertThat(repository.count()).isEqualTo(0)

        // 而"库里本来就有的 0 分钟额度"仍然读得回来（读路径不收紧）
        val id = seedLegacyZeroQuota()
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(0)
    }

    @Test
    fun `allowZeroLimit 现在只给测试夹具用 —— 存量 0 分钟行仍然读得回来`() = runTest {
        // ⚠️ v2.11 本轮规格变更：旧名字是
        // "allowZeroLimit 只给备份恢复用 —— 它能把旧备份里的 0 分钟额度搬回来"。
        // 那属于**旧规格**：恢复路径现在把 0 夹到 1（用户："不予许设置成 0 分钟"，
        // 见 `QuotaImportTest`）。于是这个参数的生产用途归零，只剩测试夹具
        // 需要它来铺"存量脏数据"。
        //
        // 断言的**意图没降低**：这个参数仍然真的能写出一条 0 分钟行，
        // 读路径对它一视同仁（不抛异常、不改写）。
        val id = seedLegacyZeroQuota(name = "老额度")
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(0)
        assertThat(repository.getById(id)!!.name).isEqualTo("老额度")
    }

    @Test
    fun `没选应用的额度被拒绝且不落库`() = runTest {
        val error = runCatching { addQuota(packages = emptySet()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("应用")
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `只选了空白包名等同于没选应用`() = runTest {
        val error = runCatching { addQuota(packages = setOf("  ", "")) }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
    }

    @Test
    fun `修改额度会整组替换绑定关系 —— 取消勾选的包必须真的被删掉`() = runTest {
        val id = addQuota(packages = setOf(douyin, kuaishou, wechat))
        val loaded = repository.getById(id)!!

        repository.update(loaded.copy(packageNames = setOf(douyin, wechat)))

        val after = repository.getById(id)!!
        assertThat(after.packageNames).containsExactly(douyin, wechat)
        // 悬空绑定行必须为 0 之外的正确值：3 个 -> 2 个
        assertThat(quotaDao.appRowCount).isEqualTo(2)
        assertThat(quotaDao.packagesOf(id)).containsExactly(douyin, wechat)
    }

    @Test
    fun `修改额度可以改时长与刷新周期`() = runTest {
        val id = addQuota(limit = 30, period = ResetPeriod.DAILY)
        val loaded = repository.getById(id)!!

        repository.update(loaded.copy(limitMinutes = 120, resetPeriod = ResetPeriod.WEEKLY))

        val after = repository.getById(id)!!
        assertThat(after.limitMinutes).isEqualTo(120)
        assertThat(after.resetPeriod).isEqualTo(ResetPeriod.WEEKLY)
    }

    @Test
    fun `修改不存在的额度被拒绝`() = runTest {
        val error = runCatching {
            repository.update(UsageQuota(id = 999L, name = "x", packageNames = setOf(douyin)))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("不存在")
    }

    @Test
    fun `id 为 0 的额度不能当修改提交`() = runTest {
        val error = runCatching {
            repository.update(UsageQuota(id = 0L, name = "x", packageNames = setOf(douyin)))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
    }

    @Test
    fun `删除额度会连带清掉绑定关系`() = runTest {
        val id = addQuota(packages = setOf(douyin, kuaishou))
        assertThat(quotaDao.appRowCount).isEqualTo(2)

        repository.delete(id)

        assertThat(repository.count()).isEqualTo(0)
        assertThat(quotaDao.appRowCount).isEqualTo(0)
    }

    @Test
    fun `删除不存在的额度是空操作，不抛异常`() = runTest {
        repository.delete(999L)
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `停用与启用`() = runTest {
        val id = addQuota()
        repository.setEnabled(id, false)
        assertThat(repository.getById(id)!!.enabled).isFalse()
        assertThat(repository.getEnabled()).isEmpty()

        repository.setEnabled(id, true)
        assertThat(repository.getEnabled().map { it.id }).containsExactly(id)
    }

    @Test
    fun `停用不存在的额度是空操作`() = runTest {
        repository.setEnabled(999L, false)
        assertThat(repository.count()).isEqualTo(0)
    }

    /**
     * Bug 2 的钉子（v2.12）：**关闭之后必须还能再打开**。
     *
     * 用户报的是界面（开关被一起置灰了），但那条规则的三层守卫里
     * **仓库层是权威的那一层** —— 所以这条往返也要在仓库层钉住：
     * 方向必须分开判，不能一刀切。
     */
    @Test
    fun `关闭后可再次打开 —— 往返必须成立，但关着的时候仍然删不掉`() = runTest {
        val id = addQuota()

        // ① 关掉
        repository.setEnabled(id, false)
        assertThat(repository.getById(id)!!.enabled).isFalse()

        // ② 关闭态下"先关掉再删掉"那条绕过路径照旧堵着（这是 v2.11 的本意，不许放宽）
        assertThat(runCatching { repository.delete(id) }.isFailure).isTrue()
        assertThat(repository.count()).isEqualTo(1)

        // ③ 但"打开"必须成功 —— 否则这份额度永远回不来（v2.11 的真实 bug）
        repository.setEnabled(id, true)
        assertThat(repository.getById(id)!!.enabled).isTrue()
        assertThat(repository.getEnabled().map { it.id }).containsExactly(id)
    }

    // ============================================================ 读取

    @Test
    fun `observeAll 把绑定关系一起带出来，且写入后立刻发射`() = runTest {
        val id = addQuota(packages = setOf(douyin, kuaishou))

        val emitted = repository.observeAll().first()
        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().id).isEqualTo(id)
        assertThat(emitted.single().packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `getEnabled 只返回启用中的额度`() = runTest {
        val a = addQuota(name = "A")
        addQuota(name = "B", enabled = false)
        assertThat(repository.getEnabled().map { it.name }).containsExactly("A")
        assertThat(repository.getAll().map { it.name }).containsExactly("A", "B").inOrder()
        assertThat(a).isGreaterThan(0L)
    }

    // ============================================================ 用量

    @Test
    fun `没有用量时已用 0 分钟、剩余等于额度`() = runTest {
        val id = addQuota(limit = 30)
        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(0)
        assertThat(usage.remainingMinutes).isEqualTo(30)
        assertThat(usage.exhausted).isFalse()
    }

    @Test
    fun `已用与剩余按本周期计算`() = runTest {
        val id = addQuota(limit = 30)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 20)  // 今天 20 分钟

        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(20)
        assertThat(usage.remainingMinutes).isEqualTo(10)
        assertThat(usage.exhausted).isFalse()
    }

    @Test
    fun `昨天的用量不计入今天的日额度`() = runTest {
        val id = addQuota(limit = 30, period = ResetPeriod.DAILY)
        use(douyin, day = 13, fromHour = 9, toHour = 11)  // 昨天 2 小时

        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(0)
        assertThat(usage.remainingMinutes).isEqualTo(30)
    }

    @Test
    fun `昨天的用量计入本月额度`() = runTest {
        val id = addQuota(limit = 30, period = ResetPeriod.MONTHLY)
        use(douyin, day = 13, fromHour = 9, toHour = 11)  // 本月内 2 小时

        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(120)
        assertThat(usage.exhausted).isTrue()
    }

    @Test
    fun `上周的用量不计入本周额度，本周内的计入`() = runTest {
        val id = addQuota(limit = 30, period = ResetPeriod.WEEKLY)
        // 10-11 是周日（上一周），10-12 是周一（本周）
        use(douyin, day = 11, fromHour = 9, toHour = 10)
        use(douyin, day = 13, fromHour = 9, toHour = 9, toMinute = 25)

        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(25)
        assertThat(usage.exhausted).isFalse()
    }

    @Test
    fun `跨 0 点的会话只把落在今天的那部分算进日额度`() = runTest {
        val id = addQuota(limit = 30, period = ResetPeriod.DAILY)
        // 10-13 23:30 -> 10-14 00:30，跨天
        val start = LocalDateTime.of(2026, 10, 13, 23, 30).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 10, 14, 0, 30).atZone(zone).toInstant().toEpochMilli()
        usageRepository.recordSegment(douyin, start, end)

        val usage = repository.usageOf(repository.getById(id)!!)
        // 只算 00:00-00:30 这 30 分钟 —— 正好用尽
        assertThat(usage.usedMinutes).isEqualTo(30)
        assertThat(usage.exhausted).isTrue()
    }

    @Test
    fun `共用额度：多个应用的用量加在一起算`() = runTest {
        val id = addQuota(limit = 30, packages = setOf(douyin, kuaishou))
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 20)
        use(kuaishou, day = 14, fromHour = 10, toHour = 10, toMinute = 10)

        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(30)
        assertThat(usage.remainingMinutes).isEqualTo(0)
        assertThat(usage.exhausted).isTrue()
    }

    @Test
    fun `不属于该额度的应用用量不算进来`() = runTest {
        val id = addQuota(limit = 30, packages = setOf(douyin))
        use(wechat, day = 14, fromHour = 9, toHour = 18)

        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.usedMinutes).isEqualTo(0)
    }

    @Test
    fun `0 分钟额度即使没有任何用量也算用尽`() = runTest {
        // 数据形态仍然支持（旧库/旧备份里的 0 分钟额度）：判定必须照旧
        val id = seedLegacyZeroQuota()
        val usage = repository.usageOf(repository.getById(id)!!)
        assertThat(usage.exhausted).isTrue()
        assertThat(usage.remainingMinutes).isEqualTo(0)
        assertThat(usage.usedMinutes).isEqualTo(0)
    }

    @Test
    fun `usageOfAll 与逐个 usageOf 的结果一致`() = runTest {
        val a = addQuota(name = "A", limit = 30, period = ResetPeriod.DAILY, packages = setOf(douyin))
        val b = addQuota(name = "B", limit = 10, period = ResetPeriod.MONTHLY, packages = setOf(kuaishou))
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 40)
        use(kuaishou, day = 2, fromHour = 9, toHour = 10)

        val all = repository.usageOfAll()
        val oneByOne = repository.getAll().associate { it.id to repository.usageOf(it) }

        assertThat(all).isEqualTo(oneByOne)
        assertThat(all.getValue(a).usedMinutes).isEqualTo(40)
        assertThat(all.getValue(a).exhausted).isTrue()
        assertThat(all.getValue(b).usedMinutes).isEqualTo(60)
        assertThat(all.getValue(b).exhausted).isTrue()
    }

    @Test
    fun `usageOfAll 在没有额度时返回空 Map`() = runTest {
        assertThat(repository.usageOfAll()).isEmpty()
    }

    // ============================================================ 窄接口

    @Test
    fun `disabledPackages 与界面显示的已用尽完全一致`() = runTest {
        val id = addQuota(limit = 30, packages = setOf(douyin, kuaishou))
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 30)

        val disabled = repository.disabledPackages(millisAt(day = 14, hour = 20))
        assertThat(disabled.keys).containsExactly(douyin, kuaishou)
        assertThat(repository.usageOf(repository.getById(id)!!).exhausted).isTrue()
        assertThat(disabled.getValue(douyin).quotaName).isEqualTo("短视频")
    }

    @Test
    fun `额度没用完时 disabledPackages 为空`() = runTest {
        addQuota(limit = 30)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 10)
        assertThat(repository.disabledPackages(millisAt(day = 14, hour = 20))).isEmpty()
    }

    @Test
    fun `停用的额度不出现在 disabledPackages 里`() = runTest {
        seedLegacyZeroQuota(enabled = false)
        assertThat(repository.disabledPackages(millisAt(day = 14, hour = 20))).isEmpty()
    }

    @Test
    fun `上限为 0 的存量坏数据不参与拦截 —— 它不再是合法的零额度禁用规则`() = runTest {
        // ⚠️ v2.11 本轮规格变更：旧行为是"0 分钟额度 = 一打开就被拦"，
        // 这条断言原先写的是 `containsExactly(douyin)`。用户本轮决定
        // "不予许设置成 0 分钟"，0 从此是**坏数据**而不是一份配置 ——
        // 拿一份"没配好"的额度去锁应用没有依据，而且会立刻和
        // "坏数据不冻结、允许用户改掉它"矛盾（改完才发现白改）。
        //
        // ⚠️ 注意这不等于"放宽了用尽场景"：上限 ≥ 1 且用满时照旧拦，
        // 见本文件末尾的 `上限 5 分钟的本周期用满后，三个动作仍然冻结且应用真的被拦`。
        seedLegacyZeroQuota(enabled = true)

        val at = millisAt(day = 14, hour = 20)
        assertThat(repository.getEnabled()).hasSize(1)   // 它确实在启用中
        assertThat(repository.disabledPackages(at)).isEmpty()
        // 看门狗也不该为它启动（那只会白耗电）
        assertThat(repository.boundPackages(at)).isEmpty()
    }

    @Test
    fun `boundPackages 返回被启用额度绑定的全部包名，不管有没有用尽`() = runTest {
        addQuota(name = "A", limit = 30, packages = setOf(douyin))
        addQuota(name = "B", limit = 10, packages = setOf(kuaishou), enabled = false)

        val bound = repository.boundPackages(millisAt(day = 14, hour = 20))
        assertThat(bound).containsExactly(douyin)
        // 关键：额度远没到用尽，boundPackages 依然包含它 ——
        // 否则看门狗永远不会启动，"中途用尽"就永远发现不了
        assertThat(repository.disabledPackages(millisAt(day = 14, hour = 20))).isEmpty()
    }

    @Test
    fun `没有任何额度时两个窄接口都返回空`() = runTest {
        val at = millisAt(day = 14, hour = 20)
        assertThat(repository.disabledPackages(at)).isEmpty()
        assertThat(repository.boundPackages(at)).isEmpty()
    }

    @Test
    fun `DAO 抛异常时 disabledPackages 退化为空集合而不是把用户全禁掉`() = runTest {
        val broken = object : FakeQuotaDao() {
            override suspend fun getEnabled(): List<com.zilv.app.data.local.entity.UsageQuotaEntity> =
                throw IllegalStateException("库坏了")
        }
        val repo = UsageQuotaRepository(broken, usageRepository, clock)
        assertThat(repo.disabledPackages(millisAt(day = 14, hour = 20))).isEmpty()
        assertThat(repo.boundPackages(millisAt(day = 14, hour = 20))).isEmpty()
    }

    @Test
    fun `QuotaEnforcementSource NONE 的实现什么都不返回`() = runTest {
        assertThat(QuotaEnforcementSource.NONE.disabledPackages(0L)).isEmpty()
        assertThat(QuotaEnforcementSource.NONE.boundPackages(0L)).isEmpty()
    }

    @Test
    fun `仓库自己实现了窄接口 —— 依赖方向单向`() = runTest {
        // 编译期就已成立，这里用一次赋值把它钉在测试里：
        // 一旦有人把 QuotaEnforcementSource 的实现在别处另起一套，这条会失败
        val source: QuotaEnforcementSource = repository
        assertThat(source.disabledPackages(millisAt(day = 14, hour = 20))).isEmpty()
    }

    // ============================================================ 边界

    @Test
    fun `刷新点前一秒仍算旧周期，后一秒就算新周期`() = runTest {
        val id = addQuota(limit = 30, period = ResetPeriod.DAILY)
        // 10-14 23:59:59 结束的一段：在 10-14 当天算，在 10-15 不算
        val start = LocalDateTime.of(2026, 10, 14, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDateTime.of(2026, 10, 14, 23, 59, 59).atZone(zone).toInstant().toEpochMilli()
        usageRepository.recordSegment(douyin, start, end)

        val quota = repository.getById(id)!!
        // 10-14 当天（周期内）：算
        val onOct14 = repository.usageOf(quota, LocalDateTime.of(2026, 10, 14, 23, 59, 59))
        assertThat(onOct14.usedMinutes).isGreaterThan(0)
        // 10-15 00:00（新周期）：不算
        val onOct15 = repository.usageOf(quota, LocalDateTime.of(2026, 10, 15, 0, 0))
        assertThat(onOct15.usedMinutes).isEqualTo(0)
        assertThat(onOct15.remainingMinutes).isEqualTo(30)
    }

    @Test
    fun `时长为 0 的额度在刷新点之后依然是用尽的`() = runTest {
        val id = seedLegacyZeroQuota()
        val quota = repository.getById(id)!!
        // 跨到任意未来时刻都一样 —— 0 分钟额度与用量无关
        assertThat(repository.usageOf(quota, LocalDateTime.of(2027, 1, 1, 0, 0)).exhausted).isTrue()
        assertThat(repository.usageOf(quota, LocalDateTime.of(2026, 11, 1, 0, 0)).exhausted).isTrue()
    }

    // ==================================== v2.11（用户第 3 条规则 2）：用尽即冻结
    //
    // 用户要求："剩余额度 == 0 时，【关闭】【编辑】【删除】三个按钮全部置灰、
    // 不可点击。不能只挡界面：请在 ViewModel（乃至仓库）也拒绝，并给出可读原因。"
    //
    // 下面这一组就是**仓库层**那道权威守卫的单测。守卫实现在
    // `UsageQuotaRepository.ensureMutable`，判据来自
    // `UsageQuota.allowsMutation` + `QuotaStatus`。

    /** 造一份"确实已经用尽"的额度：1 分钟额度 + 520 分钟用量。 */
    private suspend fun seedExhausted(
        name: String = "短视频",
        packages: Set<String> = setOf(douyin)
    ): Long {
        val id = addQuota(name = name, limit = 1, packages = packages)
        use(packages.first(), day = 14, fromHour = 9, toHour = 9, toMinute = 30)
        return id
    }

    @Test
    fun `额度用尽后删除被仓库拒绝，数据原样保留`() = runTest {
        val id = seedExhausted()
        assertThat(repository.usageOf(repository.getById(id)!!).exhausted).isTrue()

        val error = runCatching { repository.delete(id) }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("已用完")

        // 关键：拒绝就是**真的没删**（否则守卫毫无意义）
        assertThat(repository.count()).isEqualTo(1)
        assertThat(quotaDao.appRowCount).isEqualTo(1)
    }

    @Test
    fun `额度用尽后停用被仓库拒绝`() = runTest {
        val id = seedExhausted()

        val error = runCatching { repository.setEnabled(id, false) }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("无法关闭")

        // 仍然启用中 —— "用完就关掉"那条绕过路径被堵上了
        assertThat(repository.getById(id)!!.enabled).isTrue()
        assertThat(repository.getEnabled().map { it.id }).containsExactly(id)
    }

    @Test
    fun `额度用尽后修改被仓库拒绝`() = runTest {
        val id = seedExhausted()
        val loaded = repository.getById(id)!!

        val error = runCatching {
            repository.update(loaded.copy(limitMinutes = 600))
        }.exceptionOrNull()
        assertThat(error).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(error!!.message).contains("无法修改")

        // 时长没有被偷偷改大（那等于重新拿到一份额度）
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(1)
    }

    @Test
    fun `额度还有剩余时三个动作都不受影响`() = runTest {
        // 守卫只挡"用尽"，不能顺手把正常配置也挡了
        val id = addQuota(limit = 30)
        use(douyin, day = 14, fromHour = 9, toHour = 9, toMinute = 10)
        assertThat(repository.usageOf(repository.getById(id)!!).exhausted).isFalse()

        repository.setEnabled(id, false)
        assertThat(repository.getById(id)!!.enabled).isFalse()
        repository.setEnabled(id, true)

        val loaded = repository.getById(id)!!
        repository.update(loaded.copy(limitMinutes = 45))
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(45)

        repository.delete(id)
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `已用尽的额度仍然可以被'打开' —— 那是唯一的出口`() = runTest {
        // 关闭被拒，但**启用回来**永远放行：这是往更严的方向走，
        // 也是"已关闭的额度怎么才能改回来"的唯一出口。
        val id = seedExhausted()
        assertThat(runCatching { repository.setEnabled(id, false) }.isFailure).isTrue()

        // 直接用 DAO 模拟"这条额度历史遗留就是停用状态"
        quotaDao.update(
            quotaDao.getById(id)!!.copy(enabled = false)
        )
        assertThat(repository.getById(id)!!.enabled).isFalse()

        repository.setEnabled(id, true)
        assertThat(repository.getById(id)!!.enabled).isTrue()
    }

    @Test
    fun `已关闭的额度也不许改删 —— '先关掉再删掉'这条路一起堵上`() = runTest {
        val id = addQuota(limit = 30)
        repository.setEnabled(id, false)

        val deleteError = runCatching { repository.delete(id) }.exceptionOrNull()
        assertThat(deleteError).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(deleteError!!.message).contains("关闭状态")

        val updateError = runCatching {
            repository.update(repository.getById(id)!!.copy(limitMinutes = 600))
        }.exceptionOrNull()
        assertThat(updateError).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(repository.count()).isEqualTo(1)
    }

    @Test
    fun `删除不存在的额度仍然是空操作 —— 重复点删除不该报错`() = runTest {
        repository.delete(999L)
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `读用量失败时守卫放行 —— 绝不把用户锁在什么都改不了里`() = runTest {
        // 守卫的失败方向与 disabledPackages 一致：算不出用量就**不拦**。
        // 宁可漏挡一次，也不能因为"读不到用量"就把用户锁在"改不了也删不掉"里。
        //
        // 怎么造出"算不出用量"：让**用量那一边**在算的时候炸。
        // `usageOf` 会先 `getById` 再查 `usage_sessions`，
        // `quotaDao.packagesOf(id)` 一炸就正好落在"算用量"这一步里
        // （`ensureMutable` 的 runCatching 包住的就是这一步）。
        val id = addQuota(limit = 1)
        val broken = object : FakeQuotaDao() {
            override suspend fun packagesOf(quotaId: Long): List<String> =
                throw IllegalStateException("库忙")
        }
        val repo = UsageQuotaRepository(broken, usageRepository, clock)

        // 守卫放行 -> 删除一路走到 DAO -> 删掉了（而不是被守卫拒绝）
        assertThat(runCatching { repo.delete(id) }.isFailure).isFalse()
        assertThat(repo.count()).isEqualTo(0)
    }

    @Test
    fun `上限 5 分钟的本周期用满后，三个动作仍然冻结且应用真的被拦`() = runTest {
        // 🔒 本轮最重要的那条钉子：把"上限为 0 的坏数据不冻结"这件事
        // **限制在 0**，绝不能让真正的耗尽场景顺手被放宽。
        //
        // 用用户点名的那个数字造场景：上限 5 分钟、用满 5 分钟。
        // 三个动作逐个撞守卫，且每次都断言"真的没改"（拒绝必须是实打实的）。
        val id = addQuota(name = "五分钟", limit = 5)
        val start = LocalDateTime.of(2026, 10, 14, 9, 0).atZone(zone).toInstant().toEpochMilli()
        usageRepository.recordSegment(douyin, start, start + 5 * 60_000L)

        val loaded = repository.getById(id)!!
        assertThat(repository.usageOf(loaded).exhausted).isTrue()
        // 状态仍然必须是【额度已耗尽】—— 而不是被新加的【配置无效】吃掉
        assertThat(
            QuotaStatus.of(
                enabled = loaded.enabled,
                remainingMinutes = repository.usageOf(loaded).remainingMinutes,
                limitMinutes = loaded.limitMinutes
            )
        ).isEqualTo(QuotaStatus.EXHAUSTED)

        // ① 关闭
        val closeError = runCatching { repository.setEnabled(id, false) }.exceptionOrNull()
        assertThat(closeError).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(closeError!!.message).contains("无法关闭")
        assertThat(repository.getById(id)!!.enabled).isTrue()

        // ② 编辑
        val updateError = runCatching {
            repository.update(repository.getById(id)!!.copy(limitMinutes = 600))
        }.exceptionOrNull()
        assertThat(updateError).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(updateError!!.message).contains("无法修改")
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(5)  // 没被偷偷改大

        // ③ 删除
        val deleteError = runCatching { repository.delete(id) }.exceptionOrNull()
        assertThat(deleteError).isInstanceOf(QuotaRejectedException::class.java)
        assertThat(deleteError!!.message).contains("无法删除")
        assertThat(repository.count()).isEqualTo(1)

        // 顺带把"它真的还在拦"也钉住：守卫与拦截用的是同一份"已用尽"
        assertThat(repository.disabledPackages(millisAt(day = 14, hour = 20)).keys)
            .containsExactly(douyin)
    }

    @Test
    fun `上限为 0 的存量坏数据三个动作全部放行 —— 坏数据不该被冻死`() = runTest {
        // 用户本轮的核心决定：0 的判据是"配置非法"而不是"额度耗尽"，
        // 所以**不冻结** —— "允许编辑（好把它改成 ≥1 分钟）与删除（好清掉它）。
        // 「关闭」也允许。"
        // 理由："冻结是为了防'用完就关掉'，而一个上限为 0 的配置永远不可能
        // 被'用完'，它是坏数据；把它冻住只会让用户无路可走。"
        val id = seedLegacyZeroQuota(name = "坏数据")
        val loaded = repository.getById(id)!!

        // 状态是【配置无效】而不是【额度已耗尽】
        assertThat(
            QuotaStatus.of(
                enabled = loaded.enabled,
                remainingMinutes = repository.usageOf(loaded).remainingMinutes,
                limitMinutes = loaded.limitMinutes
            )
        ).isEqualTo(QuotaStatus.INVALID)

        // ① 编辑：能把它改成一个有效时长（这是坏数据唯一的出路）
        repository.update(loaded.copy(limitMinutes = 30))
        assertThat(repository.getById(id)!!.limitMinutes).isEqualTo(30)

        // ② 关闭
        repository.setEnabled(id, false)
        assertThat(repository.getById(id)!!.enabled).isFalse()

        // ③ 打开之后删除：清掉它也是允许的
        // （⚠️ 一定要先打开 —— 一份**已关闭**的额度删不掉是既有规则
        //  "先关掉再删掉这条路要堵上"，与本轮的坏数据无关。
        //  这条断言在这里顺带证明那条既有规则没被本轮改动破坏。）
        assertThat(runCatching { repository.delete(id) }.exceptionOrNull())
            .isInstanceOf(QuotaRejectedException::class.java)
        repository.setEnabled(id, true)
        repository.delete(id)
        assertThat(repository.count()).isEqualTo(0)
        assertThat(quotaDao.appRowCount).isEqualTo(0)
    }

    @Test
    fun `上限为 0 且已停用的坏数据也删得掉 —— 两种判据都不会把它冻住`() = runTest {
        // 优先级里 INVALID 高于 DISABLED，而两者都允许改动 ——
        // 所以这个组合无论走哪条判据都不该被冻住。
        val id = seedLegacyZeroQuota(name = "又坏又关着", enabled = false)
        assertThat(runCatching { repository.delete(id) }.isFailure).isFalse()
        assertThat(repository.count()).isEqualTo(0)
    }

    @Test
    fun `复位周期解码容错：未知值退化为每天`() {
        assertThat(ResetPeriodCodec.decode("WEEKLY")).isEqualTo(ResetPeriod.WEEKLY)
        assertThat(ResetPeriodCodec.decode("monthly")).isEqualTo(ResetPeriod.MONTHLY)
        assertThat(ResetPeriodCodec.decode("FUTURE_PERIOD")).isEqualTo(ResetPeriod.DAILY)
        assertThat(ResetPeriodCodec.encode(ResetPeriod.MONTHLY)).isEqualTo("MONTHLY")
    }
}
