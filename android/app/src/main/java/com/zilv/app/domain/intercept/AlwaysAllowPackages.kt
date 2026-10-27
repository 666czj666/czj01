package com.zilv.app.domain.intercept

/**
 * 「永远放行」的**关键系统组件**清单与族判定（v2.14）。
 *
 * ## 这一版修的是什么
 *
 * 用户原话：
 *  - 「系统界面组件，截屏，智能助里加入默认名单」
 *  - 「异常不是稳定触发了，但还是有一定概率触发，拦截日志显示系统界面组件，
 *    截屏，智能助理被拦截」
 *
 * v2.13 已经枚举了全部 `FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP` 的包并一律放行，
 * 但这三类仍然被拦。**查证结论（都是可以从代码里证明的，不是猜测）**：
 *
 * 1. **智能助理在商店渠道安装时没有任何 system flag**
 *    （典型：`com.google.android.googlequicksearchbox`）。
 *    [SystemPackageDetector.detectSystemPackages] 只认两个 SYSTEM 位，
 *    因此这类包**永远**进不了放行集合 —— 这是设计缺口，与概率无关，是必被拦。
 * 2. **枚举只算一次、失败即永久为空**：`LockAccessibilityService` 在
 *    `onServiceConnected` 里算一次并缓存，失败时保留旧值（初值是空集合），
 *    之后只有收到 `PACKAGE_*` 广播才重算。一次失败（开机早期、ROM 限制、
 *    PackageManager 忙）= 整段会话所有系统组件都可拦 —— 这解释了"有一定概率"。
 * 3. **`com.android.systemui` 本人在关键清单里，所以拦不到它**。
 *    反推：一条被拦记录若标签是"系统界面组件"，它的**包名必然不是**
 *    `com.android.systemui` —— 而是标签相同的**厂商 SystemUI 组件**
 *    （各 ROM 常把状态栏/音量面板/截屏面板拆成独立包）。
 *    也就是说，靠一张写死的包名清单永远追不上厂商变体。
 * 4. 另有一个真 bug：锁机状态读不出来时走的那条**纯额度拦截**路径
 *    （`LockAccessibilityService.enforceQuotaBlock`）**完全跳过了**关键系统包判定，
 *    会把 systemui / 设置 / 权限对话框一起拦掉。
 *
 * ## 于是这一版做四件事（按可靠性从高到低）
 *
 * | 手段 | 覆盖什么 | 可靠性 |
 * | --- | --- | --- |
 * | ① 显式包名清单（本文件） | 已知的 SystemUI 变体、截屏、智能助理 | 只覆盖已知名字 |
 * | ② **族判定** [isSystemUiFamily] | **所有**以 `systemui` 结尾的厂商变体 | 高（不需要知道包名） |
 * | ③ **Intent 探测** [intentActionsForAssist] 等 | 当前真正被设为"智能助理"的应用（含商店安装） | 高（是系统的配置，不是猜测） |
 * | ④ `FLAG_SYSTEM` 枚举（v2.13 保留） | 其余系统应用 | 中（受枚举时机影响） |
 *
 * ## 为什么**不**给「截屏 / 智能助理」也做族判定
 *
 * 只有 [SystemUiFamily] 这一个后缀规则。因为 `*.systemui` 这个名字**事实上被
 * 系统 UI 独占**（第三方应用不会叫 `com.foo.systemui`），而后缀 `*.screenshot`
 * / `*.assistant` 完全可能是用户自己装的应用 —— 那种规则等于给了绕过口子，
 * 与"白名单是硬边界"冲突。截屏与智能助理改用**显式清单 + Intent 探测**：
 * 探测不到宁可漏（用户报上来再加），也不要模糊放行。
 *
 * ## 为什么**没有**"连续拦截 N 次就放行"这类兜底
 *
 * 用户明确要求：不要。放行边界必须可解释 —— 一个包要么**证明**它不该被拦
 * （关键清单 / 族判定 / Intent 探测 / 系统应用），要么就拦。模糊兜底只会把
 * "包名识别不全"这个真实缺陷藏起来，让下一次排查更难。
 */
object AlwaysAllowPackages {

    // ---------------------------------------------------------------- 系统界面

    /**
     * SystemUI 的厂商变体（`com.android.systemui` 本人在
     * [InterceptDecider.CRITICAL_SYSTEM_PACKAGES] 里）。
     *
     * 这些名字来自各厂商公开 ROM 的常见包名。**列错一个不存在的包是无害的**
     * （它永远不会成为前台包），漏一个则由 [isSystemUiFamily] + 枚举兜住 ——
     * 所以这张表只做"多一道保险"，不承担正确性。
     *
     * 为什么 SystemUI 值得这么防：音量面板、关机菜单、最近任务、截屏浮层、
     * 通知栏都挂在它下面。一旦被拦，用户看到的是"调个音量就跳回桌面"
     * "退出去划不掉后台"（v2.10 / v2.13 连修两次的真机 bug）。
     */
    val SYSTEM_UI_VARIANTS: Set<String> = setOf(
        "com.android.systemui",           // AOSP / 大多数 ROM（同时也在关键清单里）
        "com.android.systemui.recents",   // 少数 ROM 把概览拆成独立包
        "com.miui.systemui",              // 小米（旧版把 SystemUI 拆出来过）
        "com.miui.systemui.overlay",      // 小米 RRO
        "com.huawei.systemui",            // 华为 / 荣耀
        "com.hihonor.systemui",           // 荣耀（分拆后）
        "com.coloros.systemui",           // OPPO / 一加（ColorOS）
        "com.oplus.systemui",             // OPPO 新包名体系
        "com.oppo.systemui",              // OPPO 旧包名
        "com.vivo.systemui",              // vivo / iQOO
        "com.bbk.systemui",               // vivo 旧包名
        "com.transsion.systemui",         // 传音（Tecno / Infinix / itel）
        "com.sec.android.systemui",       // 三星
        "com.google.android.systemui",    // Pixel 的 SystemUI 扩展
        "com.zte.systemui",               // 中兴
        "com.meizu.systemui"              // 魅族
    )

    /**
     * **SystemUI 族**：包名的最后一段恰好是 `systemui`。
     *
     * 这条规则是"厂商变体"问题的**根本修法**：不需要知道具体包名，
     * 只要厂商按惯例把自己的 SystemUI 命名为 `<vendor>.systemui` 就能命中。
     *
     * ## 为什么是"最后一段精确相等 + 至少三段"，不是"包含 systemui"
     *
     * 包含匹配会变成绕过口子：`com.android.systemui.evil` 这种伪装包
     * （既有测试 `保护清单是大写敏感的精确匹配，不做前缀放行` 钉住了它必须被拦）
     * 会命中"包含"，但**不**命中"最后一段等于 systemui"。
     * 另外两段的 `com.systemui` / `miui.systemui` 也不放行 ——
     * 真实 ROM 的 SystemUI 一定是 `厂商.前缀.systemui` 这种三段以上的形式，
     * 两段的只可能是山寨命名（见 [SYSTEM_UI_MIN_SEGMENTS]）。
     *
     * 代价：`com.miui.systemui.plugin` 这类"SystemUI 的插件包"不会命中 ——
     * 刻意的，插件包不是前台窗口宿主，不需要放行。
     *
     * @param packageName 待判定的包名；空/null 一律 false
     */
    fun isSystemUiFamily(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        // 至少要有**三段**（`com.miui.systemui`）：两段的 `com.systemui` 太容易被
        // 普通应用占用，放行它等于给一个"看起来像系统"的名字开口子。
        if (pkg.count { it == '.' } < SYSTEM_UI_MIN_SEGMENTS - 1) return false
        val lastSegment = pkg.substringAfterLast('.', missingDelimiterValue = "")
        return lastSegment == SYSTEM_UI_SEGMENT
    }

    /** SystemUI 包名的最后一段（小写，包名本身大小写敏感，这里只做逐字比较）。 */
    const val SYSTEM_UI_SEGMENT = "systemui"

    /**
     * SystemUI 族包名至少要有几段（`com.miui.systemui` = 3 段）。
     *
     * 两段的 `com.systemui` 只可能是山寨包名 —— 真实 ROM 的 SystemUI 一律是
     * `厂商.前缀.systemui` 这种三段以上的形式。
     */
    const val SYSTEM_UI_MIN_SEGMENTS = 3

    // ---------------------------------------------------------------- 截屏

    /**
     * 截屏（截图 / 长截屏 / 滚动截屏）所在的包。
     *
     * ## 为什么这一组只能靠显式清单
     *
     * **Android 没有任何公开的"截屏"Intent action**（截屏是系统功能，
     * 第三方应用无法发起，AOSP 的截图流程由 `SystemUI` 内部的
     * `TakeScreenshotService` 完成，宿主包就是 `com.android.systemui`）。
     * 也就是说：
     *  - AOSP / Pixel / 大部分 ROM：截屏面板属于 SystemUI ⇒ 由
     *    [SYSTEM_UI_VARIANTS] + [isSystemUiFamily] 覆盖
     *  - 少数厂商把它做成了**独立应用** ⇒ 只能逐个列名字
     *
     * 因此这一组的定位是"补漏"，不是主力。真机再报出新包名时，
     * 加一行的成本极低（本文件的 KDoc 就是给人看"为什么加"的）。
     */
    val SCREENSHOT: Set<String> = setOf(
        "com.miui.screenshot",                    // 小米 / 红米
        "com.coloros.screenshot",                 // OPPO / realme（ColorOS）
        "com.oplus.screenshot",                   // OPPO 新体系
        "com.oppo.screenshot",                    // OPPO 旧体系
        "com.vivo.screenshot",                    // vivo / iQOO
        "com.transsion.screenshot",               // 传音
        "com.huawei.screenshot",                  // 华为 / 荣耀
        "com.sec.android.app.screenshot",         // 三星
        "com.samsung.android.app.smartcapture",   // 三星：截屏后的编辑/长截屏宿主
        "com.meizu.screenshot"                    // 魅族
    )

    // ---------------------------------------------------------------- 智能助理

    /**
     * 智能助理（语音助手 / 负一屏 / 长按电源唤醒的那个）所在的包。
     *
     * ## 为什么这一组最需要显式清单
     *
     * 用户装的"智能助理"往往来自**应用商店**（典型：Google 助理 =
     * `com.google.android.googlequicksearchbox`），它**没有** `FLAG_SYSTEM`，
     * 也没有 `FLAG_UPDATED_SYSTEM_APP` —— v2.13 的枚举**永远**拿不到它。
     * 这是"枚举没覆盖到"里最硬的一个结构性原因。
     *
     * 但清单仍然不是主力：[intentActionsForAssist] 会向系统问
     * "现在谁才是智能助理"，那比猜包名可靠得多（见 [ASSIST_ACTIONS]）。
     */
    val ASSISTANT: Set<String> = setOf(
        // Google 助理：**商店安装时没有系统标志**，是本类问题的典型样本
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.googleassistant",
        "com.google.android.apps.bard",
        // 小米：小爱同学 + 负一屏「智能助理」
        "com.miui.voiceassist",
        "com.miui.personalassistant",
        // 华为 / 荣耀：小艺
        "com.huawei.vassistant",
        "com.hihonor.vassistant",
        // OPPO / 一加：小布
        "com.heytap.speechassist",
        "com.coloros.speechassist",
        "com.oplus.speechassist",
        // vivo / iQOO：Jovi
        "com.vivo.assistant",
        "com.vivo.jovi",
        "com.bbk.speechassist",
        // 三星：Bixby
        "com.samsung.android.bixby.agent",
        "com.samsung.android.bixby.voiceinput",
        // 传音
        "com.transsion.assistant"
    )

    /** 全部**显式**包名（三组并集）。族判定是独立的，不在这个集合里。 */
    val ALL: Set<String> = SYSTEM_UI_VARIANTS + SCREENSHOT + ASSISTANT

    /**
     * 探测"当前智能助理是谁"用的 Intent action。
     *
     * 全部是**公开常量**，查询它们不需要任何权限
     * （`PackageManager.queryIntentActivities` 对隐式 Intent 只做包可见性过滤，
     * 而本 App 已声明 `QUERY_ALL_PACKAGES`）——
     * **没有为这一条新增任何 `uses-permission`**。
     */
    val ASSIST_ACTIONS: List<String> = listOf(
        ASSIST_ACTION,
        VOICE_COMMAND_ACTION
    )

    /**
     * `Intent.ACTION_ASSIST` 的字面值。
     *
     * 刻意写字面值而不是引用 `Intent.ACTION_ASSIST`：本对象是**纯领域模型**
     * （不 import Android 的 Intent），这样 `InterceptDecider` / 单元测试都能
     * 在 JVM 上直接用它。取值与框架常量一致，并有专门一条测试钉住。
     */
    const val ASSIST_ACTION = "android.intent.action.ASSIST"

    /** `Intent.ACTION_VOICE_COMMAND` 的字面值（同上，纯领域模型里写字面值）。 */
    const val VOICE_COMMAND_ACTION = "android.intent.action.VOICE_COMMAND"

    /**
     * 系统里指向"当前默认智能助理"的 `Settings.Secure` 键。
     *
     * 值是扁平的组件名（`包名/类名` 或 `包名/.类名`）。
     * 读 `Settings.Secure` **不需要写权限**（写才需要），
     * 因此这条探测同样不引入新权限。
     */
    const val SECURE_ASSISTANT_KEY = "assistant"

    /**
     * 一个包名属于上面哪一类（诊断文案用）。
     *
     * @return `"系统界面"` / `"截屏"` / `"智能助理"`；不属于任何一类时返回 null
     */
    fun categoryOf(packageName: String?): String? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        return when {
            pkg in SYSTEM_UI_VARIANTS || isSystemUiFamily(pkg) -> "系统界面"
            pkg in SCREENSHOT -> "截屏"
            pkg in ASSISTANT -> "智能助理"
            else -> null
        }
    }

    /** 是否应当无条件放行（显式清单或 SystemUI 族）。 */
    fun isAlwaysAllowed(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return pkg in ALL || isSystemUiFamily(pkg)
    }
}
