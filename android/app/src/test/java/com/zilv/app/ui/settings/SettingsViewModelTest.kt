package com.zilv.app.ui.settings

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.repository.FakeAllowAppDao
import com.zilv.app.data.repository.FakeCheckInDao
import com.zilv.app.data.repository.FakeLockRuleDao
import com.zilv.app.data.repository.FakeRuleAllowAppDao
import com.zilv.app.data.repository.FakeRuleCheckInDao
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.lock.UnlockMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [SettingsViewModel] 的单元测试（v2.14）。
 *
 * ## 被测的是什么
 *
 * 用户原话：「锁机时设置白名单按钮改成灰色，点击不能有反应。」
 *
 * 界面的"灰 + 点不动"是 Compose 的事（`clickable(enabled = false)`），
 * 但**"现在是不是锁机"这个判断**是这里 —— 而它决定用户会不会看到
 * "锁着的时候还能点进去、点完弹一句 RuleLockedException"。
 *
 * 三条要求被钉住：
 *  1. 锁机中 → true（按钮置灰）
 *  2. 没锁机 → false（按钮可用）—— 不能因为"怕出错"就永远置灰
 *  3. **状态会变**：锁机结束后再刷一次必须变回 false（否则用户永远改不了白名单）
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)，与 `LockRuleEditViewModelTest` 同一口径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var lockDao: FakeLockRuleDao
    private lateinit var repo: LockRuleRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        lockDao = FakeLockRuleDao()
        repo = LockRuleRepository(
            lockDao,
            FakeAllowAppDao(),
            FakeRuleAllowAppDao(),
            FakeRuleCheckInDao(),
            FakeCheckInDao(),
            clock
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 16:00 时正在锁机中的配置（15:00 ~ 18:00）。 */
    private val afternoonLock = LockRule(
        name = "下午自习",
        windows = listOf(TimeWindow(15 * 60, 18 * 60)),
        unlockMode = UnlockMode.TIME_ONLY
    )

    @Test
    fun `没有任何规则时不算锁机 —— 白名单入口必须可用`() = runTest {
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        assertThat(vm.locked.value).isFalse()
    }

    @Test
    fun `锁机期间为 true —— 设置页据此把白名单入口置灰`() = runTest {
        repo.save(afternoonLock)

        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        assertThat(vm.locked.value).isTrue()
    }

    @Test
    fun `锁机结束（或规则被停用）后刷新会变回 false —— 否则用户永远改不了白名单`() =
        runTest {
            repo.save(afternoonLock)
            val vm = SettingsViewModel(repo)
            advanceUntilIdle()
            assertThat(vm.locked.value).isTrue()

            // 模拟"锁机结束"：直接把库里的规则停用（不走仓库守卫 ——
            // 锁机期间仓库会抛 RuleLockedException，那正是界面要置灰的原因）
            val id = lockDao.getAll().single().id
            lockDao.setEnabled(id, false)

            vm.refreshLockState()
            advanceUntilIdle()

            assertThat(vm.locked.value).isFalse()
        }

    @Test
    fun `锁机开始后刷新会变成 true —— 界面停在设置页也要跟着变`() = runTest {
        val vm = SettingsViewModel(repo)
        advanceUntilIdle()
        assertThat(vm.locked.value).isFalse()

        // 模拟"时间走到锁机窗口"：库里的规则变成生效
        repo.save(afternoonLock)

        vm.refreshLockState()
        advanceUntilIdle()

        assertThat(vm.locked.value).isTrue()
    }

    @Test
    fun `不在窗口内的规则不算锁机`() = runTest {
        // 20:00 才开始，现在是 16:00
        repo.save(
            LockRule(
                name = "晚上",
                windows = listOf(TimeWindow(20 * 60, 22 * 60)),
                unlockMode = UnlockMode.TIME_ONLY
            )
        )

        val vm = SettingsViewModel(repo)
        advanceUntilIdle()

        assertThat(vm.locked.value).isFalse()
    }
}
