package com.zilv.app.intercept

/**
 * 包名 → 应用显示名。
 *
 * 抽成接口是为了让依赖它的 ViewModel **可单元测试** ——
 * 真实的 [AppLabelResolver] 需要 Android Context 查 PackageManager。
 * 与 `LocationSource` 是同样的处理方式。
 */
interface AppLabelSource {

    /** 单个包的显示名；解析不到时回退为包名。 */
    fun labelOf(packageName: String): String

    /** 批量解析。 */
    fun labelsOf(packageNames: Collection<String>): Map<String, String>
}
