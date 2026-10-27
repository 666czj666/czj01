package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.local.entity.RuleCheckInEntity
import com.zilv.app.domain.lock.InstantLock
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.lock.isInstantLock
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import com.zilv.app.data.repository.RuleLockedException

/**
 * [LockRuleRepository] 的单元测试（纯 JVM，内存 Fake DAO）。
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)。
 */
class LockRuleRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var lockRuleDao: FakeLockRuleDao
    private lateinit var allowAppDao: FakeAllowAppDao
    private lateinit var ruleAllowAppDao: FakeRuleAllowAppDao
    private lateinit var ruleCheckInDao: FakeRuleCheckInDao
    private lateinit var habitCheckInDao: FakeCheckInDao
    private lateinit var repo: LockRuleRepository

    @Before
    fun setUp() {
        lockRuleDao = FakeLockRuleDao()
        allowAppDao = FakeAllowAppDao()
        ruleAllowAppDao = FakeRuleAllowAppDao()
        ruleCheckInDao = FakeRuleCheckInDao()
        habitCheckInDao = FakeCheckInDao()
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        repo = LockRuleRepository(
            lockRuleDao,
            allowAppDao,
            ruleAllowAppDao,
            ruleCheckInDao,
            habitCheckInDao,
            clock
        )
    }

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    private val morningClass = LockRule(
        name = "早课",
        windows = listOf(TimeWindow(7 * 60, 9 * 60)),
        unlockMode = UnlockMode.SPOT_CHECKIN,
        spotId = 10L
    )

    private val sleep = LockRule(
        name = "睡前",
        windows = listOf(TimeWindow(22 * 60 + 30, 7 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    // ------------------------------------------------------------------ 规则

    @Test
    fun `保存并读回规则`() = runTest {
        val id = repo.save(morningClass)
        val loaded = repo.getRule(id)!!
        assertThat(loaded.name).isEqualTo("早课")
        assertThat(loaded.windows).containsExactly(TimeWindow(7 * 60, 9 * 60))
        assertThat(loaded.unlockMode).isEqualTo(UnlockMode.SPOT_CHECKIN)
        assertThat(loaded.spotId).isEqualTo(10L)
    }

    @Test
    fun `更新规则`() = runTest {
        val id = repo.save(morningClass)
        repo.save(repo.getRule(id)!!.copy(name = "早课（改）"))
        assertThat(repo.getRule(id)!!.name).isEqualTo("早课（改）")
        assertThat(repo.getAllRules()).hasSize(1)
    }

    @Test
    fun `非法规则被拒绝保存`() = runTest {
        // v2.2：打卡点不再参与规则校验（打卡点归习惯的打卡条件管），
        // 所以"没打卡点"是合法的 —— 下面的两条仍然是硬约束。
        // 无时间窗口
        assertThat(runCatching { repo.save(morningClass.copy(windows = emptyList())) }.isFailure).isTrue()
        // 空名称
        assertThat(runCatching { repo.save(morningClass.copy(name = " ")) }.isFailure).isTrue()
    }

    @Test
    fun `没有打卡点也能保存规则`() = runTest {
        // v2.2：解锁方法统一为"打卡（任何打卡方法都可以）或等时间走完"，
        // 规则上那个已废弃的 spotId 因此不再是保存门槛
        val id = repo.save(morningClass.copy(spotId = null))
        assertThat(repo.getRule(id)).isNotNull()
    }

    @Test
    fun `仅定时解锁规则不需要打卡点`() = runTest {
        val id = repo.save(sleep)
        assertThat(repo.getRule(id)).isNotNull()
    }

    @Test
    fun `启停规则`() = runTest {
        val id = repo.save(morningClass)
        assertThat(repo.getEnabledRules()).hasSize(1)
        repo.setEnabled(id, false)
        assertThat(repo.getEnabledRules()).isEmpty()
        assertThat(repo.getAllRules()).hasSize(1)
    }

    @Test
    fun `删除规则时同时清理其规则级白名单`() = runTest {
        val id = repo.save(morningClass)
        repo.setRuleAllowApps(id, listOf("com.tencent.mm"))
        assertThat(ruleAllowAppDao.packageNamesFor(id)).hasSize(1)

        repo.delete(id)

        assertThat(repo.getRule(id)).isNull()
        assertThat(ruleAllowAppDao.packageNamesFor(id)).isEmpty()
    }

    @Test
    fun `规则列表按 sortOrder 排序`() = runTest {
        repo.save(morningClass.copy(name = "第三", sortOrder = 3))
        repo.save(morningClass.copy(name = "第一", sortOrder = 1))
        repo.save(morningClass.copy(name = "第二", sortOrder = 2))
        assertThat(repo.getAllRules().map { it.name })
            .containsExactly("第一", "第二", "第三").inOrder()
    }

    // ---------------------------------------------------------------- 白名单

    @Test
    fun `内置白名单幂等预置且不可删除`() = runTest {
        val builtins = listOf(
            "com.android.systemui" to "系统界面",
            "com.android.settings" to "设置"
        )
        repo.ensureBuiltinAllowList(builtins)
        repo.ensureBuiltinAllowList(builtins) // 重复调用不应产生重复项

        assertThat(repo.getAllowedApps()).hasSize(2)
        assertThat(repo.getAllowedApps().all { it.builtin }).isTrue()

        // 内置项删不掉 —— 这是不把手机搞坏的安全底线
        assertThat(repo.removeUserApp("com.android.systemui")).isFalse()
        assertThat(repo.getAllowedApps()).hasSize(2)
    }

    @Test
    fun `用户白名单可增可删`() = runTest {
        repo.addUserApp("com.tencent.mm", "微信")
        assertThat(repo.getAllowedApps()).hasSize(1)
        assertThat(repo.getAllowedApps().single().builtin).isFalse()

        assertThat(repo.removeUserApp("com.tencent.mm")).isTrue()
        assertThat(repo.getAllowedApps()).isEmpty()
    }

    @Test
    fun `内置预置不会覆盖同名的用户项`() = runTest {
        repo.addUserApp("com.tencent.mm", "微信")
        repo.ensureBuiltinAllowList(listOf("com.tencent.mm" to "微信（内置）"))
        val app = repo.getAllowedApps().single()
        assertThat(app.builtin).isFalse()
        assertThat(app.label).isEqualTo("微信")
    }

    @Test
    fun `有效白名单为全局与规则级的并集`() = runTest {
        repo.ensureBuiltinAllowList(listOf("com.android.systemui" to "系统界面"))
        repo.addUserApp("com.tencent.mm", "微信")
        val ruleId = repo.save(morningClass)
        repo.setRuleAllowApps(ruleId, listOf("com.eg.android.AlipayGphone"))

        val effective = repo.effectiveAllowList(listOf(ruleId))

        assertThat(effective).containsExactly(
            "com.android.systemui",
            "com.tencent.mm",
            "com.eg.android.AlipayGphone"
        )
    }

    @Test
    fun `停用的全局白名单项不出现在有效白名单中`() = runTest {
        repo.addUserApp("com.tencent.mm", "微信")
        repo.setAppEnabled("com.tencent.mm", false)
        assertThat(repo.effectiveAllowList()).isEmpty()
    }

    @Test
    fun `多条规则的规则级白名单取并集`() = runTest {
        val a = repo.save(morningClass.copy(name = "A"))
        val b = repo.save(morningClass.copy(name = "B"))
        repo.setRuleAllowApps(a, listOf("pkg.a"))
        repo.setRuleAllowApps(b, listOf("pkg.b"))

        assertThat(repo.effectiveAllowList(listOf(a, b))).containsExactly("pkg.a", "pkg.b")
    }

    // ------------------------------------------------------------------ 状态

    @Test
    fun `无规则时状态为空闲`() = runTest {
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `窗口内未打卡为锁机`() = runTest {
        repo.save(morningClass)
        val status = repo.currentStatus(dt("2026-10-14T08:00"))
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.rule!!.name).isEqualTo("早课")
        assertThat(status.minutesUntilChange).isEqualTo(60)
    }

    @Test
    fun `窗口内已打卡为已解锁`() = runTest {
        val id = repo.save(morningClass)
        val status = repo.currentStatus(
            dt("2026-10-14T08:00"),
            mapOf(id to dt("2026-10-14T07:30"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `停用的规则不参与状态判定`() = runTest {
        val id = repo.save(morningClass)
        repo.setEnabled(id, false)
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.IDLE)
    }

    @Test
    fun `跨零点窗口在次日凌晨为锁机`() = runTest {
        repo.save(sleep)
        val status = repo.currentStatus(dt("2026-10-15T03:00"))
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------ 打卡解锁联动（M5 的关键闭环）

    @Test
    fun `打卡落库后状态自动从锁机变为已解锁`() = runTest {
        val ruleId = repo.save(morningClass)

        // 打卡前：锁机中
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.LOCKED)

        // 写入一次解锁打卡（07:30，在提前 15 分钟有效期内）
        ruleCheckInDao.insert(
            com.zilv.app.data.local.entity.RuleCheckInEntity(
                ruleId = ruleId,
                spotId = 10L,
                checkedAtMillis = dt("2026-10-14T07:30")
                    .atZone(zone).toInstant().toEpochMilli(),
                epochDay = 20_000L,
                lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
                status = "ON_TIME"
            )
        )

        // 打卡后：已解锁，且**无需调用方手工传 checkInAtByRule**
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `打了别的规则的卡不影响本规则`() = runTest {
        val target = repo.save(morningClass)
        val other = repo.save(morningClass.copy(name = "另一条"))

        ruleCheckInDao.insert(
            com.zilv.app.data.local.entity.RuleCheckInEntity(
                ruleId = other,
                spotId = 10L,
                checkedAtMillis = dt("2026-10-14T07:30").atZone(zone).toInstant().toEpochMilli(),
                epochDay = 20_000L,
                lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
                status = "ON_TIME"
            )
        )

        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.LOCKED)
        assertThat(target).isNotEqualTo(other)
    }

    @Test
    fun `显式传入的打卡时刻覆盖数据库读取`() = runTest {
        val ruleId = repo.save(morningClass)
        // 数据库里没有打卡记录，但显式传入一个有效时刻
        val status = repo.currentStatus(
            dt("2026-10-14T08:00"),
            mapOf(ruleId to dt("2026-10-14T07:50"))
        )
        assertThat(status.phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `过早的打卡记录不能解锁`() = runTest {
        val ruleId = repo.save(morningClass)
        ruleCheckInDao.insert(
            com.zilv.app.data.local.entity.RuleCheckInEntity(
                ruleId = ruleId,
                spotId = 10L,
                // 06:00，早于 06:45 的提前打卡下限
                checkedAtMillis = dt("2026-10-14T06:00").atZone(zone).toInstant().toEpochMilli(),
                epochDay = 20_000L,
                lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
                status = "ON_TIME"
            )
        )
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------------ 锁机期间禁止改动规则
    //
    // 用户要求："锁机时不可以编辑锁机规则，否则在锁机时直接关闭规则就解锁了"。
    // 守卫放在仓库层（而不是只挡界面），这里直接验证。

    /** 16:00 时正处于锁机中的规则。 */
    private val afternoonClass = LockRule(
        name = "下午自习",
        windows = listOf(TimeWindow(15 * 60, 18 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    /** 16:10 开始 —— 16:00 时处于预警（WARN），还没真正锁上。 */
    private val soonClass = LockRule(
        name = "马上开始",
        windows = listOf(TimeWindow(16 * 60 + 10, 18 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    @Test
    fun `锁机期间不能停用规则`() = runTest {
        val id = repo.save(afternoonClass)
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        val error = runCatching { repo.setEnabled(id, false) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        // 关键：规则仍然处于启用状态，不能被偷偷改掉
        assertThat(repo.getRule(id)!!.enabled).isTrue()
    }

    @Test
    fun `锁机期间不能启用规则`() = runTest {
        val id = repo.save(afternoonClass)
        val error = runCatching { repo.setEnabled(id, true) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
    }

    @Test
    fun `锁机期间不能删除规则`() = runTest {
        val id = repo.save(afternoonClass)

        val error = runCatching { repo.delete(id) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRule(id)).isNotNull()
    }

    @Test
    fun `锁机期间不能修改规则内容`() = runTest {
        val id = repo.save(afternoonClass)
        val rule = repo.getRule(id)!!

        val error = runCatching { repo.save(rule.copy(name = "偷偷改掉")) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRule(id)!!.name).isEqualTo("下午自习")
    }

    @Test
    fun `锁机期间不能新增规则`() = runTest {
        repo.save(afternoonClass)
        val error = runCatching { repo.save(sleep) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
    }

    @Test
    fun `错误信息是可读的中文，能直接给用户看`() = runTest {
        val id = repo.save(afternoonClass)
        val error = runCatching { repo.setEnabled(id, false) }.exceptionOrNull()!!
        assertThat(error.message).contains("锁机进行中")
    }

    @Test
    fun `空闲时改动规则不受影响`() = runTest {
        // 早课 7:00-9:00，16:00 时是空闲
        val id = repo.save(morningClass)
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.IDLE)

        repo.setEnabled(id, false)
        assertThat(repo.getRule(id)!!.enabled).isFalse()
        repo.delete(id)
        assertThat(repo.getRule(id)).isNull()
    }

    @Test
    fun `预警中仍允许改动规则`() = runTest {
        // 还没真正锁上，允许抓紧改
        val id = repo.save(soonClass)
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.WARN)

        repo.setEnabled(id, false)
        assertThat(repo.getRule(id)!!.enabled).isFalse()
    }

    // -------------------------------------------- 锁机期间禁止改动白名单
    //
    // 用户要求："锁机时不能更改白名单"。
    // 否则把要用的应用加进白名单就等于绕开拦截。

    @Test
    fun `锁机期间不能添加用户白名单`() = runTest {
        repo.save(afternoonClass)
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        val error = runCatching { repo.addUserApp("com.example.game", "游戏") }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getAllowedApps().map { it.packageName })
            .doesNotContain("com.example.game")
    }

    @Test
    fun `锁机期间不能移除用户白名单`() = runTest {
        repo.addUserApp("com.example.app", "某应用")
        val id = repo.save(afternoonClass)

        val error = runCatching { repo.removeUserApp("com.example.app") }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(id).isGreaterThan(0L)
        assertThat(repo.getAllowedApps().map { it.packageName }).contains("com.example.app")
    }

    @Test
    fun `锁机期间不能启停白名单项`() = runTest {
        repo.addUserApp("com.example.app", "某应用")
        repo.save(afternoonClass)

        val error = runCatching { repo.setAppEnabled("com.example.app", false) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getAllowedApps().first { it.packageName == "com.example.app" }.enabled).isTrue()
    }

    @Test
    fun `空闲时白名单可以正常改动`() = runTest {
        repo.save(morningClass) // 16:00 时是空闲

        repo.addUserApp("com.example.app", "某应用")
        assertThat(repo.getAllowedApps().map { it.packageName }).contains("com.example.app")

        repo.setAppEnabled("com.example.app", false)
        assertThat(repo.getAllowedApps().first { it.packageName == "com.example.app" }.enabled).isFalse()

        assertThat(repo.removeUserApp("com.example.app")).isTrue()
        assertThat(repo.getAllowedApps().map { it.packageName }).doesNotContain("com.example.app")
    }

    @Test
    fun `内置白名单播种不受锁机影响`() = runTest {
        // 这条**必须**放行：ensureBuiltinAllowList 由无障碍服务在连接时调用，
        // 被拦下会导致桌面 / 输入法进不了白名单 —— 用户按 Home 就被遮挡层困死
        repo.save(afternoonClass)
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        repo.ensureBuiltinAllowList(listOf("com.miui.home" to "桌面"))

        assertThat(repo.getAllowedApps().map { it.packageName }).contains("com.miui.home")
    }

    // ==================================================== 即时锁机（v2.12）
    //
    // 用户要求：「添加即时锁机功能，锁机可选时间从 0 分到 12 小时。」
    // 并要求"复用既有的锁机链路（LockRule + WindowScheduler + 前台服务 +
    // 无障碍拦截），不要另做一套拦截"。
    //
    // 所以下面这一组验的就是"**落一条普通规则，整条链路就自己动起来了**"：
    // currentStatus 能算出 LOCKED、getEnabledRules 能把它交给拦截服务、
    // 窗口到点自己解除、旧规则会被清掉。

    @Test
    fun `即时锁机落库后当前状态立刻是锁机中`() = runTest {
        val id = repo.startInstantLock(minutes = 30)

        val status = repo.currentStatus()
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.rule?.id).isEqualTo(id)
        assertThat(status.rule?.name).isEqualTo(InstantLock.NAME)
        // 12 点整开始锁 30 分钟 -> 还剩 30 分钟（时钟固定在 16:00）
        assertThat(status.minutesUntilChange).isEqualTo(30L)
    }

    @Test
    fun `即时锁机会进入 getEnabledRules —— 拦截链路看的就是它`() = runTest {
        repo.startInstantLock(minutes = 60)

        val rules = repo.getEnabledRules()
        assertThat(rules.map { it.name }).contains(InstantLock.NAME)
        assertThat(rules.single { it.name == InstantLock.NAME }.isInstantLock).isTrue()
    }

    @Test
    fun `到点之后自己解除，不用任何人来关`() = runTest {
        repo.startInstantLock(minutes = 30)

        // 窗口内
        assertThat(repo.currentStatus(at = dt("2026-10-14T16:29")).phase)
            .isEqualTo(LockPhase.LOCKED)
        // 到点那一刻（半开区间）就已经不在窗口里了
        assertThat(repo.currentStatus(at = dt("2026-10-14T16:30")).phase)
            .isNotEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `打卡对即时锁机无效 —— 只能到点解锁`() = runTest {
        val id = repo.startInstantLock(minutes = 30)
        val rule = repo.getRule(id)!!
        // 显式钉住生成规则上的那个开关（用户倾向"仅到点解锁"）
        assertThat(rule.allowCheckInUnlock).isFalse()

        // 即使有人往 rule_check_ins 里写了打卡，状态仍然是 LOCKED
        ruleCheckInDao.insert(
            RuleCheckInEntity(
                ruleId = id,
                spotId = 1L,
                checkedAtMillis = dt("2026-10-14T16:05")
                    .atZone(zone).toInstant().toEpochMilli(),
                epochDay = 0L,
                lat = 0.0,
                lng = 0.0,
                accuracyM = 5f,
                distanceM = 1.0,
                status = "ON_TIME"
            )
        )
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `已经在锁机时也能再锁一把 —— 新建只会更紧，不会解开任何东西`() = runTest {
        // 既有的习惯锁机此刻正在生效
        val habitRuleId = repo.save(
            LockRule(name = "早课", windows = listOf(TimeWindow(15 * 60, 18 * 60)))
        )
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        // 用户这时点「即时锁机」：必须成功（那道"锁机期间禁止改动"的守卫
        // 要防的是"把正在生效的锁改掉/关掉"，而新建一把只会更紧）
        val instantId = repo.startInstantLock(minutes = 120)

        assertThat(instantId).isNotEqualTo(habitRuleId)
        assertThat(repo.getEnabledRules()).hasSize(2)
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `已结束的即时锁机会在下一次创建时被清掉 —— 库里不会越攒越多`() = runTest {
        val first = repo.startInstantLock(minutes = 30)
        // 这条规则只在**今天**有效，所以"已经结束"要靠窗口算：
        // 用一个远在窗口之后的时刻去清
        val purged = repo.purgeEndedInstantLocks(at = dt("2026-10-14T23:00"))
        assertThat(purged).isEqualTo(1)
        assertThat(repo.getRule(first)).isNull()
        assertThat(repo.getAllRules()).isEmpty()
    }

    @Test
    fun `还没结束的即时锁机不会被清理`() = runTest {
        val id = repo.startInstantLock(minutes = 120)
        val purged = repo.purgeEndedInstantLocks(at = dt("2026-10-14T16:30"))
        assertThat(purged).isEqualTo(0)
        assertThat(repo.getRule(id)).isNotNull()
    }

    @Test
    fun `习惯规则与规划规则永远不会被当成即时锁机清掉`() = runTest {
        val habitId = repo.save(afternoonClass)
        // 清一遍（此刻 afternoonClass 还没结束，本来就清不掉）
        repo.purgeEndedInstantLocks(at = dt("2026-10-14T23:00"))
        assertThat(repo.getRule(habitId)).isNotNull()
    }

    @Test
    fun `即时锁机的状态查询只算即时锁那几条`() = runTest {
        assertThat(repo.instantLockStatus().phase).isEqualTo(LockPhase.IDLE)

        repo.startInstantLock(minutes = 45)
        val status = repo.instantLockStatus()
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
        assertThat(status.rule?.name).isEqualTo(InstantLock.NAME)
        assertThat(status.minutesUntilChange).isEqualTo(45L)
    }

    @Test
    fun `0 分是合法时长 —— 立刻锁上，到下一个整分钟解除`() = runTest {
        val id = repo.startInstantLock(minutes = 0)
        val rule = repo.getRule(id)!!
        // 16:00 -> 16:01（不是"全天"，那是 TimeWindow 起止相同时的跨零点语义）
        assertThat(rule.effectiveLockWindows).containsExactly(TimeWindow(16 * 60, 16 * 60 + 1))
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `12 小时的即时锁占满上限`() = runTest {
        val id = repo.startInstantLock(minutes = 12 * 60)
        val rule = repo.getRule(id)!!
        // 16:00 -> 次日 04:00（跨零点）
        assertThat(rule.effectiveLockWindows.single().durationMinutes).isEqualTo(720)
        assertThat(rule.effectiveLockWindows.single().crossesMidnight).isTrue()
    }
}