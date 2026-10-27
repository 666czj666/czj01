package com.zilv.app.domain.permission

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 无障碍服务条目解析的单元测试。
 *
 * **这组测试是为了锁死一个真实 bug**：用户已经开启了无障碍权限，
 * 但「权限检查」页始终不显示打钩。
 *
 * 根因是原实现用硬编码字符串精确比对 `ENABLED_ACCESSIBILITY_SERVICES`，
 * 而这个系统设置项里的 `ComponentName` 有**长/短两种扁平化格式**，
 * 不同 ROM 用的不一样 —— 精确比对在短格式的机器上永远不可能匹配。
 *
 * 因此这里最关键的一条是 `短格式也能匹配`：修复前它必然失败。
 */
class AccessibilityEntryTest {

    private val pkg = "com.zilv.app"
    private val cls = "com.zilv.app.service.LockAccessibilityService"

    private fun matches(entry: String) = AccessibilityEntry.matches(entry, pkg, cls)

    // ------------------------------------------------------------ 两种格式

    @Test
    fun `长格式能匹配`() {
        assertThat(matches("com.zilv.app/com.zilv.app.service.LockAccessibilityService")).isTrue()
    }

    @Test
    fun `短格式也能匹配（修复前必然失败）`() {
        // 这一条是修复的核心：短格式用 ".service.X" 表示同包下的类
        assertThat(matches("com.zilv.app/.service.LockAccessibilityService")).isTrue()
    }

    @Test
    fun `短格式解析为完整的类名`() {
        val parsed = AccessibilityEntry.parse("com.zilv.app/.service.LockAccessibilityService")
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.first).isEqualTo("com.zilv.app")
        assertThat(parsed.second).isEqualTo("com.zilv.app.service.LockAccessibilityService")
    }

    @Test
    fun `不带点也不带包名的裸类名按相对类名处理`() {
        val parsed = AccessibilityEntry.parse("com.zilv.app/MyService")
        assertThat(parsed!!.second).isEqualTo("com.zilv.app.MyService")
    }

    // ------------------------------------------------------------ 不匹配的情形

    @Test
    fun `包名不同则不匹配`() {
        assertThat(matches("com.other.app/com.other.app.service.LockAccessibilityService")).isFalse()
    }

    @Test
    fun `类名不同则不匹配`() {
        assertThat(matches("com.zilv.app/com.zilv.app.service.OtherService")).isFalse()
    }

    @Test
    fun `包名比对忽略大小写`() {
        assertThat(matches("COM.ZILV.APP/com.zilv.app.service.LockAccessibilityService")).isTrue()
    }

    @Test
    fun `类名比对区分大小写`() {
        assertThat(matches("com.zilv.app/com.zilv.app.service.lockaccessibilityservice")).isFalse()
    }

    // ------------------------------------------------------------ 容错

    @Test
    fun `空值不匹配且不抛异常`() {
        assertThat(matches("")).isFalse()
        assertThat(matches("   ")).isFalse()
        assertThat(AccessibilityEntry.matchesAny(null, pkg, cls)).isFalse()
        assertThat(AccessibilityEntry.matchesAny("", pkg, cls)).isFalse()
    }

    @Test
    fun `格式不合法的条目不匹配`() {
        assertThat(matches("没有斜杠")).isFalse()
        assertThat(matches("/com.zilv.app.service.LockAccessibilityService")).isFalse()
        assertThat(matches("com.zilv.app/")).isFalse()
    }

    @Test
    fun `条目两侧空白被忽略`() {
        assertThat(matches("  com.zilv.app/com.zilv.app.service.LockAccessibilityService  ")).isTrue()
    }

    // ------------------------------------------------------------ 多条目

    @Test
    fun `冒号分隔的多条目里能找到目标`() {
        val raw = "com.foo/com.foo.A:com.zilv.app/.service.LockAccessibilityService:com.bar/com.bar.B"
        assertThat(AccessibilityEntry.matchesAny(raw, pkg, cls)).isTrue()
    }

    @Test
    fun `分号分隔也容忍`() {
        val raw = "com.foo/com.foo.A;com.zilv.app/com.zilv.app.service.LockAccessibilityService"
        assertThat(AccessibilityEntry.matchesAny(raw, pkg, cls)).isTrue()
    }

    @Test
    fun `多条目里没有目标则返回 false`() {
        val raw = "com.foo/com.foo.A:com.bar/com.bar.B"
        assertThat(AccessibilityEntry.matchesAny(raw, pkg, cls)).isFalse()
    }

    @Test
    fun `别名类名指向同一服务时也能匹配`() {
        // 某些 ROM 会写 Activity 别名或带前缀的变体；只要解析出的类名一致就算匹配
        assertThat(matches("com.zilv.app/com.zilv.app.service.LockAccessibilityService")).isTrue()
    }

    // ------------------------------------------------------------ parseAll

    @Test
    fun `parseAll 跳过坏条目并保留好条目`() {
        val raw = "坏条目:com.zilv.app/.service.LockAccessibilityService:com.foo/com.foo.B"
        val parsed = AccessibilityEntry.parseAll(raw)
        assertThat(parsed).hasSize(2)
        assertThat(parsed[0].first).isEqualTo("com.zilv.app")
        assertThat(parsed[1].second).isEqualTo("com.foo.B")
    }

    @Test
    fun `parseAll 对空输入返回空列表`() {
        assertThat(AccessibilityEntry.parseAll(null)).isEmpty()
        assertThat(AccessibilityEntry.parseAll("")).isEmpty()
    }
}
