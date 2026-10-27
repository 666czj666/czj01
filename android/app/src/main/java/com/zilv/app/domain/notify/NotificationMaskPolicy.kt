package com.zilv.app.domain.notify

/**
 * 通知脱敏的决策逻辑（PRD §3.6）。
 *
 * 把"该不该撤、能不能撤"抽成纯函数，是因为这里有一条**会直接让方案失效的
 * 平台硬约束**（已逐字核实官方 javadoc）：
 *
 * > Ongoing notifications cannot be dismissed by the user on locked devices,
 * > **or by notification listeners**...
 *
 * 即：任何 App 只要把通知设为 `setOngoing(true)`，监听器就撤不掉它。
 * 因此撤回前**必须先判 `isClearable()`**，否则会陷入无效重试。
 */
object NotificationMaskPolicy {

    data class Input(
        /** 发通知的应用包名 */
        val packageName: String,
        /** 是否是常驻通知（StatusBarNotification.isOngoing()） */
        val isOngoing: Boolean,
        /**
         * 是否可被清除（StatusBarNotification.isClearable()）。
         * 注意这是**独立于 isOngoing** 的判定，两者都要看。
         */
        val isClearable: Boolean,
        /** 需要脱敏的目标应用（默认微信 / 支付宝） */
        val targetPackages: Set<String>,
        /** 当前是否处于锁机态 */
        val locked: Boolean,
        /** 是否已获得通知使用权 */
        val hasListenerAccess: Boolean
    )

    sealed interface Decision {
        /** 不处理，放行原通知 */
        data object Ignore : Decision

        /** 撤回并重发脱敏通知 */
        data class Mask(val packageName: String) : Decision

        /** 命中目标但撤不掉，只能放弃（不能因此崩溃或死循环） */
        data class CannotMask(val packageName: String, val reason: String) : Decision
    }

    fun decide(input: Input): Decision {
        // 没有通知使用权：脱敏静默停用，不影响拦截主流程（PRD 明确的降级策略）
        if (!input.hasListenerAccess) return Decision.Ignore

        // 只在锁机窗口内脱敏（PRD 默认值）
        if (!input.locked) return Decision.Ignore

        val pkg = input.packageName.trim()
        if (pkg.isEmpty()) return Decision.Ignore

        if (pkg !in input.targetPackages) return Decision.Ignore

        // 平台硬约束：常驻通知撤不掉
        if (input.isOngoing) {
            return Decision.CannotMask(pkg, "常驻（ongoing）通知无法被通知监听器撤回")
        }
        if (!input.isClearable) {
            return Decision.CannotMask(pkg, "该通知不可被清除（isClearable = false）")
        }

        return Decision.Mask(pkg)
    }

    /**
     * 脱敏后的通知文案。
     *
     * PRD：多条消息聚合成 `微信 · 3 条新消息`。
     */
    fun maskedText(label: String, pendingCount: Int): String =
        if (pendingCount <= 1) "$label · 有新消息" else "$label · $pendingCount 条新消息"

    /** 默认脱敏目标（PRD §0 已确认：微信、支付宝）。 */
    val DEFAULT_TARGETS = setOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone"
    )
}
