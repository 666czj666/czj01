package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import com.zilv.app.domain.lock.WindowScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 锁机规则与习惯的关联（v2.0）。
 *
 * 用户要求："把锁机功能和习惯功能结合，删除单独锁机"。
 * 实现上保留了 `lock_rules` 表，只是给它加 `habit_id` ——
 * 这里验证按习惯读写、幂等保存、以及关闭锁机。
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)，此时 15:00-18:00 的规则处于锁机态。
 */
class LockRuleHabitLinkTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var lockRuleDao: FakeLockRuleDao
    private lateinit var ruleAllowAppDao: FakeRuleAllowAppDao
    private lateinit var repo: LockRuleRepository

    @Before
    fun setUp() {
        lockRuleDao = FakeLockRuleDao()
        ruleAllowAppDao = FakeRuleAllowAppDao()
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        repo = LockRuleRepository(
            lockRuleDao,
            FakeAllowAppDao(),
            ruleAllowAppDao,
            FakeRuleCheckInDao(),
            FakeCheckInDao(),
            clock
        )
    }

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    /**
     * 空闲时段（16:00 时不生效），便于测试不受锁机守卫影响。
     *
     * ⚠️ 这里踩过一次：最初用 8:00-18:00，而测试时钟是 16:00 ——
     * 规则处于锁机中，后续的保存/删除全被守卫正确拦下。
     * **是夹具写错，不是代码错**。
     */
    private fun ruleFor(habitId: Long) = LockRule(
        habitId = habitId,
        name = "自习",
        checkInWindows = listOf(TimeWindow(6 * 60, 7 * 60)),
        windows = listOf(TimeWindow(7 * 60, 8 * 60)),
        unlockMode = UnlockMode.SPOT_CHECKIN,
        spotId = 7L
    )

    /** 16:00 时正处于锁机中的配置，用于验证守卫仍然生效。 */
    private fun ruleLockedNow(habitId: Long) = ruleFor(habitId).copy(
        checkInWindows = emptyList(),
        windows = listOf(TimeWindow(15 * 60, 18 * 60))
    )

    // ------------------------------------------------------------ 读写

    @Test
    fun `没配置过时返回 null`() = runTest {
        assertThat(repo.getRuleForHabit(1L)).isNull()
    }

    @Test
    fun `保存后能按习惯读回`() = runTest {
        repo.saveForHabit(1L, ruleFor(1L))

        val loaded = repo.getRuleForHabit(1L)!!
        assertThat(loaded.habitId).isEqualTo(1L)
        assertThat(loaded.name).isEqualTo("自习")
        assertThat(loaded.checkInWindows).containsExactly(TimeWindow(6 * 60, 7 * 60))
        assertThat(loaded.lockWindows).containsExactly(TimeWindow(7 * 60, 8 * 60))
    }

    @Test
    fun `不同习惯各自独立`() = runTest {
        repo.saveForHabit(1L, ruleFor(1L))
        repo.saveForHabit(2L, ruleFor(2L).copy(name = "另一个"))

        assertThat(repo.getRuleForHabit(1L)!!.name).isEqualTo("自习")
        assertThat(repo.getRuleForHabit(2L)!!.name).isEqualTo("另一个")
        assertThat(repo.getAllRules()).hasSize(2)
    }

    @Test
    fun `反复保存不会堆出多条配置`() = runTest {
        val first = repo.saveForHabit(1L, ruleFor(1L))
        val second = repo.saveForHabit(1L, ruleFor(1L).copy(name = "改过名"))
        val third = repo.saveForHabit(1L, ruleFor(1L).copy(name = "再改一次"))

        assertThat(second).isEqualTo(first)
        assertThat(third).isEqualTo(first)
        assertThat(repo.getAllRules()).hasSize(1)
        assertThat(repo.getRuleForHabit(1L)!!.name).isEqualTo("再改一次")
    }

    @Test
    fun `新字段能往返实体`() = runTest {
        repo.saveForHabit(9L, ruleFor(9L))
        val loaded = repo.getRuleForHabit(9L)!!
        assertThat(loaded.habitId).isEqualTo(9L)
        // checkInWindows 与 windows 不能串味
        assertThat(loaded.checkInWindows).hasSize(1)
        assertThat(loaded.lockWindows).hasSize(1)
        assertThat(loaded.checkInWindows.first().startMinute).isEqualTo(6 * 60)
        assertThat(loaded.lockWindows.first().startMinute).isEqualTo(7 * 60)
    }

    @Test
    fun `没有打卡窗口时也能往返`() = runTest {
        repo.saveForHabit(1L, ruleFor(1L).copy(checkInWindows = emptyList()))
        val loaded = repo.getRuleForHabit(1L)!!
        assertThat(loaded.checkInWindows).isEmpty()
        assertThat(loaded.hasCheckInWindow).isFalse()
    }

    // ------------------------------------------------------------ 删除

    @Test
    fun `关闭锁机后配置消失`() = runTest {
        repo.saveForHabit(1L, ruleFor(1L))
        repo.deleteForHabit(1L)

        assertThat(repo.getRuleForHabit(1L)).isNull()
        assertThat(repo.getAllRules()).isEmpty()
    }

    @Test
    fun `关闭锁机会连规则级白名单一起清掉`() = runTest {
        val id = repo.saveForHabit(1L, ruleFor(1L))
        repo.setRuleAllowApps(id, listOf("com.tencent.mm"))
        assertThat(ruleAllowAppDao.packageNamesFor(id)).isNotEmpty()

        repo.deleteForHabit(1L)

        assertThat(ruleAllowAppDao.packageNamesFor(id)).isEmpty()
    }

    @Test
    fun `关闭没有配置的习惯是空操作`() = runTest {
        repo.deleteForHabit(999L) // 不应抛异常
        assertThat(repo.getAllRules()).isEmpty()
    }

    @Test
    fun `关闭一个习惯不影响别的习惯`() = runTest {
        repo.saveForHabit(1L, ruleFor(1L))
        repo.saveForHabit(2L, ruleFor(2L))

        repo.deleteForHabit(1L)

        assertThat(repo.getRuleForHabit(1L)).isNull()
        assertThat(repo.getRuleForHabit(2L)).isNotNull()
    }

    // -------------------------------------------------- 锁机期间仍然受守卫

    @Test
    fun `锁机期间不能关闭习惯的锁机`() = runTest {
        repo.saveForHabit(1L, ruleLockedNow(1L))
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        val error = runCatching { repo.deleteForHabit(1L) }.exceptionOrNull()
        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRuleForHabit(1L)).isNotNull()
    }

    @Test
    fun `锁机期间不能修改习惯的锁机配置`() = runTest {
        repo.saveForHabit(1L, ruleLockedNow(1L))

        val error = runCatching {
            repo.saveForHabit(1L, ruleLockedNow(1L).copy(name = "偷偷改掉"))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRuleForHabit(1L)!!.name).isEqualTo("自习")
    }

    // -------------------------------------------------- allowDuringLock（本轮新增）
    //
    // 用户要求"锁机时间不能控制添加界面"，但**不能**退回到
    // "锁机时改一下规则就解锁"。分界线只有一条：
    //   这次改动会不会让**此刻正在生效的那把锁**消失？
    //
    // 于是 `allowDuringLock = true` 只在"这条习惯的锁此刻没在生效"时才真的放行。
    // 下面每条都对应那条分界线的一侧。

    @Test
    fun `添加页放行时，锁机期间新建一份配置是允许的`() = runTest {
        // 习惯 1 正在锁机（它自己的配置）；习惯 2 还没有任何配置 ——
        // 给习惯 2 新建配置不会让习惯 1 的那把锁消失
        repo.saveForHabit(1L, ruleLockedNow(1L))
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        val id = repo.saveForHabit(2L, ruleFor(2L), allowDuringLock = true)

        assertThat(repo.getRuleForHabit(2L)).isNotNull()
        assertThat(id).isNotEqualTo(0L)
        // 正在锁机的那条原封不动
        assertThat(repo.getRuleForHabit(1L)!!.lockWindows)
            .containsExactly(TimeWindow(15 * 60, 18 * 60))
    }

    @Test
    fun `添加页放行也不会允许改掉正在生效的那把锁`() = runTest {
        repo.saveForHabit(1L, ruleLockedNow(1L))

        // 把锁机窗口挪到一段此刻不生效的时间 —— 存下去就等于把当前那把锁关掉。
        // （规则本身仍然合法：有名称、有窗口，所以拦住它的只可能是锁机守卫）
        val error = runCatching {
            repo.saveForHabit(
                1L,
                ruleLockedNow(1L).copy(windows = listOf(TimeWindow(6 * 60, 7 * 60))),
                allowDuringLock = true
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(error!!.message).contains("锁机进行中")
        // 库里还是原来那份
        assertThat(repo.getRuleForHabit(1L)!!.lockWindows)
            .containsExactly(TimeWindow(15 * 60, 18 * 60))
    }

    @Test
    fun `添加页放行也不会允许删掉正在生效的那把锁`() = runTest {
        repo.saveForHabit(1L, ruleLockedNow(1L))

        val error = runCatching {
            repo.deleteForHabit(1L, allowDuringLock = true)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRuleForHabit(1L)).isNotNull()
    }

    @Test
    fun `添加页放行时，改一条停用的配置是允许的（它不影响那把锁）`() = runTest {
        // 习惯 1 的配置从一开始就是**停用**的（建它的时候还没人锁着，守卫放行）
        repo.saveForHabit(1L, ruleLockedNow(1L).copy(enabled = false))
        // 习惯 3 此刻真的锁着 —— 全局状态是 LOCKED，但与习惯 1 无关
        repo.saveForHabit(3L, ruleLockedNow(3L))
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        repo.saveForHabit(
            1L,
            ruleFor(1L).copy(name = "改过名的停用配置", enabled = false),
            allowDuringLock = true
        )
        assertThat(repo.getRuleForHabit(1L)!!.name).isEqualTo("改过名的停用配置")
    }

    @Test
    fun `添加页放行时，改一条不在锁机窗口内的配置是允许的`() = runTest {
        // 习惯 1 的窗口在 06:00-07:00（16:00 时不在窗口内）
        repo.saveForHabit(1L, ruleFor(1L))
        // 习惯 3 此刻锁着 —— 全局状态是 LOCKED，但与习惯 1 无关
        repo.saveForHabit(3L, ruleLockedNow(3L))
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        repo.saveForHabit(
            1L,
            ruleFor(1L).copy(name = "与那把锁无关"),
            allowDuringLock = true
        )
        assertThat(repo.getRuleForHabit(1L)!!.name).isEqualTo("与那把锁无关")
    }

    @Test
    fun `添加页放行时，删掉一条不在锁机的配置是允许的`() = runTest {
        repo.saveForHabit(1L, ruleFor(1L))          // 16:00 不在窗口内
        repo.saveForHabit(3L, ruleLockedNow(3L))    // 此刻锁着
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        repo.deleteForHabit(1L, allowDuringLock = true)

        assertThat(repo.getRuleForHabit(1L)).isNull()
        assertThat(repo.getRuleForHabit(3L)).isNotNull()
    }

    @Test
    fun `不放行时行为与本轮改动前完全一致`() = runTest {
        // 习惯 1 不在锁机窗口内，但**别的**规则此刻锁着 ——
        // 默认参数（allowDuringLock = false）下仍然拒绝，这是既有语义
        repo.saveForHabit(1L, ruleFor(1L))
        repo.saveForHabit(3L, ruleLockedNow(3L))

        val error = runCatching {
            repo.deleteForHabit(1L)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(RuleLockedException::class.java)
        assertThat(repo.getRuleForHabit(1L)).isNotNull()
    }

    @Test
    fun `没有配置时放行与否都不报错`() = runTest {
        repo.saveForHabit(3L, ruleLockedNow(3L))
        assertThat(repo.currentStatus().phase).isEqualTo(LockPhase.LOCKED)

        // 习惯 99 压根没有配置：'关闭锁机'是空操作，不该因为锁机守卫炸掉
        repo.deleteForHabit(99L, allowDuringLock = true)
        assertThat(repo.getRuleForHabit(99L)).isNull()
    }

    // -------------------------------------- 与调度器联动：窗口确实生效

    @Test
    fun `按习惯保存的规则会被调度器正确识别`() = runTest {
        // 这条用 8:00-18:00 的锁机窗口来验证真实生效
        val ruleId = repo.saveForHabit(
            1L,
            ruleFor(1L).copy(
                checkInWindows = listOf(TimeWindow(7 * 60, 8 * 60)),
                windows = listOf(TimeWindow(8 * 60, 18 * 60))
            )
        )
        val rules = repo.getEnabledRules()

        // 07:30 在打卡窗口内 → 待打卡
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T07:30")).phase)
            .isEqualTo(LockPhase.CHECKIN_DUE)
        // 10:00 在锁机窗口内且未打卡 → 锁机
        assertThat(WindowScheduler.statusFor(rules, dt("2026-10-14T10:00")).phase)
            .isEqualTo(LockPhase.LOCKED)
        // 10:00 已（在打卡窗口）打卡 → 解锁
        assertThat(
            WindowScheduler.statusFor(
                rules, dt("2026-10-14T10:00"), mapOf(ruleId to dt("2026-10-14T07:30"))
            ).phase
        ).isEqualTo(LockPhase.UNLOCKED)
    }
}
