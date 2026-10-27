package com.zilv.app.service

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.lock.LockPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 锁机通知**每分钟刷新**的节奏控制（v2.11，用户第 1 条要求）。
 *
 * ## 这一组测试在钉什么
 *
 * 用户那句话可以拆成两条**都必须成立**的断言：
 *  1. 锁机中 → **每分钟**真的重算一次（否则"还有 X 分钟"永远停在旧值）
 *  2. 不在锁机态 → **一次都不能再戳**（否则就是后台空转耗电）
 *
 * 第 2 条比第 1 条重要得多：一个"解锁后还在每分钟跑"的 ticker 在真机上
 * 不会报任何错，只会悄悄耗电 —— **没有测试就永远发现不了**。
 *
 * ## 时间怎么推
 *
 * 全程用 `runTest` 的**虚拟时间**（[advanceTimeBy] + [runCurrent]）：
 * "过了 3 分钟正好戳了 3 次"这种断言在真机上要等 3 分钟才能验一次。
 *
 * ⚠️ 两条纪律（都踩过）：
 *  - **不用 `advanceUntilIdle()` 去推进一个活着的 ticker**：它每一分钟就会
 *    重新排一个任务，调度器永远不 idle，`runTest` 会等到超时（60 秒）再报
 *    "test coroutine is not completing"。改用 [advanceTimeBy] + [runCurrent]。
 *  - **`advanceTimeBy(n)` 只运行"严格早于" n 时刻的任务**，正好落在新时刻的
 *    任务要再 [runCurrent] 一下才会执行。
 *
 * 本测试是**纯 JVM** 的：它只碰 [LockNotificationTicker] 那个纯对象，
 * 不碰 Service、不碰通知、不碰 Android。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockNotificationTickerTest {

    /** 模拟"一次重算要花多久"（查库 + 算用量 + 写通知）。 */
    private val WORK_MILLIS = 5_000L

    /** 推进一步并让"正好落在这一刻"的任务也跑完。 */
    private fun kotlinx.coroutines.test.TestScope.tick() {
        advanceTimeBy(LockNotificationTicker.TICK_MILLIS)
        runCurrent()
    }

    /**
     * 解锁之后 ticker 是**自己结束**的，而它结束的时刻是"下一次 delay 到点"。
     *
     * ## 为什么不是立刻结束
     *
     * ticker 循环体是 `delay(60s)` → 看阶段 → `onTick()`。阶段在它睡着的时候
     * 变掉时，它要等这一觉睡醒才发现 —— 也就是最多再等一分钟。
     *
     * ## 那"解锁后还在跑一分钟"算空转吗？**不算**
     *
     * 真正的解锁路径上，`LockForegroundService.syncNotificationTicker` 会在
     * 每一次 `evaluate()` 里显式 `cancel` 这个 Job（见 Service 的 KDoc），
     * 所以线上根本走不到"等它自己醒"这一步。这里的 [wakeUp] 是给
     * **没有那次 cancel** 的兜底路径写的测试：它必须也会停。
     */
    private fun kotlinx.coroutines.test.TestScope.wakeUp() {
        advanceTimeBy(LockNotificationTicker.TICK_MILLIS)
        runCurrent()
    }

    // ============================================================ 该不该走

    @Test
    fun `只有锁机中才需要 ticker`() {
        assertThat(LockNotificationTicker.intervalMillis(LockPhase.LOCKED))
            .isEqualTo(LockNotificationTicker.TICK_MILLIS)
    }

    @Test
    fun `其余阶段一律不刷新 —— 这里返回 null 就等于不会后台空转`() {
        assertThat(LockNotificationTicker.intervalMillis(LockPhase.IDLE)).isNull()
        assertThat(LockNotificationTicker.intervalMillis(LockPhase.WARN)).isNull()
        assertThat(LockNotificationTicker.intervalMillis(LockPhase.CHECKIN_DUE)).isNull()
        assertThat(LockNotificationTicker.intervalMillis(LockPhase.UNLOCKED)).isNull()
    }

    @Test
    fun `还没算过状态（null）时也不刷新`() {
        assertThat(LockNotificationTicker.intervalMillis(null)).isNull()
    }

    @Test
    fun `刷新间隔就是一分钟`() {
        assertThat(LockNotificationTicker.TICK_MILLIS).isEqualTo(60_000L)
    }

    // ============================================================ 节奏

    @Test
    fun `锁机中每分钟重算一次`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0

        val job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()

        tick()
        assertThat(ticks).isEqualTo(1)

        tick()
        assertThat(ticks).isEqualTo(2)

        tick()
        assertThat(ticks).isEqualTo(3)

        job.cancel()
    }

    @Test
    fun `进入锁机后不会立刻多推一条 —— 那一次由 evaluate 自己发`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0

        val job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()

        // 一刻都还没过 -> 一次都不该戳。
        // 这正是"通知闪烁"的历史坑：ticker 抢在 evaluate 后面再推一条同样的通知。
        assertThat(ticks).isEqualTo(0)

        job.cancel()
    }

    @Test
    fun `解锁后 ticker 自己结束并停止戳`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0

        val job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()

        tick()
        assertThat(ticks).isEqualTo(1)

        // 打卡成功 / 到点解锁 —— 阶段一变，ticker 必须收手
        phases.value = LockPhase.UNLOCKED
        runCurrent()
        // 线上这条路是 Service 显式 cancel 的（见 wakeUp 的 KDoc）；
        // 这里先验"没有那次 cancel 时它自己也会停"。
        assertThat(job.isActive).isTrue()   // 还在睡下一觉，尚未醒来
        wakeUp()

        assertThat(job.isActive).isFalse()
        assertThat(ticks).isEqualTo(1)      // 醒来时看到阶段变了 -> 不再戳

        // 再等十分钟也不该有任何一次额外的重算（这一条就是"不空转"）
        repeat(10) { tick() }
        assertThat(ticks).isEqualTo(1)
    }

    @Test
    fun `每一条停止路径都只是阶段变化 —— 逐个阶段验证都会停`() = runTest {
        // 解锁、打卡成功、规则被停用、规划窗口结束…… 在这套逻辑里**全都是**
        // "阶段不再是 LOCKED"这一件事，所以逐个阶段各验一遍。
        listOf(
            LockPhase.IDLE,
            LockPhase.WARN,
            LockPhase.CHECKIN_DUE,
            LockPhase.UNLOCKED
        ).forEach { next ->
            val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
            var ticks = 0
            val job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
            runCurrent()

            tick()
            assertThat(ticks).isEqualTo(1)

            phases.value = next
            runCurrent()
            // 有那次显式取消时是立刻结束；没有时最多再等一觉就结束。
            // 两条路都必须停 —— 这里验的是后者（兜底路径）。
            if (job.isActive) wakeUp()
            assertThat(job.isActive).isFalse()

            repeat(5) { tick() }
            // 停下来之后一次都不许多 —— 每个阶段各断言一次
            assertThat(ticks).isEqualTo(1)

            job.cancel()
        }
    }

    @Test
    fun `不在锁机态时启动的 ticker 一次都不戳`() = runTest {
        var ticks = 0
        val idle = LockNotificationTicker.startIfTicking(
            MutableStateFlow<LockPhase?>(LockPhase.IDLE), this
        ) { ticks++ }
        runCurrent()

        // 连"还没算过状态"也一样：服务刚起来还没 evaluate 时不能先跑起来
        val unknown = LockNotificationTicker.startIfTicking(
            MutableStateFlow<LockPhase?>(null), this
        ) { ticks++ }
        runCurrent()

        repeat(10) { tick() }
        assertThat(ticks).isEqualTo(0)

        idle.cancel()
        unknown.cancel()
    }

    @Test
    fun `空闲启动的 ticker 会在稍后进入锁机时自动开始走`() = runTest {
        // 服务是先起来的（可能还没到锁机点），ticker 必须先等在那里。
        // 如果"没等到合适的阶段就直接结束"，锁机期间就永远不会刷新 ——
        // 那正是本次要修的 bug。
        val phases = MutableStateFlow<LockPhase?>(LockPhase.IDLE)
        var ticks = 0

        val job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()
        assertThat(job.isActive).isTrue()   // 等着的 ticker 仍然是活着的

        repeat(5) { tick() }
        assertThat(ticks).isEqualTo(0)      // 等的时候一次都不戳

        phases.value = LockPhase.LOCKED     // 锁机开始
        runCurrent()

        tick()
        assertThat(ticks).isEqualTo(1)

        job.cancel()
    }

    @Test
    fun `服务销毁时取消 ticker 之后就彻底安静`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0
        val job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()

        tick()
        assertThat(ticks).isEqualTo(1)

        // onDestroy：先取消 ticker，再撤 scope
        job.cancel()
        runCurrent()

        repeat(10) { tick() }
        assertThat(ticks).isEqualTo(1)
    }

    @Test
    fun `onTick 自己兜住异常之后下一次照常来`() = runTest {
        // Service 那边的 onTick 把 evaluate() 包在 runCatching 里（未捕获的异常
        // 会让 ticker 这个子协程直接结束，症状是"倒计时从此卡住"却毫无提示）。
        // 这里逐字模拟那层兜底，确认"一次失败不会让刷新永久停摆"。
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var attempts = 0
        var succeeded = 0
        val job = LockNotificationTicker.startIfTicking(phases, this) {
            attempts++
            runCatching {
                if (attempts == 1) throw IllegalStateException("库忙")
                succeeded++
            }
        }
        runCurrent()

        tick()
        assertThat(attempts).isEqualTo(1)
        assertThat(succeeded).isEqualTo(0)

        tick()
        assertThat(attempts).isEqualTo(2)
        assertThat(succeeded).isEqualTo(1)
        assertThat(job.isActive).isTrue()

        job.cancel()
    }

    @Test
    fun `scope 被取消时 ticker 一起结束`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0

        val child = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        LockNotificationTicker.startIfTicking(phases, child) { ticks++ }
        runCurrent()

        // 子作用域用的是真实调度器，它自己的 60 秒延迟不随虚拟时间走 ——
        // 这里验的是**取消**这条路径：scope 一撤，ticker 不再有任何机会戳。
        child.cancel()
        runCurrent()

        repeat(5) { tick() }
        assertThat(ticks).isEqualTo(0)
    }

    @Test
    fun `解锁再锁机会重新开始计时`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0

        val first = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()
        tick()
        assertThat(ticks).isEqualTo(1)

        phases.value = LockPhase.UNLOCKED
        runCurrent()
        if (first.isActive) wakeUp()
        assertThat(first.isActive).isFalse()

        // 下一段锁机：Service 会再 start 一个 ticker（这里直接模拟那一步）
        phases.value = LockPhase.LOCKED
        val second = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()
        tick()
        assertThat(ticks).isEqualTo(2)

        second.cancel()
    }

    @Test
    fun `锁机中反复重算不会叠加出多个 ticker`() = runTest {
        // Service 每次 evaluate 都会调用 syncNotificationTicker，
        // 而 evaluate 一分钟至少被叫一次（切应用、闹钟、ticker 自己）。
        // 去重失败的症状是"每分钟 N 条通知"，这里把那个判据连算 5 次。
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var ticks = 0
        var job: Job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
        runCurrent()

        repeat(5) {
            // syncNotificationTicker 的判据逐字搬过来：活着就不再开
            if (job.isActive != true) {
                job = LockNotificationTicker.startIfTicking(phases, this) { ticks++ }
                runCurrent()
            }
        }

        tick()
        assertThat(ticks).isEqualTo(1)

        job.cancel()
    }

    @Test
    fun `一次 tick 的耗时不会打乱节奏 —— 每分钟是按时间算的而不是按次数`() = runTest {
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        val firedAt = mutableListOf<Long>()
        val job = LockNotificationTicker.startIfTicking(phases, this) {
            firedAt += testScheduler.currentTime
            delay(WORK_MILLIS)   // 一次重算花 5 秒（查库 + 算用量 + 写通知）
        }
        runCurrent()

        // 第 1 分钟到 -> 第 1 次
        tick()
        assertThat(firedAt).hasSize(1)
        assertThat(firedAt[0]).isEqualTo(LockNotificationTicker.TICK_MILLIS)

        // ⚠️ delay 是按"上一次醒来"排的，而虚拟时钟在 delay 里**不前进** ——
        // 所以中间花掉的那 5 秒会让**前两次**之间多出 5 秒：第 2 次在 t = 125s。
        // 这是 `delay(60s) + 工作耗时` 这个结构的真实语义，不是 bug。
        // 真实环境里"工作耗时"是几十毫秒级的查库 + 一次 notify，可以忽略。
        advanceTimeBy(64_000)
        runCurrent()
        assertThat(firedAt).hasSize(1)   // 还差 1 秒，不该提前戳

        advanceTimeBy(1_000)
        runCurrent()
        assertThat(firedAt).hasSize(2)
        assertThat(firedAt[1]).isEqualTo(2 * LockNotificationTicker.TICK_MILLIS + WORK_MILLIS)

        // 再往后跑 5 个整分钟：**每一次都应该发生**，而且间隔不许超过
        // "一个周期 + 一次工作耗时"（这一条就是"每分钟刷新一次"的守门员：
        // 少了任何一次都会让它红）。
        val maxGap = LockNotificationTicker.TICK_MILLIS + WORK_MILLIS
        repeat(6) { tick() }
        assertThat(firedAt.size).isAtLeast(7)   // 1 + 1 + 至少 5 次
        firedAt.zipWithNext { a, b ->
            assertThat(b - a).isAtMost(maxGap)
        }

        job.cancel()
    }

    @Test
    fun `ticker 结束不会牵连别的协程`() = runTest {
        // 打卡成功那条协程不应该被 ticker 的退出牵连。
        // ⚠️ 这里同样不用 advanceUntilIdle：旁观协程是个无限循环，
        // 虚拟时钟永远不会 idle。
        val phases = MutableStateFlow<LockPhase?>(LockPhase.LOCKED)
        var other = 0
        val otherJob = launch { while (true) { delay(1_000); other++ } }

        val job = LockNotificationTicker.startIfTicking(phases, this) { }
        runCurrent()
        tick()
        assertThat(other).isGreaterThan(0)

        phases.value = LockPhase.UNLOCKED
        runCurrent()
        if (job.isActive) wakeUp()
        assertThat(job.isActive).isFalse()

        advanceTimeBy(3_000)
        runCurrent()
        assertThat(other).isGreaterThan(0)

        otherJob.cancel()
        job.cancel()
    }
}
