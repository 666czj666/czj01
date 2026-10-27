package com.zilv.app.intercept

import android.content.Intent
import android.content.pm.ApplicationInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [SystemPackageDetector] 里那条**纯判据**的单元测试（v2.13）。
 *
 * 这一次的真机 bug 修法是"把全部系统应用放进默认白名单"，而"哪些包算系统应用"
 * 就是靠 [`ApplicationInfo.FLAG_SYSTEM`] / [`ApplicationInfo.FLAG_UPDATED_SYSTEM_APP`] 判的。
 * 枚举本身要 `PackageManager`（只能在真机上验证），但**判据**是纯函数，
 * 因此可以在这里钉死 —— 本项目的惯例：能离机验证的逻辑，一律不留到真机。
 *
 * 这两条 flag 一旦判错，后果是**双向**的：
 *  - 判宽了 → 用户自己的应用被永久放行，「锁机」等于失效
 *  - 判窄了 → 音量面板 / 最近任务 / 权限对话框被拦，用户又被踢回桌面
 */
class SystemPackageDetectorTest {

    @Test
    fun `FLAG_SYSTEM 是系统应用`() {
        assertThat(SystemPackageDetector.isSystemAppFlags(ApplicationInfo.FLAG_SYSTEM)).isTrue()
    }

    @Test
    fun `FLAG_UPDATED_SYSTEM_APP 也算系统应用 —— 预装应用被商店更新后 flag 会换成它`() {
        // 只认 FLAG_SYSTEM 的话，"更新过的系统组件"会在锁机时被拦，
        // 正好回到这次要修的 bug
        assertThat(
            SystemPackageDetector.isSystemAppFlags(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
        ).isTrue()
    }

    @Test
    fun `两个 flag 同时存在也算系统应用`() {
        val flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        assertThat(SystemPackageDetector.isSystemAppFlags(flags)).isTrue()
    }

    @Test
    fun `flags 里的其它位不会被误判成系统应用`() {
        // 用户安装的应用典型 flags：FLAG_INSTALLED / FLAG_ALLOW_BACKUP 之类，
        // 一个都不能让 isSystemAppFlags 返回 true
        val userAppFlags = ApplicationInfo.FLAG_INSTALLED or 0
        assertThat(SystemPackageDetector.isSystemAppFlags(userAppFlags)).isFalse()
        assertThat(SystemPackageDetector.isSystemAppFlags(0)).isFalse()
    }

    @Test
    fun `判据用位与而不是等值比较 —— 系统应用几乎总带着其它 flag`() {
        // 真机上系统应用的 flags 往往是 FLAG_SYSTEM | FLAG_INSTALLED | … 的组合。
        // 若写成 `flags == FLAG_SYSTEM`，几乎所有系统应用都会漏判。
        val realWorldSystemFlags = ApplicationInfo.FLAG_SYSTEM or
            ApplicationInfo.FLAG_INSTALLED or
            ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES
        assertThat(realWorldSystemFlags).isNotEqualTo(ApplicationInfo.FLAG_SYSTEM)
        assertThat(SystemPackageDetector.isSystemAppFlags(realWorldSystemFlags)).isTrue()
    }

    @Test
    fun `flag 常量与 Android 框架取值一致`() {
        // 纯函数不依赖 Android 运行时，所以把取值钉死 —— 写错一个位就会全盘失效
        assertThat(ApplicationInfo.FLAG_SYSTEM).isEqualTo(1 shl 0)
        assertThat(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP).isEqualTo(1 shl 7)
    }

    // ============================================ v2.15：「只拦能启动的应用」

    @Test
    fun `可启动判据用的 action 与框架常量一致`() {
        // 这个字符串是"这个包有没有启动入口"的**唯一**查询条件：
        // 写错一个字符 → 查询永远返回 0 个包 → 本规则静默失效。
        // 注意失效方向是安全的（规则不生效 = 旧行为），但这次修复就等于白做了，
        // 所以必须钉住。
        assertThat(SystemPackageDetector.LAUNCHER_ACTION).isEqualTo(Intent.ACTION_MAIN)
        assertThat(SystemPackageDetector.LAUNCHER_ACTION).isEqualTo("android.intent.action.MAIN")
    }

    @Test
    fun `可启动判据用的 category 与框架常量一致`() {
        // 必须是 LAUNCHER（"从桌面能点开"），不是 DEFAULT / INFO ——
        // 后两者会把一堆没有启动入口的系统组件也算成"可启动"。
        assertThat(SystemPackageDetector.LAUNCHER_CATEGORY).isEqualTo(Intent.CATEGORY_LAUNCHER)
        assertThat(SystemPackageDetector.LAUNCHER_CATEGORY)
            .isEqualTo("android.intent.category.LAUNCHER")
        assertThat(SystemPackageDetector.LAUNCHER_CATEGORY)
            .isNotEqualTo(Intent.CATEGORY_DEFAULT)
    }
}
