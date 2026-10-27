package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.mapper.toEntity
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.LockRule
import com.zilv.app.domain.lock.TimeWindow
import com.zilv.app.domain.model.CheckInStatus
import com.zilv.app.domain.model.Habit
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * **习惯打卡也能解锁锁机**（v2.2）。
 *
 * 用户要求："锁机后的解锁方法 = 打卡（**任何打卡方法都可以**），或者过完锁机时间"。
 * 原先 [LockRuleRepository.currentStatus] 只读 `rule_check_ins`，于是
 * "在习惯上点一下"这种打卡对锁机完全不起作用 —— 那正是要求 2 要修的东西。
 *
 * 这里的每一条都对应一个具体的行为承诺：
 *  1. 习惯打卡（`check_ins`）能解锁
 *  2. 两张表取**较晚**的那个，谁新谁说了算
 *  3. 没有 `habitId` 的旧规则只看 `rule_check_ins`
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)；锁机窗口 07:00–09:00。
 */
class HabitCheckInUnlockTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var habitRepo: HabitRepository
    private lateinit var checkInDao: FakeCheckInDao
    private lateinit var ruleCheckInDao: FakeRuleCheckInDao
    private lateinit var lockRuleDao: FakeLockRuleDao
    private lateinit var repo: LockRuleRepository

    @Before
    fun setUp() {
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        checkInDao = FakeCheckInDao()
        ruleCheckInDao = FakeRuleCheckInDao()
        lockRuleDao = FakeLockRuleDao()
        habitRepo = HabitRepository(FakeHabitDao(), checkInDao, clock)
        repo = LockRuleRepository(
            lockRuleDao,
            FakeAllowAppDao(),
            FakeRuleAllowAppDao(),
            ruleCheckInDao,
            checkInDao,
            clock
        )
    }

    private fun dt(s: String): LocalDateTime = LocalDateTime.parse(s)

    private fun millis(s: String): Long = dt(s).atZone(zone).toInstant().toEpochMilli()

    /** 08:00 时正处于锁机中的规则（07:00–09:00），挂在 [habitId] 上。 */
    private fun ruleFor(habitId: Long) = LockRule(
        name = "早课",
        habitId = habitId,
        windows = listOf(TimeWindow(7 * 60, 9 * 60))
    )

    private suspend fun seedHabitRule(): Pair<Long, Long> {
        val habitId = habitRepo.save(Habit(name = "早起"))
        val ruleId = repo.saveForHabit(habitId, ruleFor(habitId))
        return habitId to ruleId
    }

    // ------------------------------------------------ 1. 习惯打卡能解锁

    @Test
    fun `没打卡时锁机`() = runTest {
        seedHabitRule()
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `在习惯上点的打卡也能解锁锁机`() = runTest {
        val (habitId, _) = seedHabitRule()

        // 用户在今日列表点了一下（写入 check_ins，**不写** rule_check_ins）
        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T07:30"), status = CheckInStatus.MANUAL)

        assertThat(ruleCheckInDao.count()).isEqualTo(0) // 确认确实没写规则表
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `习惯打卡解锁有提前有效期下限`() = runTest {
        val (habitId, _) = seedHabitRule()

        // 06:00 打的卡早于窗口起点（提前有效期 0）→ 不算数
        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T06:00"))

        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------------ 2. 两张表取较晚的那个

    @Test
    fun `规则表打卡更晚时以规则表为准`() = runTest {
        val (habitId, ruleId) = seedHabitRule()

        // 习惯打卡太早（06:00，无效），规则打卡 07:30 有效 → 解锁
        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T06:00"))
        ruleCheckInDao.insert(
            com.zilv.app.data.local.entity.RuleCheckInEntity(
                ruleId = ruleId,
                spotId = 7L,
                checkedAtMillis = millis("2026-10-14T07:30"),
                epochDay = 20_000L,
                lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
                status = "ON_TIME"
            )
        )

        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `习惯打卡更晚时以习惯为准（不会被旧的规则打卡顶掉）`() = runTest {
        val (habitId, ruleId) = seedHabitRule()

        // 规则表里有一条**太早**的打卡（06:00，无效）……
        ruleCheckInDao.insert(
            com.zilv.app.data.local.entity.RuleCheckInEntity(
                ruleId = ruleId,
                spotId = 7L,
                checkedAtMillis = millis("2026-10-14T06:00"),
                epochDay = 20_000L,
                lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
                status = "ON_TIME"
            )
        )
        // ……而习惯上有一条有效的（07:40）。取较晚的那个 → 解锁。
        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T07:40"))

        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.UNLOCKED)
    }

    @Test
    fun `显式传入的打卡时刻仍然覆盖两张表`() = runTest {
        val (habitId, ruleId) = seedHabitRule()
        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T07:30"))

        val status = repo.currentStatus(
            dt("2026-10-14T08:00"),
            mapOf(ruleId to dt("2026-10-14T06:00")) // 太早，明确覆盖成"无效"
        )
        assertThat(status.phase).isEqualTo(LockPhase.LOCKED)
    }

    // ------------------------------------------------ 3. 没有 habitId 的旧规则

    @Test
    fun `没有 habitId 的旧规则不会误读别人的习惯打卡`() = runTest {
        // 一条挂习惯的规则 + 一条旧式独立规则（habitId = null），窗口相同
        val (habitId, _) = seedHabitRule()
        val orphanId = lockRuleDao.insert(
            LockRule(name = "旧式独立规则", windows = listOf(TimeWindow(7 * 60, 9 * 60))).toEntity()
        )

        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T07:30"))

        // 挂习惯的那条已解锁；旧式那条**读不到任何打卡**，仍然锁着 ——
        // 多规则叠加时 LOCKED 优先，所以整体状态仍是锁机
        assertThat(orphanId).isGreaterThan(0L)
        assertThat(repo.latestCheckInAt(repo.getRule(orphanId)!!)).isNull()
        assertThat(repo.currentStatus(dt("2026-10-14T08:00")).phase).isEqualTo(LockPhase.LOCKED)
    }

    @Test
    fun `latestCheckInAt 取两张表里较晚的一个`() = runTest {
        val (habitId, ruleId) = seedHabitRule()
        ruleCheckInDao.insert(
            com.zilv.app.data.local.entity.RuleCheckInEntity(
                ruleId = ruleId,
                spotId = 7L,
                checkedAtMillis = millis("2026-10-14T07:10"),
                epochDay = 20_000L,
                lat = 30.0, lng = 120.0, accuracyM = 10f, distanceM = 5.0,
                status = "ON_TIME"
            )
        )
        habitRepo.checkIn(habitId, checkedAtMillis = millis("2026-10-14T07:50"))

        val rule = repo.getRuleForHabit(habitId)!!
        assertThat(repo.latestCheckInAt(rule)).isEqualTo(dt("2026-10-14T07:50"))
    }
}
