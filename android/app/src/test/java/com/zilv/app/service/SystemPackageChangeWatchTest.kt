package com.zilv.app.service

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [SystemPackageChangeWatch] 的单元测试（v2.13）。
 *
 * 「系统应用集合」是**缓存**的（枚举一次几十毫秒，绝不能放进每个窗口事件的热路径）。
 * 缓存必然过期，所以"什么时候必须重算"就成了正确性的一部分：
 *  - 漏掉 `ACTION_PACKAGE_REPLACED` → 系统组件被应用商店更新后 flags 翻转，
 *    缓存里还是老的，**新版本的音量面板 / 权限控制器可能被拦**
 *  - 漏掉 `ACTION_PACKAGE_ADDED` → 新推下来的系统组件不在白名单里
 *
 * 这里把四类动作钉死，改动 `ACTIONS` 就会立刻变红。
 */
class SystemPackageChangeWatchTest {

    @Test
    fun `四类包变化动作都在监听清单里`() {
        assertThat(SystemPackageChangeWatch.ACTIONS).containsAtLeast(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED
        )
    }

    @Test
    fun `action 字符串与 Android 框架一致`() {
        // 纯判据不依赖 Android 运行时，所以把取值钉死 —— 写错一个字符，
        // 广播就永远不会被当成"包变化"，缓存再也不会重算
        assertThat(Intent.ACTION_PACKAGE_ADDED).isEqualTo("android.intent.action.PACKAGE_ADDED")
        assertThat(Intent.ACTION_PACKAGE_REMOVED).isEqualTo("android.intent.action.PACKAGE_REMOVED")
        assertThat(Intent.ACTION_PACKAGE_REPLACED).isEqualTo("android.intent.action.PACKAGE_REPLACED")
        assertThat(Intent.ACTION_PACKAGE_CHANGED).isEqualTo("android.intent.action.PACKAGE_CHANGED")
    }

    @Test
    fun `isPackageChangeAction 对四类动作都返回 true`() {
        SystemPackageChangeWatch.ACTIONS.forEach { action ->
            assertThat(SystemPackageChangeWatch.isPackageChangeAction(action)).isTrue()
        }
    }

    @Test
    fun `无关广播不会被当成包变化`() {
        // 亮屏 / 息屏走的是 ScreenStateReceiver，不该触发一次全量枚举
        assertThat(SystemPackageChangeWatch.isPackageChangeAction(Intent.ACTION_SCREEN_ON)).isFalse()
        assertThat(SystemPackageChangeWatch.isPackageChangeAction(Intent.ACTION_SCREEN_OFF)).isFalse()
        assertThat(SystemPackageChangeWatch.isPackageChangeAction(Intent.ACTION_BOOT_COMPLETED)).isFalse()
        assertThat(SystemPackageChangeWatch.isPackageChangeAction(null)).isFalse()
        assertThat(SystemPackageChangeWatch.isPackageChangeAction("")).isFalse()
    }

    @Test
    fun `监听清单没有重复项`() {
        assertThat(SystemPackageChangeWatch.ACTIONS).containsNoDuplicates()
    }
}
