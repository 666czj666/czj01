package com.zilv.app.ui.backup

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeQuotaDao
import com.zilv.app.data.repository.FakeUsageDao
import com.zilv.app.data.repository.UsageQuotaRepository
import com.zilv.app.data.repository.UsageRepository
import com.zilv.app.domain.backup.DataBackup
import com.zilv.app.domain.quota.QuotaStatus
import com.zilv.app.domain.quota.ResetPeriod
import com.zilv.app.domain.quota.UsageQuota
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「软件使用时间限时」额度进备份与恢复的测试（v2.7）。
 *
 * 覆盖三件事：
 *  1. **接线**：导出时额度进快照、导入时经 [QuotaImport] 落回库里
 *  2. **不绕过校验**：恢复走 `UsageQuotaRepository.add`，所以
 *     "名称非空 / 时长 1..44640（744 小时）/ 至少绑一个应用"这些约束
 *     **一条都没被绕过**
 *  3. **有拒绝也不影响其余**：一条坏额度被拒绝不该让整次恢复失败
 *
 * ## v2.11 本轮：0 分钟额度的恢复语义变了（旧规格的断言因此被改写）
 *
 * 旧规格是"0 分钟也是合法配置，恢复时原样搬回来"（旧实现显式传
 * `allowZeroLimit = true`）。用户本轮明确"**不予许设置成 0 分钟**"，
 * 所以恢复路径改成**把 0 夹到 1**（理由见 [QuotaImport.restore] 的 KDoc）。
 * 受影响的断言在本文件里逐条标了"⚠️ 规格变更"。
 *
 * 时间固定在 2026-10-14 20:00 (+08:00)。
 */
class QuotaImportTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), zone)

    private lateinit var quotaDao: FakeQuotaDao
    private lateinit var usageDao: FakeUsageDao
    private lateinit var repo: UsageQuotaRepository

    private val douyin = "com.ss.android.ugc.aweme"
    private val kuaishou = "com.smile.gifmaker"

    @Before
    fun setUp() {
        quotaDao = FakeQuotaDao()
        usageDao = FakeUsageDao()
        repo = UsageQuotaRepository(quotaDao, UsageRepository(usageDao, clock), clock)
    }

    private fun quota(
        name: String,
        limit: Int = 30,
        period: ResetPeriod = ResetPeriod.DAILY,
        enabled: Boolean = true,
        packages: Set<String> = setOf(douyin)
    ) = UsageQuota(
        name = name,
        limitMinutes = limit,
        resetPeriod = period,
        enabled = enabled,
        packageNames = packages
    )

    // ------------------------------------------------------------ 恢复

    @Test
    fun `额度能被恢复，绑定关系一起落库`() = runTest {
        val result = QuotaImport.restore(
            listOf(quota("短视频", limit = 45, packages = setOf(douyin, kuaishou))),
            repo
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(0)
        val saved = repo.getAll().single()
        assertThat(saved.name).isEqualTo("短视频")
        assertThat(saved.limitMinutes).isEqualTo(45)
        assertThat(saved.packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `刷新周期与启用状态被保留`() = runTest {
        QuotaImport.restore(
            listOf(
                quota("每周的", period = ResetPeriod.WEEKLY),
                quota("停用的", period = ResetPeriod.MONTHLY, enabled = false)
            ),
            repo
        )

        val all = repo.getAll()
        assertThat(all.map { it.resetPeriod })
            .containsExactly(ResetPeriod.WEEKLY, ResetPeriod.MONTHLY).inOrder()
        assertThat(all[1].enabled).isFalse()
    }

    @Test
    fun `0 分钟额度被夹到 1 分钟 —— 恢复不再产出非法配置`() = runTest {
        // ⚠️ v2.11 本轮规格变更（用户："不予许设置成 0 分钟"）。
        // 旧断言是"0 分钟也是合法配置，恢复后不会变成默认值"（isEqualTo(0)）——
        // 那条属于**旧规格**：它当时是刻意的（"额度为零时禁用指定软件"），
        // 但用户本轮把 0 定为非法配置，恢复路径不能再把它搬回来。
        val result = QuotaImport.restore(listOf(quota("直接禁用", limit = 0)), repo)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(1)
        assertThat(repo.getAll().single().limitMinutes)
            .isEqualTo(UsageQuota.MIN_CREATABLE_LIMIT_MINUTES)
        assertThat(repo.getAll().single().limitMinutes).isEqualTo(1)
        // 恢复出来的是一条**正常**额度（生效中），不是一条永远修不好的坏数据
        val saved = repo.getAll().single()
        assertThat(QuotaStatus.of(saved, repo.usageOf(saved))).isEqualTo(QuotaStatus.ACTIVE)
    }

    @Test
    fun `负数时长也被夹到 1 分钟而不是把整条丢掉`() = runTest {
        // 与 0 同一处理：非正数都不可能"用完"，都是"没配好"。
        // 用户数据（名称 / 周期 / 绑定应用）不该因为一个字段非法就整条丢掉。
        val result = QuotaImport.restore(
            listOf(quota("负的", limit = -1, packages = setOf(douyin, kuaishou))),
            repo
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(1)
        val saved = repo.getAll().single()
        assertThat(saved.limitMinutes).isEqualTo(1)
        // 其余字段一字未动 —— 这正是"夹到 1"而不是"跳过整条"的价值
        assertThat(saved.name).isEqualTo("负的")
        assertThat(saved.resetPeriod).isEqualTo(ResetPeriod.DAILY)
        assertThat(saved.enabled).isTrue()
        assertThat(saved.packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `既有 0 分钟也有正常额度时，repaired 只数被改过的那条`() = runTest {
        val result = QuotaImport.restore(
            listOf(quota("旧的", limit = 0), quota("正常的", limit = 30)),
            repo
        )

        assertThat(result.imported).isEqualTo(2)
        assertThat(result.repaired).isEqualTo(1)
        assertThat(repo.getAll().map { it.limitMinutes }).containsExactly(1, 30).inOrder()
    }

    @Test
    fun `名称为空的额度被拒绝，其余照旧落库`() = runTest {
        val result = QuotaImport.restore(
            listOf(quota("   "), quota("好额度")),
            repo
        )

        assertThat(result.imported).isEqualTo(1)
        assertThat(repo.getAll().map { it.name }).containsExactly("好额度")
    }

    @Test
    fun `时长越界的额度被拒绝 —— 越界不夹取，方向是开放的`() = runTest {
        // ⚠️ 与 0 / 负数**刻意不同**：0 有一个"最接近的合法值"（1 分钟），
        // 而"越界"没有 —— 44641 分钟是"多了一点"还是"用户想要 100 小时"？
        // 猜一个值等于替用户编数据，所以照旧拒绝。
        val result = QuotaImport.restore(
            listOf(quota("越界", limit = 99_999), quota("超上限一点点", limit = 44_641)),
            repo
        )
        assertThat(result.imported).isEqualTo(0)
        assertThat(result.repaired).isEqualTo(0)
        assertThat(quotaDao.count()).isEqualTo(0)
    }

    @Test
    fun `没绑应用的额度被拒绝`() = runTest {
        val result = QuotaImport.restore(listOf(quota("空的", packages = emptySet())), repo)
        assertThat(result.imported).isEqualTo(0)
        assertThat(result.repaired).isEqualTo(0)
        assertThat(quotaDao.count()).isEqualTo(0)
    }

    @Test
    fun `空列表恢复 0 条且不报错`() = runTest {
        val result = QuotaImport.restore(emptyList(), repo)
        assertThat(result).isEqualTo(QuotaImport.Result(imported = 0, repaired = 0))
        assertThat(quotaDao.count()).isEqualTo(0)
    }

    @Test
    fun `重复恢复同一份额度会得到多条（额度没有天然主键去重）`() = runTest {
        // 恢复是**追加**而不是覆盖（与备份页的说明一致）。这里把已知行为钉住，
        // 避免以后有人以为它是幂等的而依赖这一点。
        QuotaImport.restore(listOf(quota("短视频")), repo)
        QuotaImport.restore(listOf(quota("短视频")), repo)

        assertThat(repo.getAll()).hasSize(2)
        // 绑定关系也必须跟着两条各存一份，不能串到一起
        assertThat(quotaDao.appRowCount).isEqualTo(2)
    }

    // ------------------------------------------------------------ 端到端

    @Test
    fun `导出再导入能把额度完整搬回来`() = runTest {
        repo.add(
            name = "短视频",
            limitMinutes = 45,
            resetPeriod = ResetPeriod.WEEKLY,
            packageNames = setOf(douyin, kuaishou)
        )
        val text = DataBackup.encode(DataBackup.Snapshot(quotas = repo.getAll()))

        // 模拟"库被清空"：换一个空 DAO，但时钟相同
        val freshDao = FakeQuotaDao()
        val fresh = UsageQuotaRepository(
            freshDao,
            UsageRepository(FakeUsageDao(), clock),
            clock
        )
        val decoded = DataBackup.decode(text)!!

        val result = QuotaImport.restore(decoded.quotas, fresh)

        assertThat(result.imported).isEqualTo(1)
        assertThat(result.repaired).isEqualTo(0)
        val saved = fresh.getAll().single()
        assertThat(saved.name).isEqualTo("短视频")
        assertThat(saved.limitMinutes).isEqualTo(45)
        assertThat(saved.resetPeriod).isEqualTo(ResetPeriod.WEEKLY)
        assertThat(saved.packageNames).containsExactly(douyin, kuaishou)
    }

    @Test
    fun `恢复后的额度立刻参与拦截判定 —— 被夹到 1 分钟的老额度仍然拦得住`() = runTest {
        // ⚠️ v2.11 本轮规格变更：旧名字是"0 分钟额度马上生效"，断言的是
        // "恢复出来的 0 分钟额度立刻出现在 disabledPackages 里"。
        // 现在 0 不再是合法配置，它会被夹成 1 分钟 —— 但**这条断言的意图
        // 一个字都没降低**："备份恢复"与"拦截生效"必须接得上。
        // 1 分钟额度在"用满 1 分钟"之后照样拦（下面真的用满它）。
        val text = DataBackup.encode(
            DataBackup.Snapshot(quotas = listOf(quota("上次设成 0 的", limit = 0)))
        )
        val at = LocalDateTime.of(2026, 10, 14, 20, 0)
            .atZone(zone).toInstant().toEpochMilli()

        QuotaImport.restore(DataBackup.decode(text)!!.quotas, repo)

        // 恢复出来的是 1 分钟（夹取生效），而且是一条**正常**额度
        assertThat(repo.getAll().single().limitMinutes).isEqualTo(1)
        // 还没用量：1 分钟额度是"生效中"，看门狗该盯的包也已经登记
        assertThat(repo.disabledPackages(at)).isEmpty()
        assertThat(repo.boundPackages(at)).containsExactly(douyin)

        // 用满 1 分钟 → 真的被拦（恢复出来的额度不是摆设）
        val start = LocalDateTime.of(2026, 10, 14, 19, 0)
            .atZone(zone).toInstant().toEpochMilli()
        UsageRepository(usageDao, clock).recordSegment(douyin, start, start + 2 * 60_000L)

        assertThat(repo.disabledPackages(at).keys).containsExactly(douyin)
        assertThat(repo.disabledPackages(at).getValue(douyin).quotaName).isEqualTo("上次设成 0 的")
    }
}
