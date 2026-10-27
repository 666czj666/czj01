package com.zilv.app.domain.permission

/**
 * 权限向导的步骤模型（PRD §3.10）。
 *
 * 顺序**有依赖，不可乱**：第 2 步「允许受限设置」必须最先做，
 * 否则无障碍 / 悬浮窗 / 通知使用权的开关在系统里全是灰的。
 *
 * 抽成纯数据模型是为了能确定性地测试"进度计算"与"下一步该做什么"，
 * 而不需要在 JVM 上模拟 Android 的权限系统。
 */
enum class PermissionStep(
    val title: String,
    val why: String,
    /** 未授予是否阻塞核心功能（PRD：通知使用权是唯一可跳过的一步） */
    val required: Boolean
) {
    NOTIFICATIONS(
        "通知权限",
        "常驻通知与到点预警的基础",
        required = true
    ),
    RESTRICTED_SETTINGS(
        "允许受限设置",
        "Android 13+ 侧载应用的前置关卡；不做这一步，后面几个开关在系统里是灰的",
        required = true
    ),
    ACCESSIBILITY(
        "无障碍服务",
        "拦截引擎的核心：没有它，App 无法知道你打开了哪个应用",
        required = true
    ),
    OVERLAY(
        "悬浮窗权限",
        "遮挡层的核心：没有它，拦不住白名单以外的应用",
        required = true
    ),
    EXACT_ALARM(
        "精确闹钟",
        "Android 14 起默认被拒；没有它，到点不能准时进入锁机态",
        required = true
    ),
    BATTERY_OPTIMIZATION(
        "电池优化白名单",
        "避免后台服务被系统回收导致拦截失效",
        required = false
    ),
    AUTO_START(
        "自启动 / 后台运行",
        "国产 ROM 必需；不同品牌路径不同，需要手动设置",
        required = false
    ),
    LOCATION(
        "前台定位",
        "到点打卡需要校验位置；只在点击「立即打卡」时取一次",
        required = true
    ),
    NOTIFICATION_ACCESS(
        "通知使用权",
        "通知脱敏所需；未授予时脱敏静默停用，不影响拦截主流程",
        required = false
    );

    companion object {
        /** 向导展示顺序（即枚举声明顺序）。 */
        val ordered: List<PermissionStep> get() = entries.toList()
    }
}

/** 单个步骤的当前状态。 */
data class PermissionState(
    val step: PermissionStep,
    val granted: Boolean
)

/** 向导整体状态。 */
data class PermissionSummary(
    val states: List<PermissionState>
) {
    private fun stateOf(step: PermissionStep) = states.firstOrNull { it.step == step }

    fun isGranted(step: PermissionStep): Boolean = stateOf(step)?.granted == true

    /** 全部**必需**步骤是否已完成（可跳过项不阻塞）。 */
    val ready: Boolean
        get() = PermissionStep.ordered.filter { it.required }.all { isGranted(it) }

    /** 缺失的必需步骤，按向导顺序。 */
    val missingRequired: List<PermissionStep>
        get() = PermissionStep.ordered.filter { it.required && !isGranted(it) }

    /** 缺失的可选步骤。 */
    val missingOptional: List<PermissionStep>
        get() = PermissionStep.ordered.filter { !it.required && !isGranted(it) }

    /** 进度 0..1，**只统计必需步骤**（可选步骤不拉低进度，避免误导）。 */
    val progress: Float
        get() {
            val required = PermissionStep.ordered.filter { it.required }
            if (required.isEmpty()) return 1f
            return required.count { isGranted(it) }.toFloat() / required.size
        }

    /** 当前第一个未完成的必需步骤；全部完成时返回第一个未完成的可选步骤；否则 null。 */
    val nextStep: PermissionStep?
        get() = missingRequired.firstOrNull() ?: missingOptional.firstOrNull()

    companion object {
        fun from(granted: Set<PermissionStep>): PermissionSummary =
            PermissionSummary(PermissionStep.ordered.map { PermissionState(it, it in granted) })
    }
}
