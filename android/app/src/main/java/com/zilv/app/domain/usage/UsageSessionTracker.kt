package com.zilv.app.domain.usage

/**
 * 使用时长会话跟踪（PRD §3.7）。
 *
 * ⚠️ **最关键的一条：按包名去重**（开发坑清单 #11）。
 * App 内部切换 Activity 同样会触发 `TYPE_WINDOW_STATE_CHANGED` ——
 * 微信从聊天切到朋友圈再切到视频号，会连发好几个事件。
 * 不做去重的话，一次连续 40 分钟的使用会被切成几十个碎段，时长统计全错。
 *
 * 另外两个必须处理的点：
 *  - **息屏暂停计时**：否则挂后台的时间会被算进去
 *  - **时钟回拨保护**：用户改系统时间时不能产生负时长
 *
 * 本类只做会话记账（纯逻辑，无 Android 依赖），落库由仓库负责。
 */
class UsageSessionTracker {

    /** 一段连续的前台使用。 */
    data class Segment(
        val packageName: String,
        val startMillis: Long,
        val endMillis: Long
    ) {
        val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
    }

    private var activePackage: String? = null
    private var activeSince: Long = 0L
    private var interactive: Boolean = true

    /**
     * 当前正在计时的段（含未结束的尾巴）。
     *
     * @param atMillis 当前时刻，由调用方提供 —— 这样本类完全无时钟依赖，
     *        单元测试可以精确控制时间。
     */
    fun currentSegmentAt(atMillis: Long): Segment? {
        if (!interactive) return null
        val pkg = activePackage ?: return null
        val end = if (atMillis < activeSince) activeSince else atMillis
        return Segment(pkg, activeSince, end)
    }

    /** 正在计时的包名；息屏时为 null。 */
    val currentPackage: String? get() = if (interactive) activePackage else null

    val isTracking: Boolean get() = interactive && activePackage != null

    /**
     * 前台应用变化。
     *
     * @return 被结算掉的上一段；若前台仍是同一个包（**去重命中**）则返回 null
     */
    fun onForeground(packageName: String, atMillis: Long): Segment? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null

        // 息屏期间不跟踪：只更新记录，不产生时长
        if (!interactive) {
            activePackage = pkg
            activeSince = atMillis
            return null
        }

        // 同一个包：去重，什么都不做（这是坑清单 #11 的核心）
        if (activePackage == pkg) return null

        val finished = closeActive(atMillis)
        activePackage = pkg
        activeSince = atMillis
        return finished
    }

    /**
     * 息屏：结算当前段并暂停计时。
     */
    fun onScreenOff(atMillis: Long): Segment? {
        val finished = closeActive(atMillis)
        interactive = false
        activePackage = finished?.packageName
        activeSince = atMillis
        return finished
    }

    /** 亮屏：恢复计时，从此刻重新开始计。 */
    fun onScreenOn(atMillis: Long) {
        interactive = true
        activeSince = atMillis
    }

    /**
     * 强制结算当前段（服务停止、跨天等场景）。
     */
    fun flush(atMillis: Long): Segment? {
        val finished = closeActive(atMillis)
        activePackage = null
        activeSince = atMillis
        return finished
    }

    private fun closeActive(atMillis: Long): Segment? {
        val pkg = activePackage ?: return null
        if (!interactive) return null
        val start = activeSince
        // 时钟回拨保护：结束早于开始时按 0 时长处理，不产生负数
        val end = if (atMillis < start) start else atMillis
        return Segment(pkg, start, end)
    }

    companion object {
        /**
         * 把若干段聚合成「包名 -> 总时长」。
         *
         * 与 Tracker 分开是为了让它成为纯函数，便于独立测试。
         */
        fun aggregate(segments: Iterable<Segment>): Map<String, Long> {
            val result = mutableMapOf<String, Long>()
            segments.forEach { seg ->
                if (seg.durationMillis <= 0L) return@forEach
                result[seg.packageName] = (result[seg.packageName] ?: 0L) + seg.durationMillis
            }
            return result
        }
    }
}
