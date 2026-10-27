package com.zilv.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.zilv.app.MainActivity
import com.zilv.app.data.repository.BlockedAttemptRepository
import com.zilv.app.data.repository.LockRuleRepository
import com.zilv.app.domain.intercept.AlwaysAllowPackages
import com.zilv.app.domain.intercept.InterceptDecider
import com.zilv.app.domain.intercept.TemporaryAllowList
import com.zilv.app.domain.lock.LockPhase
import com.zilv.app.domain.lock.WindowScheduler
import com.zilv.app.domain.quota.QuotaEnforcementSource
import com.zilv.app.domain.quota.QuotaExhaustion
import com.zilv.app.intercept.OverlayController
import com.zilv.app.intercept.InterceptDiagnostics
import com.zilv.app.intercept.AppLabelResolver
import com.zilv.app.intercept.SystemPackageDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 拦截引擎（PRD §3.3 L1）。
 *
 * 三条硬性要求（对应 PRD 开发坑清单 #2 / #3 / #4）：
 *  1. **只拦应用窗口** —— 用 `AccessibilityWindowInfo.getType()` 判定，
 *     绝不动系统窗口、输入法窗口、系统对话框。这是"不把手机搞坏"的第一道保险。
 *  2. **必须给「返回桌面」出口** —— 否则用户被困在遮挡层里。
 *  3. **来电 / 闹钟 / 系统对话框必须让路** —— 非应用窗口出现时立刻隐藏遮挡层。
 *
 * 判定逻辑本身在 [InterceptDecider]（纯函数，12 个单元测试覆盖）；
 * 本类只负责把 Android 的窗口事件翻译成它的输入。
 */
class LockAccessibilityService : AccessibilityService(), KoinComponent {

    private val lockRuleRepository: LockRuleRepository by inject()
    private val blockedAttemptRepository: BlockedAttemptRepository by inject()
    private val overlayController: OverlayController by inject()
    private val temporaryAllowList: TemporaryAllowList by inject()
    private val systemPackageDetector: SystemPackageDetector by inject()
    private val appLabelResolver: AppLabelResolver by inject()

    /**
     * 「软件使用时间限时」的判定入口（v2.7）。
     *
     * ⚠️ 注入的是**窄接口** [QuotaEnforcementSource] 而不是
     * `UsageQuotaRepository`：无障碍服务只需要"这些包现在该被禁"，
     * 不需要知道额度名、周期、用量怎么算 —— 那些在仓库层与纯函数里。
     * 接法与 v2.6 的 `PlanRuleSource` 完全一致（依赖方向单向、不循环：
     * service → 接口 ← repository → dao + UsageRepository）。
     */
    private val quotaEnforcement: QuotaEnforcementSource by inject()

    /**
     * 运行时探测到的系统必需包（桌面 / 输入法 / 电话 …）。
     *
     * 连接时探测一次并缓存：拦截判定在热路径上，不能每个窗口事件都查一遍 PackageManager。
     *
     * ⚠️ 这一份只包含"按 Intent 探测得出的必需包"（桌面 / 输入法 / 电话 / 短信 / 相机 / 地图）
     * 与 [InterceptDecider.CRITICAL_SYSTEM_PACKAGES] 兜底清单 ——
     * 它会被写进 `allow_apps`（内置强制项），所以**刻意不放全部系统应用**：
     * 那会让白名单页多出上百条"包名当标签"的记录。
     * 全部系统应用在 [systemAppPackages] 里，只参与判定、不落库。
     */
    @Volatile
    private var runtimeSystemPackages: Set<String> = emptySet()

    /**
     * **全部系统应用**的包名（v2.13）：`FLAG_SYSTEM` 或 `FLAG_UPDATED_SYSTEM_APP`。
     *
     * 这是"锁机时调音量被踢回桌面 / 退出软件后划不掉后台 / 权限对话框把应用顶掉"
     * 这几个真机 bug 的**根本修法**：不再靠手写清单猜厂商包名，
     * 而是让系统告诉我们哪些包是系统应用（音量面板、最近任务、权限控制器、
     * 厂商安全中心全在其中）。
     *
     * ## 为什么要缓存
     *
     * `getInstalledApplications()` 是跨进程调用，几百个包要几十毫秒。
     * 拦截判定跑在**每个窗口事件**上，绝不能每次都枚举一遍。
     * 于是：连接时算一次（[refreshSystemAppPackages]），
     * 之后只在收到包变化广播时失效重算（[packageChangeReceiver]）。
     *
     * ## 为什么只参与判定、不写库
     *
     * 它属于"环境事实"而不是"用户的配置"：写进 `allow_apps` 会让白名单页
     * 多出上百条记录（标签还只能是包名），用户既看不懂也删不掉。
     * 判定入口 [InterceptDecider.decide] 的 `systemPackages` 参数就是为它准备的。
     */
    @Volatile
    private var systemAppPackages: Set<String> = emptySet()

    /**
     * **运行时按 Intent 探测到的关键组件**（v2.14）：系统界面组件 / 截屏 / 智能助理。
     *
     * 与 [systemAppPackages] 分开存放，因为它可能包含**商店安装**的应用
     * （Google 助理 `com.google.android.googlequicksearchbox` 就没有任何系统标志），
     * 那类包永远进不了 `FLAG_SYSTEM` 枚举。探测手段见
     * [SystemPackageDetector.detectAlwaysAllowByIntent]：
     * `Intent.ACTION_ASSIST` / `ACTION_VOICE_COMMAND` 的解析结果、
     * `Settings.Secure` 的默认助理、以及以 `systemui` 结尾的厂商变体。
     *
     * 生命周期与 [systemAppPackages] 完全一致（连接时算一次 + 包变化时重算 +
     * 曾失败时限频重试），因为它同样来自 `PackageManager` 查询。
     */
    @Volatile
    private var alwaysAllowPackages: Set<String> = emptySet()

    /**
     * 上一次**成功/尝试**重算包缓存的时间（v2.14）。
     *
     * 只用于 [retryPackageCachesIfEmpty] 的限频：枚举一旦返回空（开机早期
     * PackageManager 还没扫完、ROM 限制），缓存会一直是空的 —— 而缓存为空
     * 意味着整段会话内所有系统组件都可能被拦（"有一定概率触发"的直接原因）。
     * 于是允许在窗口事件路径上**限频重试**，而不是等下一次安装广播。
     */
    @Volatile
    private var lastPackageCacheRefreshAt: Long = 0L

    /**
     * **有启动入口**的包名（v2.15）：「只拦能启动的应用」这条规则的全部数据。
     *
     * 判据与理由见 [InterceptDecider.hasNoLaunchEntry]；枚举见
     * [SystemPackageDetector.detectLaunchablePackages]（一次
     * `queryIntentActivities(ACTION_MAIN / CATEGORY_LAUNCHER)`）。
     *
     * 用户报了四次的"调音量被拦 / 截屏被拦 / 助理被拦"就是这一条要根治的：
     * 那些包**没有启动入口**，用户永远打不开它们。
     *
     * ## 缓存与失效
     *
     * 与 [systemAppPackages] / [alwaysAllowPackages] **完全同一套机制**：
     *  - [onServiceConnected] 时算一次（[refreshPackageCaches]）
     *  - 收到 [SystemPackageChangeReceiver] 的包变化广播时失效重算
     *    （新装应用后它的启动入口才存在，缓存必须跟着变，否则"刚装的抖音拦不住"）
     *  - 枚举失败（集合为空）时由 [retryPackageCachesIfEmpty] 限频重试
     *
     * ⚠️ **绝不能在每次窗口事件里查 `PackageManager`**：那是跨进程调用，
     * 几百个包要几十毫秒，而窗口事件一秒可能来好几次。
     *
     * ## 空集合的语义（安全底线）
     *
     * 空 = "枚举没拿到东西，无从判断"。判定侧对它的处理是
     * **本规则不生效、退化为旧行为**（不是"全都不可启动"）——
     * 见 [InterceptDecider.hasNoLaunchEntry]。否则一次枚举失败就会让所有应用被放行，
     * 等于锁机当场失效。
     */
    @Volatile
    private var launchablePackages: Set<String> = emptySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 主线程 Handler。
     *
     * ⚠️ `Toast.show()` 必须在有 Looper 的线程调用，而拦截判定跑在
     * `Dispatchers.Default` 上 —— 直接在里面 show 会抛
     * "Can't create handler inside thread that has not called Looper.prepare()"。
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var screenReceiver: ScreenStateReceiver? = null

    /**
     * 安装 / 卸载 / 覆盖安装监听（v2.13）。
     *
     * 唯一的职责是**让 [systemAppPackages] 这个缓存失效**：
     * 系统组件被更新（`FLAG_SYSTEM` ↔ `FLAG_UPDATED_SYSTEM_APP` 会翻转）、
     * 被停用、或者新增之后，缓存必须重算，否则"新系统组件被拦"这种 bug 会复发。
     */
    private var packageChangeReceiver: SystemPackageChangeReceiver? = null

    /** 使用时长采集：**按包名去重**（坑清单 #11），息屏暂停 */
    private val usageTracker get() = UsageTrackingHub.tracker

    /** 缓存状态只用于遮挡层文案，**不参与拦截判定**。 */
    @Volatile
    private var cachedRuleName: String = ""

    @Volatile
    private var cachedMessage: String = ""

    @Volatile
    private var cachedShowCheckIn: Boolean = false

    @Volatile
    private var cachedRuleId: Long = 0L

    @Volatile
    private var cachedSpotId: Long? = null

    @Volatile
    private var cachedEndMinutes: Long = 0

    /** 当前遮挡层覆盖的包名（null 表示未显示）。 */
    private var overlayPackage: String? = null

    /** 最近一次已记录的被拦包名，用于去重（同一应用反复切前台只记一次）。 */
    private var lastRecordedBlockPackage: String? = null

    /** 最近一次记录拦截时的**原因**，与 [lastRecordedBlockPackage] 一起去重。 */
    private var lastRecordedBlockReason: String? = null

    /**
     * 最近一次算出的"因额度用完而被禁用"的包 → 原因（v2.7）。
     *
     * 只用于**文案**：拦截判定本身每次都用新鲜数据（见 [evaluateAndEnforce]），
     * 这里缓存只是为了在真正执行拦截时能说出"是哪个额度用完了"。
     */
    @Volatile
    private var quotaExhaustion: Map<String, QuotaExhaustion> = emptyMap()

    /**
     * 最近一次算出的"被启用额度绑定"的包名集合（v2.7）。
     *
     * 看门狗用它判断"当前这个前台应用还值不值得盯"：只盯
     * [quotaExhaustion] 是不够的 —— 应用刚打开时额度还没用完，
     * 它压根不在禁用集合里，看门狗就永远不会启动。
     */
    @Volatile
    private var quotaBoundPackages: Set<String> = emptySet()

    /** 当前的前台包名（额度看门狗用它判断"还要不要继续盯"）。 */
    @Volatile
    private var foregroundPackage: String = ""

    /**
     * 最近一次**判定为应用窗口**的包名（v2.10）。
     *
     * 只用于 [eventPackageOwnsVisibleApplicationWindow] 的判据二/四：
     * 判断一个"窗口类型未知"的事件，究竟是这个应用自己的窗口变化，
     * 还是一个没有应用窗口的系统进程（音量面板 / 权限对话框）在冒泡。
     *
     * 刻意只记录"确认过是应用窗口"的包名 —— 系统窗口的包名绝不能进来，
     * 否则接下来的判定会拿 systemui 当参照物，反而把真实应用判成"别人"。
     */
    @Volatile
    private var lastApplicationPackage: String = ""

    /** 额度看门狗任务；前台变化时重启，避免延时计算用了旧包名。 */
    private var quotaWatchdog: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "无障碍服务已连接")
        InterceptDiagnostics.onServiceConnected(overlayController.canDrawOverlays())

        // 先探测系统必需包（桌面/输入法）—— 必须早于任何拦截判定
        runtimeSystemPackages = runCatching {
            systemPackageDetector.detectBuiltins().map { it.packageName }.toSet()
        }.onFailure { Log.e(TAG, "探测系统包失败", it) }.getOrDefault(emptySet())

        // v2.13：再枚举**全部系统应用**（音量面板 / 最近任务 / 权限对话框 / 厂商组件）。
        // v2.14：同时探测**系统界面组件 / 截屏 / 智能助理**（含商店安装的助理）。
        // 这两份只供拦截判定使用，不写库（见字段注释）。
        refreshPackageCaches()

        // 包变化时让上面那两份缓存失效 —— 动态注册，生命周期跟着服务走
        packageChangeReceiver = SystemPackageChangeReceiver { refreshPackageCaches() }
            .also { SystemPackageChangeReceiver.register(this, it) }

        primeCache()

        // 启动即自检：把关键链路的结果写进日志，
        // 出问题时用 `adb logcat -s LockAccessibilityService` 一看就知道断在哪
        scope.launch {
            runCatching {
                // 顺便把内置项写库，让「白名单」页与拦截行为一致（幂等）
                lockRuleRepository.ensureBuiltinAllowList(
                    runtimeSystemPackages.map { it to it }
                )

                val status = lockRuleRepository.currentStatus()
                val rules = lockRuleRepository.getEnabledRules()
                val persisted = lockRuleRepository.effectiveAllowList(rules.map { it.id })
                val effective = InterceptDecider.effectiveAllowList(persisted, runtimeSystemPackages)

                Log.i(
                    TAG,
                    "自检 → 启用规则 ${rules.size} 条 / 当前阶段 ${status.phase} / " +
                        "系统包 ${runtimeSystemPackages.size} 个 / " +
                        "系统应用 ${systemAppPackages.size} 个（v2.13 全部放行）/ " +
                        "关键组件 ${alwaysAllowPackages.size} 个（v2.14 系统界面/截屏/助理，一律放行）/ " +
                        // v2.15：为 0 = 枚举失败 = 「只拦能启动的应用」不生效（退化为旧行为）
                        "可启动应用 ${launchablePackages.size} 个（v2.15 无启动入口的一律放行）/ " +
                        "有效白名单 ${effective.size} 项 / " +
                        "悬浮窗权限 ${overlayController.canDrawOverlays()}"
                )
                Log.i(TAG, "系统必需包: ${runtimeSystemPackages.sorted().joinToString()}")
                Log.i(TAG, "关键组件: ${alwaysAllowPackages.sorted().joinToString()}")
            }.onFailure { Log.e(TAG, "自检失败", it) }
        }

        // 亮屏/息屏监听只能动态注册（见 ScreenStateReceiver 注释）
        screenReceiver = ScreenStateReceiver(scope) { segment ->
            UsageTrackingHub.persist(scope, segment)
        }.also { ScreenStateReceiver.register(this, it) }

        if (!ScreenStateReceiver.isInteractive(this)) {
            // 罕见情况：连接时屏幕是关的，先按暂停处理
            UsageTrackingHub.tracker.onScreenOff(System.currentTimeMillis())
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "无障碍服务被解绑")
        InterceptDiagnostics.onServiceDisconnected()
        overlayController.hide()
        screenReceiver?.let { ScreenStateReceiver.unregister(this, it) }
        screenReceiver = null
        packageChangeReceiver?.let { SystemPackageChangeReceiver.unregister(this, it) }
        packageChangeReceiver = null
        // 解绑时把当前段结算掉，避免时长丢失
        UsageTrackingHub.tracker.flush(System.currentTimeMillis())?.let {
            UsageTrackingHub.persist(scope, it)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        InterceptDiagnostics.onServiceDisconnected()
        overlayController.hide()
        screenReceiver?.let { ScreenStateReceiver.unregister(this, it) }
        screenReceiver = null
        packageChangeReceiver?.let { SystemPackageChangeReceiver.unregister(this, it) }
        packageChangeReceiver = null
        super.onDestroy()
    }

    /**
     * 重算并缓存**全部系统应用**、**关键组件**与**可启动应用**（v2.13 / v2.14 / v2.15）。
     *
     * 调用时机（都是刻意选的，不在拦截热路径上）：
     *  1. [onServiceConnected] —— 服务刚连上，缓存还是空的
     *  2. 收到 [SystemPackageChangeReceiver] 的包变化广播 —— 安装 / 卸载 / 覆盖安装 / 组件启停
     *     （v2.15 起这也覆盖了"新装的应用才有启动入口"：不重算就拦不住刚装的应用）
     *  3. [retryPackageCachesIfEmpty] —— 上一次枚举**失败**（返回空）时的限频重试
     *
     * 失败时保留**上一次**的结果而不是清空：
     * 枚举失败（ROM 限制）只会让缓存变旧，而清空会让所有系统应用瞬间变成"可拦"——
     * 那正是这次要修的 bug，绝不能因为一次查询失败就回退到那个状态。
     */
    private fun refreshPackageCaches() {
        lastPackageCacheRefreshAt = System.currentTimeMillis()

        val detected = runCatching { systemPackageDetector.detectSystemPackages() }
            .onFailure { Log.e(TAG, "枚举系统应用失败", it) }
            .getOrNull()
        if (detected != null) {
            systemAppPackages = detected
            Log.i(TAG, "系统应用共 ${detected.size} 个（已缓存，随包变化失效）")
        }

        // v2.14：再把"系统界面组件 / 截屏 / 智能助理"探测出来。
        // 失败同样保留旧值（宁可少拦，不可误拦）。
        val alwaysAllow = runCatching { systemPackageDetector.detectAlwaysAllowByIntent() }
            .onFailure { Log.e(TAG, "探测关键组件失败", it) }
            .getOrNull()
        if (alwaysAllow != null) {
            alwaysAllowPackages = alwaysAllow
            Log.i(TAG, "关键组件共 ${alwaysAllow.size} 个：${alwaysAllow.sorted().joinToString()}")
        }

        // v2.15：枚举**有启动入口**的包 ——「只拦能启动的应用」这条规则的数据源。
        //
        // ⚠️ 查询失败时 detector 内部已经 runCatching 成空集合，而空集合的语义是
        // "无从判断 ⇒ 本规则不生效 ⇒ 旧行为"，方向是安全的
        // （宁可拦得和上一版一样多，也不能因为一次查询失败把锁机放松）。
        // 这里刻意**不用** `?: emptySet()` 去覆盖旧值：查不到就保持上一轮的结果，
        // 免得把一次成功的旧结论擦掉，让排查分不清"这次失败"和"一直没查到"。
        val launchable = runCatching { systemPackageDetector.detectLaunchablePackages() }
            .onFailure { Log.e(TAG, "枚举可启动应用失败", it) }
            .getOrNull()
        if (launchable != null) {
            launchablePackages = launchable
            if (launchable.isEmpty()) {
                // 空 = 查不到（ROM 限制 / 包管理器忙）⇒ 规则不生效。显式记一笔。
                //
                // ⚠️ 刻意**不**调 InterceptDiagnostics.onError()：那会把自检页的
                // `verdict()` 永久顶成"执行时出错"，把真正有用的结论
                // （断在第几环 / 拦到了什么）全盖住。这个状态有专门的一行
                // 「参考 · 可启动应用」会自己说清楚。
                Log.w(TAG, "可启动应用枚举为空 → 「只拦能启动应用」不生效，退化为旧行为")
            } else {
                Log.i(TAG, "可启动应用共 ${launchable.size} 个（已缓存，随包变化失效）")
            }
        }

        // 自检页的「可启动应用 N 个」必须在**连接时**就有值 ——
        // 否则在收到第一个窗口事件之前，那一行会显示成 0（看起来像枚举失败）。
        InterceptDiagnostics.updateLaunchable(launchablePackages.size)
    }

    /** 兼容旧调用点的别名（v2.13 的 `refreshSystemAppPackages`）。 */
    @Suppress("unused")
    private fun refreshSystemAppPackages() = refreshPackageCaches()

    /**
     * 包缓存在**上一次枚举失败**（空集合）时的限频重试（v2.14 / v2.15）。
     *
     * 为什么需要：v2.13 只在服务连接与包变化广播时重算。若连接那一刻
     * `getInstalledApplications()` 恰好返回空（开机早期、ROM 限制、包管理器忙），
     * 缓存就**一直是空的**，直到下一次安装/卸载 —— 期间所有系统组件都可被拦。
     * 这正是用户说的"不是稳定触发，但还是有一定概率触发"。
     *
     * v2.15 起，**可启动集合**也纳入同一套重试：它是「只拦能启动的应用」
     * 这条规则的开关，为空 = 那次修复等于没做，同样需要自愈。
     *
     * 限频 60 秒：正常情况下这个函数一次都不会做重活（两个集合都非空直接返回），
     * 所以不会给热路径带来可感知的代价。
     */
    private fun retryPackageCachesIfEmpty() {
        if (systemAppPackages.isNotEmpty() && launchablePackages.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastPackageCacheRefreshAt < PACKAGE_CACHE_RETRY_MILLIS) return
        runCatching { refreshPackageCaches() }
            .onFailure { Log.e(TAG, "重试包缓存失败", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowEvent(e)
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 事件处理

    private fun handleWindowEvent(event: AccessibilityEvent) {
        val now = System.currentTimeMillis()

        val pkg = event.packageName?.toString()
        if (pkg.isNullOrBlank()) {
            InterceptDiagnostics.onEvent("（无包名）", "忽略：事件没有包名")
            return
        }

        // ---- 必须排除的一类事件：我们自己添加遮挡层所产生的窗口变化 ----
        //
        // ⚠️ 这是第二个"拦不住"的真凶。OverlayController.show() 用
        // WindowManager.addView 加窗口，**这个动作本身会触发 TYPE_WINDOWS_CHANGED，
        // 而该事件的包名正是本应用**。若照常走判定，decide() 会因为
        // "自己永远放行" 返回 Allow，紧接着调用 clearOverlay() ——
        // 结果是遮挡层刚显示就被自己关掉，用户只看到一闪。
        //
        // 因此：本应用的 TYPE_WINDOWS_CHANGED 一律忽略。
        // 而本应用的 TYPE_WINDOW_STATE_CHANGED 仍然要处理 —— 那是用户真的
        // 打开了我们的打卡页，此时应该收起遮挡层。
        //
        // ⚠️ 这一段必须排在窗口归属判定**之前**：遮挡层是我们自己加的窗口，
        // 归属判定会把它判成"非应用窗口"从而直接 return，
        // 那样"进打卡页收起遮挡层"就永远不会发生了（回归风险）。
        if (pkg == packageName) {
            // 本应用自己的窗口事件不参与归属判定，直接走判定流程：
            //  - TYPE_WINDOWS_CHANGED：多半是遮挡层自己引起的，忽略
            //  - TYPE_WINDOW_STATE_CHANGED：用户真的打开了打卡页 → decide() 放行 →
            //    clearOverlay() 收起遮挡层
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                InterceptDiagnostics.onEvent(pkg, "忽略：本应用的窗口变化（多为遮挡层自身）")
                return
            }
            foregroundPackage = pkg
            InterceptDiagnostics.onEvent(pkg, "本应用窗口事件，走放行判定")
            scope.launch { evaluateAndEnforce(pkg) }
            return
        }

        // ---- 窗口归属判定（v2.10）----
        //
        // ⚠️ 这一步必须在**任何**副作用之前：在使用时长统计、前台包名缓存、
        // 拦截判定之前就要知道"这个窗口到底是不是应用窗口"。
        //
        // 修的是三个真机 bug（详见 InterceptDecider 里 v2.10 那一段注释）：
        //  1. 锁机中按音量键 → systemui 的音量面板触发窗口变化事件
        //     → 窗口类型判定不出来 → 被当成普通应用 → 踢回桌面
        //  2. 最近任务（概览）同理 → 划后台时被踢回桌面（"空桌面"）
        //  3. 白名单应用申请权限时，对话框属于 permissioncontroller
        //     → 被当成普通应用 → 把正在申请权限的应用踢掉（"闪退"）
        val windowList = runCatching { windows }.getOrNull()
        val isWindowStateEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val windowType = windowTypeOf(event, windowList)
        val windowAnalysis = InterceptDecider.analyzeWindowForPackage(
            packageName = pkg,
            windowType = windowType,
            isWindowStateChangeEvent = isWindowStateEvent,
            eventWindowMayBeApplication = eventWindowMayBeApplication(pkg, event, windowList)
        )
        InterceptDiagnostics.onWindowClassified(
            packageName = pkg,
            windowType = windowType,
            analysis = windowAnalysis.name,
            isWindowStateChangeEvent = isWindowStateEvent
        )

        if (windowAnalysis == InterceptDecider.WindowAnalysis.ApplicationWindow) {
            lastApplicationPackage = pkg
        }

        // ---- 让路机制（PRD 坑清单 #3）----
        //
        // ⚠️ 这里修的是一个**致命 bug**：原先写的是
        //     if (windowType == null) { clearOverlay(); return }
        // 即"判定不出窗口类型就不拦"。但 windowTypeOf 依赖 getWindows()，
        // 而它在很多 ROM 上返回空列表、TYPE_WINDOWS_CHANGED 的 windowId 也常常
        // 对不上号 —— 于是 windowType 几乎总是 null，**整个拦截永久失效**。
        //
        // 正确策略：**只有确实判定出这是非应用窗口时才让路**。
        // 判定不出来时继续走包名判定，安全性由两道独立保险保证：
        //   1. InterceptDecider.CRITICAL_SYSTEM_PACKAGES（精确匹配的系统包白名单）
        //   2. 白名单里的桌面 / 输入法（由 SystemPackageDetector 运行时探测）
        //
        // v2.10 起，"判定不出来"这一支被 [windowAnalysis] 分成两半：
        //  - 该包名下确实有应用窗口 / 是窗口状态变化事件 → 照旧执行拦截
        //    （不这样做就会重犯"拦截整体失效"的老 bug）
        //  - 该包名下**没有任何应用窗口**（音量面板 / 最近任务 / 权限对话框）
        //    → 让路，绝不执行"踢回桌面"
        //
        // ⚠️ 两个条件必须 **OR** 在一起，不是二选一：
        //  - [InterceptDecider.shouldYieldToWindow] 保留"类型确实判定出来时
        //    按类型让路"这条老语义（来电 / 输入法 / 系统对话框）
        //  - [windowAnalysis] 补上"类型判定不出来时**还能不能证明**它是应用窗口"
        val shouldYield = InterceptDecider.shouldYieldToWindow(
            windowTypeKnown = windowType != null,
            isApplicationWindow = windowType == AccessibilityWindowInfo.TYPE_APPLICATION
        ) || windowAnalysis != InterceptDecider.WindowAnalysis.ApplicationWindow
        if (shouldYield) {
            InterceptDiagnostics.onEvent(pkg, "让路：${windowAnalysis.name}（type=${windowType ?: "未知"}）")
            InterceptDiagnostics.onYield()
            // ⚠️ 这里刻意**不**调用 clearOverlay()：让路事件常常是
            // "系统浮层从被拦应用上方掠过"（音量面板、权限对话框），
            // 此时收起遮挡层会让用户看到锁机界面无端消失。
            // 真正该收起的时候（回到桌面 / 进了白名单应用 / 打开打卡页）
            // 那次判定自己会走到 clearOverlay()。
            return
        }

        // ---- 使用时长采集与前台包名缓存 ----
        //
        // 到这里已经确认"用户真的打开了这个应用"，记时长才是对的。
        // 系统窗口（音量面板 / 最近任务 / 权限对话框）在前面就让路了，
        // 它们的包名绝不会被记进使用时长，也不会把额度看门狗指到错误的包。
        //
        // ⚠️ 本应用自己的事件在上面（归属判定之前）就已经单独放行了，
        // 走到这里的 pkg 一定不是本应用。
        //
        // [InterceptDecider.shouldTrackAsForeground] 在这里必定为 true
        // （前面已经把所有"非应用窗口"的事件让路掉了），保留这一层显式判断
        // 是为了让"什么才算用户打开了应用"这条规则只有一个出处，
        // 以后有人调整上面分支的顺序时不会被悄悄绕开。
        if (!InterceptDecider.shouldTrackAsForeground(windowAnalysis)) return

        // Tracker 内部按包名去重：同一 App 内部切 Activity 不会重置计时
        usageTracker.onForeground(pkg, now)?.let { segment ->
            UsageTrackingHub.persist(scope, segment)
        }

        // 记录前台包名：额度看门狗与遮挡层文案都要用它
        foregroundPackage = pkg

        // 通知前台服务更新"使用时长"通知（微信进入/离开时切换秒表，PRD §3.7）
        runCatching { LockForegroundService.notifyForegroundChanged(this, pkg) }

        scope.launch { evaluateAndEnforce(pkg) }
    }

    /**
     * 取新鲜状态 → 判定 → 执行。
     *
     * ## v2.7：额度用尽的禁用**不依赖锁机**
     *
     * 原实现里"不是锁机态"就直接 return，于是额度这条线永远不会被判定。
     * 现在锁机态只决定"最终兜底要不要拦"，而额度用尽是**独立**的一条拦截理由：
     * 用户一条锁机规则都没配，额度也必须生效（这正是需求的原话）。
     *
     * 因此判定顺序调整为：先算两组输入（是否锁机、哪些包因额度被禁），
     * 再统一交给纯函数 [InterceptDecider.decide] ——
     * 优先级全部在那一处定义（见它的 KDoc 表），这里不重复判断。
     */
    private suspend fun evaluateAndEnforce(pkg: String) {
        val now = System.currentTimeMillis()

        // ---- v2.14：包缓存曾失败时限频重试 ----
        //
        // 若上一次 `getInstalledApplications()` 返回空（开机早期 / ROM 限制），
        // 缓存会一直是空的，整段会话内所有系统组件都可能被拦 —— 这正是用户说的
        // "有一定概率触发"。这里只在**集合为空**时补一次（限频 60 秒），
        // 正常路径上是一次布尔判断，不进 PackageManager。
        retryPackageCachesIfEmpty()

        // ---- 额度用尽的包（v2.7）----
        //
        // ⚠️ 这一步**必须在读锁机状态之前**：额度用尽是**独立于锁机**的
        // 拦截理由，即使 `currentStatus()` 失败（返回 null）也要先把它算出来，
        // 否则"锁机状态读不到"会连带把额度功能一起废掉。
        //
        // 失败方向是"空集合"（见 QuotaEnforcementSource 的实现）：
        // 判定不出来就不拦 —— 与"拿不到包名就放行"同一条安全取向。
        quotaExhaustion = runCatching { quotaEnforcement.disabledPackages(now) }
            .getOrElse { emptyMap() }
        val quotaDisabled = quotaExhaustion.keys
        InterceptDiagnostics.updateQuota(quotaDisabled.size)

        // 看门狗：只要前台应用**被启用额度绑定**就盯着它。
        // 注意条件不是"已经用尽" —— 刚打开时额度还没用完，若那时不启动，
        // 就永远不会有人去发现"它在中途用完了"。
        quotaBoundPackages = runCatching { quotaEnforcement.boundPackages(now) }
            .getOrElse { emptySet() }
        startQuotaWatchdog(watching = pkg in quotaBoundPackages)

        // ⚠️ 判定必须用**新鲜**的状态。
        // 原先靠 cachedLocked（由 refreshState 异步写入），而事件处理紧接着同步读，
        // 拿到的必然是上一次的旧值 —— 锁机刚开始时那一两个事件会被漏放。
        val status = runCatching { lockRuleRepository.currentStatus() }.getOrNull()
        if (status == null) {
            InterceptDiagnostics.onError("读取锁机状态失败")
            // 锁机状态读不到时**只**保留额度这条线：它不依赖锁机状态，
            // 数据也已经在手上了，没有理由因为另一条链路出错就放过。
            //
            // ⚠️ v2.14：这条路径也必须先过**关键系统包 / 关键组件**这道保险丝。
            // 原先它直接 `if (!quotaDisabled.contains(pkg)) return` 然后拦，
            // 完全跳过了 `decide()` 里的放行判定 —— 于是"状态读不到"时，
            // 一旦 systemui / 设置 / 权限对话框出现在禁用集合里就会被拦
            // （把用户关在锁机里，且看不出原因）。这是本次查证发现的真 bug。
            if (pkg == packageName) return
            if (pkg in InterceptDecider.CRITICAL_SYSTEM_PACKAGES) return
            if (AlwaysAllowPackages.isAlwaysAllowed(pkg)) return
            if (pkg in systemAppPackages) return
            if (pkg in alwaysAllowPackages) return
            // v2.15：这条兜底路径同样要过"有没有启动入口"这一关 ——
            // 它与锁机状态无关，没理由因为另一条链路出错就失去效力。
            if (InterceptDecider.hasNoLaunchEntry(pkg, launchablePackages)) return
            if (!quotaDisabled.contains(pkg)) return
            enforceQuotaBlock(pkg, ruleId = null)
            return
        }
        updateCache(status)
        InterceptDiagnostics.updateOverlayPermission(overlayController.canDrawOverlays())

        val locked = status.phase == LockPhase.LOCKED
        if (locked) InterceptDiagnostics.onLockedEvaluated()

        if (!locked && !quotaDisabled.contains(pkg)) {
            InterceptDiagnostics.onEvent(pkg, "放行：非锁机态，且额度未用尽")
            clearOverlay()
            return
        }

        // 紧急放行仍在有效期内。
        // ⚠️ 它优先于额度：用户明确按下的"我就是要用 5 分钟"是**最后一道出口**，
        // 不该被额度挡死（额度是"表面限制"，紧急放行是逃生门）。
        if (temporaryAllowList.isAllowed(pkg, now)) {
            InterceptDiagnostics.onEvent(pkg, "放行：紧急放行有效期内")
            clearOverlay()
            return
        }

        val ruleId = status.rule?.id
        val persisted = lockRuleRepository.effectiveAllowList(listOfNotNull(ruleId))

        // 运行时探测到的桌面 / 输入法等**无条件并入** ——
        // 否则全新安装（没进过白名单页）时按 Home 会被遮挡层困死。
        // 详见 InterceptDecider.effectiveAllowList 的注释。
        val allowList = InterceptDecider.effectiveAllowList(persisted, runtimeSystemPackages)
        InterceptDiagnostics.updateLists(
            effectiveAllow = allowList.size,
            runtimeSystemPackages = runtimeSystemPackages.size,
            systemApps = systemAppPackages.size,
            alwaysAllow = alwaysAllowPackages.size,
            // v2.15：0 = 枚举失败 = 「只拦能启动的应用」未生效（自检页会明确写出来）
            launchable = launchablePackages.size
        )

        val decision = InterceptDecider.decide(
            foregroundPackage = pkg,
            selfPackage = packageName,
            allowList = allowList,
            locked = locked,
            quotaExhaustedPackages = quotaDisabled,
            // v2.13：全部系统应用无条件放行（音量面板 / 最近任务 / 权限对话框 …）
            systemPackages = systemAppPackages,
            // v2.14：系统界面组件 / 截屏 / 智能助理（含按 Intent 探测到的商店应用）
            alwaysAllowPackages = alwaysAllowPackages,
            // v2.15：**没有启动入口的包一律放行**（不依赖任何包名清单，
            // 因此不受厂商变体影响 —— 这是前三次修复失败的根因）
            launchablePackages = launchablePackages
        )

        when (decision) {
            is InterceptDecider.Decision.Allow -> {
                InterceptDiagnostics.onEvent(
                    pkg,
                    when {
                        pkg == packageName -> "放行：本应用"
                        pkg in InterceptDecider.CRITICAL_SYSTEM_PACKAGES -> "放行：关键系统包"
                        AlwaysAllowPackages.isAlwaysAllowed(pkg) ->
                            "放行：${AlwaysAllowPackages.categoryOf(pkg) ?: "关键组件"}（内置清单）"

                        pkg in systemAppPackages -> "放行：系统应用"
                        pkg in alwaysAllowPackages -> "放行：关键组件（运行时探测）"
                        pkg in allowList -> "放行：在白名单内"
                        // v2.15：排在上面几条之后，与 decide() 的判定顺序严格一致
                        InterceptDecider.hasNoLaunchEntry(pkg, launchablePackages) ->
                            "放行：没有启动入口（系统浮层/组件，不是用户能打开的应用）"

                        else -> "放行：额度未用尽"
                    }
                )
                clearOverlay()
            }

            is InterceptDecider.Decision.Block -> {
                val exhaustion = quotaExhaustion[pkg]
                val reason = if (exhaustion != null) {
                    "拦截：额度用尽（${exhaustion.quotaName}）"
                } else {
                    "拦截：不在白名单，执行拦截动作"
                }
                // v2.14：判定依据（关键清单 / 关键组件 / 枚举 / 探测 / 白名单 各打勾与否 + 集合大小）。
                // 这是"系统界面组件被拦"这类反馈唯一能自证的现场 —— 用户截图即可。
                val basis = InterceptDecider.explainBlock(
                    foregroundPackage = pkg,
                    selfPackage = packageName,
                    allowList = allowList,
                    locked = locked,
                    quotaExhaustedPackages = quotaDisabled,
                    systemPackages = systemAppPackages,
                    alwaysAllowPackages = alwaysAllowPackages,
                    // v2.15：判定依据里带上"是否可启动"这一项
                    launchablePackages = launchablePackages
                )
                InterceptDiagnostics.onEvent(pkg, "$reason；$basis")
                if (exhaustion != null) InterceptDiagnostics.onQuotaBlocked(pkg)
                Log.i(TAG, "拦截: $pkg（${exhaustion?.message ?: "规则=${status.rule?.name}"}）；$basis")

                // 同一个应用反复切前台时只记一次（合并计数由仓库按 5 秒窗口处理）。
                // 去重键里带上"原因"：额度用尽与锁机是两次不同的拦截，
                // 只按包名去重会把第二条悄悄吞掉。
                if (lastRecordedBlockPackage != pkg || lastRecordedBlockReason != reason) {
                    lastRecordedBlockPackage = pkg
                    lastRecordedBlockReason = reason
                    runCatching {
                        blockedAttemptRepository.recordAttempt(packageName = pkg, ruleId = ruleId)
                    }.onFailure {
                        Log.e(TAG, "记录拦截失败", it)
                        InterceptDiagnostics.onError("记录拦截失败：${it.message}")
                    }
                }

                enforceBlock(pkg, exhaustion, basis)
            }
        }
    }

    // ------------------------------------------------------ 额度看门狗（v2.7）

    /**
     * 保证额度看门狗在运行（[watching] 为假时让它自然停下）。
     *
     * 为什么必须有它：额度可能**在用户正用着某个应用的过程中**被用尽，
     * 而那时不会再来一个窗口事件 —— 光靠事件驱动，用户会把额度用完还继续用，
     * 直到下次切应用才被拦（"限时"实际上变成了"下次打开才限时"）。
     *
     * 看门狗的做法很朴素：每 [QUOTA_TICK_MILLIS] 毫秒重跑一次
     * [evaluateAndEnforce]（用的就是事件路径那一个方法，**不另做一套拦截**）。
     * 30 秒的粒度对"分钟级额度"完全够用，代价是每 30 秒一次走索引的小查询 ——
     * 比为了精确到秒而引入 AlarmManager/WorkManager 的复杂度划算得多。
     *
     * ⚠️ 只在"当前前台应用的包名确实被启用额度绑定"时才继续循环（见
     * [quotaBoundPackages]）。这一条同时解决了一个坑：前台切到别的应用时，
     * 旧包名对应的看门狗会在一轮之后自己退出，不会把新应用误判成"额度用尽"。
     */
    private fun startQuotaWatchdog(watching: Boolean) {
        val running = quotaWatchdog?.isActive == true
        if (!watching) {
            // 不在额度约束内：让正在跑的那一个自然结束即可（不必取消，
            // 它每轮都会重新判断，这样少一次竞态）
            return
        }
        if (running) return
        quotaWatchdog = scope.launch {
            while (true) {
                delay(QUOTA_TICK_MILLIS)
                val pkg = foregroundPackage
                if (pkg.isBlank() || pkg !in quotaBoundPackages) break
                InterceptDiagnostics.onQuotaWatchdogTick()
                runCatching { evaluateAndEnforce(pkg) }
                    .onFailure { Log.e(TAG, "额度看门狗检查失败", it) }
            }
        }
    }

    /**
     * 锁机状态读不到时的**纯额度拦截**路径。
     *
     * 额度判定不依赖锁机状态，数据也已经在手上，所以没有理由因为另一条链路
     * 出错就把它一起放过。文案与动作与常规路径完全一致（[enforceBlock]）。
     */
    private suspend fun enforceQuotaBlock(pkg: String, ruleId: Long?) {
        val exhaustion = quotaExhaustion[pkg] ?: return
        val reason = "拦截：额度用尽（${exhaustion.quotaName}）"
        // v2.14：这条路径同样要留下判定依据（它走的是"锁机状态读不到"的兜底分支，
        // 正是最需要说清楚的一条）
        val basis = InterceptDecider.explainBlock(
            foregroundPackage = pkg,
            selfPackage = packageName,
            allowList = emptySet(),
            locked = false,
            quotaExhaustedPackages = quotaExhaustion.keys,
            systemPackages = systemAppPackages,
            alwaysAllowPackages = alwaysAllowPackages,
            launchablePackages = launchablePackages
        )
        InterceptDiagnostics.onEvent(pkg, "$reason；$basis")
        InterceptDiagnostics.onQuotaBlocked(pkg)
        Log.i(TAG, "拦截: $pkg（${exhaustion.message}）；$basis")
        if (lastRecordedBlockPackage != pkg || lastRecordedBlockReason != reason) {
            lastRecordedBlockPackage = pkg
            lastRecordedBlockReason = reason
            runCatching {
                blockedAttemptRepository.recordAttempt(packageName = pkg, ruleId = ruleId)
            }.onFailure {
                Log.e(TAG, "记录拦截失败", it)
                InterceptDiagnostics.onError("记录拦截失败：${it.message}")
            }
        }
        enforceBlock(pkg, exhaustion, basis)
    }

    /**
     * 当前前台窗口的类型；**判定不出来时返回 null**。
     *
     * 调用方必须把 null 当作"未知"，而不是"不拦"（v2.10 起交给
     * [InterceptDecider.analyzeWindowForPackage] 统一裁决）。
     *
     * 为什么常常判定不出来：
     *  - `getWindows()` 需要 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，部分 ROM 直接返回空
     *  - `TYPE_WINDOWS_CHANGED` 事件的 windowId 经常对不上任何现存窗口
     *
     * @param windowList 已经在 [handleWindowEvent] 里取过一次的窗口列表
     *        （每个事件只调一次 `windows`，这是个跨进程调用，不便宜）
     */
    private fun windowTypeOf(
        event: AccessibilityEvent,
        windowList: List<AccessibilityWindowInfo>?
    ): Int? {
        val list = windowList ?: return null
        if (list.isEmpty()) return null

        val windowId = event.windowId
        if (windowId < 0) return null

        return list.firstOrNull { it.id == windowId }?.type
    }

    /**
     * 事件声称的前台包名，是否确实拥有一个**可见的应用窗口**（v2.10）。
     *
     * 这是"窗口类型判定不出来"时的兜底依据，也是三个真机 bug 的直接修复点：
     *
     * | 场景 | 窗口列表里有什么 | 本函数 | 结果 |
     * |---|---|---|---|
     * | 音量面板浮在被拦应用上方 | 应用窗口（那个人）+ systemui 窗口 | 那个包**有**应用窗口 → true，且事件包名多为 systemui，会被系统包保险丝放行 | 不误拦 |
     * | 音量面板事件包名是那个应用 | 同上 | true | 判定为应用窗口 → 会拦（用户此时确实在按那个应用的音量键，是既有语义） |
     * | 最近任务（概览） | 桌面/launcher 的应用窗口 | true（launcher 在运行时白名单里） | 放行，能正常划掉后台 |
     * | 权限申请对话框 | 白名单应用的应用窗口 + permissioncontroller 窗口 | true | 放行，不会把申请权限的应用踢掉 |
     * | 事件包名是纯系统进程且它没有应用窗口 | 只有 systemui / 空 | false | 让路，**绝不执行「踢回桌面」** |
     *
     * 判定的四个判据按可靠性从高到低（都是刻意的）：
     *  1. **事件自己的 windowId 就是个应用窗口 → true**（最硬的证据）
     *  2. **等于最近一次确认的应用包名 → true**（同一应用的后续事件不必再查列表）
     *  3. **拿不到窗口列表 → true**（漏拦会让"锁机失效"，误拦只是少拦一次，
     *     方向不能反）
     *  4. 屏幕上确实有**别人的**应用窗口，而事件包名既不是它、也不是最近确认的
     *     应用 → false（来的是一个没有应用窗口的系统进程）
     *
     * ⚠️ 判据 4 是启发式，不是精确判定：`AccessibilityWindowInfo.getRoot()`
     * 需要 `canRetrieveWindowContent=true`（本项目刻意不开，见
     * accessibility_service_config.xml），因此窗口**所属包名**拿不到。
     * 但只要判据 1 能成立（窗口类型对得上号），就根本不会走到判据 4 ——
     * 走到那里时窗口类型必然未知，此时"宁可不拦"是唯一安全的方向。
     */
    private fun eventWindowMayBeApplication(
        pkg: String,
        event: AccessibilityEvent,
        windowList: List<AccessibilityWindowInfo>?
    ): Boolean {
        val claimed = pkg.trim()
        if (claimed.isEmpty()) return true

        // 判据一：事件自己指向的窗口就是应用窗口 —— 最硬的证据
        val windowId = event.windowId
        if (windowId >= 0) {
            val own = windowList?.firstOrNull { it.id == windowId }
            if (own != null) return own.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }

        // 判据二：与最近一次确认的应用窗口是同一个包 —— 后续事件不必再看列表
        if (claimed == lastApplicationPackage) return true

        // 判据三：拿不到窗口列表时保守地当成真实应用
        // （漏拦会让"锁机失效"，误拦只是少拦一次 —— 方向不能反）
        val list = windowList ?: return true
        if (list.isEmpty()) return true

        val appWindows = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

        // 判据四：屏幕上确实存在**别人的**应用窗口，而事件包名既不是它、
        // 也不是最近确认的那个应用 —— 说明这个事件来自一个没有应用窗口的进程
        // （systemui 的音量面板 / 权限控制器 / 关机菜单），不能当成应用窗口。
        val hasOtherAppWindow = appWindows.isNotEmpty() &&
            appWindows.none { it.id == windowId }
        val isDifferentApp = lastApplicationPackage.isNotBlank() &&
            lastApplicationPackage != claimed

        return !(hasOtherAppWindow && isDifferentApp)
    }

    // ---------------------------------------------------------------- 状态缓存

    /**
     * 更新**仅用于遮挡层文案**的缓存。
     *
     * ⚠️ 这里刻意不再缓存"是否锁机"：
     * 拦截判定必须用当次事件的新鲜状态（见 [handleWindowEvent]）。
     * 把布尔状态放进异步缓存会造成读取到旧值 —— 那正是修复前的第二个 bug。
     */
    private fun updateCache(status: WindowScheduler.LockStatus) {
        cachedRuleName = status.rule?.name.orEmpty()
        cachedMessage = status.rule?.message.orEmpty()
        // v2.2：解锁方法统一为"打卡（任何打卡方法都可以）或等时间走完"，
        // 所以遮挡层**总是**给出打卡入口（已废弃的 unlockMode 不再参与判断）。
        cachedShowCheckIn = status.rule != null
        cachedRuleId = status.rule?.id ?: 0L
        cachedSpotId = status.rule?.spotId
        cachedEndMinutes = status.minutesUntilChange
    }

    /** 服务刚连接时预热一次文案缓存（不参与拦截判定）。 */
    private fun primeCache() {
        scope.launch {
            runCatching { lockRuleRepository.currentStatus() }
                .onSuccess { updateCache(it) }
                .onFailure { Log.e(TAG, "预热状态缓存失败", it) }
        }
    }

    // ------------------------------------------------------------ 拦截动作

    /**
     * 执行拦截。
     *
     * **主机制是「踢回桌面」，不是盖遮挡层** —— 这是按用户要求做的方案调整。
     *
     * 为什么换掉：遮挡层的可见性依赖浮窗权限、窗口层级、以及我们自己添加窗口
     * 所产生的 `TYPE_WINDOWS_CHANGED` 事件干扰，环节多、失败面大（已经出过一次
     * "刚显示就被自己关掉"的事故）。而 `GLOBAL_ACTION_HOME` 只需要无障碍服务
     * 本身可用 —— 那是无论如何都必须具备的条件，路径最短、最可靠。
     *
     * 效果上等同于"禁止打开"：应用被切到前台后立刻被推出，
     * 反复打开就会被反复推出，实际不可用。
     *
     * 遮挡层退化为**兜底**：只有踢不回去时才显示。
     *
     * ⚠️ v2.7 起多一个 [exhaustion]：额度用尽时的**动作与锁机完全一样**
     * （同一个方法、同一条路径），只是提示文案不同。刻意不另做一套拦截 ——
     * 两套"踢回桌面"迟早会分叉，而其中一套坏掉时没人会发现。
     *
     * @param exhaustion 额度用尽的原因；为 null 表示这是锁机拦截
     * @param basis 判定依据（v2.14，[InterceptDecider.explainBlock] 的输出），
     *        写进自检快照，供用户反馈"为什么这个包被拦"
     */
    private fun enforceBlock(
        pkg: String,
        exhaustion: QuotaExhaustion? = null,
        basis: String = ""
    ) {
        val kicked = runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
            .onFailure { InterceptDiagnostics.onError("踢回桌面失败：${it.message}") }
            .getOrDefault(false)

        if (kicked) {
            InterceptDiagnostics.onBlocked(pkg, basis)
            // 踢回桌面后，遮挡层绝不能留在桌面上 ——
            // 用户明确要求：锁机时仍可正常使用系统桌面。
            clearOverlay()
            showBlockedToast(pkg, exhaustion)
        } else {
            // 兜底路径：踢不动才盖遮挡层
            InterceptDiagnostics.onBlocked(pkg, basis)
            showOverlay(pkg, exhaustion)
        }
    }

    /** 轻提示，告知被拦下来的应用与原因（尽力而为，失败不影响拦截）。 */
    private fun showBlockedToast(pkg: String, exhaustion: QuotaExhaustion? = null) {
        val label = runCatching { appLabelResolver.labelOf(pkg) }.getOrDefault(pkg)
        val text = exhaustion?.let { "「$label」使用时间已用完 · ${it.quotaName}" }
            ?: "锁机中：「$label」已被拦截"
        mainHandler.post {
            runCatching {
                Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
            }.onFailure { InterceptDiagnostics.onError("提示失败：${it.message}") }
        }
    }

    // ---------------------------------------------------------------- 遮挡层

    private fun showOverlay(packageName: String, exhaustion: QuotaExhaustion? = null) {
        val countdown = when {
            exhaustion != null -> "使用时间已用完"
            cachedEndMinutes > 0 -> "还有 $cachedEndMinutes 分钟"
            else -> "锁机中"
        }
        val message = exhaustion?.message
            ?: cachedMessage.ifBlank { "现在是锁机时段" }

        val shown = overlayController.show(
            content = OverlayController.Content(
                countdownText = countdown,
                message = message,
                showCheckIn = cachedShowCheckIn
            ),
            onCheckIn = { openApp() },
            onGoHome = {
                clearOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            onEmergencyRelease = {
                temporaryAllowList.grant(packageName, System.currentTimeMillis())
                clearOverlay()
                Log.i(TAG, "紧急放行 5 分钟: $packageName")
            }
        )

        if (shown) {
            overlayPackage = packageName
        } else {
            // 没有悬浮窗权限（或添加窗口失败）时，遮挡层这条路彻底不可用。
            // 记录下来让「拦截自检」页明确告诉用户该去补哪个权限。
            InterceptDiagnostics.onError(
                "遮挡层显示失败：请检查「显示在其他应用上层」权限"
            )
        }
    }

    /** 收起遮挡层并清除其状态。 */
    private fun clearOverlay() {
        if (overlayController.isShowing) overlayController.hide()
        overlayPackage = null
    }

    private fun openApp() {
        clearOverlay()
        val ruleId = cachedRuleId
        val spotId = cachedSpotId
        // 有打卡点就直接跳到打卡页，否则回首页。
        //
        // v2.2：打卡点归**习惯的打卡条件**管，规则上的 spotId 只是历史值，
        // 新配置通常为 null —— 那时回首页，用户在今日列表点一下（或去打卡点）即可。
        val intent = if (ruleId > 0 && spotId != null) {
            MainActivity.checkInIntent(this, ruleId, spotId)
        } else {
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.e(TAG, "打开打卡页失败", it) }
    }

    companion object {
        private const val TAG = "LockAccessibilityService"

        /**
         * 额度看门狗的检查间隔（毫秒）。
         *
         * 30 秒：对"分钟级"的额度完全够用（最坏多给用户 30 秒），
         * 而每轮只是一次走索引的区间查询 —— 比为了秒级精度去引入
         * AlarmManager / WorkManager 划算得多（也避免新增权限）。
         */
        const val QUOTA_TICK_MILLIS = 30_000L

        /**
         * 包缓存"曾为空"时的重试间隔（v2.14，毫秒）。
         *
         * 只在 `systemAppPackages` 为空时才会真正触发重算，正常路径上一次判断就返回，
         * 所以这个值取 60 秒即可 —— 它的作用是把"一次失败的枚举"从
         * "整段会话都失效"压缩成"最多 60 秒后自愈"。
         */
        const val PACKAGE_CACHE_RETRY_MILLIS = 60_000L

        /** 无障碍服务在系统设置里的完整组件名。 */
        fun componentName(packageName: String): String = "$packageName/$packageName.service.LockAccessibilityService"
    }
}
