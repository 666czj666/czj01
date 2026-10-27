package com.zilv.app.intercept

import android.content.Context

/**
 * 包名 → 应用显示名。
 *
 * 带内存缓存：报告页一次要解析几十个包名，PackageManager 查询不便宜。
 * 解析不到时回退为包名本身（不抛异常、不显示空白）。
 */
class AppLabelResolver(private val context: Context) : AppLabelSource {

    private val cache = mutableMapOf<String, String>()

    private val pm get() = context.packageManager

    override fun labelOf(packageName: String): String {
        cache[packageName]?.let { return it }

        val label = runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrElse { fallback(packageName) }

        cache[packageName] = label
        return label
    }

    /** 批量解析（一次遍历，供报告页使用）。 */
    override fun labelsOf(packageNames: Collection<String>): Map<String, String> =
        packageNames.associateWith { labelOf(it) }

    fun invalidate() = cache.clear()

    private fun fallback(packageName: String): String = when (packageName) {
        "com.tencent.mm" -> "微信"
        "com.eg.android.AlipayGphone" -> "支付宝"
        "com.android.systemui" -> "系统界面"
        "com.android.settings" -> "设置"
        else -> packageName
    }
}
