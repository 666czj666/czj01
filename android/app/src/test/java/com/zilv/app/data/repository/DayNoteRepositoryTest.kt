package com.zilv.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DayNoteRepository] 的单元测试（纯 JVM）。
 *
 * 用户要求："点「所有任务已完成」可以留言，留言记录可以在日历上看见。"
 *
 * 时间固定在 2026-10-14 16:00 (+08:00)，因此"今天"始终是 2026-10-14。
 * DAO 用 [FakeDayNoteDao] 的内存实现；真实 Room 的运行期行为
 * （主键约束、upsert 语义）由注解处理器在编译期校验 + androidTest 覆盖。
 */
class DayNoteRepositoryTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 14)

    private lateinit var dao: FakeDayNoteDao
    private lateinit var repo: DayNoteRepository

    @Before
    fun setUp() {
        dao = FakeDayNoteDao()
        // 2026-10-14T08:00:00Z == 2026-10-14 16:00 +08:00
        val clock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), zone)
        repo = DayNoteRepository(dayNoteDao = dao, clock = clock)
    }

    // ------------------------------------------------------------------ 基础

    @Test
    fun `没有留言时读到 null`() = runTest {
        assertThat(repo.get(today)).isNull()
        assertThat(repo.observe(today).first()).isNull()
    }

    @Test
    fun `保存后能读回`() = runTest {
        val saved = repo.save(today, "今天很稳")
        assertThat(saved).isTrue()

        val note = repo.get(today)!!
        assertThat(note.text).isEqualTo("今天很稳")
        assertThat(note.date).isEqualTo(today)
        // updatedAt 取自注入的时钟：2026-10-14T08:00:00Z
        assertThat(note.updatedAtMillis).isEqualTo(Instant.parse("2026-10-14T08:00:00Z").toEpochMilli())
    }

    @Test
    fun `今天由注入的时钟决定`() {
        assertThat(repo.today()).isEqualTo(today)
    }

    @Test
    fun `保存后 observe 立刻能读到`() = runTest {
        repo.save(today, "写点什么")
        assertThat(repo.observe(today).first()!!.text).isEqualTo("写点什么")
    }

    // -------------------------------------------------------------- 一天一条

    @Test
    fun `同一天重复保存是覆盖而不是新增`() = runTest {
        repo.save(today, "第一版")
        repo.save(today, "第二版")

        assertThat(repo.get(today)!!.text).isEqualTo("第二版")
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun `不同日期各存一条`() = runTest {
        repo.save(today, "今天")
        repo.save(today.minusDays(1), "昨天")

        assertThat(dao.count()).isEqualTo(2)
        assertThat(repo.get(today)!!.text).isEqualTo("今天")
        assertThat(repo.get(today.minusDays(1))!!.text).isEqualTo("昨天")
    }

    // -------------------------------------------------------------- 空 = 清除

    @Test
    fun `空文本等于清除`() = runTest {
        repo.save(today, "先写一句")
        assertThat(repo.get(today)).isNotNull()

        val saved = repo.save(today, "")

        assertThat(saved).isFalse()
        assertThat(repo.get(today)).isNull()
        assertThat(dao.count()).isEqualTo(0)
    }

    @Test
    fun `纯空白也等于清除`() = runTest {
        repo.save(today, "先写一句")
        val saved = repo.save(today, "  \n\t ")

        assertThat(saved).isFalse()
        assertThat(repo.get(today)).isNull()
    }

    @Test
    fun `清除一个本来就没有的日期不会抛异常`() = runTest {
        val saved = repo.save(today, "   ")
        assertThat(saved).isFalse()
        assertThat(dao.count()).isEqualTo(0)

        repo.clear(today)
        assertThat(repo.get(today)).isNull()
    }

    @Test
    fun `显式 clear 能删掉留言`() = runTest {
        repo.save(today, "写下")
        repo.clear(today)
        assertThat(repo.get(today)).isNull()
    }

    @Test
    fun `首尾空白会被去掉后保存`() = runTest {
        repo.save(today, "  今天很稳  ")
        assertThat(repo.get(today)!!.text).isEqualTo("今天很稳")
    }

    @Test
    fun `多行留言完整保留`() = runTest {
        val text = "第一行\n第二行"
        repo.save(today, text)
        assertThat(repo.get(today)!!.text).isEqualTo(text)
    }

    // ---------------------------------------------------------- 日历用的日期集

    @Test
    fun `没有留言时日期集合为空`() = runTest {
        assertThat(repo.noteDates()).isEmpty()
        assertThat(repo.observeNoteDates().first()).isEmpty()
    }

    @Test
    fun `有留言的日期集合按天去重且不含没有内容的日子`() = runTest {
        repo.save(today, "今天")
        repo.save(today.minusDays(2), "前天")
        // 同一天再写一次仍然只算一天
        repo.save(today, "今天（改）")
        // 空文本不该留下痕迹
        repo.save(today.minusDays(1), "")

        assertThat(repo.noteDates()).containsExactly(today, today.minusDays(2))
    }

    @Test
    fun `observeNoteDates 会随写入更新`() = runTest {
        assertThat(repo.observeNoteDates().first()).isEmpty()

        repo.save(today, "刚写的")

        assertThat(repo.observeNoteDates().first()).containsExactly(today)
    }

    @Test
    fun `getAll 返回全部留言`() = runTest {
        repo.save(today, "今天")
        repo.save(today.minusDays(1), "昨天")

        assertThat(repo.getAll().map { it.text }).containsExactly("昨天", "今天")
    }
}
