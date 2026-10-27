package com.zilv.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * 安装 / 卸载 / 覆盖安装监听（v2.13）。
 *
 * ## 为什么需要它
 *
 * 无障碍服务在连接时枚举一次**系统应用**（`FLAG_SYSTEM` / `FLAG_UPDATED_SYSTEM_APP`）
 * 并缓存起来，供每一次拦截判定使用 —— 枚举要走
 * `PackageManager.getInstalledApplications()`，那是个跨进程调用，
 * 绝不能放进每个窗口事件的热路径。
 *
 * 缓存带来的问题就是**过期**：
 *  - 系统组件被厂商 OTA / 应用商店**更新**（`ACTION_PACKAGE_REPLACED`）后，
 *    包的 flags 可能从 `FLAG_SYSTEM` 变成 `FLAG_UPDATED_SYSTEM_APP`，或反过来
 *  - 用户**停用 / 卸载更新**某个系统应用（`ACTION_PACKAGE_REMOVED` /
 *    `ACTION_PACKAGE_CHANGED`）后，它可能不再是系统应用
 *  - 新增一个系统组件（`ACTION_PACKAGE_ADDED`）后，它应该立刻进白名单
 *
 * 所以收到这些广播就把缓存**整体重算**一次（而不是增量维护）：
 * 枚举是几百个包的集合，重算一次只是几十毫秒，而且它发生在广播里、
 * 不在拦截的热路径上。增量维护反而要处理"包名相同但 flags 变了"这种边界，
 * 容易写出与 `PackageManager` 真不一致的幽灵状态。
 *
 * ## 为什么动态注册
 *
 * `ACTION_PACKAGE_*` 虽然是隐式广播，但它在 Android 8+ 的**隐式广播限制豁免清单**里，
 * 因此动态注册照旧能收到。这里刻意跟 [ScreenStateReceiver] 一样用动态注册：
 * 生命周期跟着无障碍服务走 —— 服务不在时缓存本来也没人用，不需要常驻接收器。
 *
 * ## 判据为什么抽成 [SystemPackageChangeWatch]
 *
 * 与本项目一贯的做法一致：Android 相关的"收广播"留在这里，
 * "哪些 action 算包变化"这条**判据**放在纯对象里，于是它可以被单元测试钉住，
 * 不需要真机也不需要 Robolectric。
 */
class SystemPackageChangeReceiver(
    private val onPackageChanged: () -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (!SystemPackageChangeWatch.isPackageChangeAction(action)) return
        Log.i(TAG, "包变化（$action），重算系统应用缓存")
        runCatching { onPackageChanged() }
            .onFailure { Log.e(TAG, "重算系统应用缓存失败", it) }
    }

    companion object {
        private const val TAG = "SystemPackageChange"

        fun register(context: Context, receiver: SystemPackageChangeReceiver) {
            val filter = IntentFilter().apply {
                SystemPackageChangeWatch.ACTIONS.forEach { addAction(it) }
                // ⚠️ 这个 data scheme 必须加：不带它，PACKAGE_* 广播**根本不会被投递**
                // （它们的数据是 `package:` 形式的 URI）
                addDataScheme("package")
            }
            runCatching { context.registerReceiver(receiver, filter) }
                .onFailure { Log.e(TAG, "注册包变化监听失败", it) }
        }

        fun unregister(context: Context, receiver: SystemPackageChangeReceiver) {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}

/**
 * 「哪些广播算包变化」—— 纯判据，可单元测试（无 Android 依赖的逻辑部分）。
 *
 * 覆盖四类，每一类都会让"系统应用集合"这个缓存过期：
 *
 * | action | 什么时候来 | 缓存为什么要重算 |
 * | --- | --- | --- |
 * | `ACTION_PACKAGE_ADDED` | 新装 / 系统推送新增组件 | 新包可能是系统应用（预装恢复、系统更新追加） |
 * | `ACTION_PACKAGE_REMOVED` | 卸载 / 系统应用被停用 | 包没了，或者它不再带 `FLAG_SYSTEM` |
 * | `ACTION_PACKAGE_REPLACED` | 覆盖安装（应用商店更新） | flags 可能在两种 SYSTEM 标记之间翻转 |
 * | `ACTION_PACKAGE_CHANGED` | 组件启用/停用状态变化 | 系统应用的 enabled 状态变了，flags 也可能跟着变 |
 */
object SystemPackageChangeWatch {

    /** 需要重算系统应用缓存的全部广播 action。 */
    val ACTIONS: List<String> = listOf(
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_REMOVED,
        Intent.ACTION_PACKAGE_REPLACED,
        Intent.ACTION_PACKAGE_CHANGED
    )

    /** 这个 action 是否意味着"系统应用集合可能变了"。 */
    fun isPackageChangeAction(action: String?): Boolean = action in ACTIONS
}
