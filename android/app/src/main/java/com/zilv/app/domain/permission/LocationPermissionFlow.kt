package com.zilv.app.domain.permission

import android.Manifest

/**
 * 「前台定位」这一项在权限体检页里的**交互流程判据**（纯函数，可离机单元测试）。
 *
 * ## 为什么需要这个类
 *
 * 用户报的原话是：「权限体检里的前台定位按了无反应。」
 *
 * 根因不是按钮没接线，而是**这一项被当成了"看系统设置页"类权限**：
 * `PermissionChecker.settingsIntentFor(LOCATION)` 返回 `null`
 * （注释写着"运行时权限，由 Activity 发起请求"），而权限体检页对所有
 * 非手动确认项一律调 `settingsIntent(step)` 并 `if (intent != null) startActivity` ——
 * 于是 `null` 被静默吞掉，**点击什么都不发生**。同页其它 8 项都有非 null 的
 * Intent（或走「我已完成」手动确认），所以只有这一项"失灵"。
 *
 * 修法是：定位改成**就地发起运行时请求**（`ActivityResultContracts.RequestMultiplePermissions`），
 * 请求结果之后该做什么由 [afterRequest] 决定 —— 抽成纯函数是为了让这条
 * "永久拒绝要退化成跳应用详情设置页"的规则能被单元测试钉死，
 * 而不是埋在一段只有真机才能验证的 Compose 回调里。
 *
 * ## 为什么要同时请求两个权限
 *
 * Android 12（API 31）起，定位是"近似 / 精确"二选一，系统**只在同时请求
 * `ACCESS_FINE_LOCATION` 与 `ACCESS_COARSE_LOCATION` 时才给出"精确"这个选项**；
 * 只请求 FINE 会被系统拒绝整个请求。因此 [PERMISSIONS] 两个都带上 ——
 * 这也和 `AndroidManifest.xml` 里声明的两条（12 条权限里的第 11、12 条）一致，
 * **没有新增任何权限**。
 *
 * 但判定"已授予"仍以**精确定位**为准（[isGranted]），理由：打卡点校验靠的是
 * 精度，近似定位会让"到点打卡"变成"在附近就能打卡"，与 PRD §3.6 的口径不符。
 * 这与 `PermissionChecker.fineLocationGranted()` 用的是同一个判据 ——
 * 两处口径必须一致，否则用户会遇到"系统说授权了、页面还说没授"。
 */
object LocationPermissionFlow {

    /**
     * 运行时请求的权限（顺序固定：先精确后近似）。
     *
     * 用 `List` 而不是数组，是为了让调用点不必每次 `.toTypedArray()` 之外
     * 还要担心可变性 —— 启动器只读它。
     */
    val PERMISSIONS: List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /**
     * 一次运行时请求**之后**该做什么。
     *
     * 只有三种结果：什么都不用做（已授予）、让用户再点一次（普通拒绝）、
     * 去应用详情设置页（系统不会再弹框了）。刻意不合并成"成功/失败"两态：
     * 永久拒绝与普通拒绝的补救方式完全不同，混在一起就只能二选一地猜。
     */
    enum class AfterRequest {
        /** 已授予（或至少精确定位已授予）：刷新状态即可。 */
        RefreshOnly,

        /** 被拒，但系统仍允许再次弹框：刷新状态，用户可再点一次。 */
        AskAgain,

        /**
         * 永久拒绝（用户勾了「不再询问」，或系统这次直接返回拒绝且不再弹框）：
         * 运行时请求已经**不可能**再问了，唯一出路是应用详情设置页。
         */
        OpenAppDetails
    }

    /**
     * 请求结果 → 下一步动作。
     *
     * @param granted [isGranted] 的结果（精确定位已授予）
     * @param fineRationale `shouldShowRequestPermissionRationale(ACCESS_FINE_LOCATION)`
     * @param coarseRationale `shouldShowRequestPermissionRationale(ACCESS_COARSE_LOCATION)`
     * @param previouslyPermanentlyDenied 上一次请求已经判定为"永久拒绝"。
     *        记着它，用户第二次点按钮时就可以**直接**跳设置页，而不是再走一次
     *        "请求 → 系统立刻返回拒绝"这种看起来仍然"按了没反应"的循环。
     *
     * ## 为什么"两个 rationale 都为 false"就判定为永久拒绝
     *
     * `shouldShowRequestPermissionRationale` 的语义是"**还能不能弹框**"：
     *  - 从未请求过 → false（此时我们才刚请求完，所以这种情况不成立）
     *  - 普通拒绝 → true（系统仍允许再弹）
     *  - 勾了「不再询问」→ false（系统不再弹）
     *
     * 也就是说：**在刚刚请求完之后**，false 只能意味着永久拒绝。
     * 两个权限都看，是因为 Android 12+ 允许只拒绝其中一个。
     */
    fun afterRequest(
        granted: Boolean,
        fineRationale: Boolean,
        coarseRationale: Boolean,
        previouslyPermanentlyDenied: Boolean = false
    ): AfterRequest = when {
        granted -> AfterRequest.RefreshOnly
        previouslyPermanentlyDenied -> AfterRequest.OpenAppDetails
        !fineRationale && !coarseRationale -> AfterRequest.OpenAppDetails
        else -> AfterRequest.AskAgain
    }

    /**
     * 请求结果里**精确定位**是否已授予。
     *
     * 只需要看精确那一条：用户选了"近似"时 `ACCESS_FINE_LOCATION` 是 false，
     * 而本 App 的打卡点校验需要精度（见类 KDoc）。
     */
    fun isGranted(result: Map<String, Boolean>): Boolean =
        result[Manifest.permission.ACCESS_FINE_LOCATION] == true

    /** 只拿到了近似定位（Android 12+ 的"近似"选项）。用于给用户一句可行动的提示。 */
    fun onlyApproximate(result: Map<String, Boolean>): Boolean =
        !isGranted(result) && result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
}
