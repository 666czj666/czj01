package com.zilv.app.permission

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zilv.app.domain.permission.AccessibilityEntry
import com.zilv.app.domain.permission.PermissionStep
import com.zilv.app.service.LockAccessibilityService

/**
 * 各权限的**当前真实状态**检测。
 *
 * 有两步在 Android 上没有查询 API，只能靠用户手动确认（见 [MANUAL_STEPS]）：
 *  - [PermissionStep.RESTRICTED_SETTINGS]：系统不暴露"是否已允许受限设置"
 *  - [PermissionStep.AUTO_START]：国产 ROM 的自启动管理没有任何标准 API
 * 因此这两步由用户点「我已完成」写入本地记录。
 */
class PermissionChecker(
    private val context: Context
) {

    // 注意：这里刻意**不做**"手动确认过的步骤"的合并 ——
    // 那需要读取 DataStore（suspend），属于 ViewModel 的职责。
    // PermissionViewModel 会用手动确认集合覆盖 MANUAL_STEPS 的结果，
    // 其余步骤调用下面的 isGranted()。

    fun isGranted(step: PermissionStep): Boolean = when (step) {
        PermissionStep.NOTIFICATIONS -> notificationsEnabled()

        // 无标准 API。用"依赖它的权限是否有一个已经放行"做推断：
        // 受限设置被解除后，用户才有可能开启无障碍 / 悬浮窗 / 通知使用权。
        PermissionStep.RESTRICTED_SETTINGS ->
            accessibilityEnabled() || canDrawOverlays() || notificationAccessGranted()

        PermissionStep.ACCESSIBILITY -> accessibilityEnabled()
        PermissionStep.OVERLAY -> canDrawOverlays()
        PermissionStep.EXACT_ALARM -> canScheduleExactAlarms()
        PermissionStep.BATTERY_OPTIMIZATION -> ignoringBatteryOptimizations()
        PermissionStep.AUTO_START -> false // 只能手动确认
        PermissionStep.LOCATION -> fineLocationGranted()
        PermissionStep.NOTIFICATION_ACCESS -> notificationAccessGranted()
    }

    // ---------------------------------------------------------------- 各项检测

    private fun notificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 无障碍服务是否已开启。
     *
     * 两条独立通道，任一命中即算开启（**宁可误判为已开启，也不要误判为未开启** ——
     * 后者会让用户以为没生效而反复折腾）：
     *
     *  1. `AccessibilityManager` 查询 —— 用系统解析好的 `ServiceInfo`，
     *     拿到的是**真实类名**，不依赖任何字符串格式
     *  2. `Settings.Secure` 字符串解析 —— 兜底，容忍长/短两种扁平化格式
     *     （见 [AccessibilityEntry] 的说明：原实现只认长格式，
     *     在短格式的 ROM 上永远匹配不上，这才是"开了权限却检测不到"的根因）
     */
    private fun accessibilityEnabled(): Boolean =
        enabledByAccessibilityManager() || enabledBySecureSetting()

    private fun enabledByAccessibilityManager(): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        return runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info?.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == context.packageName &&
                        si.name == ACCESSIBILITY_SERVICE_CLASS
                }
        }.getOrDefault(false)
    }

    private fun enabledBySecureSetting(): Boolean {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()

        return AccessibilityEntry.matchesAny(
            raw = raw,
            packageName = context.packageName,
            serviceClassName = ACCESSIBILITY_SERVICE_CLASS
        )
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun ignoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun fineLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    // ---------------------------------------------------------------- 跳转

    /**
     * 该步骤对应的系统设置页跳转。返回 null 表示"只能手动确认"。
     */
    fun settingsIntentFor(step: PermissionStep): Intent? = when (step) {
        PermissionStep.NOTIFICATIONS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        // 应用信息页：受限设置的开关在这个页面的右上角三点菜单里
        PermissionStep.RESTRICTED_SETTINGS -> appDetailsIntent()

        PermissionStep.ACCESSIBILITY ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        PermissionStep.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .setData(Uri.fromParts("package", context.packageName, null))

        PermissionStep.EXACT_ALARM -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.fromParts("package", context.packageName, null))
            } else {
                null
            }
        }

        PermissionStep.BATTERY_OPTIMIZATION ->
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.fromParts("package", context.packageName, null))

        PermissionStep.AUTO_START -> null // 各厂商路径不同，只做图文指引

        // 运行时权限：没有能"直接授予"的系统设置页，必须由 Activity 就地发起请求
        // （见 LocationPermissionFlow）。这里刻意返回 null，调用方**必须**把它当作
        // "走运行时请求"的信号，而不是"什么都不做" —— 后者正是用户报的
        // "前台定位按了无反应"那个 bug。
        PermissionStep.LOCATION -> null

        PermissionStep.NOTIFICATION_ACCESS ->
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    /**
     * 应用详情设置页（`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`）。
     *
     * 两个地方要用同一个 Intent，所以收成一处：
     *  - 「允许受限设置」这一项的跳转
     *  - 定位权限被**永久拒绝**（勾了"不再询问"）时的退化路径
     *
     * 用 `Uri.fromParts("package", …)` 而不是拼字符串：后者在不同 `applicationIdSuffix`
     * （debug 包名带 `.debug`）下会指到不存在的包，跳转失败且没有任何提示。
     */
    fun appDetailsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))

    companion object {
        /**
         * 无障碍服务的**真实类名**。
         *
         * 刻意用 `::class.java.name` 而不是拼接字符串：
         * 拼接版本会随着包结构或被 `applicationIdSuffix` 影响而悄悄失配，
         * 而失配的表现是"权限开了却检测不到"，极难排查。
         */
        private val ACCESSIBILITY_SERVICE_CLASS: String =
            LockAccessibilityService::class.java.name

        /** 没有查询 API、只能靠用户手动确认的步骤。 */
        val MANUAL_STEPS = setOf(
            PermissionStep.RESTRICTED_SETTINGS,
            PermissionStep.AUTO_START
        )
    }
}
