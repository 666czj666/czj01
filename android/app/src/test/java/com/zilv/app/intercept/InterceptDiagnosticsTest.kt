package com.zilv.app.intercept

import com.google.common.truth.Truth.assertThat
import com.zilv.app.domain.intercept.InterceptDecider
import org.junit.Before
import org.junit.Test

/**
 * 拦截自检诊断的单元测试。
 *
 * **为什么这组测试重要**：用户两次报告"拦不住"，而我无法在真机上调试。
 * 拦截依赖四个彼此独立的环节，任一断掉现象都一样。这里把"如何从快照
 * 判断断在哪一环"的推理钉死，保证界面上给出的结论不会误导用户。
 */
class InterceptDiagnosticsTest {

    @Before
    fun setUp() {
        InterceptDiagnostics.resetForTest()
    }

    @Test
    fun `初始状态指向第一环`() {
        val snapshot = InterceptDiagnostics.snapshot()
        assertThat(snapshot.first().first).contains("无障碍服务")
        assertThat(snapshot.first().second).contains("未连接")
        assertThat(InterceptDiagnostics.verdict()).contains("第①环")
    }

    @Test
    fun `服务已连接但收不到事件时指向第二环`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        assertThat(InterceptDiagnostics.verdict()).contains("第②环")
        assertThat(InterceptDiagnostics.snapshot()[1].second).contains("0 次")
    }

    @Test
    fun `有事件但从未判为锁机时提示检查规则`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.android.chrome", "放行：当前阶段是 IDLE，不是锁机态")
        InterceptDiagnostics.onEvent("com.android.chrome", "放行：当前阶段是 IDLE，不是锁机态")

        val verdict = InterceptDiagnostics.verdict()
        assertThat(verdict).contains("还没出现过锁机态")
        assertThat(InterceptDiagnostics.eventsSeen).isEqualTo(2L)
    }

    @Test
    fun `判为锁机却从未拦截时指出应用都在白名单内`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.tencent.mm", "放行：在白名单内")
        InterceptDiagnostics.onLockedEvaluated()

        assertThat(InterceptDiagnostics.verdict()).contains("都在白名单里")
    }

    @Test
    fun `真正拦截过之后判定为正常`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.ss.android.ugc.aweme", "拦截：不在白名单，执行拦截动作")
        InterceptDiagnostics.onLockedEvaluated()
        InterceptDiagnostics.onBlocked("com.ss.android.ugc.aweme")

        assertThat(InterceptDiagnostics.blockedCount).isEqualTo(1L)
        assertThat(InterceptDiagnostics.verdict()).contains("正常")
        assertThat(InterceptDiagnostics.lastBlockedPackage).isEqualTo("com.ss.android.ugc.aweme")
    }

    @Test
    fun `出错时优先显示错误`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = false)
        InterceptDiagnostics.onEvent("com.foo", "判定中")
        InterceptDiagnostics.onError("踢回桌面失败：SecurityException")

        assertThat(InterceptDiagnostics.verdict()).contains("踢回桌面失败")
    }

    @Test
    fun `快照包含四个必查环节`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        val labels = InterceptDiagnostics.snapshot().map { it.first }

        // v2.15 起是 12 行：前四行仍是四个必查环节（①~④），
        // 后面是八条参考资料 —— v2.10 加的是「窗口归属」，
        // v2.14 加的是「最近拦截依据」（用户报"系统界面组件被拦"时唯一能自证的现场），
        // v2.15 加的是「可启动应用」（"只拦能启动的应用"这条规则的开关状态）。
        // ⚠️ 这条断言的**意图没变**（保证四个必查环节在最前面且齐全），
        // 只是行数随诊断行增加而从 11 变 12 —— 归类为「测试错（旧规格）」。
        assertThat(labels).hasSize(12)
        assertThat(labels[0]).startsWith("①")
        assertThat(labels[1]).startsWith("②")
        assertThat(labels[2]).startsWith("③")
        assertThat(labels[3]).startsWith("④")
        // 新增的诊断行必须在快照里，否则用户看不到"判定依据"
        assertThat(labels).contains("参考 · 最近拦截依据")
        // v2.15：这一行是新规则的开关状态，缺了就无法判断修复到底有没有生效
        assertThat(labels).contains("参考 · 可启动应用")
    }

    // ------------------------------------------------- v2.14：拦截判定依据

    @Test
    fun `判定依据会写进快照，用户截图即可反馈为什么被拦`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onBlocked(
            "com.unknown.oem.systemcomponent",
            "拦截依据：关键清单✗ 系统界面/截屏/助理✗；理由=锁机中且不在任何放行集合里"
        )

        val line = InterceptDiagnostics.snapshot()
            .first { it.first == "参考 · 最近拦截依据" }.second
        assertThat(line).contains("拦截依据")
        assertThat(line).contains("系统界面/截屏/助理✗")
    }

    @Test
    fun `没有拦过任何应用时判定依据给出明确占位而不是空白`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        val line = InterceptDiagnostics.snapshot()
            .first { it.first == "参考 · 最近拦截依据" }.second
        assertThat(line).contains("还没有拦到任何应用")
    }

    @Test
    fun `最近拦到关键系统组件时结论直接说这是缺陷，而不是"链路正常"`() {
        // 用户报"系统界面组件 / 截屏 / 智能助理被拦"时，如果结论仍然是
        // "拦截链路正常"，就等于把缺陷盖过去了 —— 所以这一条单独钉死。
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.miui.systemui", "拦截：不在白名单")
        InterceptDiagnostics.onBlocked("com.miui.systemui", "拦截依据：…")

        val verdict = InterceptDiagnostics.verdict()
        assertThat(verdict).contains("系统界面")
        assertThat(verdict).contains("缺陷")
        assertThat(verdict).contains("com.miui.systemui")
    }

    @Test
    fun `拦到关键系统组件时判定为"不正常"，界面据此显示结论而不是打勾`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        assertThat(InterceptDiagnostics.lastBlockedWasCriticalComponent).isFalse()

        InterceptDiagnostics.onBlocked("com.miui.screenshot")
        assertThat(InterceptDiagnostics.lastBlockedWasCriticalComponent).isTrue()

        // 换成普通第三方应用后恢复正常（不能一直停在"有缺陷"）
        InterceptDiagnostics.onBlocked("com.ss.android.ugc.aweme")
        assertThat(InterceptDiagnostics.lastBlockedWasCriticalComponent).isFalse()
    }

    @Test
    fun `自检页报告关键组件探测个数`() {
        InterceptDiagnostics.updateLists(
            effectiveAllow = 12,
            runtimeSystemPackages = 5,
            systemApps = 187,
            alwaysAllow = 4
        )
        val line = InterceptDiagnostics.snapshot().first { it.first == "参考 · 白名单" }.second
        assertThat(line).contains("关键组件探测 4 个")
        assertThat(InterceptDiagnostics.alwaysAllowDetectedCount).isEqualTo(4)
    }

    // ============================================================ 窗口归属（v2.10）
    //
    // 这一组是为三个真机 bug 加的可观测性：音量面板 / 最近任务 / 权限对话框
    // 被误当成普通应用而拦掉。我无法上真机，所以把"这次事件到底被判成了什么窗口"
    // 留在自检页上 —— 这是用户反馈时唯一能拿到的现场证据。

    @Test
    fun `应用窗口与系统窗口分开计数`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)

        InterceptDiagnostics.onWindowClassified(
            packageName = "com.ss.android.ugc.aweme",
            windowType = InterceptDecider.WINDOW_TYPE_APPLICATION,
            analysis = InterceptDecider.WindowAnalysis.ApplicationWindow.name,
            isWindowStateChangeEvent = true
        )
        InterceptDiagnostics.onWindowClassified(
            packageName = "com.android.systemui",
            windowType = 3,
            analysis = InterceptDecider.WindowAnalysis.NonApplicationWindow.name,
            isWindowStateChangeEvent = false
        )
        InterceptDiagnostics.onWindowClassified(
            packageName = "com.android.permissioncontroller",
            windowType = null,
            analysis = InterceptDecider.WindowAnalysis.Unconfirmed.name,
            isWindowStateChangeEvent = false
        )

        assertThat(InterceptDiagnostics.appWindowCount).isEqualTo(1L)
        assertThat(InterceptDiagnostics.nonAppWindowCount).isEqualTo(2L)
        assertThat(InterceptDiagnostics.lastWindowPackage).isEqualTo("com.android.permissioncontroller")
        assertThat(InterceptDiagnostics.lastWindowEventKind).isEqualTo("WINDOWS_CHANGED")
        // 判定不出来时记 -1（而不是 0，0 会让"类型未知"和"类型 0"混淆）
        assertThat(InterceptDiagnostics.lastWindowType).isEqualTo(-1)
        assertThat(InterceptDiagnostics.lastWindowAnalysis)
            .isEqualTo(InterceptDecider.WindowAnalysis.Unconfirmed.name)
    }

    @Test
    fun `快照里的窗口归属行给出判定结果与原因`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onWindowClassified(
            packageName = "com.android.systemui",
            windowType = 3,
            analysis = InterceptDecider.WindowAnalysis.NonApplicationWindow.name,
            isWindowStateChangeEvent = true
        )

        val line = InterceptDiagnostics.snapshot().first { it.first.contains("窗口归属") }.second
        assertThat(line).contains("com.android.systemui")
        assertThat(line).contains("WINDOW_STATE_CHANGED")
        assertThat(line).contains(InterceptDecider.WindowAnalysis.NonApplicationWindow.name)
        assertThat(line).contains("type=3")
    }

    @Test
    fun `所有事件都被判成非应用窗口时给出专门的结论`() {
        // 这条结论存在的意义：若窗口判定过宽，拦截会静默失效，
        // 而"从未拦截"的默认结论会把用户引到错误的方向（以为没打开过被拦的应用）
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.android.systemui", "让路：Unconfirmed")
        repeat(5) {
            InterceptDiagnostics.onWindowClassified(
                packageName = "com.android.systemui",
                windowType = null,
                analysis = InterceptDecider.WindowAnalysis.Unconfirmed.name,
                isWindowStateChangeEvent = false
            )
        }

        val verdict = InterceptDiagnostics.verdict()
        assertThat(verdict).contains("非应用窗口")
        assertThat(verdict).doesNotContain("还没出现过锁机态")
    }

    @Test
    fun `resetForTest 会把窗口归属计数一起清零`() {
        InterceptDiagnostics.onWindowClassified(
            packageName = "com.android.systemui",
            windowType = 3,
            analysis = InterceptDecider.WindowAnalysis.NonApplicationWindow.name,
            isWindowStateChangeEvent = false
        )
        InterceptDiagnostics.onWindowClassified(
            packageName = "com.foo",
            windowType = InterceptDecider.WINDOW_TYPE_APPLICATION,
            analysis = InterceptDecider.WindowAnalysis.ApplicationWindow.name,
            isWindowStateChangeEvent = true
        )

        InterceptDiagnostics.resetForTest()

        assertThat(InterceptDiagnostics.appWindowCount).isEqualTo(0L)
        assertThat(InterceptDiagnostics.nonAppWindowCount).isEqualTo(0L)
        assertThat(InterceptDiagnostics.lastWindowPackage).isEmpty()
        assertThat(InterceptDiagnostics.lastWindowEventKind).isEmpty()
        assertThat(InterceptDiagnostics.lastWindowType).isEqualTo(-1)
        assertThat(InterceptDiagnostics.lastWindowAnalysis).isEmpty()
    }

    // ============================================================ 额度限时（v2.7）
    //
    // 用户报"限时没生效"时，光看"拦截 0 次"分不清是
    //   (a) 额度根本没被算出来，还是
    //   (b) 算出来了但被判定的前台应用不在那个集合里。
    // 这一组把两个计数分开钉死，让真机排查有据可依。

    @Test
    fun `额度禁用数与拦截数分开统计`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.updateQuota(disabledPackages = 3)
        InterceptDiagnostics.onEvent("com.ss.android.ugc.aweme", "拦截：额度用尽")
        InterceptDiagnostics.onQuotaBlocked("com.ss.android.ugc.aweme")

        assertThat(InterceptDiagnostics.quotaDisabledCount).isEqualTo(3)
        assertThat(InterceptDiagnostics.quotaBlockedCount).isEqualTo(1L)
        assertThat(InterceptDiagnostics.lastQuotaBlockedPackage)
            .isEqualTo("com.ss.android.ugc.aweme")
        // 两个计数**互相独立**：`onQuotaBlocked` 只记"原因"，不碰总的拦截数
        // （总的拦截数由 onBlocked 在实际执行踢回桌面时记）。
        // 这正是"分开统计"的意义 —— 真机上能看出"判定了但没执行"。
        assertThat(InterceptDiagnostics.blockedCount).isEqualTo(0L)
    }

    @Test
    fun `额度看门狗轮数单独统计`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        repeat(4) { InterceptDiagnostics.onQuotaWatchdogTick() }
        assertThat(InterceptDiagnostics.quotaWatchdogTicks).isEqualTo(4L)
    }

    @Test
    fun `额度拦截过之后结论不再说从未出现过拦截`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.douyin", "拦截：额度用尽")
        InterceptDiagnostics.updateQuota(disabledPackages = 2)
        InterceptDiagnostics.onQuotaBlocked("com.douyin")

        val verdict = InterceptDiagnostics.verdict()
        // 已经真的拦过了，就不该再说"还没出现过锁机态，也没拦截过额度"
        assertThat(verdict).doesNotContain("还没出现过锁机态")
        assertThat(verdict).contains("额度")
    }

    @Test
    fun `额度被算出但还没拦过时结论仍提示检查配置`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onEvent("com.tencent.mm", "放行：非锁机态，且额度未用尽")
        // 只有"算出来有额度被禁用"，还没有真正执行过拦截
        InterceptDiagnostics.updateQuota(disabledPackages = 2)

        val verdict = InterceptDiagnostics.verdict()
        assertThat(verdict).contains("额度已绑应用")
        // 诊断行里能看到禁用数，便于区分"没算出来"与"算出来了但没拦"
        val line = InterceptDiagnostics.snapshot().first { it.first.contains("额度") }.second
        assertThat(line).contains("2")
    }

    @Test
    fun `快照里的额度行给出禁用数与拦截数`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.updateQuota(disabledPackages = 2)
        InterceptDiagnostics.onQuotaBlocked("com.douyin")

        val line = InterceptDiagnostics.snapshot().first { it.first.contains("额度") }.second
        assertThat(line).contains("2")
        assertThat(line).contains("com.douyin")
    }

    @Test
    fun `resetForTest 会把额度计数一起清零`() {
        InterceptDiagnostics.updateQuota(5)
        InterceptDiagnostics.onQuotaBlocked("com.douyin")
        InterceptDiagnostics.onQuotaWatchdogTick()

        InterceptDiagnostics.resetForTest()

        assertThat(InterceptDiagnostics.quotaDisabledCount).isEqualTo(0)
        assertThat(InterceptDiagnostics.quotaBlockedCount).isEqualTo(0L)
        assertThat(InterceptDiagnostics.quotaWatchdogTicks).isEqualTo(0L)
        assertThat(InterceptDiagnostics.lastQuotaBlockedPackage).isEmpty()
    }

    @Test
    fun `让路计数单独统计，不计入拦截`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        repeat(5) { InterceptDiagnostics.onYield() }
        InterceptDiagnostics.onEvent("com.android.systemui", "让路：非应用窗口")

        assertThat(InterceptDiagnostics.yieldedCount).isEqualTo(5L)
        assertThat(InterceptDiagnostics.blockedCount).isEqualTo(0L)
    }

    @Test
    fun `服务断开后结论回到第一环`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.onBlocked("com.foo")
        InterceptDiagnostics.onServiceDisconnected()

        assertThat(InterceptDiagnostics.serviceConnected).isFalse()
        assertThat(InterceptDiagnostics.verdict()).contains("第①环")
    }

    @Test
    fun `悬浮窗权限未授予不影响结论为正常`() {
        // 主拦截机制是"踢回桌面"，不依赖悬浮窗权限
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = false)
        InterceptDiagnostics.onEvent("com.foo", "拦截：不在白名单")
        InterceptDiagnostics.onLockedEvaluated()
        InterceptDiagnostics.onBlocked("com.foo")

        assertThat(InterceptDiagnostics.verdict()).contains("正常")
        assertThat(InterceptDiagnostics.canDrawOverlays).isFalse()
    }

    // ------------------------------------------------- v2.13 系统应用枚举（自检）

    @Test
    fun `自检页报告全部系统应用的个数`() {
        // 这一行是"锁机时系统浮层被误拦"的第一现场：
        // 为 0 说明 getInstalledApplications() 没返回东西（只剩兜底清单），
        // 几百个才说明枚举正常。所以它必须出现在快照里。
        InterceptDiagnostics.updateLists(
            effectiveAllow = 12,
            runtimeSystemPackages = 5,
            systemApps = 187
        )

        val line = InterceptDiagnostics.snapshot().first { it.first == "参考 · 白名单" }.second
        assertThat(line).contains("全部系统应用 187 个")
        assertThat(line).contains("有效 12 项")
        assertThat(line).contains("运行时系统包 5 个")
    }

    @Test
    fun `系统应用个数的默认值保持旧行为`() {
        // 既有调用点只传两个参数时，系统应用数按 0 计（与 v2.12 的快照一致）
        InterceptDiagnostics.updateLists(effectiveAllow = 3, runtimeSystemPackages = 2)

        assertThat(InterceptDiagnostics.systemAppCount).isEqualTo(0)
        val line = InterceptDiagnostics.snapshot().first { it.first == "参考 · 白名单" }.second
        assertThat(line).contains("全部系统应用 0 个")
    }

    // ======================================= v2.15：可启动应用（「只拦能启动的应用」）

    @Test
    fun `自检页报告可启动应用个数`() {
        // 这一行是新规则的开关状态：枚举正常时应该是几百个，
        // 用户（和我）扫一眼就知道"这次修复到底有没有生效"。
        InterceptDiagnostics.updateLists(
            effectiveAllow = 12,
            runtimeSystemPackages = 5,
            systemApps = 187,
            alwaysAllow = 4,
            launchable = 137
        )

        val line = InterceptDiagnostics.snapshot()
            .first { it.first == "参考 · 可启动应用" }.second
        assertThat(line).contains("137 个")
        assertThat(line).contains("没有启动入口的包一律放行")
        assertThat(InterceptDiagnostics.launchableAppCount).isEqualTo(137)
        assertThat(InterceptDiagnostics.launchableRuleInactive).isFalse()
    }

    @Test
    fun `可启动枚举为空时明确写出"已退化为旧行为"`() {
        // 空集合 = 枚举失败 = 本规则不生效。这时"音量浮层被误拦"可能复发，
        // 所以必须显式告诉用户，而不是显示一个看起来一切正常的 0。
        InterceptDiagnostics.updateLists(
            effectiveAllow = 12,
            runtimeSystemPackages = 5,
            systemApps = 0,
            alwaysAllow = 0,
            launchable = 0
        )

        val line = InterceptDiagnostics.snapshot()
            .first { it.first == "参考 · 可启动应用" }.second
        assertThat(line).contains("0 个")
        assertThat(line).contains("退化")
        assertThat(line).contains("不生效")
        assertThat(InterceptDiagnostics.launchableRuleInactive).isTrue()
    }

    @Test
    fun `可启动应用数的默认值保持旧行为`() {
        // 既有调用点不传 launchable 时按 0 计，不抛异常、不改变其它计数
        InterceptDiagnostics.updateLists(effectiveAllow = 3, runtimeSystemPackages = 2)

        assertThat(InterceptDiagnostics.launchableAppCount).isEqualTo(0)
        assertThat(InterceptDiagnostics.systemAppCount).isEqualTo(0)
    }

    @Test
    fun `updateLaunchable 只改可启动个数，不会把其它集合计数刷成 0`() {
        // 服务连接时就算出了可启动个数（那时还没有任何拦截判定），
        // 而其它计数要等第一次判定才齐 —— 所以它必须能单独刷新。
        InterceptDiagnostics.updateLists(
            effectiveAllow = 24,
            runtimeSystemPackages = 5,
            systemApps = 312,
            alwaysAllow = 7,
            launchable = 1
        )
        InterceptDiagnostics.updateLaunchable(137)

        assertThat(InterceptDiagnostics.launchableAppCount).isEqualTo(137)
        assertThat(InterceptDiagnostics.effectiveAllowCount).isEqualTo(24)
        assertThat(InterceptDiagnostics.systemAppCount).isEqualTo(312)
        assertThat(InterceptDiagnostics.alwaysAllowDetectedCount).isEqualTo(7)
        assertThat(InterceptDiagnostics.runtimeSystemPackageCount).isEqualTo(5)
    }

    @Test
    fun `resetForTest 会把可启动应用数一起清零`() {
        InterceptDiagnostics.updateLists(
            effectiveAllow = 3,
            runtimeSystemPackages = 2,
            launchable = 137
        )
        InterceptDiagnostics.resetForTest()

        assertThat(InterceptDiagnostics.launchableAppCount).isEqualTo(0)
        assertThat(InterceptDiagnostics.launchableRuleInactive).isTrue()
    }

    // ------------------------------------- v2.15：「复制诊断信息」的文本内容
    //
    // 复制出去的这段文本就是用户唯一会发给我的现场证据，所以它的**内容**
    // 必须在这里钉住 —— 否则只能"点了按钮才知道复制了什么"。

    @Test
    fun `复制诊断信息包含版本、结论与最近被拦包名`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)
        InterceptDiagnostics.updateLists(
            effectiveAllow = 24,
            runtimeSystemPackages = 5,
            systemApps = 312,
            alwaysAllow = 7,
            launchable = 137
        )
        InterceptDiagnostics.onEvent("com.ss.android.ugc.aweme", "拦截：不在白名单")
        InterceptDiagnostics.onLockedEvaluated()
        InterceptDiagnostics.onBlocked(
            "com.ss.android.ugc.aweme",
            "拦截依据：关键清单✗ 白名单(24)✗ 无启动入口✗；理由=锁机中且不在放行集合里"
        )

        val report = InterceptDiagnostics.diagnosticReport(
            appVersion = "2.15",
            nowMillis = 0L
        )

        // 1) 版本（排查第一件事：用户装的是哪一版）
        assertThat(report).contains("【自律 拦截诊断】v2.15")
        // 2) 最近拦截依据（判定现场）
        assertThat(report).contains("拦截依据：")
        assertThat(report).contains("无启动入口✗")
        // 3) 各集合大小
        assertThat(report).contains("全部系统应用 312 个")
        assertThat(report).contains("可启动应用")
        assertThat(report).contains("137 个")
        // 4) 最近被拦包名（这次 bug 只有包名能定位）
        assertThat(report).contains("最近被拦包名：com.ss.android.ugc.aweme")
        // 5) 结论
        assertThat(report).contains("结论：")
    }

    @Test
    fun `复制诊断信息在没有拦截时也不留空白占位`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)

        val report = InterceptDiagnostics.diagnosticReport(appVersion = "", nowMillis = 0L)

        // 版本读不到时不能显示成空
        assertThat(report).contains("【自律 拦截诊断】v未知")
        assertThat(report).contains("最近被拦包名：（本次运行还没有拦到任何应用）")
        assertThat(report).contains("最近额度拦截包名：（没有因额度拦过）")
    }

    @Test
    fun `复制诊断信息里出现异常时会带上最近异常`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = false)
        InterceptDiagnostics.onEvent("com.foo", "判定中")
        InterceptDiagnostics.onError("踢回桌面失败：SecurityException")

        val report = InterceptDiagnostics.diagnosticReport(nowMillis = 0L)
        assertThat(report).contains("最近异常：踢回桌面失败：SecurityException")
    }

    @Test
    fun `复制诊断信息会带上本版安装时间 —— 用来确认用户真的装上了这一次构建`() {
        InterceptDiagnostics.onServiceConnected(canDrawOverlays = true)

        val withInstall = InterceptDiagnostics.diagnosticReport(
            appVersion = "2.14",
            installedAtMillis = 1_700_000_000_000L,
            nowMillis = 1_700_000_100_000L
        )
        assertThat(withInstall).contains("本版安装时间：")

        // 读不到安装时间（个别 ROM 的 getPackageInfo 会失败）时不能留一行空标题
        val withoutInstall = InterceptDiagnostics.diagnosticReport(appVersion = "2.14", nowMillis = 0L)
        assertThat(withoutInstall).doesNotContain("本版安装时间：")
    }
}
