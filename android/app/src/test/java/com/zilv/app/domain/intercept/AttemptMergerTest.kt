package com.zilv.app.domain.intercept

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 尝试合并的单元测试（PRD §3.8 数据口径：同一 App 5 秒内合并为 1 次）。
 */
class AttemptMergerTest {

    private val base = 1_700_000_000_000L

    @Test
    fun `首次尝试一定新建记录`() {
        assertThat(AttemptMerger.startsNewRecord(null, base)).isTrue()
    }

    @Test
    fun `窗口内的连续尝试并入上一条`() {
        assertThat(AttemptMerger.startsNewRecord(base, base)).isFalse()
        assertThat(AttemptMerger.startsNewRecord(base, base + 1)).isFalse()
        assertThat(AttemptMerger.startsNewRecord(base, base + 4_999)).isFalse()
    }

    @Test
    fun `窗口边界：第5秒整算新记录`() {
        assertThat(AttemptMerger.startsNewRecord(base, base + 5_000)).isTrue()
        assertThat(AttemptMerger.startsNewRecord(base, base + 5_001)).isTrue()
    }

    @Test
    fun `自定义窗口生效`() {
        assertThat(AttemptMerger.startsNewRecord(base, base + 1_000, windowMillis = 1_000)).isTrue()
        assertThat(AttemptMerger.startsNewRecord(base, base + 999, windowMillis = 1_000)).isFalse()
    }

    @Test
    fun `时钟回拨时当作新记录，不产生负窗口`() {
        // 用户把系统时间改早了
        assertThat(AttemptMerger.startsNewRecord(base, base - 10_000)).isTrue()
    }

    @Test
    fun `窗口为0时每次都是新记录`() {
        assertThat(AttemptMerger.startsNewRecord(base, base, windowMillis = 0)).isTrue()
    }

    @Test
    fun `负窗口参数抛异常`() {
        assertThat(runCatching { AttemptMerger.startsNewRecord(base, base, -1) }.isFailure).isTrue()
    }

    @Test
    fun `PRD 约定的默认窗口为5秒`() {
        assertThat(AttemptMerger.MERGE_WINDOW_MILLIS).isEqualTo(5_000L)
    }

    @Test
    fun `模拟连点10次只产生1条记录`() {
        // 用户在 4 秒内连点 10 次
        var lastRecorded: Long? = null
        var records = 0
        repeat(10) { i ->
            val now = base + i * 400L
            if (AttemptMerger.startsNewRecord(lastRecorded, now)) {
                records++
                lastRecorded = now
            }
        }
        assertThat(records).isEqualTo(1)
    }

    @Test
    fun `间隔超过窗口的连点产生多条记录`() {
        // 每次间隔 6 秒，连点 3 次 -> 3 条记录
        var lastRecorded: Long? = null
        var records = 0
        repeat(3) { i ->
            val now = base + i * 6_000L
            if (AttemptMerger.startsNewRecord(lastRecorded, now)) {
                records++
                lastRecorded = now
            }
        }
        assertThat(records).isEqualTo(3)
    }
}
