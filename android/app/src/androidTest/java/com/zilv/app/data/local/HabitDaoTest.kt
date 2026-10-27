package com.zilv.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.zilv.app.data.local.dao.CheckInDao
import com.zilv.app.data.local.dao.HabitDao
import com.zilv.app.data.local.entity.CheckInEntity
import com.zilv.app.data.local.entity.HabitEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 数据库的**运行期**行为验证（instrumented）。
 *
 * 与 [com.zilv.app.data.repository.HabitRepositoryTest] 的分工：
 *  - 那边用内存 Fake DAO 验证仓库业务逻辑（纯 JVM，每阶段都跑）
 *  - 这边验证真实 SQLite 的行为：外键级联、排序、GROUP BY 聚合、按天计数
 *
 * ⚠️ 需要连接设备或模拟器。当前开发机无设备，因此本测试处于 PENDING 状态，
 * 尚未被执行过 —— 这一点在 docs/变更日志.md 中有记录，不做隐瞒。
 *
 * 运行方式（接上设备后）：
 *   gradle connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class HabitDaoTest {

    private lateinit var db: ZilvDatabase
    private lateinit var habitDao: HabitDao
    private lateinit var checkInDao: CheckInDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ZilvDatabase::class.java)
            .build()
        habitDao = db.habitDao()
        checkInDao = db.checkInDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun habit(name: String, sortOrder: Int = 0) = HabitEntity(
        name = name,
        icon = "check_circle",
        color = 0xFF1B2A4A,
        type = "SIMPLE",
        frequencyType = "DAILY",
        frequencyPayload = "",
        remindTimes = "",
        spotId = null,
        sortOrder = sortOrder,
        archived = false,
        createdAt = 0L
    )

    private fun checkIn(habitId: Long, epochDay: Long, at: Long = 0L) = CheckInEntity(
        habitId = habitId,
        ruleId = null,
        sessionId = null,
        checkedAtMillis = at,
        epochDay = epochDay,
        status = "MANUAL",
        lat = null, lng = null, accuracyM = null, distanceM = null,
        spotId = null, photoPath = null, note = null
    )

    @Test
    fun 插入并读回习惯() = runBlocking {
        val id = habitDao.insert(habit("早起"))
        val loaded = habitDao.getById(id)
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.name).isEqualTo("早起")
    }

    @Test
    fun 活跃列表按sortOrder升序排序() = runBlocking {
        habitDao.insert(habit("第三", 3))
        habitDao.insert(habit("第一", 1))
        habitDao.insert(habit("第二", 2))
        assertThat(habitDao.getActive().map { it.name })
            .containsExactly("第一", "第二", "第三").inOrder()
    }

    @Test
    fun 归档的习惯不出现在活跃列表() = runBlocking {
        val id = habitDao.insert(habit("早起"))
        habitDao.archive(id)
        assertThat(habitDao.getActive()).isEmpty()
        assertThat(habitDao.getById(id)).isNotNull()
    }

    @Test
    fun 外键级联删除生效() = runBlocking {
        val id = habitDao.insert(habit("早起"))
        checkInDao.insert(checkIn(id, 20_000L))
        checkInDao.insert(checkIn(id, 20_001L))
        assertThat(checkInDao.countForHabit(id)).isEqualTo(2)

        habitDao.delete(id)

        assertThat(checkInDao.countAll()).isEqualTo(0)
    }

    @Test
    fun 同一天的多次打卡按checked_at倒序返回() = runBlocking {
        val id = habitDao.insert(habit("早起"))
        checkInDao.insert(checkIn(id, 20_000L, at = 1000L))
        checkInDao.insert(checkIn(id, 20_000L, at = 2000L))

        val rows = checkInDao.forDay(id, 20_000L)
        assertThat(rows).hasSize(2)
        assertThat(rows.first().checkedAtMillis).isEqualTo(2000L)
        assertThat(checkInDao.countForDay(id, 20_000L)).isEqualTo(2)
    }

    @Test
    fun 热力图按天聚合计数() = runBlocking {
        val a = habitDao.insert(habit("A"))
        val b = habitDao.insert(habit("B"))
        checkInDao.insert(checkIn(a, 20_000L))
        checkInDao.insert(checkIn(b, 20_000L))
        checkInDao.insert(checkIn(a, 20_001L))

        val counts = checkInDao.observeDailyCounts().first()
        val map = counts.associate { it.epochDay to it.count }
        assertThat(map[20_000L]).isEqualTo(2)
        assertThat(map[20_001L]).isEqualTo(1)
    }

    @Test
    fun 删除某天打卡只影响那一天() = runBlocking {
        val id = habitDao.insert(habit("早起"))
        checkInDao.insert(checkIn(id, 20_000L))
        checkInDao.insert(checkIn(id, 20_001L))

        checkInDao.deleteForDay(id, 20_000L)

        assertThat(checkInDao.countForDay(id, 20_000L)).isEqualTo(0)
        assertThat(checkInDao.countForDay(id, 20_001L)).isEqualTo(1)
    }

    @Test
    fun 按习惯清理打卡记录() = runBlocking {
        val a = habitDao.insert(habit("A"))
        val b = habitDao.insert(habit("B"))
        checkInDao.insert(checkIn(a, 20_000L))
        checkInDao.insert(checkIn(b, 20_000L))

        checkInDao.deleteAllForHabit(a)

        assertThat(checkInDao.countAll()).isEqualTo(1)
        assertThat(checkInDao.countForHabit(b)).isEqualTo(1)
    }

    @Test
    fun 观察活跃习惯的Flow会随写入更新() = runBlocking {
        assertThat(habitDao.observeActive().first()).isEmpty()
        habitDao.insert(habit("早起"))
        assertThat(habitDao.observeActive().first()).hasSize(1)
    }
}
