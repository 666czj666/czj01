package com.zilv.app.domain.intercept

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [AlwaysAllowPackages] 的单元测试（v2.14）。
 *
 * ## 为什么这一组是本轮最重要的断言
 *
 * 用户报的是：「系统界面组件，截屏，智能助里加入默认名单」+
 * 「拦截日志显示系统界面组件，截屏，智能助理被拦截」。
 *
 * 这三类一旦被拦，后果是用户**看得见的坏**（调音量被踢回桌面、截屏面板一闪、
 * 语音助手把正在做的事顶掉）。所以：
 *  - 「必须放行」这一组要宽（厂商变体、商店安装的助理）
 *  - 「绝不能顺带放行」这一组要严（第三方应用、伪装包名）
 *
 * 后者尤其重要：把判据写宽（比如 `contains("systemui")`）会变成绕过口子，
 * 用户装一个 `com.android.systemui.evil` 就永久免拦了。
 */
class AlwaysAllowPackagesTest {

    // ------------------------------------------------------------ 必须放行

    @Test
    fun `系统界面、截屏、智能助理的显式清单都非空`() {
        // 三组清单任一为空，就说明有人把整组删了 —— 那等于回到"靠枚举碰运气"
        assertThat(AlwaysAllowPackages.SYSTEM_UI_VARIANTS).isNotEmpty()
        assertThat(AlwaysAllowPackages.SCREENSHOT).isNotEmpty()
        assertThat(AlwaysAllowPackages.ASSISTANT).isNotEmpty()
        assertThat(AlwaysAllowPackages.ALL).containsAtLeast(
            "com.android.systemui",
            "com.miui.screenshot",
            "com.google.android.googlequicksearchbox"
        )
    }

    @Test
    fun `智能助理清单必须包含商店安装的 Google 助理`() {
        // 用户在 v2.14 反复复现的那一类：**商店安装、没有任何系统标志**，
        // 所以 v2.13 的 FLAG_SYSTEM 枚举永远拿不到它 —— 这正是根因之一
        assertThat(AlwaysAllowPackages.ASSISTANT)
            .containsAtLeast(
                "com.google.android.googlequicksearchbox",
                "com.miui.voiceassist",
                "com.miui.personalassistant",
                "com.huawei.vassistant",
                "com.heytap.speechassist",
                "com.vivo.assistant",
                "com.samsung.android.bixby.agent"
            )
    }

    @Test
    fun `截屏清单覆盖常见厂商`() {
        assertThat(AlwaysAllowPackages.SCREENSHOT)
            .containsAtLeast(
                "com.miui.screenshot",
                "com.coloros.screenshot",
                "com.oplus.screenshot",
                "com.vivo.screenshot",
                "com.samsung.android.app.smartcapture"
            )
    }

    @Test
    fun `SystemUI 族：任何以 systemui 结尾的厂商变体都算`() {
        // 这一条是"厂商变体"问题的根本修法：不需要知道具体包名。
        // 各 ROM 把状态栏 / 音量面板 / 截屏浮层拆成独立包时都是这个命名习惯。
        listOf(
            "com.android.systemui",
            "com.miui.systemui",
            "com.transsion.systemui",
            "com.oplus.systemui",
            "com.vivo.systemui",
            "com.huawei.systemui",
            "com.sec.android.systemui",
            // 连"从没见过的厂商"也命中 —— 这正是族判定的意义
            "com.someoem.neverheardof.systemui"
        ).forEach { pkg ->
            assertThat(AlwaysAllowPackages.isSystemUiFamily(pkg)).isTrue()
            assertThat(AlwaysAllowPackages.isAlwaysAllowed(pkg)).isTrue()
        }
    }

    @Test
    fun `显式清单里的包一律 always allowed`() {
        AlwaysAllowPackages.ALL.forEach { pkg ->
            assertThat(AlwaysAllowPackages.isAlwaysAllowed(pkg)).isTrue()
        }
    }

    // -------------------------------------------------------- 绝不能顺带放行

    @Test
    fun `SystemUI 族判定不看包含关系，伪装包名照样被拦`() {
        // 既有断言 `保护清单是大写敏感的精确匹配，不做前缀放行` 已经钉住了
        // `com.android.systemui.evil` 必须被拦；这里从族判定这一侧再钉一次，
        // 因为族判定是 v2.14 新加的入口，写松了就是新的绕过漏洞。
        listOf(
            "com.android.systemui.evil",
            "com.evil.systemui.plugin",
            "systemui",
            "com.systemui",
            "com.miui.systemuiX",
            "com.miui.systemuix",
            "com.miui.xsystemui"
        ).forEach { pkg ->
            assertThat(AlwaysAllowPackages.isSystemUiFamily(pkg)).isFalse()
            assertThat(AlwaysAllowPackages.isAlwaysAllowed(pkg)).isFalse()
        }
    }

    @Test
    fun `第三方应用不会被误放行`() {
        listOf(
            "com.ss.android.ugc.aweme",      // 抖音
            "com.tencent.mm",                // 微信
            "com.eg.android.AlipayGphone",   // 支付宝
            "com.smile.gifmaker",            // 快手
            "com.xingin.xhs",                // 小红书
            "com.android.chrome"             // 浏览器
        ).forEach { pkg ->
            assertThat(AlwaysAllowPackages.isAlwaysAllowed(pkg)).isFalse()
            assertThat(AlwaysAllowPackages.categoryOf(pkg)).isNull()
        }
    }

    @Test
    fun `空包名一律不算`() {
        assertThat(AlwaysAllowPackages.isSystemUiFamily(null)).isFalse()
        assertThat(AlwaysAllowPackages.isSystemUiFamily("")).isFalse()
        assertThat(AlwaysAllowPackages.isSystemUiFamily("   ")).isFalse()
        assertThat(AlwaysAllowPackages.isAlwaysAllowed(null)).isFalse()
        assertThat(AlwaysAllowPackages.isAlwaysAllowed("")).isFalse()
        assertThat(AlwaysAllowPackages.categoryOf(null)).isNull()
    }

    // ---------------------------------------------------------------- 诊断

    @Test
    fun `categoryOf 给出可读分类，供自检页解释包为什么被放行`() {
        assertThat(AlwaysAllowPackages.categoryOf("com.android.systemui")).isEqualTo("系统界面")
        assertThat(AlwaysAllowPackages.categoryOf("com.transsion.systemui")).isEqualTo("系统界面")
        assertThat(AlwaysAllowPackages.categoryOf("com.miui.screenshot")).isEqualTo("截屏")
        assertThat(
            AlwaysAllowPackages.categoryOf("com.google.android.googlequicksearchbox")
        ).isEqualTo("智能助理")
    }

    @Test
    fun `探测用的 Intent action 与 Android 框架常量一致`() {
        // 纯领域模型里写字面值是为了能在 JVM 单测里直接用；
        // 这一条把"字面值别写错"钉死 —— 写错一个字符，探测就永远返回空，
        // 而现象又是"智能助理被拦"，极难排查。
        assertThat(AlwaysAllowPackages.ASSIST_ACTION).isEqualTo(Intent.ACTION_ASSIST)
        assertThat(AlwaysAllowPackages.VOICE_COMMAND_ACTION)
            .isEqualTo(Intent.ACTION_VOICE_COMMAND)
        assertThat(AlwaysAllowPackages.ASSIST_ACTIONS).containsExactly(
            Intent.ACTION_ASSIST,
            Intent.ACTION_VOICE_COMMAND
        ).inOrder()
        assertThat(AlwaysAllowPackages.SECURE_ASSISTANT_KEY).isEqualTo("assistant")
    }
}
