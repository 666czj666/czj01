package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.mapper.RuleCheckInStatus
import com.zilv.app.domain.model.GeoPoint
import com.zilv.app.domain.model.Spot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [SpotRepository] 的单元测试（纯 JVM，内存 Fake DAO）。
 *
 * 时间固定为 2026-10-14 16:00 (+08:00)。
 */
class SpotRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private lateinit var spotDao: FakeSpotDao
    private lateinit var checkInDao: FakeRuleCheckInDao
    private lateinit var repo: SpotRepository

    @Before
    fun setUp() {
        spotDao = FakeSpotDao()
        checkInDao = FakeRuleCheckInDao()
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        repo = SpotRepository(spotDao = spotDao, ruleCheckInDao = checkInDao, clock = clock)
    }

    private fun point(lat: Double = 30.0, lng: Double = 120.0, accuracy: Float = 10f) =
        GeoPoint(lat = lat, lng = lng, accuracyM = accuracy, atMillis = 0L)

    private fun newSpot(name: String = "三教一楼") = Spot(
        name = name,
        lat = 30.0,
        lng = 120.0,
        radiusM = 100,
        minAccuracyM = 50f
    )

    // ---------------------------------------------------------------- 打卡点

    @Test
    fun `保存并读回打卡点`() = runTest {
        val id = repo.save(newSpot())
        val spot = repo.getSpot(id)!!
        assertThat(spot.name).isEqualTo("三教一楼")
        assertThat(spot.lat).isEqualTo(30.0)
        assertThat(spot.radiusM).isEqualTo(100)
        assertThat(spot.minAccuracyM).isEqualTo(50f)
    }

    /**
     * ⚠️ **这条用例改写自旧规格 —— 是旧断言与用户新要求正面冲突，不是代码错了。**
     *
     * 旧规格（v2.11 及以前）：`save(spot.copy(id = 已存在的 id, ...))` 表示"修改"，
     * 断言改完之后读回来是新名字、新半径，且列表里仍然只有一条。
     *
     * 新规格（v2.12，用户原话）：「**打卡点创建好之后不能再修改**」。
     * 于是同一个调用现在必须被**拒绝**。旧断言里的"改成功了"正是用户要去掉的
     * 那件事，所以它不能保留 —— 我把三条断言换成新规格下的三条，强度不回退：
     *  1. 抛 `SpotImmutableException` 而不是静默成功
     *  2. 库里的名字**没有**被改掉（拒绝必须是真拒绝）
     *  3. 用来改的那个对象也没被写进去（列表仍然只有一条）
     */
    @Test
    fun `创建之后不能再修改 —— 带已存在 id 的保存被拒绝且库里的值不变`() = runTest {
        val id = repo.save(newSpot())
        val existing = repo.getSpot(id)!!

        val error = runCatching {
            repo.save(existing.copy(name = "图书馆", radiusM = 200))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(SpotImmutableException::class.java)
        assertThat(repo.getSpot(id)!!.name).isEqualTo("三教一楼")
        assertThat(repo.getSpot(id)!!.radiusM).isEqualTo(100)
        assertThat(repo.getAllSpots()).hasSize(1)
    }

    @Test
    fun `锁定的是改，不是删 —— 已创建的打卡点照旧删得掉`() = runTest {
        // 用户倾向「允许删除」，本实现照此执行：否则误建的打卡点永远清不掉，
        // 而它又不能改 —— 用户会被一个错误的位置永久绑住。
        val id = repo.save(newSpot())
        repo.delete(id)
        assertThat(repo.getSpot(id)).isNull()
    }

    @Test
    fun `删掉之后可以用同名同坐标重新建一个`() = runTest {
        // "删掉重建"是只读之后唯一的调整路径，必须真的走得通
        val first = repo.save(newSpot())
        repo.delete(first)
        val second = repo.save(newSpot().copy(radiusM = 200))

        assertThat(second).isNotEqualTo(first)
        assertThat(repo.getAllSpots().single().radiusM).isEqualTo(200)
    }

    @Test
    fun `备份恢复那条路不受影响 —— id 归零就是新建`() = runTest {
        // BackupViewModel 走的是 `save(it.copy(id = 0L))`，所以恢复快照
        // 仍然能把打卡点原样搬回来（它们本来就是"新建"出来的）
        val id = repo.save(newSpot())
        val snapshot = repo.getSpot(id)!!

        val restored = repo.save(snapshot.copy(id = 0L))

        assertThat(restored).isNotEqualTo(id)
        assertThat(repo.getAllSpots()).hasSize(2)
        assertThat(repo.getSpot(restored)!!.name).isEqualTo(snapshot.name)
    }

    @Test
    fun `非法打卡点被拒绝保存`() = runTest {
        assertThat(runCatching { repo.save(newSpot().copy(name = " ")) }.isFailure).isTrue()
        assertThat(runCatching { repo.save(newSpot().copy(radiusM = 10)) }.isFailure).isTrue()
        assertThat(runCatching { repo.save(newSpot().copy(radiusM = 5000)) }.isFailure).isTrue()
    }

    @Test
    fun `删除打卡点`() = runTest {
        val id = repo.save(newSpot())
        repo.delete(id)
        assertThat(repo.getSpot(id)).isNull()
        assertThat(repo.getAllSpots()).isEmpty()
    }

    @Test
    fun `打卡点按创建时间排序`() = runTest {
        repo.save(newSpot("第二个").copy(createdAt = 2000L))
        repo.save(newSpot("第一个").copy(createdAt = 1000L))
        assertThat(repo.getAllSpots().map { it.name })
            .containsExactly("第一个", "第二个").inOrder()
    }

    @Test
    fun `observeSpots 随写入更新`() = runTest {
        assertThat(repo.observeSpots().first()).isEmpty()
        repo.save(newSpot())
        assertThat(repo.observeSpots().first()).hasSize(1)
    }

    // ------------------------------------------------------------ 解锁打卡

    @Test
    fun `记录打卡解锁并读回`() = runTest {
        val spotId = repo.save(newSpot())
        repo.recordCheckIn(
            ruleId = 7L,
            spotId = spotId,
            location = point(),
            distanceM = 34.0,
            status = RuleCheckInStatus.ON_TIME
        )

        val record = repo.latestCheckIn(7L)!!
        assertThat(record.ruleId).isEqualTo(7L)
        assertThat(record.spotId).isEqualTo(spotId)
        assertThat(record.distanceM).isEqualTo(34.0)
        assertThat(record.accuracyM).isEqualTo(10f)
        assertThat(record.status).isEqualTo(RuleCheckInStatus.ON_TIME)
    }

    @Test
    fun `latestCheckIn 返回最近一次`() = runTest {
        val spotId = repo.save(newSpot())
        val early = Instant.parse("2026-10-14T07:30:00Z").toEpochMilli()
        val late = Instant.parse("2026-10-14T08:30:00Z").toEpochMilli()

        repo.recordCheckIn(7L, spotId, point(), 50.0, RuleCheckInStatus.ON_TIME, atMillis = early)
        repo.recordCheckIn(7L, spotId, point(), 20.0, RuleCheckInStatus.LATE, atMillis = late)

        val latest = repo.latestCheckIn(7L)!!
        assertThat(latest.checkedAtMillis).isEqualTo(late)
        assertThat(latest.status).isEqualTo(RuleCheckInStatus.LATE)
    }

    @Test
    fun `不同规则的打卡互不影响`() = runTest {
        val spotId = repo.save(newSpot())
        repo.recordCheckIn(1L, spotId, point(), 10.0, RuleCheckInStatus.ON_TIME)
        assertThat(repo.latestCheckIn(1L)).isNotNull()
        assertThat(repo.latestCheckIn(2L)).isNull()
    }

    @Test
    fun `latestCheckInTime 按注入时区换算`() = runTest {
        val spotId = repo.save(newSpot())
        // 2026-10-14T08:00:00Z == 2026-10-14 16:00 +08:00
        repo.recordCheckIn(
            7L, spotId, point(), 10.0, RuleCheckInStatus.ON_TIME,
            atMillis = Instant.parse("2026-10-14T08:00:00Z").toEpochMilli()
        )
        assertThat(repo.latestCheckInTime(7L)).isEqualTo(LocalDateTime.parse("2026-10-14T16:00"))
    }

    @Test
    fun `epochDay 按注入时区归属`() = runTest {
        val spotId = repo.save(newSpot())
        // 2026-10-14 23:30 +08:00，仍属 10-14
        val lateNight = LocalDateTime.parse("2026-10-14T23:30")
            .atZone(zone).toInstant().toEpochMilli()
        repo.recordCheckIn(7L, spotId, point(), 10.0, RuleCheckInStatus.ON_TIME, atMillis = lateNight)

        val record = repo.latestCheckIn(7L)!!
        assertThat(record.localDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 14))
    }

    @Test
    fun `异常解锁状态被保存`() = runTest {
        val spotId = repo.save(newSpot())
        repo.recordCheckIn(7L, spotId, point(), 80.0, RuleCheckInStatus.ABNORMAL_UNLOCK)
        assertThat(repo.latestCheckIn(7L)!!.status).isEqualTo(RuleCheckInStatus.ABNORMAL_UNLOCK)
    }

    @Test
    fun `清空某规则的打卡记录不影响其它规则`() = runTest {
        val spotId = repo.save(newSpot())
        repo.recordCheckIn(1L, spotId, point(), 10.0, RuleCheckInStatus.ON_TIME)
        repo.recordCheckIn(2L, spotId, point(), 10.0, RuleCheckInStatus.ON_TIME)

        repo.clearCheckInsForRule(1L)

        assertThat(repo.latestCheckIn(1L)).isNull()
        assertThat(repo.latestCheckIn(2L)).isNotNull()
    }

    @Test
    fun `checkInsFor 按时间倒序返回全部记录`() = runTest {
        val spotId = repo.save(newSpot())
        repo.recordCheckIn(7L, spotId, point(), 1.0, RuleCheckInStatus.ON_TIME, atMillis = 1000L)
        repo.recordCheckIn(7L, spotId, point(), 2.0, RuleCheckInStatus.ON_TIME, atMillis = 3000L)
        repo.recordCheckIn(7L, spotId, point(), 3.0, RuleCheckInStatus.ON_TIME, atMillis = 2000L)

        val list = repo.checkInsFor(7L)
        assertThat(list.map { it.checkedAtMillis }).containsExactly(3000L, 2000L, 1000L).inOrder()
    }
}
