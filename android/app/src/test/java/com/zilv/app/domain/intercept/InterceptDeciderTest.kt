package com.zilv.app.domain.intercept

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 拦截判定的单元测试。
 *
 * 最重要的两类断言：
 *  1. 处于拦截态时，白名单外的应用确实被拦
 *  2. **关键系统包永远不会被拦** —— 这是"不把手机搞坏"的底线
 */
class InterceptDeciderTest {

    private val self = "com.zilv.app"
    private val allow = setOf("com.tencent.mm", "com.android.settings")

    private fun decide(
        pkg: String?,
        locked: Boolean = true,
        allowList: Set<String> = allow,
        quotaExhausted: Set<String> = emptySet()
    ) =
        InterceptDecider.decide(
            foregroundPackage = pkg,
            selfPackage = self,
            allowList = allowList,
            locked = locked,
            quotaExhaustedPackages = quotaExhausted
        )

    // ------------------------------------------------------------ 基础行为

    @Test
    fun `未处于拦截态时一律放行`() {
        assertThat(decide("com.eg.android.AlipayGphone", locked = false))
            .isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(decide("com.ss.android.ugc.aweme", locked = false))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `拦截态下白名单外的应用被拦并返回包名`() {
        val d = decide("com.ss.android.ugc.aweme")
        assertThat(d).isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat((d as InterceptDecider.Decision.Block).packageName)
            .isEqualTo("com.ss.android.ugc.aweme")
    }

    @Test
    fun `拦截态下白名单内的应用放行`() {
        assertThat(decide("com.tencent.mm")).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `本应用永远放行，否则无法打卡`() {
        assertThat(decide(self, allowList = emptySet())).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `空包名放行（宁可漏拦也不卡死用户）`() {
        assertThat(decide(null)).isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(decide("")).isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(decide("   ")).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `包名前后空格被忽略`() {
        assertThat(decide("  com.tencent.mm  ")).isEqualTo(InterceptDecider.Decision.Allow)
    }

    // ------------------------------------------------ 关键系统包保险丝（最重要）

    @Test
    fun `关键系统包即使不在白名单也放行`() {
        InterceptDecider.CRITICAL_SYSTEM_PACKAGES.forEach { pkg ->
            assertThat(decide(pkg, allowList = emptySet()))
                .isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `系统界面与权限对话框必须在保护清单内`() {
        // 这几条一旦被拦，手机直接不可用，单独钉死
        assertThat(InterceptDecider.CRITICAL_SYSTEM_PACKAGES)
            .containsAtLeast(
                "com.android.systemui",
                "com.android.settings",
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "android"
            )
    }

    @Test
    fun `电话与紧急呼叫必须在保护清单内`() {
        assertThat(InterceptDecider.CRITICAL_SYSTEM_PACKAGES)
            .containsAtLeast(
                "com.android.server.telecom",
                "com.android.incallui",
                "com.android.phone",
                "com.android.emergency"
            )
    }

    @Test
    fun `保护清单是大写敏感的精确匹配，不做前缀放行`() {
        // 前缀放行会变成绕过漏洞：只要把包名改成 com.android.xxx 就能躲开拦截
        assertThat(decide("com.android.systemui.evil"))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(decide("com.android.settingsx"))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `空白名单时只放行本应用与关键系统包`() {
        assertThat(decide("com.tencent.mm", allowList = emptySet()))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(decide("com.android.systemui", allowList = emptySet()))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `白名单以包名为准，大小写敏感`() {
        assertThat(InterceptDecider.decide(
            foregroundPackage = "com.tencent.MM",
            selfPackage = self,
            allowList = setOf("com.tencent.mm"),
            locked = true
        )).isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    // ------------------------------------------------------------------------
    // 窗口类型让路策略 —— 这一组是为了锁死一个**已出厂的真实 bug** 而写。
    //
    // 原实现把"判定不出窗口类型"当成"不拦"，而窗口类型依赖 getWindows()，
    // 它在很多 ROM 上返回空 → 判定失败成为常态 → 整个拦截永久失效。
    //
    // 正确的语义是：只有**确实判定出**是非应用窗口才让路。
    // ------------------------------------------------------------------------

    @Test
    fun `判定出是应用窗口时不让路`() {
        assertThat(
            InterceptDecider.shouldYieldToWindow(
                windowTypeKnown = true,
                isApplicationWindow = true
            )
        ).isFalse()
    }

    @Test
    fun `判定出是非应用窗口时让路`() {
        // 来电、闹钟、输入法、系统对话框都属于这一类
        assertThat(
            InterceptDecider.shouldYieldToWindow(
                windowTypeKnown = true,
                isApplicationWindow = false
            )
        ).isTrue()
    }

    @Test
    fun `判定不出窗口类型时不让路（这是修复的核心）`() {
        // 关键：unknown 必须继续走包名判定，否则拦截会整体失效。
        // 若把这条断言反过来写，就是修复前的 bug。
        assertThat(
            InterceptDecider.shouldYieldToWindow(
                windowTypeKnown = false,
                isApplicationWindow = false // 类型未知时这个值无意义，必须被忽略
            )
        ).isFalse()

        assertThat(
            InterceptDecider.shouldYieldToWindow(
                windowTypeKnown = false,
                isApplicationWindow = true
            )
        ).isFalse()
    }

    @Test
    fun `即使窗口类型未知，关键系统包依然不会被拦`() {
        // 两道保险合起来看：不让路 + 包名保险丝 = 系统界面仍然安全
        val shouldYield = InterceptDecider.shouldYieldToWindow(
            windowTypeKnown = false,
            isApplicationWindow = false
        )
        assertThat(shouldYield).isFalse()

        // 于是流程会走到 decide，而 decide 会放行系统包
        CRITICAL_PACKAGES.forEach { pkg ->
            assertThat(InterceptDecider.decide(pkg, self, emptySet(), locked = true))
                .isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `即使窗口类型未知，白名单内的桌面与输入法依然不会被拦`() {
        val launcher = "com.miui.home"
        val ime = "com.sohu.inputmethod.sogou"
        val list = setOf(launcher, ime)

        InterceptDecider.shouldYieldToWindow(windowTypeKnown = false, isApplicationWindow = false)

        assertThat(InterceptDecider.decide(launcher, self, list, locked = true))
            .isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(InterceptDecider.decide(ime, self, list, locked = true))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `窗口类型未知时白名单外的第三方应用仍然会被拦`() {
        // 这是"不让路"策略的意义所在：修复后必须真的能拦住
        val shouldYield = InterceptDecider.shouldYieldToWindow(
            windowTypeKnown = false,
            isApplicationWindow = false
        )
        assertThat(shouldYield).isFalse()

        assertThat(InterceptDecider.decide("com.ss.android.ugc.aweme", self, allow, locked = true))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    // ------------------------------------------------------ 生效白名单的组合
    // 这一组锁死的是"修复让路策略时可能引入的回归"：
    // 桌面/输入法由运行时探测得出，包名各厂商不同，无法进兜底清单。
    // 如果只依赖数据库里的白名单（用户没进过白名单页就是空的），
    // 按 Home 键会让遮挡层盖住桌面，把用户困死。

    @Test
    fun `数据库白名单为空时桌面依然被放行`() {
        val launcher = "com.miui.home"
        val effective = InterceptDecider.effectiveAllowList(
            persisted = emptySet(),
            runtimeSystemPackages = setOf(launcher, "com.sohu.inputmethod.sogou")
        )

        assertThat(effective).contains(launcher)
        assertThat(InterceptDecider.decide(launcher, self, effective, locked = true))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `运行时系统包与数据库白名单取并集`() {
        val effective = InterceptDecider.effectiveAllowList(
            persisted = setOf("com.tencent.mm"),
            runtimeSystemPackages = setOf("com.miui.home")
        )
        assertThat(effective).containsExactly("com.tencent.mm", "com.miui.home")
    }

    @Test
    fun `并集不会把第三方应用误放进来`() {
        val effective = InterceptDecider.effectiveAllowList(
            persisted = setOf("com.tencent.mm"),
            runtimeSystemPackages = setOf("com.miui.home")
        )
        assertThat(InterceptDecider.decide("com.ss.android.ugc.aweme", self, effective, locked = true))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    private companion object {
        /** 与实现共用的同一份清单，避免测试写死后与实现脱节。 */
        val CRITICAL_PACKAGES = InterceptDecider.CRITICAL_SYSTEM_PACKAGES
    }

    // ========================================================================
    // v2.7「软件使用时间限时」：额度用尽即禁用
    //
    // 这一组要钉死的**最重要**一条是优先级：白名单与系统包永远赢过额度。
    // 理由是它会直接决定"用户会不会被卡死"：
    // 额度可能恰好绑到了桌面（用户自己选的），或者锁机期间用户按 Home
    // 要回桌面 —— 若让"额度用尽"先于白名单判定，用户就会被反复踢出桌面，
    // 表现为"手机没法用了"。本项目历史上踩过同类坑（遮挡层盖住桌面）。
    // ========================================================================

    @Test
    fun `额度用尽时该应用被拦（不依赖锁机）`() {
        // 用户要求"时间额度为零时禁用指定软件"：一条锁机规则都没配也要拦
        val d = decide("com.ss.android.ugc.aweme", locked = false, quotaExhausted = setOf("com.ss.android.ugc.aweme"))
        assertThat(d).isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat((d as InterceptDecider.Decision.Block).packageName)
            .isEqualTo("com.ss.android.ugc.aweme")
    }

    @Test
    fun `额度没用尽的应用在非锁机态下照常放行`() {
        assertThat(
            decide("com.ss.android.ugc.aweme", locked = false, quotaExhausted = setOf("com.douyin"))
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `额度用尽时白名单依然优先放行 —— 否则会把用户卡死`() {
        // 桌面包就在白名单里。若额度耗尽赢了白名单，用户按 Home 会被踢回来，
        // 反复几次就等于"手机不能用了"
        val launcher = "com.miui.home"
        val list = setOf(launcher, "com.tencent.mm")

        assertThat(decide(launcher, locked = true, allowList = list, quotaExhausted = setOf(launcher)))
            .isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(
            decide("com.tencent.mm", locked = true, allowList = list, quotaExhausted = setOf("com.tencent.mm"))
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `额度用尽时关键系统包依然放行 —— 设置与权限对话框不能被拦`() {
        CRITICAL_PACKAGES.forEach { pkg ->
            assertThat(
                decide(pkg, locked = true, allowList = emptySet(), quotaExhausted = CRITICAL_PACKAGES)
            ).isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `额度用尽时本应用依然放行 —— 否则用户进不了 App 去改额度`() {
        assertThat(
            decide(self, locked = true, allowList = emptySet(), quotaExhausted = setOf(self))
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `空包名在额度用尽时也放行`() {
        assertThat(decide(null, locked = true, quotaExhausted = setOf("com.douyin")))
            .isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(decide("  ", locked = true, quotaExhausted = setOf("com.douyin")))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `额度用尽与锁机各自独立 —— 两个理由都能拦`() {
        // 只锁机：拦
        assertThat(decide("com.douyin", locked = true, quotaExhausted = emptySet()))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        // 只额度：拦
        assertThat(decide("com.douyin", locked = false, quotaExhausted = setOf("com.douyin")))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        // 都没有：放行
        assertThat(decide("com.douyin", locked = false, quotaExhausted = emptySet()))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `额度集合的默认值是空集 —— 既有调用点行为完全不变`() {
        // 不传 quotaExhaustedPackages 时，行为必须与 v2.6 逐字相同
        assertThat(
            InterceptDecider.decide("com.douyin", self, allow, locked = false)
        ).isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(
            InterceptDecider.decide("com.douyin", self, allow, locked = true)
        ).isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(
            InterceptDecider.decide("com.tencent.mm", self, allow, locked = true)
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `额度用尽不放宽锁机判定 —— 不在白名单的应用照样被拦`() {
        // 反过来的漏洞：不能因为"实现了额度"就把锁机的兜底判定改松
        assertThat(decide("com.ss.android.ugc.aweme", locked = true, quotaExhausted = setOf("com.douyin")))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `额度包名是大小写敏感的精确匹配`() {
        assertThat(
            decide("com.Douyin", locked = false, quotaExhausted = setOf("com.douyin"))
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `额度用尽时被拦的是当次前台包名本身`() {
        val d = decide(
            "com.smile.gifmaker",
            locked = false,
            quotaExhausted = setOf("com.smile.gifmaker", "com.ss.android.ugc.aweme")
        )
        assertThat((d as InterceptDecider.Decision.Block).packageName)
            .isEqualTo("com.smile.gifmaker")
    }

    // ------------------------------------------------------------------
    // 优先级顺序的总检：
    //   ① 空包名 ② 自己 ③ 关键系统包 ④ 白名单 ⑤ 额度用尽 ⑥ 锁机兜底
    // 下面这条把 ③④ 相对 ⑤ 的优势一次性钉住 —— 改动顺序会立刻变红。
    // ------------------------------------------------------------------

    @Test
    fun `优先级：系统包与白名单都排在额度用尽之前`() {
        val allExhausted = CRITICAL_PACKAGES + allow + self
        // ③ 关键系统包
        assertThat(decide("com.android.systemui", locked = true, allowList = emptySet(), quotaExhausted = allExhausted))
            .isEqualTo(InterceptDecider.Decision.Allow)
        // ④ 白名单
        assertThat(decide("com.tencent.mm", locked = true, allowList = allow, quotaExhausted = allExhausted))
            .isEqualTo(InterceptDecider.Decision.Allow)
        // ⑤ 额度用尽（不在白名单、不是系统包）→ 拦
        assertThat(decide("com.douyin", locked = false, quotaExhausted = setOf("com.douyin")))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    // ========================================================================
    // v2.10：窗口归属判定（analyzeWindowForPackage）
    //
    // 这一组锁死的是三个**真机反馈、我无法复现**的 bug：
    //   1. 锁机中按音量键 → 音量面板（systemui 系统窗口）→ 被踢回桌面
    //   2. 最近任务（概览）→ 划后台时被踢回桌面 / 出现"空桌面"
    //   3. 白名单应用申请权限 → 对话框属于 permissioncontroller → 应用被踢（"闪退"）
    //
    // 三者的共同根因：窗口类型**判定不出来**时，原来会拿"事件包名"当应用继续判定，
    // 于是系统浮层被当成普通应用拦掉。
    //
    // ⚠️ 与上面 `shouldYieldToWindow` 那一组的区别：
    // 那一组要求"类型未知时不让路"（否则拦截会整体失效），
    // 这一组要求"类型未知、且没有证据说明是应用窗口时让路"。
    // 两者并不冲突 —— 前者管"确实判定出来时怎么办"，
    // 后者管"判定不出来时**有没有别的证据**"。
    // ========================================================================

    /** 系统 UI / 系统对话框的窗口类型（`AccessibilityWindowInfo.TYPE_SYSTEM`）。 */
    private val systemWindow = InterceptDecider.WINDOW_TYPE_SYSTEM

    /** 输入法的窗口类型（`TYPE_INPUT_METHOD`）。 */
    private val imeWindow = 2

    private fun analyze(
        pkg: String?,
        windowType: Int?,
        windowStateEvent: Boolean = true,
        mayBeApplication: Boolean = true
    ) = InterceptDecider.analyzeWindowForPackage(
        packageName = pkg,
        windowType = windowType,
        isWindowStateChangeEvent = windowStateEvent,
        eventWindowMayBeApplication = mayBeApplication
    )

    @Test
    fun `窗口类型是应用窗口时判定为应用窗口`() {
        assertThat(analyze("com.ss.android.ugc.aweme", InterceptDecider.WINDOW_TYPE_APPLICATION))
            .isEqualTo(InterceptDecider.WindowAnalysis.ApplicationWindow)
    }

    @Test
    fun `音量面板与关机菜单这类系统窗口一律判定为非应用窗口`() {
        // 真机 bug #1 的直接修复点：systemui 的系统窗口绝不能被当成应用
        assertThat(analyze("com.android.systemui", systemWindow))
            .isEqualTo(InterceptDecider.WindowAnalysis.NonApplicationWindow)
    }

    @Test
    fun `最近任务（概览）窗口判定为非应用窗口`() {
        // 真机 bug #2：从概览切到被拦应用时仍应拦，但**概览本身**不能被拦
        assertThat(analyze("com.miui.home", systemWindow))
            .isEqualTo(InterceptDecider.WindowAnalysis.NonApplicationWindow)
    }

    @Test
    fun `权限申请对话框（权限控制器、安装器、厂商变体）判定为非应用窗口`() {
        // 真机 bug #3：白名单应用申请权限时，对话框属于权限控制器，不能踢掉那个应用。
        // 这一条走的是**窗口类型**（TYPE_SYSTEM）那条路 ——
        // 因此即使某个厂商变体的包名没被收录进 CRITICAL_SYSTEM_PACKAGES
        // （例如小米的 com.miui.securitycenter），也一样不会被误拦。
        val dialogHosts = listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.miui.securitycenter",
            "com.vivo.permissionmanager",
            "com.coloros.securitypermission"
        )
        dialogHosts.forEach { host ->
            assertThat(analyze(host, systemWindow))
                .isEqualTo(InterceptDecider.WindowAnalysis.NonApplicationWindow)
        }
    }

    @Test
    fun `输入法窗口判定为非应用窗口`() {
        assertThat(analyze("com.sohu.inputmethod.sogou", imeWindow))
            .isEqualTo(InterceptDecider.WindowAnalysis.NonApplicationWindow)
    }

    @Test
    fun `无障碍浮层（本应用遮挡层）判定为非应用窗口`() {
        assertThat(analyze(self, InterceptDecider.WINDOW_TYPE_ACCESSIBILITY_OVERLAY))
            .isEqualTo(InterceptDecider.WindowAnalysis.NonApplicationWindow)
    }

    @Test
    fun `窗口类型未知但事件指向应用窗口时判定为应用窗口`() {
        assertThat(
            analyze(
                "com.ss.android.ugc.aweme",
                windowType = null,
                windowStateEvent = true,
                mayBeApplication = true
            )
        ).isEqualTo(InterceptDecider.WindowAnalysis.ApplicationWindow)
    }

    @Test
    fun `窗口类型未知且事件来自窗口状态变化时仍然判定为应用窗口（防止拦截整体失效）`() {
        // ⚠️ 这一条是"拦截不能失效"的保险丝：
        // 很多 ROM 上 getWindows() 永远返回空，那时 mayBeApplication 可能是 false，
        // 但 TYPE_WINDOW_STATE_CHANGED 是应用真的切到前台的最强信号，必须照拦。
        assertThat(
            analyze(
                "com.ss.android.ugc.aweme",
                windowType = null,
                windowStateEvent = true,
                mayBeApplication = false
            )
        ).isEqualTo(InterceptDecider.WindowAnalysis.ApplicationWindow)
    }

    @Test
    fun `窗口类型未知且事件不是窗口状态变化时判定为未确认（让路）`() {
        // 真机 bug #1 / #2 的核心修复：TYPE_WINDOWS_CHANGED（全局窗口集合变化）
        // 在窗口类型对不上号、又没有应用窗口证据时，绝不能执行"踢回桌面"
        assertThat(
            analyze(
                "com.android.systemui",
                windowType = null,
                windowStateEvent = false,
                mayBeApplication = false
            )
        ).isEqualTo(InterceptDecider.WindowAnalysis.Unconfirmed)

        assertThat(
            analyze(
                "com.miui.home",
                windowType = null,
                windowStateEvent = false,
                mayBeApplication = false
            )
        ).isEqualTo(InterceptDecider.WindowAnalysis.Unconfirmed)
    }

    @Test
    fun `空包名一律判定为未确认`() {
        assertThat(analyze(null, InterceptDecider.WINDOW_TYPE_APPLICATION))
            .isEqualTo(InterceptDecider.WindowAnalysis.Unconfirmed)
        assertThat(analyze("", InterceptDecider.WINDOW_TYPE_APPLICATION))
            .isEqualTo(InterceptDecider.WindowAnalysis.Unconfirmed)
        assertThat(analyze("   ", systemWindow))
            .isEqualTo(InterceptDecider.WindowAnalysis.Unconfirmed)
    }

    @Test
    fun `只有应用窗口事件才计入使用时长与前台包名`() {
        // 系统浮层的包名（systemui / 权限控制器）绝不能进使用时长，
        // 也不该把额度看门狗指到错误的包上
        assertThat(InterceptDecider.shouldTrackAsForeground(InterceptDecider.WindowAnalysis.ApplicationWindow))
            .isTrue()
        assertThat(InterceptDecider.shouldTrackAsForeground(InterceptDecider.WindowAnalysis.NonApplicationWindow))
            .isFalse()
        assertThat(InterceptDecider.shouldTrackAsForeground(InterceptDecider.WindowAnalysis.Unconfirmed))
            .isFalse()
    }

    @Test
    fun `窗口类型常量与 Android 框架取值一致`() {
        // 纯函数不依赖 Android 常量，所以这里把取值钉死 ——
        // 写错一个数字就会让"非应用窗口让路"整体失效
        assertThat(InterceptDecider.WINDOW_TYPE_APPLICATION).isEqualTo(1)
        assertThat(InterceptDecider.WINDOW_TYPE_SYSTEM).isEqualTo(3)
        assertThat(InterceptDecider.WINDOW_TYPE_ACCESSIBILITY_OVERLAY).isEqualTo(4)
        assertThat(InterceptDecider.WINDOW_TYPE_MAGNIFICATION).isEqualTo(5)
    }

    @Test
    fun `厂商 ROM 的权限对话框宿主也在关键系统包清单里`() {
        // 音量面板 / 最近任务 / 关机菜单靠"窗口类型判定"兜住；
        // 权限对话框与安装器则必须有精确的包名清单，否则白名单应用申请权限时
        // 对话框会被当成普通应用拦掉（用户报的"闪退"）
        assertThat(InterceptDecider.CRITICAL_SYSTEM_PACKAGES)
            .containsAtLeast(
                "com.huawei.systemmanager",
                "com.vivo.permissionmanager",
                "com.coloros.securitypermission",
                "com.oplus.securitypermission",
                "com.lbe.security.miui",
                "com.miui.packageinstaller",
                "com.sec.android.app.packageinstaller"
            )
    }

    // ========================================================================
    // v2.13：全部**系统应用**一律放行
    //
    // 这一组锁死的是一条真机 bug 的根本修法。用户原话：
    //   「锁机时，调节声音大小会显示锁机中，并退出软件。退出软件时无法直接划掉后台，
    //     会变成一个空的桌面 …… 检查默认的锁机名单，把系统软件都加入默认白名单。」
    //
    // v2.10 的"窗口类型判定"在真机上取不到证据（`TYPE_WINDOWS_CHANGED` 的 windowId
    // 对不上号），所以这一次改成**直接问系统哪些包是系统应用**
    // （`FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP`），把集合作为参数传进纯函数。
    //
    // 两组断言同等重要，缺一不可：
    //   1. 系统应用一律放行 —— 否则修不掉那个 bug
    //   2. **用户安装的第三方应用照旧被拦** —— 否则"锁机"这个功能本身就没了
    // ========================================================================

    /** 一份典型的真机系统应用集合（含音量面板宿主与厂商组件）。 */
    private val systemApps = setOf(
        "com.android.systemui",              // 音量面板 / 最近任务 / 状态栏
        "com.android.settings",              // 系统设置
        "com.android.launcher3",             // 原生桌面
        "com.miui.securitycenter",           // 小米安全中心（权限对话框宿主）
        "com.miui.home",                     // 小米桌面
        "com.google.android.permissioncontroller",
        "com.android.providers.settings",
        "com.sec.android.app.launcher"       // 三星桌面
    )

    private fun decideWithSystemPackages(
        pkg: String,
        locked: Boolean = true,
        allowList: Set<String> = emptySet(),
        quotaExhausted: Set<String> = emptySet(),
        systemPackages: Set<String> = systemApps
    ) = InterceptDecider.decide(
        foregroundPackage = pkg,
        selfPackage = self,
        allowList = allowList,
        locked = locked,
        quotaExhaustedPackages = quotaExhausted,
        systemPackages = systemPackages
    )

    @Test
    fun `系统应用在锁机态下一律放行（含音量面板与厂商权限管理器）`() {
        // 真机 bug 的直接修复点：锁机中按音量键，事件包名可能是 systemui；
        // 从概览划后台，包名可能是桌面/系统 UI。这些都必须放行。
        systemApps.forEach { pkg ->
            assertThat(decideWithSystemPackages(pkg))
                .isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `关键系统包里没有的系统应用也放行（这才是枚举的意义）`() {
        // 手写清单永远不全：com.miui.securitycenter、com.android.providers.settings
        // 这类包名不在 CRITICAL_SYSTEM_PACKAGES 里，但它们是系统应用
        val notInCritical = setOf(
            "com.miui.securitycenter",
            "com.android.providers.settings",
            "com.android.launcher3",
            "com.miui.home"
        )
        notInCritical.forEach { pkg ->
            assertThat(InterceptDecider.CRITICAL_SYSTEM_PACKAGES).doesNotContain(pkg)
            assertThat(decideWithSystemPackages(pkg))
                .isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `系统应用即使在额度用尽时也放行`() {
        // 额度可能恰好绑到桌面/系统组件上（用户自己选的）。若额度赢了系统应用，
        // 用户按 Home 会被反复踢回来 —— 与"遮挡层盖住桌面"是同一类事故。
        systemApps.forEach { pkg ->
            assertThat(
                decideWithSystemPackages(pkg, quotaExhausted = systemApps)
            ).isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `用户安装的第三方应用仍然会被拦 —— 放行的边界只能是系统应用`() {
        // ⚠️ 这条是"锁机还有没有用"的底线：只要有人把系统包判据放宽成
        // "以 com. 开头"或"包名在已安装列表里"，这条断言立刻变红。
        val thirdParty = setOf(
            "com.ss.android.ugc.aweme",      // 抖音
            "com.tencent.mm",                // 微信
            "com.eg.android.AlipayGphone",   // 支付宝
            "com.smile.gifmaker",            // 快手
            "com.xingin.xhs"                 // 小红书
        )
        thirdParty.forEach { pkg ->
            assertThat(decideWithSystemPackages(pkg))
                .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        }
    }

    @Test
    fun `系统应用集合不做前缀放行 —— 伪装成系统包的第三方应用照样拦`() {
        // 前缀放行会变成绕过漏洞：把包名改成 com.android.xxx 就能躲开拦截
        assertThat(decideWithSystemPackages("com.android.systemui.evil"))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(decideWithSystemPackages("com.miui.home.pro"))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `系统应用集合是空集时行为与上一版逐字相同（默认参数不变）`() {
        // 既有调用点不传 systemPackages 时，行为必须完全一样
        assertThat(
            InterceptDecider.decide("com.douyin", self, allow, locked = true)
        ).isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(
            InterceptDecider.decide("com.douyin", self, allow, locked = false)
        ).isEqualTo(InterceptDecider.Decision.Allow)
        // 显式传空集合与不传等价
        assertThat(decideWithSystemPackages("com.douyin", systemPackages = emptySet()))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        // 传了空集也不会把系统包"放行错"，那部分仍由关键系统包清单兜住
        assertThat(
            decideWithSystemPackages("com.android.systemui", systemPackages = emptySet())
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `系统应用放行是无条件的 —— 不依赖锁机态也不依赖白名单`() {
        assertThat(decideWithSystemPackages("com.miui.securitycenter", locked = false))
            .isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(decideWithSystemPackages("com.miui.securitycenter", locked = true))
            .isEqualTo(InterceptDecider.Decision.Allow)
        // 白名单为空也放行
        assertThat(decideWithSystemPackages("com.miui.securitycenter", allowList = emptySet()))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `系统应用与本应用自己优先于一切 —— 空包名仍然放行`() {
        assertThat(decideWithSystemPackages(""))
            .isEqualTo(InterceptDecider.Decision.Allow)
        assertThat(decideWithSystemPackages(self, allowList = emptySet()))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `系统应用集合不会影响额度对第三方应用的判定`() {
        // 反过来的漏洞：不能因为"放行了系统应用"就把额度的兜底判定改松
        assertThat(
            decideWithSystemPackages(
                "com.ss.android.ugc.aweme",
                locked = false,
                quotaExhausted = setOf("com.ss.android.ugc.aweme")
            )
        ).isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    // ========================================================================
    // v2.14：系统界面组件 / 截屏 / 智能助理 —— 用户报"还是会被拦"
    //
    // 用户原话：
    //   「系统界面组件，截屏，智能助里加入默认名单」
    //   「拦截日志显示系统界面组件，截屏，智能助理被拦截」
    //
    // v2.13 已经枚举了全部 FLAG_SYSTEM 的应用，仍然拦到这三类，根因有三条
    // （完整论证在 AlwaysAllowPackages 的类 KDoc）：
    //   1. 智能助理常常是**商店安装**的（Google 助理没有任何系统标志），枚举拿不到
    //   2. 厂商标称"系统界面组件"的包**不叫** com.android.systemui（它在 v1 就在
    //      关键清单里，根本拦不到），而是 <vendor>.systemui 这类变体
    //   3. 枚举只算一次，失败即永久为空
    //
    // 这一组钉住"这三类无条件放行、且不会顺带放行第三方应用"。
    // ========================================================================

    private fun decideWithAlwaysAllow(
        pkg: String,
        locked: Boolean = true,
        allowList: Set<String> = emptySet(),
        quotaExhausted: Set<String> = emptySet(),
        alwaysAllow: Set<String> = emptySet()
    ) = InterceptDecider.decide(
        foregroundPackage = pkg,
        selfPackage = self,
        allowList = allowList,
        locked = locked,
        quotaExhaustedPackages = quotaExhausted,
        // 刻意不传 systemPackages：这一组要证明"**即使枚举返回 0 个系统应用**，
        // 这三类也照样放行" —— 也就是不依赖那次可能失败的枚举。
        systemPackages = emptySet(),
        alwaysAllowPackages = alwaysAllow
    )

    @Test
    fun `系统界面组件 截屏 智能助理在锁机态一律放行（不依赖系统应用枚举）`() {
        listOf(
            "com.android.systemui",                       // AOSP / 大多数 ROM
            "com.miui.systemui",                          // 厂商变体（族判定）
            "com.transsion.systemui",                     // 厂商变体（族判定）
            "com.miui.screenshot",                        // 截屏
            "com.coloros.screenshot",
            "com.google.android.googlequicksearchbox",    // 智能助理（商店安装，无系统标志）
            "com.miui.voiceassist",
            "com.huawei.vassistant"
        ).forEach { pkg ->
            assertThat(decideWithAlwaysAllow(pkg))
                .isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `额度用尽时这三类也放行 —— 否则用户会在锁机里被自己的额度踢出去`() {
        listOf(
            "com.miui.systemui",
            "com.miui.screenshot",
            "com.google.android.googlequicksearchbox"
        ).forEach { pkg ->
            assertThat(
                decideWithAlwaysAllow(pkg, quotaExhausted = setOf(pkg))
            ).isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `按 Intent 运行时探测到的助理包一律放行`() {
        // 探测结果作为参数传入（`alwaysAllowPackages`）：不靠写死清单，
        // 而是"系统告诉我们现在谁在接 ASSIST"
        val detected = setOf("com.example.oem.assistant")
        assertThat(
            decideWithAlwaysAllow("com.example.oem.assistant", alwaysAllow = detected)
        ).isEqualTo(InterceptDecider.Decision.Allow)
        // 没探测到就是普通应用 —— 照拦（放行边界不能被"可能是系统组件"放宽）
        assertThat(decideWithAlwaysAllow("com.example.oem.assistant"))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `这三类的放行不会顺带放行第三方应用`() {
        listOf(
            "com.ss.android.ugc.aweme",
            "com.tencent.mm",
            "com.android.systemui.evil",   // 伪装包名
            "com.miui.home.pro"
        ).forEach { pkg ->
            assertThat(decideWithAlwaysAllow(pkg))
                .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        }
    }

    @Test
    fun `不传 alwaysAllowPackages 时行为与上一版逐字相同（默认参数）`() {
        assertThat(InterceptDecider.decide("com.douyin", self, allow, locked = true))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(InterceptDecider.decide("com.douyin", self, allow, locked = false))
            .isEqualTo(InterceptDecider.Decision.Allow)
    }

    // ------------------------------------------------ 判定依据（v2.14 新增诊断）

    @Test
    fun `explainBlock 说清"为什么这个包被判成要拦"`() {
        val basis = InterceptDecider.explainBlock(
            foregroundPackage = "com.ss.android.ugc.aweme",
            selfPackage = self,
            allowList = allow,
            locked = true,
            systemPackages = setOf("com.android.systemui", "com.miui.home"),
            alwaysAllowPackages = setOf("com.google.android.googlequicksearchbox")
        )
        // 开头是固定前缀，用户能一眼认出这是"判定依据"那一行
        assertThat(basis).startsWith("拦截依据：")
        // 五个放行集合逐个打叉（用户可以据此看出到底缺哪一环）
        assertThat(basis).contains("关键清单✗")
        assertThat(basis).contains("系统界面/截屏/助理✗")
        assertThat(basis).contains("系统应用枚举(2)✗")
        assertThat(basis).contains("运行时探测(1)✗")
        assertThat(basis).contains("白名单(")
        // 结论：锁机态 + 不在任何放行集合
        assertThat(basis).contains("锁机中且不在任何放行集合里")
    }

    @Test
    fun `explainBlock 把"枚举为 0"直接写出来 —— 那是枚举失败的第一现场`() {
        val basis = InterceptDecider.explainBlock(
            foregroundPackage = "com.unknown.oem.systemcomponent",
            selfPackage = self,
            allowList = emptySet(),
            locked = true,
            systemPackages = emptySet(),
            alwaysAllowPackages = emptySet()
        )
        assertThat(basis).contains("系统应用枚举(0)")
        assertThat(basis).contains("运行时探测(0)")
    }

    @Test
    fun `explainBlock 区分得出"额度用尽"与"锁机"两种理由`() {
        val quota = InterceptDecider.explainBlock(
            foregroundPackage = "com.ss.android.ugc.aweme",
            selfPackage = self,
            allowList = emptySet(),
            locked = false,
            quotaExhaustedPackages = setOf("com.ss.android.ugc.aweme")
        )
        assertThat(quota).contains("额度用尽")

        val locked = InterceptDecider.explainBlock(
            foregroundPackage = "com.ss.android.ugc.aweme",
            selfPackage = self,
            allowList = emptySet(),
            locked = true
        )
        assertThat(locked).contains("锁机中")
        assertThat(locked).doesNotContain("额度用尽")
    }

    @Test
    fun `explainBlock 与 decide 的口径必须一致`() {
        // ⚠️ 这是防"解释与判定分叉"的那一条：
        // 只要 decide 放行，explainBlock 就不能说"拦截依据"；反之亦然。
        val cases = listOf(
            // Triple(包名, 是否传进放行集合, 期望是否被拦)
            Triple("com.android.systemui", false, false),
            Triple("com.miui.systemui", false, false),
            Triple("com.miui.screenshot", false, false),
            Triple("com.google.android.googlequicksearchbox", false, false),
            Triple("com.miui.home", true, false),       // 在 systemPackages 里
            Triple("com.tencent.mm", false, true),      // 第三方
            Triple("com.android.systemui.evil", false, true),
            Triple(self, false, false)
        )
        cases.forEach { (pkg, inSystemPackages, shouldBlock) ->
            val systemPackages = if (inSystemPackages) setOf(pkg) else emptySet()
            val decision = InterceptDecider.decide(
                foregroundPackage = pkg,
                selfPackage = self,
                allowList = emptySet(),
                locked = true,
                systemPackages = systemPackages
            )
            val basis = InterceptDecider.explainBlock(
                foregroundPackage = pkg,
                selfPackage = self,
                allowList = emptySet(),
                locked = true,
                systemPackages = systemPackages
            )
            assertThat(decision is InterceptDecider.Decision.Block)
                .isEqualTo(shouldBlock)
            assertThat(basis.startsWith("拦截依据："))
                .isEqualTo(shouldBlock)
        }
    }

    // ========================================================================
    // v2.15：只拦「能启动的应用」
    //
    // 用户第 4 次报同一个 bug（调音量仍被拦、日志显示「系统界面组件」）。
    // 前三次修复（v2.10 窗口类型 / v2.13 系统应用枚举 / v2.14 关键组件清单）
    // 都在"给系统组件找一个放行的理由"，而厂商变体永远能躲开清单。
    //
    // 这一版换判据：**没有启动入口的包一律放行**。
    // 下面两组断言同等重要，缺一不可：
    //   1. 没有启动入口的包放行 —— 否则修不掉这个 bug
    //   2. **可启动但不在白名单的应用照旧被拦** —— 否则"锁机"本身失效
    // ========================================================================

    /** 一份典型的"用户会真的打开"的可启动应用集合。 */
    private val launchableApps = setOf(
        "com.tencent.mm",                  // 微信
        "com.ss.android.ugc.aweme",        // 抖音
        "com.smile.gifmaker",              // 快手
        "com.eg.android.AlipayGphone",     // 支付宝
        "com.miui.home"                    // 桌面
    )

    /**
     * 一批**没有启动入口**的系统浮层宿主：音量面板 / 截屏浮层 / 分享面板 /
     * 智能助理悬浮层。它们都不是用户能"打开"的应用。
     *
     * ⚠️ 刻意全部选**不在** [InterceptDecider.CRITICAL_SYSTEM_PACKAGES] 里、
     * 也不命中 [AlwaysAllowPackages] 的名字 —— 否则这条测试就证明不了
     * "是第 5.5 条放行的"（这一点由下面的 `这些包不在任何旧判据里` 单独钉死）。
     */
    private val noLaunchEntryComponents = setOf(
        "com.oplus.sharesheet",              // 分享面板
        "com.example.oem.volume.panel",      // 音量面板（虚构厂商变体）
        "com.transsion.volumeui",            // 音量 UI（传音风格命名）
        "com.example.oem.screenshot.overlay" // 截屏浮层
    )

    private fun decideWithLaunchable(
        pkg: String,
        locked: Boolean = true,
        allowList: Set<String> = emptySet(),
        quotaExhausted: Set<String> = emptySet(),
        launchablePackages: Set<String> = launchableApps
    ) = InterceptDecider.decide(
        foregroundPackage = pkg,
        selfPackage = self,
        allowList = allowList,
        locked = locked,
        quotaExhaustedPackages = quotaExhausted,
        launchablePackages = launchablePackages
    )

    @Test
    fun `没有启动入口的包一律放行 —— 锁机态也不例外`() {
        // 这就是本次 bug 的直接修复点：音量面板 / 截屏浮层 / 分享面板
        // 根本没有"启动"这回事，拦它们除了打断用户没有任何意义。
        noLaunchEntryComponents.forEach { pkg ->
            assertThat(decideWithLaunchable(pkg, locked = true, allowList = emptySet()))
                .isEqualTo(InterceptDecider.Decision.Allow)
        }
    }

    @Test
    fun `这些浮层包不在任何旧判据里 —— 放行只能来自新的可启动判据`() {
        // 防"测试其实是被旧判据放行的"这种假绿：如果某个包能被
        // CRITICAL_SYSTEM_PACKAGES 或 AlwaysAllowPackages 放行，那它就证明不了新判据。
        noLaunchEntryComponents.forEach { pkg ->
            assertThat(InterceptDecider.CRITICAL_SYSTEM_PACKAGES).doesNotContain(pkg)
            assertThat(AlwaysAllowPackages.isAlwaysAllowed(pkg)).isFalse()
            assertThat(launchableApps).doesNotContain(pkg)
        }
    }

    @Test
    fun `没有启动入口时额度用尽也放行`() {
        // 额度是"限制可打开的应用能用多久"，对一个打不开的组件毫无意义。
        // 若这一条失败，用户会在额度耗尽时被自己的手机浮层反复踢出桌面。
        val pkg = "com.example.oem.volume.panel"
        assertThat(
            decideWithLaunchable(
                pkg,
                locked = false,
                quotaExhausted = setOf(pkg)
            )
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `可启动但不在白名单的应用仍然被拦 —— 这条不能把锁机变松`() {
        // ⚠️ 安全底线：用户要拦的微信 / 抖音 / 游戏都能启动，所以不受影响。
        // 谁要是把这条改成"能启动就放行"，锁机当场失效，这条断言立刻变红。
        launchableApps.forEach { pkg ->
            assertThat(decideWithLaunchable(pkg, locked = true, allowList = emptySet()))
                .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        }
    }

    @Test
    fun `可启动应用在额度用尽时同样被拦 —— 额度这条线也没变松`() {
        val pkg = "com.ss.android.ugc.aweme"
        assertThat(
            decideWithLaunchable(pkg, locked = false, quotaExhausted = setOf(pkg))
        ).isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `可启动集合为空时退化为全都算可启动（= 旧行为，锁机不受影响）`() {
        // ⚠️ 兜底方向是刻意的：枚举失败（ROM 限制）**绝不能**理解成
        // "所有包都没有启动入口" —— 那等于把所有应用都放行，锁机当场消失。
        // 正确语义：证明不了 ⇒ 本规则不生效 ⇒ 用旧行为（该拦的照拦）。
        assertThat(decideWithLaunchable("com.tencent.mm", launchablePackages = emptySet()))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(
            decideWithLaunchable(
                "com.ss.android.ugc.aweme",
                locked = false,
                quotaExhausted = setOf("com.ss.android.ugc.aweme"),
                launchablePackages = emptySet()
            )
        ).isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `不传可启动集合时行为与上一版逐字相同（默认参数）`() {
        // 既有调用点与既有测试一行都不用改，靠的就是这个默认空集合
        assertThat(InterceptDecider.decide("com.douyin", self, allow, locked = true))
            .isInstanceOf(InterceptDecider.Decision.Block::class.java)
        assertThat(
            InterceptDecider.decide("com.example.oem.volume.panel", self, allow, locked = true)
        ).isInstanceOf(InterceptDecider.Decision.Block::class.java)
    }

    @Test
    fun `hasNoLaunchEntry 的判据：空集合一律返回 false`() {
        // 这一条直接钉住兜底逻辑本身（不必绕过 decide）
        assertThat(InterceptDecider.hasNoLaunchEntry("com.example.oem.volume.panel", emptySet()))
            .isFalse()
        // 拿不到包名时也不成立（与"空包名放行"同口径）
        assertThat(InterceptDecider.hasNoLaunchEntry(null, launchableApps)).isFalse()
        assertThat(InterceptDecider.hasNoLaunchEntry("   ", launchableApps)).isFalse()
    }

    @Test
    fun `hasNoLaunchEntry 的判据：集合非空时按精确匹配判定`() {
        assertThat(InterceptDecider.hasNoLaunchEntry("com.tencent.mm", launchableApps)).isFalse()
        assertThat(
            InterceptDecider.hasNoLaunchEntry("com.example.oem.volume.panel", launchableApps)
        ).isTrue()
        // 精确匹配、大小写敏感（与其它放行集合口径一致，不做前缀/模糊放行）
        assertThat(InterceptDecider.hasNoLaunchEntry("com.tencent.MM", launchableApps)).isTrue()
        assertThat(
            InterceptDecider.hasNoLaunchEntry("com.tencent.mm.pro", launchableApps)
        ).isTrue()
    }

    @Test
    fun `被拦的包一定有启动入口（锁机路径的性质检验）`() {
        // 性质：在锁机态 + 空白名单下，Block ⇔ 该包在可启动集合里。
        // 这一条把"只拦能启动的应用"这件事说全了 —— 既不误拦浮层，也不放过应用。
        val candidates = launchableApps + noLaunchEntryComponents
        candidates.forEach { pkg ->
            val decision = decideWithLaunchable(pkg, locked = true, allowList = emptySet())
            val shouldBlock = pkg in launchableApps
            assertThat(decision is InterceptDecider.Decision.Block)
                .isEqualTo(shouldBlock)
        }
    }

    @Test
    fun `可启动判定排在白名单与系统包之后 —— 不会改变它们的放行结论`() {
        // 关键系统包即使没出现在可启动集合里也必须放行（测试机的集合可能不全）
        assertThat(
            decideWithLaunchable("com.android.systemui", allowList = emptySet())
        ).isEqualTo(InterceptDecider.Decision.Allow)
        // 白名单内的应用照旧放行
        assertThat(
            decideWithLaunchable("com.tencent.mm", allowList = setOf("com.tencent.mm"))
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    // ------------------------------------------------ 判定依据带上"是否可启动"

    @Test
    fun `explainBlock 带上"是否可启动"这一项`() {
        val basis = InterceptDecider.explainBlock(
            foregroundPackage = "com.tencent.mm",
            selfPackage = self,
            allowList = emptySet(),
            locked = true,
            launchablePackages = launchableApps
        )
        assertThat(basis).startsWith("拦截依据：")
        // `✗` = 这个包确实能启动（所以拦它是有意义的）
        assertThat(basis).contains("无启动入口✗")
    }

    @Test
    fun `explainBlock 对没有启动入口的包说"未拦截"—— 与 decide 口径一致`() {
        val pkg = "com.example.oem.volume.panel"
        assertThat(
            InterceptDecider.explainBlock(
                foregroundPackage = pkg,
                selfPackage = self,
                allowList = emptySet(),
                locked = true,
                quotaExhaustedPackages = setOf(pkg),
                launchablePackages = launchableApps
            )
        ).isEqualTo("未拦截")
        assertThat(
            InterceptDecider.decide(
                foregroundPackage = pkg,
                selfPackage = self,
                allowList = emptySet(),
                locked = true,
                quotaExhaustedPackages = setOf(pkg),
                launchablePackages = launchableApps
            )
        ).isEqualTo(InterceptDecider.Decision.Allow)
    }

    @Test
    fun `可启动判据上 decide 与 explainBlock 不会分叉`() {
        val candidates = launchableApps + noLaunchEntryComponents
        candidates.forEach { pkg ->
            val decision = InterceptDecider.decide(
                foregroundPackage = pkg,
                selfPackage = self,
                allowList = emptySet(),
                locked = true,
                launchablePackages = launchableApps
            )
            val blocked = decision is InterceptDecider.Decision.Block
            val basis = InterceptDecider.explainBlock(
                foregroundPackage = pkg,
                selfPackage = self,
                allowList = emptySet(),
                locked = true,
                launchablePackages = launchableApps
            )
            assertThat(basis.startsWith("拦截依据：")).isEqualTo(blocked)
        }
    }
}
