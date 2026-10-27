package com.zilv.app.domain.intercept

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [TemporaryAllowList] 的单元测试（紧急放行 5 分钟的到期语义）。 */
class TemporaryAllowListTest {

    private val base = 1_700_000_000_000L

    @Test
    fun `未放行的包名不允许`() {
        val list = TemporaryAllowList()
        assertThat(list.isAllowed("pkg.a", base)).isFalse()
        assertThat(list.remainingMillis("pkg.a", base)).isEqualTo(0L)
    }

    @Test
    fun `放行后在时长内允许`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base, 5 * 60_000L)
        assertThat(list.isAllowed("pkg.a", base)).isTrue()
        assertThat(list.isAllowed("pkg.a", base + 4 * 60_000L)).isTrue()
        assertThat(list.isAllowed("pkg.a", base + 299_999L)).isTrue()
    }

    @Test
    fun `到期时刻起不再允许（半开区间）`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base, 5 * 60_000L)
        assertThat(list.isAllowed("pkg.a", base + 300_000L)).isFalse()
        assertThat(list.isAllowed("pkg.a", base + 300_001L)).isFalse()
    }

    @Test
    fun `剩余时间正确且不会为负`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base, 60_000L)
        assertThat(list.remainingMillis("pkg.a", base)).isEqualTo(60_000L)
        assertThat(list.remainingMillis("pkg.a", base + 20_000L)).isEqualTo(40_000L)
        assertThat(list.remainingMillis("pkg.a", base + 90_000L)).isEqualTo(0L)
    }

    @Test
    fun `重复放行取较晚的到期时刻，不会被缩短`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base, 5 * 60_000L)              // 到期 base+5min
        list.grant("pkg.a", base + 60_000L, 60_000L)        // 新到期 base+2min，更早
        assertThat(list.remainingMillis("pkg.a", base + 60_000L)).isEqualTo(4 * 60_000L)
    }

    @Test
    fun `不同包名互不影响`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base, 60_000L)
        assertThat(list.isAllowed("pkg.a", base)).isTrue()
        assertThat(list.isAllowed("pkg.b", base)).isFalse()
        assertThat(list.size).isEqualTo(1)
    }

    @Test
    fun `清理过期条目`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base, 60_000L)
        list.grant("pkg.b", base + 120_000L, 60_000L)

        assertThat(list.purgeExpired(base + 61_000L)).isEqualTo(1)
        assertThat(list.isAllowed("pkg.a", base + 61_000L)).isFalse()
        assertThat(list.isAllowed("pkg.b", base + 121_000L)).isTrue()
        assertThat(list.size).isEqualTo(1)
    }

    @Test
    fun `清空`() {
        val list = TemporaryAllowList()
        list.grant("pkg.a", base)
        list.clear()
        assertThat(list.size).isEqualTo(0)
        assertThat(list.isAllowed("pkg.a", base)).isFalse()
    }

    @Test
    fun `非正数时长抛异常`() {
        val list = TemporaryAllowList()
        assertThat(runCatching { list.grant("pkg.a", base, 0) }.isFailure).isTrue()
        assertThat(runCatching { list.grant("pkg.a", base, -1) }.isFailure).isTrue()
    }

    @Test
    fun `默认时长为 PRD 约定的 5 分钟`() {
        assertThat(TemporaryAllowList.DEFAULT_DURATION_MILLIS).isEqualTo(5 * 60 * 1000L)
        val list = TemporaryAllowList()
        val expiry = list.grant("pkg.a", base)
        assertThat(expiry - base).isEqualTo(5 * 60 * 1000L)
    }
}
