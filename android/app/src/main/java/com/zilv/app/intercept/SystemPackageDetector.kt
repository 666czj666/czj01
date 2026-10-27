package com.zilv.app.intercept

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.view.inputmethod.InputMethodManager
import com.zilv.app.domain.intercept.AlwaysAllowPackages
import com.zilv.app.domain.intercept.InterceptDecider

/**
 * 系统必需包的**运行时探测**（PRD T4.1）。
 *
 * ⚠️ 这是整个项目最容易埋雷的地方：桌面和输入法的包名各厂商 ROM 完全不同
 * （小米 `com.miui.home`、华为 `com.huawei.android.launcher` ……），
 * **写死任何一个包名都会在别的机器上把用户锁死**。
 *
 * 因此：
 *  - 本类在运行时通过系统 API 探测真实包名
 *  - [InterceptDecider.CRITICAL_SYSTEM_PACKAGES] 提供兜底清单
 *  - 两者叠加，任何一环失效都还有另一环
 *
 * v2.13 追加了第三种探测：[detectSystemPackages] ——
 * 直接枚举 `FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP` 的**全部**系统应用。
 * 前两种（写死清单 + 按 Intent 探测桌面/输入法）覆盖的是"手机可用性"，
 * 这一种覆盖的是"锁机时冒出来的系统浮层"（音量面板 / 最近任务 / 权限对话框），
 * 那批组件既不在清单里、也没有可探测的 Intent。
 *
 * v2.14 追加第四种：[detectAlwaysAllowByIntent] ——
 * 覆盖"系统界面组件 / 截屏 / 智能助理"。这一批是**枚举与手写清单都覆盖不到的那类**：
 *  - 智能助理常常是**商店安装**的应用（`com.google.android.googlequicksearchbox`），
 *    没有任何 system flag，`detectSystemPackages()` 永远拿不到它
 *  - 厂商的 SystemUI 拆出来的独立包，名字无法穷举
 * 于是改成**向系统问**：`Intent.ACTION_ASSIST` 现在指向谁、
 * `Settings.Secure` 的 `assistant` 键是谁、已安装的包里哪些以 `systemui` 结尾。
 *
 * v2.15 追加第五种：[detectLaunchablePackages] —— 枚举**有桌面启动入口**的包。
 * 这一种和前四种的**思路完全不同**：前四种都在回答"这个包像不像系统组件"
 * （于是只能枚举 / 猜包名，永远追不上厂商变体），这一种回答的是
 * "用户到底能不能打开它"。音量面板 / 截屏浮层 / 分享面板都打不开，
 * 所以不拦；微信 / 抖音 / 游戏都打得开，所以照拦 —— 判据不依赖任何包名清单。
 */
class SystemPackageDetector(private val context: Context) {

    private val pm: PackageManager get() = context.packageManager

    /** 探测到的一条内置白名单项。 */
    data class Detected(
        val packageName: String,
        val label: String,
        val category: String
    )

    /**
     * 枚举**全部系统应用**的包名（v2.13）。
     *
     * ## 为什么需要它
     *
     * v2.10 靠"窗口类型判定"去修"锁机时调音量会把用户踢回桌面、退出软件后划不掉后台"，
     * 但真机没生效 —— 那个判据在多数 ROM 上取不到证据。用户给的方向更可靠：
     * **把系统软件全部放进默认白名单**。
     *
     * 音量面板、最近任务（概览）、权限对话框、安装器、各厂商的"安全中心 / 手机管家"
     * 这类**没有应用窗口**却会在锁机时冒出来的组件，全都带
     * [`ApplicationInfo.FLAG_SYSTEM`] 或 [`ApplicationInfo.FLAG_UPDATED_SYSTEM_APP`]。
     * 与其手写一张永远不全的包名清单（各厂商 ROM 千差万别），不如直接问系统。
     *
     * ## 为什么用 `FLAG_UPDATED_SYSTEM_APP` 也算
     *
     * 预装应用通过应用商店更新后，`FLAG_SYSTEM` 会被清掉、改由
     * `FLAG_UPDATED_SYSTEM_APP` 标记（典型：Play 商店更新的系统应用）。
     * 只认前者会让"更新过的系统组件"在锁机时被拦，正好回到这次要修的 bug。
     *
     * ## 失败方向
     *
     * 查询失败（被 ROM 限制、包管理器异常）返回**空集合** ——
     * 判定随即退化为 v2.12 的行为（只靠兜底清单 + 白名单），
     * 而不是抛异常把拦截链路一起带崩。
     *
     * ⚠️ 这个函数**不能在热路径上调用**：`getInstalledApplications` 是跨进程调用，
     * 几百个包要几十毫秒。调用方（无障碍服务）只在连接时算一次并缓存，
     * 收到安装 / 卸载 / 覆盖安装广播时再失效重算。
     */
    fun detectSystemPackages(): Set<String> = runCatching {
        pm.getInstalledApplications(0)
            .filter { isSystemAppFlags(it.flags) }
            .map { it.packageName }
            .toSet()
    }.getOrElse { emptySet() }

    /** 已安装的全部包名；查询失败返回空集合（调用方按"没探到"处理）。 */
    private fun installedPackageNames(): Set<String> = runCatching {
        pm.getInstalledApplications(0).map { it.packageName }.toSet()
    }.getOrElse { emptySet() }

    /**
     * 枚举**有启动入口**的包名（v2.15）——「只拦能启动的应用」这条规则的唯一数据源。
     *
     * ## 它回答的问题
     *
     * 「用户能不能'打开'这个包？」——等价于
     * `PackageManager.getLaunchIntentForPackage(pkg) != null`。
     * 判据要解决的是用户报了四次的真机 bug：锁机时调音量 / 截屏 / 唤醒助理 → 被拦截。
     * 那几个包（厂商把状态栏、音量面板、截屏浮层拆出来的独立包）**没有启动入口**，
     * 用户永远打不开它们，拦它们毫无意义；而用户真正会打开的应用
     * （微信 / 抖音 / 游戏）**都有启动入口**。完整论证见
     * [com.zilv.app.domain.intercept.InterceptDecider.hasNoLaunchEntry]。
     *
     * ## 为什么用 `queryIntentActivities(CATEGORY_LAUNCHER)` 而不是逐个包问
     *
     * `getLaunchIntentForPackage` 一次只能问一个包。已安装应用通常有 300 个以上，
     * 逐包询问 = 300+ 次跨进程调用；本函数在 `onServiceConnected()`（**主线程**）上跑，
     * 那样会明显拖慢服务连接，极端情况下 ANR。
     * 而 `ACTION_MAIN / CATEGORY_LAUNCHER` 的**一次查询**就拿到了"有桌面启动入口"
     * 的全部包 —— 这正是判据需要的那个集合（用户点开应用的唯一入口就是桌面图标）。
     *
     * 少覆盖到的是"没有桌面图标、但被别的应用拉起"的极少数应用：它们会被算成
     * 不可启动 → 放行。这个方向是**故意选的**：漏拦一个用户打不开的应用，
     * 代价远小于再把音量面板拦一次（前者用户永远看不见，后者用户立刻被踢出桌面）。
     *
     * 两种写法都不需要任何 `uses-permission`
     * （`QUERY_ALL_PACKAGES` 早已为白名单选择器声明过，本次没有新增）。
     *
     * ## 失败方向
     *
     * 查询失败（ROM 限制 / 包管理器异常）返回**空集合**，含义是"无从判断"。
     * 调用方（`InterceptDecider.hasNoLaunchEntry`）对空集合的处理是
     * **本规则不生效、退化为旧行为**（绝不反过来把所有应用都放行 ——
     * 那等于锁机失效）。自检页会把这个状态显示成「可启动应用 0 个」。
     *
     * ⚠️ 与 [detectSystemPackages] 一样**不能在热路径上调用**：调用方只在服务连接时
     * 算一次并缓存，收到安装 / 卸载 / 覆盖安装广播时再失效重算。
     */
    fun detectLaunchablePackages(): Set<String> = runCatching {
        pm.queryIntentActivities(launcherIntent(), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filter { it.isNotBlank() }
            .toSet()
    }.getOrElse { emptySet() }

    /** 「桌面启动入口」Intent：`ACTION_MAIN` + `CATEGORY_LAUNCHER`（见 [detectLaunchablePackages]）。 */
    private fun launcherIntent(): Intent =
        Intent(LAUNCHER_ACTION).addCategory(LAUNCHER_CATEGORY)

    /**
     * 探测**必须无条件放行**的关键组件（v2.14）：系统界面组件 / 截屏 / 智能助理。
     *
     * 四路证据**取并集**（任何一路命中就够，因为漏放行的代价是"手机看起来坏了"）：
     *
     * | 路 | 手段 | 覆盖什么 | 要权限吗 |
     * | --- | --- | --- | --- |
     * | ① | [AlwaysAllowPackages.ALL] ∩ 已安装 | 已知包名（各厂商变体） | 否 |
     * | ② | `Intent.ACTION_ASSIST` / `ACTION_VOICE_COMMAND` 的 `queryIntentActivities` + `resolveActivity` | **当前真正接智能助理的应用**（含商店安装） | 否 |
     * | ③ | `Settings.Secure` 的 `assistant` 键 | 用户设的默认助理（比 ② 更直接） | 否（读 secure 不需要权限，写才需要） |
     * | ④ | 已安装包里**最后一段是 `systemui`** 的 | **未知的**厂商 SystemUI 变体 | 否 |
     *
     * ## 为什么"截屏"没有 Intent 探测
     *
     * Android **没有公开的截屏 action**（截屏是系统功能，AOSP 的
     * `TakeScreenshotService` 就跑在 `com.android.systemui` 里）。
     * 所以截屏只能靠：SystemUI 族（①④）+ 显式清单（①）+ 系统应用枚举。
     * 这一点在 [AlwaysAllowPackages.SCREENSHOT] 的注释里写清了，不做假探测。
     *
     * ## 失败方向
     *
     * 任何一步失败都只是**少一条证据**，不会抛异常（拦截链路不能被探测失败拖崩）。
     */
    fun detectAlwaysAllowByIntent(): Set<String> {
        val installed = installedPackageNames()
        val result = linkedSetOf<String>()

        // ① 显式清单里**确实装了**的（没装的包名加进来毫无意义，还会让自检页的计数虚高）
        installed.filterTo(result) { it in AlwaysAllowPackages.ALL }

        // ② 当前智能助理：向系统问"谁在接 ASSIST / VOICE_COMMAND"
        AlwaysAllowPackages.ASSIST_ACTIONS.forEach { action ->
            runCatching {
                queryIntentPackages(Intent(action))
                    .filterNot { isSystemChooser(it) }
                    .forEach { result += it }
            }
            resolveActivityPackage(Intent(action))
                ?.takeUnless { isSystemChooser(it) }
                ?.let { result += it }
        }

        // ③ 系统设置里记录的默认助理（扁平组件名 → 包名）
        defaultAssistantPackage()?.let { result += it }

        // ④ 未知的厂商 SystemUI 变体：按"最后一段 == systemui"的族判定
        installed.filterTo(result) { AlwaysAllowPackages.isSystemUiFamily(it) }

        return result
    }

    /**
     * 解析一个隐式 Intent 会落到哪个包。
     *
     * 失败（没有应用能接、ROM 限制查询）返回 null —— 调用方按"没探到"处理。
     * 刻意**不用** `resolveActivity` 的 ActivityInfo 判空后强转：
     * `MATCH_DEFAULT_ONLY` 下返回的可能是系统的"选择器"，那由 [isSystemChooser] 过滤。
     */
    private fun resolveActivityPackage(intent: Intent): String? = runCatching {
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }.getOrNull()

    /**
     * 系统"选择器 / Resolver"这类中介包。
     *
     * 如果把 `com.android.intentresolver` 当成智能助理加进放行集合，
     * 就等于把"任何隐式 Intent 的选择框"放行 —— 那是系统组件，本来也不该被拦，
     * 但它**不是**我们想找的那个助理，记进名单只会误导诊断。
     */
    private fun isSystemChooser(packageName: String): Boolean = packageName in SYSTEM_CHOOSER_PACKAGES

    /** 系统设置里"默认智能助理"的包名（`Settings.Secure` 的 `assistant` 键）。 */
    private fun defaultAssistantPackage(): String? = runCatching {
        Settings.Secure.getString(
            context.contentResolver,
            AlwaysAllowPackages.SECURE_ASSISTANT_KEY
        )
            ?.substringBefore('/')
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
    }.getOrNull()


    /**
     * 探测全部内置强制白名单项。
     *
     * 结果会去重；同一包名可能同时命中多个类别（例如某些 ROM 的桌面兼任电话）。
     */
    fun detectBuiltins(): List<Detected> {
        val result = linkedMapOf<String, Detected>()

        fun add(pkg: String?, label: String, category: String) {
            if (pkg.isNullOrBlank()) return
            result.putIfAbsent(pkg, Detected(pkg, label, category))
        }

        // 1) 兜底清单里的关键系统包（可能查不到 label，用包名兜底）
        InterceptDecider.CRITICAL_SYSTEM_PACKAGES.forEach { pkg ->
            add(pkg, safeLabel(pkg) ?: pkg, CATEGORY_CRITICAL)
        }

        // 2) 桌面 Launcher —— 各厂商包名不同，必须探测
        queryIntentPackages(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        ).forEach { add(it, safeLabel(it) ?: it, CATEGORY_LAUNCHER) }

        // 3) 输入法 —— 必须探测，且用户可能装了多个
        detectInputMethods().forEach { add(it, safeLabel(it) ?: it, CATEGORY_IME) }

        // 4) 电话 / 拨号
        queryIntentPackages(Intent(Intent.ACTION_DIAL))
            .forEach { add(it, safeLabel(it) ?: it, CATEGORY_PHONE) }

        // 5) 短信
        queryIntentPackages(Intent(Intent.ACTION_VIEW).setType("vnd.android-dir/mms-sms"))
            .forEach { add(it, safeLabel(it) ?: it, CATEGORY_SMS) }
        safeDefaultSmsPackage()?.let { add(it, safeLabel(it) ?: it, CATEGORY_SMS) }

        // 6) 相机
        queryIntentPackages(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            .forEach { add(it, safeLabel(it) ?: it, CATEGORY_CAMERA) }

        // 7) 地图（geo: 协议）
        queryIntentPackages(Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("geo:0,0")))
            .forEach { add(it, safeLabel(it) ?: it, CATEGORY_MAPS) }

        // 8) v2.14：系统界面组件 / 截屏 / 智能助理 —— 用户明确要求"加入默认名单"。
        //
        // 只加**已安装且能查到标签**的：没装的包名列进白名单页毫无意义
        // （用户会看到一串看不懂的包名），而拦截判定根本不依赖这一份
        // （`InterceptDecider` 用的是 `AlwaysAllowPackages` + 运行时探测集合）。
        runCatching { detectAlwaysAllowByIntent() }.getOrElse { emptySet() }
            .sorted()
            .forEach { add(it, safeLabel(it) ?: it, CATEGORY_PROTECTED) }

        return result.values.sortedWith(compareBy({ it.category }, { it.label }))
    }

    private fun detectInputMethods(): List<String> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return emptyList()
        // enabledInputMethodList 覆盖已启用但可能未选中的输入法；
        // 用户可能在不同场景切换，所以全部放行
        return runCatching { imm.enabledInputMethodList.map { it.packageName } }
            .getOrElse { emptyList() }
    }

    private fun safeDefaultSmsPackage(): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()

    private fun queryIntentPackages(intent: Intent): List<String> =
        runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
        }.getOrElse { emptyList() }

    private fun safeLabel(pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    // ------------------------------------------------------------ 白名单选择器

    /**
     * 已安装的**可启动**应用列表，供白名单选择器使用。
     *
     * 注意：Android 11+ 需要 `QUERY_ALL_PACKAGES` 才能枚举全部应用。
     * 这是产品功能的硬需求（白名单制的本质就是"从全部应用里挑"），
     * 已在 AndroidManifest 中显式声明并注明原因。
     *
     * ⚠️ 与 [detectLaunchablePackages]（v2.15，拦截判定用）查的是**同一个 Intent**，
     * 但两者**不能合并**：这里要的是"带标签、有排序、去掉自己"的展示数据，
     * 那边要的是"纯粹用于判定的包名集合"。合并会让其中一方的需求悄悄改掉另一方。
     */
    fun installedLaunchableApps(): List<Detected> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(launcherIntent, 0)
        }.getOrElse { emptyList() }

        return resolved
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg -> Detected(pkg, safeLabel(pkg) ?: pkg, CATEGORY_USER) }
            .sortedBy { it.label }
    }

    /** 判断某包名是否为系统应用（用于 UI 分组展示）。 */
    fun isSystemApp(packageName: String): Boolean = runCatching {
        val info: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
        // v2.13：与 [detectSystemPackages] 用**同一个判据** ——
        // 两处口径不一致会导致"界面上算系统应用、拦截时却不算"这种诡异现象
        isSystemAppFlags(info.flags)
    }.getOrDefault(false)

    companion object {
        /**
         * 「桌面启动入口」Intent 的 action（v2.15）。
         *
         * 刻意写成**字面值常量**而不是直接用 `Intent.ACTION_MAIN`：这样
         * 「用什么判据判定可启动」这件事本身就能被 JVM 单元测试钉住
         * （见 `SystemPackageDetectorTest` 里"常量与框架取值一致"那一条）。
         * 写错一个字符 = 查询永远返回 0 个包 = 「只拦可启动应用」这条规则
         * 静默失效（虽然不会误拦，但这次修复就等于没做）。
         */
        const val LAUNCHER_ACTION = "android.intent.action.MAIN"

        /** 「桌面启动入口」Intent 的 category（v2.15），取值与框架常量一致。 */
        const val LAUNCHER_CATEGORY = "android.intent.category.LAUNCHER"

        const val CATEGORY_CRITICAL = "关键系统"

        /** v2.14：系统界面组件 / 截屏 / 智能助理 —— 用户要求"加入默认名单"的那一类。 */
        const val CATEGORY_PROTECTED = "关键组件"
        const val CATEGORY_LAUNCHER = "桌面"
        const val CATEGORY_IME = "输入法"
        const val CATEGORY_PHONE = "电话"
        const val CATEGORY_SMS = "短信"
        const val CATEGORY_CAMERA = "相机"
        const val CATEGORY_MAPS = "地图"
        const val CATEGORY_USER = "应用"

        /**
         * 系统的"选择器 / Resolver"包：隐式 Intent 没有明确处理者时由它们兜住。
         *
         * 探测智能助理时必须把这类包排除：它们不是助理，
         * 记进放行集合只会让自检页的计数与诊断文案产生误导。
         */
        val SYSTEM_CHOOSER_PACKAGES: Set<String> = setOf(
            "android",
            "com.android.intentresolver",
            "com.android.internal.app",
            "com.google.android.apps.wellbeing" // 少数 ROM 的助手中介
        )

        /**
         * 这条应用信息是不是"系统应用"（见上面那段 KDoc）。
         *
         * 纯函数、不碰 `PackageManager`/`Context`，所以单元测试可以直接调用
         * `SystemPackageDetector.isSystemAppFlags(flags)` 验证 —— 不需要真机，
         * 也不需要 Robolectric。
         *
         * 判据用**位与**而不是等值比较：真机上系统应用的 flags 几乎总是
         * `FLAG_SYSTEM | FLAG_INSTALLED | …` 这种组合，写成 `== FLAG_SYSTEM`
         * 会让几乎所有系统应用漏判（→ 又回到"调音量被踢回桌面"）。
         */
        fun isSystemAppFlags(flags: Int): Boolean =
            (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
}
