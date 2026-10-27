package com.zilv.app.domain.intercept

/**
 * 拦截判定（PRD §3.3 L1 / §3.4）。
 *
 * 抽成纯函数是为了能用单元测试钉死"什么该拦、什么绝对不能拦" ——
 * 后者尤其重要：一旦拦到系统界面或权限对话框，用户的手机可能直接不可用。
 */
object InterceptDecider {

    /**
     * 兜底的**关键系统包**清单。
     *
     * 这些包即使因为探测失败而没进白名单，也**永远放行**。
     * 这是"不把手机搞坏"的最后一道保险。
     *
     * ⚠️ v2.14 起，"系统界面组件 / 截屏 / 智能助理"这一整类搬到了**同级**的
     * [AlwaysAllowPackages]（用户明确要求加进默认名单），原因见那边：它们
     * 既有厂商变体（需要"族判定"）又有商店渠道安装（没有任何 system flag），
     * 混在这张"精确包名"清单里会让人误以为覆盖到了。
     * 本清单继续只放"系统对话框 / 系统窗口的宿主"这一类**必须精确匹配**的包。
     */
    val CRITICAL_SYSTEM_PACKAGES: Set<String> = setOf(
        "android",                                   // 系统框架
        "com.android.systemui",                      // 状态栏 / 通知栏 / 最近任务
        "com.android.settings",                      // 系统设置（用户明确要求始终可用）
        "com.android.permissioncontroller",          // 权限对话框
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",              // 安装器
        "com.google.android.packageinstaller",
        "com.android.server.telecom",                // 来电
        "com.android.incallui",                      // 通话界面
        "com.android.phone",                         // 电话
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.mms",                           // 短信
        "com.google.android.apps.messaging",
        "com.android.emergency",                     // 紧急呼叫
        // ---- v2.10 补：厂商 ROM 上的系统界面变体 ----
        //
        // ⚠️ 这些不是"顺手加的"：音量面板、关机菜单、最近任务、权限对话框、
        // 安装器在各厂商 ROM 上**不在** com.android.systemui 里，
        // 而是各自独立的系统包。它们一旦被拦，用户看到的是
        // "调个音量手机就跳回桌面"（见 LockAccessibilityService 的窗口判定注释）。
        //
        // 收录标准（刻意收得很紧，只放"系统对话框 / 系统窗口的宿主"）：
        //  - **权限对话框 / 安装器**：白名单应用申请权限时，对话框属于这些包，
        //    拦了它们就等于把正在申请权限的应用踢掉（用户报的"闪退"）
        //  - 系统自带的**普通应用**（相册、浏览器、小米的"安全中心"主界面等）
        //    刻意不在此列 —— 那些是用户可以正常被拦的对象。
        //
        // 真正兜住"音量面板 / 最近任务 / 关机菜单"的是**窗口类型判定**
        // （InterceptDecider.analyzeWindowForPackage + WINDOW_TYPE_SYSTEM），
        // 这里只是把"系统对话框宿主"这类**没有应用窗口**的包补全。
        "com.android.permissioncontroller.auto",     // 车机 ROM 变体
        "com.samsung.android.permissioncontroller",  // 三星
        "com.transsion.permissioncontroller",        // 传音
        "com.huawei.systemmanager",                  // 华为 / 荣耀：权限对话框与弹窗
        "com.hihonor.systemmanager",                 // 荣耀（分拆后的包名）
        "com.coloros.securitypermission",            // OPPO / 一加
        "com.oppo.securitypermission",
        "com.oplus.securitypermission",              // OPPO 新包名
        "com.vivo.permissionmanager",                // vivo / iQOO
        "com.bbk.securitypermission",
        "com.iqoo.securitypermission",
        "com.lbe.security.miui",                     // 小米：权限管理对话框
        "com.android.packageinstaller.auto",
        "com.sec.android.app.packageinstaller",      // 三星安装器
        "com.miui.packageinstaller",                 // 小米安装器
        "com.huawei.packageinstaller"
    )

    sealed interface Decision {
        /** 放行 */
        data object Allow : Decision

        /** 拦截，并给出被拦的包名 */
        data class Block(val packageName: String) : Decision
    }

    /**
     * 判定顺序（**从最优先到最次**）。
     *
     * 这是一份刻意写下来的优先级清单：每一条都对应一类"绝不能拦"或
     * "必须拦"的场景，顺序错了就会有用户可见的坏事发生
     * （最严重的是把用户困在遮挡层里出不去）。
     *
     * | 序 | 条件 | 结果 | 为什么必须在这个位置 |
     * |---|---|---|---|
     * | 1 | 包名取不到 | 放行 | 信息缺失时"宁可漏拦也不能卡死用户" |
     * | 2 | 是自己 | 放行 | 否则用户打不了卡、进不了 App |
     * | 3 | [CRITICAL_SYSTEM_PACKAGES] | 放行 | **手机可用性的底线**：设置 / 权限对话框 / 来电 / 紧急呼叫 |
     * | 3.5 | [AlwaysAllowPackages]（系统界面组件 / 截屏 / 智能助理，含 SystemUI 族） | 放行 | v2.14 新增：这三类既可能是厂商变体、也可能是商店安装的应用（无系统标志） |
     * | 4 | 在**系统应用集合**里（运行时枚举的 `FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP` 包） | 放行 | v2.13 新增：音量面板 / 最近任务 / 权限对话框 / 厂商系统组件全在这一支 |
     * | 4.5 | 在**运行时按 Intent 探测**到的关键组件里（当前智能助理等） | 放行 | v2.14 新增：问系统"谁在接 ASSIST"，比猜包名可靠 |
     * | 5 | 在白名单（全局 ∪ 规则级 ∪ 运行时探测的桌面/输入法） | 放行 | 按 Home 必须能回桌面；桌面包名各厂商不同，只能靠白名单 |
     * | 5.5 | **没有启动入口**（[hasNoLaunchEntry]，见下方 v2.15 说明） | 放行 | v2.15 新增：音量面板 / 截屏浮层 / 分享面板等系统组件根本没有启动入口，拦它们毫无意义，却正是反复误拦的来源 |
     * | 6 | 额度未用尽 且 未锁机 | 放行 | 两条拦截理由都不成立 |
     * | 7 | 额度用尽 | **拦截** | v2.7 新增：额度为零 / 用完即禁用指定应用 |
     * | 8 | 锁机态且不在白名单 | **拦截** | 原有行为（PRD §3.3 L1） |
     *
     * ⚠️ 第 3～5.5 条**必须在第 7/8 条之前，且必须无条件生效** ——
     * 该顺序是"不把用户卡死"的关键：
     *  - 额度耗尽的应用可能恰好是**桌面**（用户自己把桌面绑进了额度），
     *    或者锁机期间用户按 Home 要回桌面、要进系统设置；
     *  - 若让"额度用尽"先于白名单判定，用户就会被反复踢出桌面，
     *    表现为"手机没法用了" —— 这正是本项目历史上踩过的坑
     *    （遮挡层盖住桌面，用户被困）。
     *  - 因此额度只能拦"本来就被允许打开"的应用之外的那些。
     *
     * 第 2/3/4/5/7 条与"是否处于锁机态"无关：额度用尽时的禁用**不依赖锁机**，
     * 这是用户要求"时间额度为零时禁用指定软件"的直接落地
     * （用户没有配任何锁机规则，额度也必须生效）。
     *
     * @param foregroundPackage 无障碍服务报告的前台包名
     * @param selfPackage 本应用包名（自己永远放行，否则无法打卡）
     * @param allowList 有效白名单（全局 ∪ 规则级，应已包含运行时探测到的桌面/输入法等）
     * @param locked 当前是否处于锁机态
     * @param quotaExhaustedPackages 「软件使用时间限时」里**本周期额度已用尽**的包名。
     *        默认空集合 ⇒ 没有任何额度限制，行为与 v2.6 完全一致
     *        （既有调用点与既有测试因此一行都不用改）。
     * @param systemPackages **运行时枚举到的系统应用包名**（见下方 v2.13 说明）。
     *        默认空集合 ⇒ 行为与 v2.12 逐字相同（既有调用点与既有测试因此一行都不用改）。
     *        ⚠️ 这个集合**只能装系统应用**：把用户应用放进来就等于把它们永久放行。
     * @param alwaysAllowPackages **运行时探测到的关键组件包名**（v2.14）：
     *        当前被设为智能助理的应用（`Intent.ACTION_ASSIST` / `Settings.Secure`）、
     *        厂商截屏应用等，见 `SystemPackageDetector.detectAlwaysAllowByIntent`。
     *        与 [systemPackages] 分开是因为**这一类里可以有商店安装的应用**
     *        （Google 助理就没有任何 system flag），混进"系统应用"会让人误解口径。
     *        默认空集合 ⇒ 既有调用点行为不变。
     * @param launchablePackages **运行时枚举到的「有启动入口」的包名**（v2.15）。
     *        判据见 [hasNoLaunchEntry]：不在这个集合里（且集合非空）= 没有启动入口 =
     *        **一律放行**。音量面板、截屏浮层、智能助理悬浮层、分享面板都属于这一类。
     *        ⚠️ **默认空集合 ⇒ 本规则不生效，行为与 v2.14 逐字相同** ——
     *        空集合的语义是"枚举没成功，无从判断"，绝不能反过来理解成
     *        "所有包都没有启动入口"（那等于把锁机整体关掉）。
     */
    fun decide(
        foregroundPackage: String?,
        selfPackage: String,
        allowList: Set<String>,
        locked: Boolean,
        quotaExhaustedPackages: Set<String> = emptySet(),
        systemPackages: Set<String> = emptySet(),
        alwaysAllowPackages: Set<String> = emptySet(),
        launchablePackages: Set<String> = emptySet()
    ): Decision {
        val pkg = foregroundPackage?.trim().orEmpty()

        // 1. 拿不到包名时不拦 —— 宁可漏拦，也不能因为信息缺失把用户卡死
        if (pkg.isEmpty()) return Decision.Allow

        // 2. 自己永远放行（打卡入口）
        if (pkg == selfPackage) return Decision.Allow

        // 3. 关键系统包保险丝：即使白名单里没有也必须放行
        if (pkg in CRITICAL_SYSTEM_PACKAGES) return Decision.Allow

        // 3.5 v2.14：系统界面组件 / 截屏 / 智能助理（显式清单 + SystemUI 族）。
        //     用户明确要求这三个进默认名单；它们既可能是厂商变体（包名未知，
        //     靠"最后一段 == systemui"的族判定），也可能是商店安装的应用
        //     （没有任何 system flag，枚举拿不到）。详见 [AlwaysAllowPackages]。
        if (AlwaysAllowPackages.isAlwaysAllowed(pkg)) return Decision.Allow

        // 4. 系统应用（v2.13）：运行时枚举的 FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP 包。
        //    这一支是"调音量被踢回桌面 / 划不掉后台"这类真机 bug 的**根本修法**：
        //    不再靠手写清单去猜厂商包名，而是问系统"哪些包是系统应用"。
        if (pkg in systemPackages) return Decision.Allow

        // 4.5 v2.14：运行时**按 Intent 探测**到的关键组件（当前智能助理等）。
        //     比猜包名可靠：问的是系统"现在谁负责接 ASSIST"，而不是我们的清单。
        if (pkg in alwaysAllowPackages) return Decision.Allow

        // 5. 白名单内的应用放行。
        //    ⚠️ **无条件**放行（不看 locked、也不看额度）：
        //    桌面与输入法就在白名单里，这一步是"按 Home 能回桌面"的保证。
        if (pkg in allowList) return Decision.Allow

        // 5.5 v2.15：**没有启动入口的包一律放行** —— 本次的主交付。
        //     判据是"这个包有没有启动入口"，不是"包名像不像系统组件"，
        //     所以不依赖任何手写清单，也就不会被厂商变体绕开（详见 [hasNoLaunchEntry]）。
        //     ⚠️ 位置刻意在额度 / 锁机之前：它们是"能不能打开"的判据，
        //     而这里问的是"它到底有没有'打开'这回事" —— 后者更根本。
        if (hasNoLaunchEntry(pkg, launchablePackages)) return Decision.Allow

        // 6. 额度用尽 —— 与锁机无关，独立成立
        if (pkg in quotaExhaustedPackages) return Decision.Block(pkg)

        // 7. 没在拦截态，一律放行
        if (!locked) return Decision.Allow

        // 8. 锁机态下的兜底：不在白名单就拦
        return Decision.Block(pkg)
    }

    // ========================================================================
    // v2.15：只拦「能启动的应用」 —— 从根上消除"系统浮层被误拦"
    // ========================================================================
    //
    // ## 这一版修的是什么
    //
    // 用户第 4 次报同一个问题：「调节音量还是被拦截，拦截日志显示系统界面组件被拦截」。
    // 前三次（v2.10 窗口类型判定、v2.13 系统应用枚举、v2.14 关键组件清单 + Intent 探测）
    // 都**没能修掉**，原因可以从代码里直接推出来：
    //
    //   一个包能走到"被拦"，说明它**通过了**前面所有放行判定 ——
    //   即：它既不是 SystemUI 族、也没有 system flag、也不在用户白名单里。
    //   也就是说，这三次修复改的都是"给它找一个放行的理由"，
    //   而厂商把状态栏 / 音量面板 / 截屏 / 智能助理拆成的那些独立包，
    //   恰恰是**既没有系统标志、包名也不在清单里**的那一类 —— 手写清单永远追不上。
    //
    // ## 于是换判据：不问"你是不是系统组件"，问"你有没有启动入口"
    //
    // 判据：`PackageManager.getLaunchIntentForPackage(pkg) == null`（等价地：
    // 不在 `queryIntentActivities(ACTION_MAIN / CATEGORY_LAUNCHER)` 的结果里）
    // ⇒ 这个包**没有启动入口** ⇒ 不该拦它。
    //
    // 理由（三条，缺一条这个判据都不成立）：
    //
    //  1. **没有启动入口的包，用户根本"打不开"它。**
    //     音量面板、截屏浮层、智能助理的悬浮层、分享面板、输入法设置后台服务……
    //     全都是**被系统或其他应用拉起**的窗口宿主 —— 它们不是应用意义上的"应用"。
    //     对一个用户无法打开的东西执行"踢回桌面"，唯一的效果就是**打断用户**，
    //     而它本身没有任何"被限制"的意义可言。这正是反复误拦的来源。
    //
    //  2. **用户真正会打开、真正需要被拦的应用，全都有启动入口。**
    //     微信、抖音、游戏、购物……它们必须能被用户从桌面点开，否则没有存在的意义。
    //     所以这条规则**不会把锁机改松**（"可启动 + 不在白名单"的应用照样拦，
    //     单测 `可启动但不在白名单的应用仍然被拦` 把这一点钉死）。
    //
    //  3. **它不依赖任何包名清单**，因此**不受厂商变体影响**。
    //     v2.10 / v2.13 / v2.14 之所以修不掉，根子就在于它们都在"枚举/猜包名"：
    //     每换一个 ROM、每拆一个新组件，清单就漏一个。而"有没有启动入口"
    //     是 `PackageManager` 的**事实**，不随厂商命名习惯变化。
    //
    // ## 兜底方向（很重要，不能反过来）
    //
    // 判据依赖一个"可启动包集合"，它由服务在连接时枚举并缓存。
    // 如果枚举**失败**（ROM 限制、开机早期 PackageManager 还没扫完），集合是空的。
    // 此时 **绝不能**理解成"所有包都没有启动入口" —— 那等于把所有应用都放行，
    // 锁机直接失效。正确方向是：**集合为空 ⇒ 无法证明谁不可启动 ⇒ 本规则不生效，
    // 退化为旧行为**（[hasNoLaunchEntry] 在空集合上一律返回 false）。
    // 这个状态会被写进诊断（「可启动应用 0 个」），不会被悄悄吞掉。
    //
    // ## 与其它放行判定的关系
    //
    // 它不是替代关系，而是**又一道独立保险**：
    //  - [CRITICAL_SYSTEM_PACKAGES] 保"手机不会坏"（设置 / 来电 / 权限对话框）
    //  - 系统应用枚举、[AlwaysAllowPackages] 保"已知的系统组件"
    //  - 本条保"**未知的**、以后才出现的、厂商自定义名字的**浮层组件**"
    //    —— 也就是前三道唯一覆盖不到的那部分

    /**
     * **这个包是否没有启动入口**（v2.15）—— 判定的纯函数核心。
     *
     * @param packageName 待判定的前台包名（可以带空格；空/null 一律返回 false，
     *        与 [decide] 的"拿不到包名就放行"保持一致口径）
     * @param launchablePackages 运行时枚举到的"有启动入口"的包名集合
     *        （见 `SystemPackageDetector.detectLaunchablePackages`）。
     *
     * @return `true` 表示**已证明**它没有启动入口 ⇒ 调用方必须放行。
     *
     * ⚠️ **集合为空时返回 false**（= 证明不了 ⇒ 规则不生效 ⇒ 旧行为）。
     * 这是本函数最重要的一条：若反过来返回 true，一次枚举失败就会让
     * **所有应用**都被放行，"锁机"这个功能当场消失。默认参数
     * `launchablePackages = emptySet()` 因此天然保证"不传 = 行为不变"。
     */
    fun hasNoLaunchEntry(packageName: String?, launchablePackages: Set<String>): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        // 空集合 = 枚举失败，无从判断 → 不启用本规则（见上面"兜底方向"）
        if (launchablePackages.isEmpty()) return false
        return pkg !in launchablePackages
    }

    /**
     * **这个包为什么会被判成"要拦"** —— 给自检页 / 拦截日志用的一句话（v2.14）。
     *
     * ## 为什么必须补这一句
     *
     * 用户报"系统界面组件 / 截屏 / 智能助理被拦"时，原来的拦截日志只记包名 + 时间 +
     * 次数，**看不出判定依据**，于是既无法区分"不在任何放行集合里"与"额度用尽"，
     * 也无法知道"枚举是不是返回了 0 个系统应用"。有了这句话，用户截个图就能定位。
     *
     * 输出形如：
     * ```
     * 拦截依据：关键清单✗ 系统界面/截屏/助理✗ 系统应用枚举(312)✗ 运行时探测(7)✗ 白名单(24)✗
     *          无启动入口✗；理由=锁机中且不在白名单
     * ```
     * 括号里的数字是**当次判定时**各放行集合的大小 ——
     * `系统应用枚举(0)` 就说明这次枚举失败了（只剩清单兜底），
     * 这是排查"某个系统组件被拦"最关键的一条信息。
     * v2.15 追加的 `无启动入口✗` 表示"这个包确实有启动入口（所以它不是浮层组件）"，
     * 于是"被拦"是有意义的；若它显示 ✓ 却仍然被拦，那就是缺陷（见下方的最后一道判定）。
     *
     * ⚠️ 判定顺序与 [decide] **必须一致**：本函数只解释，不另立一套规则；
     * 单元测试用"对同一批包，[decide] 是否 Block 与本文案是否以『拦截依据』开头
     * 必须一致"来钉住这一点。
     *
     * @param launchablePackages v2.15：可启动包集合（口径与 [decide] 完全一致，
     *        默认空集合 ⇒ 与 v2.14 的输出逐字相同）
     * @return 一句话；未命中拦截时返回 `"未拦截"`（正常情况下调用方只在 Block 时用它）
     */
    fun explainBlock(
        foregroundPackage: String?,
        selfPackage: String,
        allowList: Set<String>,
        locked: Boolean,
        quotaExhaustedPackages: Set<String> = emptySet(),
        systemPackages: Set<String> = emptySet(),
        alwaysAllowPackages: Set<String> = emptySet(),
        launchablePackages: Set<String> = emptySet()
    ): String {
        val pkg = foregroundPackage?.trim().orEmpty()

        fun allowed(condition: Boolean): String = if (condition) "✓" else "✗"

        if (pkg.isEmpty() || pkg == selfPackage) return "未拦截"
        if (pkg in CRITICAL_SYSTEM_PACKAGES) return "未拦截"
        if (AlwaysAllowPackages.isAlwaysAllowed(pkg)) return "未拦截"
        if (pkg in systemPackages) return "未拦截"
        if (pkg in alwaysAllowPackages) return "未拦截"
        if (pkg in allowList) return "未拦截"
        // v2.15：与 [decide] 的第 5.5 条严格对应
        if (hasNoLaunchEntry(pkg, launchablePackages)) return "未拦截"

        val quotaExhausted = pkg in quotaExhaustedPackages
        if (!quotaExhausted && !locked) return "未拦截"

        val checks = buildString {
            append("关键清单")
            append(allowed(pkg in CRITICAL_SYSTEM_PACKAGES))
            append(" 系统界面/截屏/助理")
            append(allowed(AlwaysAllowPackages.isAlwaysAllowed(pkg)))
            append(" 系统应用枚举(")
            append(systemPackages.size)
            append(")")
            append(allowed(pkg in systemPackages))
            append(" 运行时探测(")
            append(alwaysAllowPackages.size)
            append(")")
            append(allowed(pkg in alwaysAllowPackages))
            append(" 白名单(")
            append(allowList.size)
            append(")")
            append(allowed(pkg in allowList))
            // v2.15：最后一项 —— "它有没有启动入口"。
            // `无启动入口✗` = 这个包确实能启动（所以拦它是有意义的）；
            // `无启动入口✓` 而仍然被拦 = 缺陷（但按第 5.5 条它根本走不到这里，
            // 所以这一项出现在这一行时必然是 ✗，除非集合是空的）。
            append(" 无启动入口")
            append(allowed(hasNoLaunchEntry(pkg, launchablePackages)))
        }
        val reason = if (quotaExhausted) {
            "额度用尽（该包被「软件使用时间限时」绑定且本周期已用完）"
        } else {
            "锁机中且不在任何放行集合里"
        }
        return "拦截依据：$checks；理由=$reason"
    }

    // ========================================================================
    // v2.13：把「系统应用」整体放行 —— 不再靠手写清单猜包名
    // ========================================================================
    //
    // ## 这一版修的是什么
    //
    // v2.10 用"窗口类型判定"去修"锁机时调音量会显示锁机中并退出软件、退出后划不掉后台"，
    // 但真机上**没生效**。原因不是判定写错了，而是它**依赖的证据在真机上常常取不到**：
    // `AccessibilityWindowInfo.getType()` 要求 `event.windowId` 能在 `getWindows()`
    // 里对上号，而 `TYPE_WINDOWS_CHANGED` 常常带着 `UNDEFINED_WINDOW_ID(-1)`；
    // 于是窗口归属只能落到 `Unconfirmed`，第二道防线的价值大打折扣。
    //
    // 用户给的方向更直接：**把系统软件全部放进默认白名单**。
    // 音量面板（`com.android.systemui`）、最近任务（系统 UI 的概览窗口）、
    // 权限对话框（`permissioncontroller` / 各厂商权限管理器）、
    // 各家 ROM 的系统组件（安全中心、手机管家、SystemUI 变体 ……）**全都在
    // `PackageManager.getInstalledApplications()` 里带 `FLAG_SYSTEM` 或
    // `FLAG_UPDATED_SYSTEM_APP`** —— 不需要猜包名，问系统就行。
    //
    // ## 为什么这件事必须放在纯函数里
    //
    // 本项目一贯靠纯函数做"无真机验证"：[decide] 的入参就是全部判据。
    // 所以"枚举"（Android API）留在 `SystemPackageDetector`，
    // "该不该拦"（判据）留在这里，集合**作为参数传入**。
    // 好处是这三件事都能被单元测试钉死：
    //  1. 系统包在锁机态 / 额度用尽时一律放行
    //  2. **用户装的第三方应用照旧被拦**（放行的边界只能是系统应用）
    //  3. 不传这个参数时行为与 v2.12 一字不差（默认空集合）
    //
    // ## 与 [CRITICAL_SYSTEM_PACKAGES] 的关系
    //
    // 两者不是替代关系，是**两道独立的保险**：
    //  - [CRITICAL_SYSTEM_PACKAGES] 是写死的兜底清单：即使
    //    `getInstalledApplications()` 被 ROM 限制、返回空列表，桌面 / 设置 /
    //    权限对话框 / 来电也照样放行（"不把手机搞坏"的最后一道保险）
    //  - 本参数是**运行时枚举**：覆盖清单里没写到的所有系统包，
    //    包括以后新装的系统组件和厂商自定义包名
    //
    // 桌面、输入法、系统设置必须永远可进这条底线，两支都覆盖到了：
    // 系统设置 `com.android.settings` 在两个集合里都有；桌面与输入法既在
    // 白名单的运行时探测里（`SystemPackageDetector.detectBuiltins`），
    // 也几乎必然是系统应用。

    /**
     * 是否因为"前台窗口不是应用窗口"而让路（PRD 坑清单 #3）。
     *
     * ⚠️ **这个函数是为了修一个已出厂的真实 bug 而抽出来的**。
     *
     * 原先是这么写的：
     * ```
     * if (windowType == null) { 让路 }   // 判定不出来就不拦
     * ```
     * 而窗口类型依赖 `AccessibilityService.getWindows()`，它在很多 ROM 上
     * 返回空列表、`TYPE_WINDOWS_CHANGED` 的 windowId 也常常对不上号 ——
     * 于是"判定不出来"成了常态，**整个拦截永久失效**。
     *
     * 正确的语义是：
     *  - **确实判定出**是非应用窗口 → 让路（来电、闹钟、输入法、系统对话框）
     *  - **判定不出来** → 不让路，继续走包名判定

     * 安全性由另外三道独立保险保证，不需要靠"未知就不拦"来兜底：
     *  1. [CRITICAL_SYSTEM_PACKAGES]：系统界面 / 设置 / 权限对话框 / 来电 精确匹配放行
     *  2. 白名单里由运行时探测加入的桌面与输入法
     *  3. v2.13 起还有 `decide(systemPackages = …)` 传进来的**全部系统应用**
     *
     * @param windowTypeKnown 是否成功判定出了窗口类型
     * @param isApplicationWindow 判定出的类型是否为应用窗口
     * @return true 表示应当让路（不显示遮挡层）
     */
    fun shouldYieldToWindow(windowTypeKnown: Boolean, isApplicationWindow: Boolean): Boolean =
        windowTypeKnown && !isApplicationWindow

    // ========================================================================
    // v2.10：窗口归属判定 —— 修"音量面板把用户踢回桌面"这一类真机 bug
    // ========================================================================
    //
    // ## 为什么要再加一层判定
    //
    // [shouldYieldToWindow] 的语义是"只有**确实判定出**是非应用窗口才让路"，
    // 这是为了避免重蹈"判定不出类型就不拦 → 拦截整体失效"的覆辙。
    // 但它有个**没被覆盖的缺口**：`AccessibilityWindowInfo.getType()` 依赖
    // `event.windowId` 能在 `getWindows()` 里对上号，而
    // `TYPE_WINDOWS_CHANGED` 是**全局窗口集合变化**事件，它在绝大多数 ROM 上
    // 携带的是 `UNDEFINED_WINDOW_ID(-1)` 或一个转瞬即逝的临时 id。
    //
    // 于是用户真机上的现象是：
    //   锁机中 → 按音量键 → 系统弹音量面板（`com.android.systemui` 的系统窗口）
    //   → 收到 `TYPE_WINDOWS_CHANGED` → 窗口类型**判定不出来**
    //   → 不让路 → 拿"事件包名"继续判定 → 判定为要拦 → `GLOBAL_ACTION_HOME`
    //   → **用户被踢回桌面**（看起来就是"调音量导致锁机退出软件"）。
    //
    // 同一个缺口还解释了另外两个 bug：最近任务（概览）与
    // 权限申请对话框都是 systemui / permissioncontroller **进程**里的窗口，
    // 它们同样会在"类型判定不出来"时被当成普通应用窗口拦掉。
    //
    // ## 修法：把"这个窗口到底属不属于被判定应用"变成纯函数输入
    //
    // PRD（§3.3 与坑清单 #2）本来就写着**只对 `TYPE_APPLICATION` 做拦截**。
    // 这里把那条规则补全成可判定的三段：
    //
    //  1. 窗口类型 == 应用窗口            → [WindowAnalysis.ApplicationWindow]  执行拦截
    //  2. 窗口类型已知且非应用窗口        → [WindowAnalysis.NonApplicationWindow] 让路
    //  3. 类型未知：
    //     a. 事件是 `TYPE_WINDOW_STATE_CHANGED`（应用/Activity 真的切前台了）
    //        → 按应用窗口处理（否则会重犯"拦截整体失效"的老 bug）
    //     b. 事件是 `TYPE_WINDOWS_CHANGED` 且该包名下**没有任何应用窗口**
    //        → [WindowAnalysis.Unconfirmed] 让路
    //          （音量面板 / 最近任务 / 权限对话框 / 关机菜单都属于这一类）
    //     c. 该包名确实拥有一个应用窗口 → 按应用窗口处理
    //
    // 3b 的取值方向是刻意的：**没有可确认的应用窗口，就不要执行"踢回桌面"**。
    // 拦截的漏网风险由 3a 兜住 —— 应用真的切到前台时 `TYPE_WINDOW_STATE_CHANGED`
    // 一定会来，那一条照拦不误。

    /** 应用窗口类型常量（`AccessibilityWindowInfo.TYPE_APPLICATION`）。 */
    const val WINDOW_TYPE_APPLICATION = 1

    /** 无障碍浮层类型常量（`AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY`，即本应用的遮挡层）。 */
    const val WINDOW_TYPE_ACCESSIBILITY_OVERLAY = 4

    /**
     * 系统窗口类型常量（`AccessibilityWindowInfo.TYPE_SYSTEM` 起的全部类型）。
     *
     * 音量面板、关机菜单、最近任务、权限对话框、输入法、来电界面都在其中。
     * 只要 `type` 落在 `[WINDOW_TYPE_SYSTEM, WINDOW_TYPE_MAGNIFICATION]` 区间内，
     * 就是"绝不该执行拦截"的窗口。
     */
    const val WINDOW_TYPE_SYSTEM = 3

    /** 无障碍窗口类型的最大值（`AccessibilityWindowInfo.TYPE_MAGNIFICATION`）。 */
    const val WINDOW_TYPE_MAGNIFICATION = 5

    /** [analyzeWindowForPackage] 的判定结果。 */
    enum class WindowAnalysis {
        /** 判定为**应用窗口**：可以按包名执行拦截。 */
        ApplicationWindow,

        /** 判定为**非应用窗口**（系统窗口 / 输入法 / 系统对话框）：必须让路。 */
        NonApplicationWindow,

        /**
         * **无法确认**事件来自应用窗口：不执行拦截，也不把它当成应用。
         *
         * 与 [NonApplicationWindow] 分开是为了两件事：
         *  1. 自检页能区分"确实判定出是系统窗口"与"根本没判定出来"（真机排查的关键信息）
         *  2. 两者对**使用时长统计 / 前台包名**的处理相同：都不该记成"用户打开了这个应用"
         */
        Unconfirmed
    }

    /**
     * 判定一个窗口事件是否属于应用窗口（纯函数）。
     *
     * @param packageName 事件包名；空表示信息缺失（返回 [WindowAnalysis.Unconfirmed]）
     * @param windowType `AccessibilityWindowInfo.getType()` 的结果；null 表示没查到
     * @param isWindowStateChangeEvent 是否 `TYPE_WINDOW_STATE_CHANGED`
     *        （相对于 `TYPE_WINDOWS_CHANGED`）。**必须由调用方传入** ——
     *        纯函数不依赖 Android 常量。
     * @param eventWindowMayBeApplication 窗口类型查不出来时，**有没有证据说明
     *        这个事件就是应用窗口**。调用方按"保守方向 = true"计算：
     *        拿不到窗口列表、或窗口列表里确实有这个事件指向的应用窗口时为 true。
     *        详见 `LockAccessibilityService.eventPackageOwnsVisibleApplicationWindow`。
     */
    fun analyzeWindowForPackage(
        packageName: String?,
        windowType: Int?,
        isWindowStateChangeEvent: Boolean,
        eventWindowMayBeApplication: Boolean
    ): WindowAnalysis {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return WindowAnalysis.Unconfirmed

        // 1) 窗口类型判定出来了 —— 这是最硬的证据，直接用
        if (windowType != null) {
            return if (windowType == WINDOW_TYPE_APPLICATION) {
                WindowAnalysis.ApplicationWindow
            } else {
                WindowAnalysis.NonApplicationWindow
            }
        }

        // 2) 类型查不出来，但有证据说它是应用窗口（含"拿不到窗口列表"的保守情形）
        if (eventWindowMayBeApplication) return WindowAnalysis.ApplicationWindow

        // 3) 类型查不出来，且证据表明这个事件**不是**应用窗口：
        //    屏幕上只有别人的应用窗口，甚至一个应用窗口都没有
        //    （音量面板 / 最近任务 / 权限对话框 / 关机菜单都是这一支）。
        //
        //    ⚠️ 唯一例外是 `TYPE_WINDOW_STATE_CHANGED`：它是"应用/Activity 真的
        //    切到前台"的最强信号。若把它也让路，就会重犯历史上
        //    "判定不出窗口类型 → 整个拦截永久失效"的 bug（见
        //    [shouldYieldToWindow] 的注释）。
        return if (isWindowStateChangeEvent) {
            WindowAnalysis.ApplicationWindow
        } else {
            WindowAnalysis.Unconfirmed
        }
    }

    /**
     * 是否应当把"某包名成为前台"记入**使用时长**与前台包名缓存。
     *
     * 只有判定为应用窗口的事件才算"用户真的打开了这个应用"。
     * 音量面板 / 最近任务 / 权限对话框这些系统窗口事件绝不能计入 ——
     * 否则系统组件的包名会被当成前台应用写进使用时长，
     * 还会把额度看门狗指到错误的包上（v2.10 修的真机 bug 之一）。
     */
    fun shouldTrackAsForeground(analysis: WindowAnalysis): Boolean =
        analysis == WindowAnalysis.ApplicationWindow

    /**
     * 组合最终生效的白名单。
     *
     * ⚠️ **运行时探测到的系统包必须无条件并入，不能只依赖数据库里已有的记录。**
     *
     * 为什么：内置白名单原先只在用户访问「白名单」页时才写库。全新安装、
     * 用户没进过那个页面时，**桌面包的记录是空的** —— 而桌面包名各厂商不同
     * （`com.miui.home` / `com.huawei.android.launcher` …），无法进
     * [CRITICAL_SYSTEM_PACKAGES] 兜底清单。
     *
     * 后果：按 Home 键 → 桌面成为前台 → 不在白名单 → 显示遮挡层盖住桌面 →
     * 点「返回桌面」→ 又触发一次 → **用户被永久困在遮挡层里**。
     *
     * 所以运行时探测结果永远是白名单的一部分，与用户的数据库状态无关。
     *
     * @param persisted 数据库里的白名单（全局 ∪ 规则级）
     * @param runtimeSystemPackages 运行时探测到的桌面 / 输入法 / 电话等
     */
    fun effectiveAllowList(
        persisted: Set<String>,
        runtimeSystemPackages: Set<String>
    ): Set<String> = persisted + runtimeSystemPackages
}
