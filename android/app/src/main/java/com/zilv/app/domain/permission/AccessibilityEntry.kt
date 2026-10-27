package com.zilv.app.domain.permission

/**
 * 解析 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 里的条目。
 *
 * ⚠️ **这个类存在的原因是修一个真实 bug**：用户已经开启了无障碍权限，
 * 但「权限检查」页始终不显示打钩。
 *
 * 根因是原实现用**硬编码字符串精确比对**：
 * ```
 * "${context.packageName}/${context.packageName}.service.LockAccessibilityService"
 * ```
 * 而这个系统设置项里存的是 `ComponentName` 的扁平化结果，**格式并不唯一**：
 *
 * | 形式 | 示例 |
 * | --- | --- |
 * | 长格式（flattenToString） | `com.zilv.app/com.zilv.app.service.LockAccessibilityService` |
 * | 短格式（flattenToShortString） | `com.zilv.app/.service.LockAccessibilityService` |
 *
 * 不同 Android 版本与厂商 ROM 用的形式不同，精确比对在短格式的机器上
 * **永远不可能匹配**，且失败时完全静默 —— 表现为"权限开了但检测不到"。
 *
 * 因此这里把两种形式都解析掉，并且用**真实的类名**（`X::class.java.name`）
 * 而不是拼接出来的字符串来比对，避免类名重构后悄悄失配。
 */
object AccessibilityEntry {

    /** 无法解析时的占位。 */
    private val EMPTY = "" to ""

    /**
     * 把一条 `包名/类名` 条目拆成 (包名, 完整类名)。
     *
     * - 类名以 `.` 开头时按短格式处理，补上前面的包名
     * - 类名不含 `.` 时同样视为相对类名（罕见但存在）
     * - 格式不合法时返回 null
     */
    fun parse(entry: String): Pair<String, String>? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null

        val slash = trimmed.indexOf('/')
        if (slash <= 0 || slash == trimmed.length - 1) return null

        val pkg = trimmed.substring(0, slash).trim()
        var cls = trimmed.substring(slash + 1).trim()
        if (pkg.isEmpty() || cls.isEmpty()) return null

        // 短格式：.service.XxxService -> pkg.service.XxxService
        if (cls.startsWith('.')) {
            cls = pkg + cls
        } else if (cls.indexOf('.') < 0) {
            // 既不以点开头、也不含点（例如 "MyService"）—— 同样按相对类名处理
            cls = "$pkg.$cls"
        }

        return pkg to cls
    }

    /**
     * 判断单条条目是否指向指定的服务。
     *
     * 包名比对忽略大小写；类名比对**区分大小写**（Java 类名有意义）。
     */
    fun matches(entry: String, packageName: String, serviceClassName: String): Boolean {
        val (pkg, cls) = parse(entry) ?: return false
        return pkg.equals(packageName, ignoreCase = true) && cls == serviceClassName
    }

    /**
     * 在整串「冒号分隔」的设置值里找匹配项。
     *
     * 分隔符同时容忍 `:` 与 `;` —— 前者是 AOSP 的标准分隔符，
     * 个别 ROM 的定制实现用过后者。
     */
    fun matchesAny(raw: String?, packageName: String, serviceClassName: String): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.split(':', ';', '\n').any { matches(it, packageName, serviceClassName) }
    }

    /** 供 UI 展示用：列出该设置值里所有可解析的服务。 */
    fun parseAll(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(':', ';', '\n').mapNotNull { parse(it) }
    }

    /** 空值常量，避免调用方处理 null。 */
    val UNKNOWN: Pair<String, String> = EMPTY
}
