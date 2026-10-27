package com.zilv.app.intercept

import com.zilv.app.domain.intercept.AlwaysAllowPackages
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 拦截链路的运行时自检快照。
 *
 * **为什么需要这个类**：拦截是否生效依赖四个彼此独立的环节
 * （无障碍服务连接 → 收到窗口事件 → 状态判定为锁机 → 执行拦截动作），
 * 任何一环断掉，用户看到的现象都是"没拦住"，但从现象无法区分是哪一环。
 *
 * 我无法在用户的真机上调试，所以让 App **自己把每一环的结果记下来**，
 * 用户在「设置 → 拦截自检」里一眼就能看出断在哪里。
 *
 * 纯内存、无持久化：进程重启即清零，正合适 —— 它描述的是"当前这次运行"。
 */
object InterceptDiagnostics {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 「复制诊断信息」用的完整日期时间（v2.15；只有时间签不出是哪一天）。 */
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 无障碍服务是否已连接。这是最基础的一环。 */
    @Volatile
    var serviceConnected: Boolean = false
        private set

    /** 服务连接的时刻。 */
    @Volatile
    var connectedAtMillis: Long = 0L
        private set

    /** 收到的窗口事件总数。为 0 说明事件根本没来。 */
    @Volatile
    var eventsSeen: Long = 0L
        private set

    /** 因"非应用窗口"而让路的次数。 */
    @Volatile
    var yieldedCount: Long = 0L
        private set

    /** 判定为"处于锁机态"的次数。 */
    @Volatile
    var lockedEvalCount: Long = 0L
        private set

    /** 实际执行拦截的次数。 */
    @Volatile
    var blockedCount: Long = 0L
        private set

    /** 最近一个窗口事件的包名。 */
    @Volatile
    var lastEventPackage: String = ""
        private set

    @Volatile
    var lastEventAtMillis: Long = 0L
        private set

    /** 最近一次判定结果的可读描述。 */
    @Volatile
    var lastDecision: String = "（还没有收到任何窗口事件）"
        private set

    /** 最近一次拦截的包名。 */
    @Volatile
    var lastBlockedPackage: String = ""
        private set

    /** 最近一次异常。 */
    @Volatile
    var lastError: String = ""
        private set

    /** 生效白名单条数（运行时快照）。 */
    @Volatile
    var effectiveAllowCount: Int = 0
        private set

    /** 运行时探测到的系统包数量。 */
    @Volatile
    var runtimeSystemPackageCount: Int = 0
        private set

    /**
     * 运行时枚举到的**系统应用**数量（v2.13，`FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP`）。
     *
     * 单列一项是因为它是"调音量被踢回桌面 / 划不掉后台"这类问题的第一现场：
     *  - **一直是 0** → `getInstalledApplications()` 没返回东西（ROM 限制），
     *    此时只剩 [InterceptDecider.CRITICAL_SYSTEM_PACKAGES] 与白名单两道保险
     *  - **几百个** → 枚举成功，系统组件应该都能被放行
     */
    @Volatile
    var systemAppCount: Int = 0
        private set

    /**
     * 运行时**按 Intent 探测**到的关键组件数量（v2.14）。
     *
     * 包括当前被系统设为"智能助理"的应用、已安装的截屏应用、以及以
     * `systemui` 结尾的厂商 SystemUI 变体。这一项与 [systemAppCount] 互补：
     *  - [systemAppCount] 为 0、而本项 > 0 → 枚举被 ROM 限制，但 Intent 探测还在工作
     *  - 两项都是 0 → 只剩显式清单与 SystemUI 族判定
     */
    @Volatile
    var alwaysAllowDetectedCount: Int = 0
        private set

    /**
     * 最近一次拦截的**判定依据**（v2.14，由
     * [InterceptDecider.explainBlock] 生成）。
     *
     * 用户报"系统界面组件事被拦"时，原来只能看到包名与次数，无法区分
     * "不在任何放行集合里"与"额度用尽"，也看不到枚举是否返回了 0 个系统应用。
     * 这一句把这些一次性说清，用户截图即可反馈。
     */
    @Volatile
    var lastBlockBasis: String = ""
        private set

    /**
     * 运行时枚举到的**有启动入口**的包数量（v2.15）。
     *
     * 这是「只拦能启动的应用」这条规则的**唯一开关状态**：
     *  - **0** → 枚举失败 / 被 ROM 限制 ⇒ 本规则不生效（退化为旧行为）。
     *    这时"音量面板被误拦"这类 bug 可能复发，所以必须显示出来。
     *  - **几百个** → 枚举正常，没有启动入口的包（音量面板 / 截屏浮层 / 分享面板）
     *    一律放行，本规则生效。
     *
     * 用户第 4 次反馈同一个问题时，这一行就是"新修法到底有没有生效"的第一现场。
     */
    @Volatile
    var launchableAppCount: Int = 0
        private set

    /**
     * 「只拦能启动的应用」这条规则当前是否**退化为旧行为**（v2.15）。
     *
     * 为 true 表示 [launchableAppCount] 是 0（枚举没拿到东西），
     * 判定逻辑因此不启用该规则 —— 这是安全方向（不会把锁机改松），
     * 但也意味着这一类误拦可能复发，所以要单独暴露给界面与文案。
     */
    val launchableRuleInactive: Boolean
        get() = launchableAppCount == 0

    /** 悬浮窗权限。 */
    @Volatile
    var canDrawOverlays: Boolean = false
        private set

    /**
     * 最近一次拦截的包是不是**本该放行**的关键组件（系统界面 / 截屏 / 智能助理）。
     *
     * 界面用它决定"要不要把结论显示出来"：`blockedCount > 0` 通常意味着
     * 拦截链路正常，但若最近拦到的是这三类，那就是缺陷，**必须**把
     * [verdict] 显示出来，而不是用"✅ 拦截链路正常"把它盖过去。
     */
    val lastBlockedWasCriticalComponent: Boolean
        get() = AlwaysAllowPackages.categoryOf(lastBlockedPackage) != null

    /**
     * 最近一次判定时"因额度用完而被禁用"的包数量（v2.7）。
     *
     * 单列一行是为了让真机排查能一眼区分两类情况：
     *  - **一直是 0** → 额度没有被触发（配置没生效 / 没绑应用 / 用量没记上）
     *  - **> 0 但拦截计数不涨** → 被判定的前台应用不在那个集合里
     *    （用户其实已经离开了受限应用），或者被白名单放行了
     */
    @Volatile
    var quotaDisabledCount: Int = 0
        private set

    /** 因**额度用尽**而执行的拦截次数。与 [blockedCount] 分开计数，便于定位问题。 */
    @Volatile
    var quotaBlockedCount: Long = 0L
        private set

    /** 额度看门狗已经跑的轮数（0 表示它从未启动过 —— 说明前台没进过受限应用）。 */
    @Volatile
    var quotaWatchdogTicks: Long = 0L
        private set

    /** 最近一次因额度被拦的包名。 */
    @Volatile
    var lastQuotaBlockedPackage: String = ""
        private set

    // ---------------------------------------------------------------- 窗口归属（v2.10）
    //
    // 这一组是为"音量面板 / 最近任务 / 权限对话框被误拦"那三个真机 bug 加的。
    // 它们共同的现象都是"系统浮层把用户踢出当前应用"，而**唯一能区分**
    // "真的拦了一个应用"与"误拦了一个系统窗口"的证据，就是这个窗口的归属判定。
    // 我无法上真机，所以把判定结果留在自检页上，用户一看就知道走的是哪条分支。

    /** 因"非应用窗口 / 无法确认是应用窗口"而让路的次数（v2.10）。 */
    @Volatile
    var nonAppWindowCount: Long = 0L
        private set

    /** 其中判定为**应用窗口**的事件次数（正常拦截路径的入口）。 */
    @Volatile
    var appWindowCount: Long = 0L
        private set

    /** 最近一次窗口事件的类型（`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOWS_CHANGED`）。 */
    @Volatile
    var lastWindowEventKind: String = ""
        private set

    /** 最近一次窗口的 `AccessibilityWindowInfo.getType()`；-1 表示判定不出来。 */
    @Volatile
    var lastWindowType: Int = -1
        private set

    /** 最近一次窗口归属判定涉及的包名。 */
    @Volatile
    var lastWindowPackage: String = ""
        private set

    /** 最近一次窗口归属判定结果（`ApplicationWindow` / `NonApplicationWindow` / `Unconfirmed`）。 */
    @Volatile
    var lastWindowAnalysis: String = ""
        private set

    /** 由无障碍服务在每次判定时刷新。 */
    fun updateOverlayPermission(granted: Boolean) {
        canDrawOverlays = granted
    }

    /** 仅供单元测试：把快照恢复到初始状态。 */
    fun resetForTest() {
        serviceConnected = false
        connectedAtMillis = 0L
        eventsSeen = 0L
        yieldedCount = 0L
        lockedEvalCount = 0L
        blockedCount = 0L
        lastEventPackage = ""
        lastEventAtMillis = 0L
        lastDecision = "（还没有收到任何窗口事件）"
        lastBlockedPackage = ""
        lastError = ""
        effectiveAllowCount = 0
        runtimeSystemPackageCount = 0
        systemAppCount = 0
        alwaysAllowDetectedCount = 0
        lastBlockBasis = ""
        launchableAppCount = 0
        canDrawOverlays = false
        quotaDisabledCount = 0
        quotaBlockedCount = 0L
        quotaWatchdogTicks = 0L
        lastQuotaBlockedPackage = ""
        nonAppWindowCount = 0L
        appWindowCount = 0L
        lastWindowEventKind = ""
        lastWindowType = -1
        lastWindowAnalysis = ""
        lastWindowPackage = ""
    }

    fun onServiceConnected(canDrawOverlays: Boolean) {
        serviceConnected = true
        connectedAtMillis = System.currentTimeMillis()
        lastError = ""
        lastDecision = "服务已连接，等待窗口事件…"
        this.canDrawOverlays = canDrawOverlays
    }

    fun onServiceDisconnected() {
        serviceConnected = false
        lastDecision = "服务已断开"
    }

    fun onEvent(packageName: String, decision: String) {
        eventsSeen++
        lastEventPackage = packageName
        lastEventAtMillis = System.currentTimeMillis()
        lastDecision = decision
    }

    fun onYield() {
        yieldedCount++
    }

    fun onLockedEvaluated() {
        lockedEvalCount++
    }

    /**
     * 记录一次拦截（v2.14 追加 [basis]）。
     *
     * @param basis 判定依据（[InterceptDecider.explainBlock] 的输出）。
     *        不传时保持旧行为（只记包名），既有调用点与断言因此不受影响。
     */
    fun onBlocked(packageName: String, basis: String = "") {
        blockedCount++
        lastBlockedPackage = packageName
        if (basis.isNotBlank()) lastBlockBasis = basis
    }

    /** 额度看门狗跑过一轮（v2.7）。 */
    fun onQuotaWatchdogTick() {
        quotaWatchdogTicks++
    }

    /** 刷新"当前有多少个包因额度用完被禁用"（v2.7）。 */
    fun updateQuota(disabledPackages: Int) {
        quotaDisabledCount = disabledPackages
    }

    /** 因**额度用尽**执行了一次拦截（v2.7）。[blockedCount] 同时也会 +1。 */
    fun onQuotaBlocked(packageName: String) {
        quotaBlockedCount++
        lastQuotaBlockedPackage = packageName
    }

    fun onError(message: String) {
        lastError = message
    }

    /**
     * 刷新各放行集合的大小（v2.14 追加 [alwaysAllow]，v2.15 追加 [launchable]）。
     *
     * @param alwaysAllow 运行时按 Intent 探测到的关键组件数量（可为 0，默认值保持旧行为）
     * @param launchable v2.15：运行时枚举到的**有启动入口**的包数量。
     *        0 表示枚举失败 ⇒ 只拦可启动应用这条规则不生效（默认值保持旧行为）
     */
    fun updateLists(
        effectiveAllow: Int,
        runtimeSystemPackages: Int,
        systemApps: Int = 0,
        alwaysAllow: Int = 0,
        launchable: Int = 0
    ) {
        effectiveAllowCount = effectiveAllow
        runtimeSystemPackageCount = runtimeSystemPackages
        systemAppCount = systemApps
        alwaysAllowDetectedCount = alwaysAllow
        launchableAppCount = launchable
    }

    /**
     * **只**刷新「可启动应用」个数（v2.15）。
     *
     * 为什么要一个单独的方法：这个数在**服务连接时**就已经知道了
     * （`refreshPackageCaches()` 算完就有），而 [updateLists] 的其它参数
     * （有效白名单 / 系统应用数 …）要等第一次窗口事件判定时才齐。
     * 若连接时也去调 [updateLists]，就会把其它计数刷成 0；
     * 若等第一次事件，自检页在"服务刚连上、还没来事件"时会显示
     * 「可启动应用 0 个」—— 看起来像枚举失败，实际不是。
     */
    fun updateLaunchable(count: Int) {
        launchableAppCount = count
    }

    /**
     * 记录一次窗口归属判定（v2.10）。
     *
     * @param analysis [InterceptDecider.WindowAnalysis] 的名字
     * @param windowType 窗口类型；null 表示判定不出来（记 -1）
     */
    fun onWindowClassified(
        packageName: String,
        windowType: Int?,
        analysis: String,
        isWindowStateChangeEvent: Boolean
    ) {
        lastWindowEventKind = if (isWindowStateChangeEvent) {
            "WINDOW_STATE_CHANGED"
        } else {
            "WINDOWS_CHANGED"
        }
        lastWindowType = windowType ?: -1
        lastWindowAnalysis = analysis
        if (analysis == "ApplicationWindow") {
            appWindowCount++
        } else {
            nonAppWindowCount++
        }
        lastWindowPackage = packageName
    }

    /** 「拦截自检」页展示的一行行文本。 */
    fun snapshot(): List<Pair<String, String>> = listOf(
        "① 无障碍服务" to if (serviceConnected) {
            "已连接（${timeFormat.format(Date(connectedAtMillis))}）"
        } else {
            "未连接 —— 拦截不可能生效，请到系统设置里开启"
        },
        "② 收到的窗口事件" to if (eventsSeen == 0L) {
            "0 次 —— 服务连着但事件没来，检查服务配置或重新开关一次服务"
        } else {
            "$eventsSeen 次；最近 ${lastEventPackage.ifBlank { "（无包名）" }} " +
                "于 ${timeFormat.format(Date(lastEventAtMillis))}"
        },
        "③ 最近判定" to lastDecision,
        "④ 执行拦截" to "$blockedCount 次" +
            (lastBlockedPackage.takeIf { it.isNotBlank() }?.let { "；最近：$it" } ?: ""),
        // v2.14：**判定依据**。这是"系统界面组件 / 截屏 / 智能助理被拦"这类反馈的
        // 第一现场 —— 它能直接区分"不在任何放行集合里"与"额度用尽"，
        // 并给出各放行集合当时的大小（枚举为 0 = 枚举失败）。
        "参考 · 最近拦截依据" to lastBlockBasis.ifBlank {
            "（本次运行还没有拦到任何应用）"
        },
        "参考 · 让路" to "$yieldedCount 次（来电/闹钟/输入法等非应用窗口）",
        "参考 · 锁机判定" to "$lockedEvalCount 次判为锁机态",
        // v2.7：额度拦截单独计数。这一行是"额度到底有没有生效"的第一现场 ——
        // 用户报"限时没生效"时，看它就能区分"额度没算出来"与"算出来了但没拦"。
        "参考 · 额度限时" to buildString {
            append("当前禁用 $quotaDisabledCount 个应用")
            append("；因额度拦截 $quotaBlockedCount 次")
            if (lastQuotaBlockedPackage.isNotBlank()) append("（最近：$lastQuotaBlockedPackage）")
            append("；看门狗 $quotaWatchdogTicks 轮")
        },
        "参考 · 白名单" to "有效 $effectiveAllowCount 项（含运行时系统包 $runtimeSystemPackageCount 个）" +
            // v2.13：这一项是"锁机时系统浮层被误拦"的第一现场。
            // 为 0 说明枚举没成功（只剩兜底清单），几百个说明枚举正常。
            "；全部系统应用 $systemAppCount 个（一律放行）" +
            // v2.14：系统界面组件 / 截屏 / 智能助理按 Intent 探测到的个数。
            // 与上一项互补：枚举被 ROM 限制时这一项仍可能 > 0。
            "；关键组件探测 $alwaysAllowDetectedCount 个（智能助理/截屏/SystemUI 变体）",
        // v2.15：这一行是「只拦能启动的应用」这条新规则的**开关状态**。
        // 为 0 = 枚举失败 = 规则未生效 = 本次修复等于没做，必须让用户/我一眼看到。
        "参考 · 可启动应用" to if (launchableRuleInactive) {
            "0 个 —— 还没枚举到（无障碍服务未连接，或枚举被 ROM 限制）；" +
                "本规则不生效，已退化为旧行为（音量/截屏浮层类误拦可能复发，请把这一页发我）"
        } else {
            "$launchableAppCount 个；没有启动入口的包一律放行" +
                "（音量面板 / 截屏浮层 / 智能助理悬浮层 / 分享面板都在其中）"
        },
        // v2.10：窗口归属。这三行是"音量面板 / 最近任务 / 权限对话框被误拦"
        // 这类问题的第一现场 —— 能直接看出事件到底被判成了什么窗口。
        "参考 · 窗口归属" to buildString {
            append("应用窗口 $appWindowCount 次")
            append("；非应用/未确认 $nonAppWindowCount 次")
            if (lastWindowAnalysis.isNotBlank()) {
                append("；最近：${lastWindowPackage.ifBlank { "（无包名）" }} ")
                append(lastWindowEventKind)
                append(" → ")
                append(lastWindowAnalysis)
                append(if (lastWindowType >= 0) "（type=$lastWindowType）" else "（type 未知）")
            }
        },
        "参考 · 悬浮窗权限" to if (canDrawOverlays) {
            "已授予"
        } else {
            "未授予（主拦截机制是「踢回桌面」，不依赖此权限；仅在兜底遮挡层时用到）"
        }
    )

    /** 诊断结论：直接告诉用户断在哪一环。 */
    fun verdict(): String = when {
        !serviceConnected -> "断在第①环：无障碍服务未连接。这是必须开启的权限。"
        eventsSeen == 0L -> "断在第②环：服务已连接但收不到窗口事件。请重新关闭再开启一次无障碍服务。"
        lastError.isNotBlank() -> "执行时出错：$lastError"
        // v2.10：所有窗口事件都被判成"非应用窗口 / 无法确认是应用窗口"时，
        // 拦截永远不会发生。这一条必须排在"还没出现过锁机态"之前 ——
        // 否则用户会被引到"是不是规则没启用"，而真正的问题是窗口判定过宽。
        appWindowCount == 0L && nonAppWindowCount > 0L && blockedCount == 0L ->
            "所有窗口事件都被判成「非应用窗口 / 无法确认是应用窗口」。" +
                "若锁机时打开应用没有被拦，请把这一页截图反馈（窗口归属那一行是关键信息）。"
        blockedCount == 0L && lockedEvalCount == 0L && quotaBlockedCount == 0L ->
            "链路正常，但还没出现过锁机态，也没拦截过额度用尽的应用。" +
                "请确认规则已启用（或额度已绑应用）且当前时间在窗口内。"
        blockedCount == 0L && lockedEvalCount > 0L && quotaBlockedCount == 0L ->
            "判定出锁机态但从未拦截 —— 说明打开的应用都在白名单里。"
        // v2.14：最近拦到的是**关键系统组件**（系统界面 / 截屏 / 智能助理）。
        // 这一定是个缺陷（那三类应当无条件放行），所以直接把判定依据指出来，
        // 而不是用"链路正常"把问题盖过去。
        lastBlockedWasCriticalComponent ->
            "最近拦到了「${AlwaysAllowPackages.categoryOf(lastBlockedPackage)}」" +
                "（$lastBlockedPackage）—— 这属于本该放行的关键组件，是个缺陷。" +
                "请把「参考 · 最近拦截依据」那一行发我（含各放行集合的个数）。"
        else -> "拦截链路正常，已执行 $blockedCount 次拦截" +
            (if (quotaBlockedCount > 0L) "（其中因额度 $quotaBlockedCount 次）。" else "。")
    }

    // ------------------------------------------------------------ 可复制的诊断文本（v2.15）

    /**
     * 把自检快照拼成**一段可直接粘贴发送**的纯文本（v2.15）。
     *
     * ## 为什么需要它
     *
     * 之前让用户反馈只能靠"截图自检页"：截图在聊天软件里会被压缩、折叠，
     * 包名那种长英文字符串最容易糊掉 —— 而这次的 bug（同一个显示名「系统界面组件」
     * 背后是一堆不同的包名）**只有包名能定位**。让用户点一下「复制诊断信息」
     * 再粘贴，信息一个字符都不会丢，而且比截图省事。
     *
     * ## 内容（顺序即排查顺序）
     *
     *  1. 版本与生成时间 —— 先确认装的是哪一版（v2.14 的修复和 v2.15 的不是一回事）
     *  2. [snapshot] 的全部行 —— 含最近拦截依据、各放行集合大小、
     *     系统应用数、**可启动应用数**（v2.15 的关键开关）
     *  3. [verdict] 的结论
     *  4. 最近被拦的包名（锁机 / 额度各一条）
     *
     * ## 为什么做成纯函数
     *
     * 它不碰 `Context`、不碰剪贴板 —— 复制动作在 UI 层（`InterceptStatusSection`）。
     * 这样这段文本的**内容**可以在 JVM 单元测试里逐条钉住，
     * 而不是"点了按钮才知道复制了什么"。
     *
     * @param appVersion 由调用方从 `PackageManager` 读到的版本名；
     *        空字符串时显示"未知"（本对象不持有 Context，读不了它）
     * @param installedAtMillis 本次安装/覆盖安装的时间（`PackageInfo.lastUpdateTime`），
     *        0 表示不显示。**这是"用户到底装没装上新版"的唯一硬证据** ——
     *        版本号在同一个版本号内可能连出好几轮修复（本项目的常态），
     *        而这一行每次覆盖安装都会变。
     * @param nowMillis 生成时间（可注入，便于单测）
     * @return 多行文本，`\\n` 分隔，不含任何 emoji 依赖
     */
    fun diagnosticReport(
        appVersion: String = "",
        installedAtMillis: Long = 0L,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val version = appVersion.ifBlank { "未知" }
        return buildString {
            appendLine("【自律 拦截诊断】v$version")
            appendLine("生成时间：${dateTimeFormat.format(Date(nowMillis))}")
            if (installedAtMillis > 0L) {
                appendLine("本版安装时间：${dateTimeFormat.format(Date(installedAtMillis))}")
            }
            appendLine("————————————————")
            // 快照：一行一条，`标签：值` —— 用户粘贴到聊天里仍然可读
            snapshot().forEach { (label, value) ->
                appendLine("$label：$value")
            }
            appendLine("————————————————")
            appendLine("结论：${verdict()}")
            appendLine(
                "最近被拦包名：" +
                    lastBlockedPackage.ifBlank { "（本次运行还没有拦到任何应用）" }
            )
            appendLine(
                "最近额度拦截包名：" +
                    lastQuotaBlockedPackage.ifBlank { "（没有因额度拦过）" }
            )
            if (lastError.isNotBlank()) appendLine("最近异常：$lastError")
            append("（本段由「锁机运行状态 → 复制诊断信息」生成）")
        }
    }
}
