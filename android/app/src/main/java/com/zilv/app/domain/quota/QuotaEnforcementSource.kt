package com.zilv.app.domain.quota

/**
 * 拦截侧读取"因额度用完而被禁用的应用"的**窄接口**。
 *
 * ## 为什么是接口，而不是直接注入 `UsageQuotaRepository`
 *
 * 与 `PlanRuleSource` 完全同一手法（见该类 KDoc 的 A1 决策）：
 *
 *  1. **依赖方向单向、不循环**。
 *     `LockAccessibilityService` → [QuotaEnforcementSource] ←
 *     `UsageQuotaRepository` → (`UsageQuotaDao` + `UsageRepository`)。
 *     接口在 domain 包、实现在 data 包，于是 domain 不依赖 data，
 *     而"额度"这个业务概念不会渗进无障碍服务。
 *  2. **单测可以给一条什么都不返回的实现**（[NONE]），
 *     既有测试与既有调用点因此一行都不用改。
 *  3. **一个方法**：拦截只要"这些包现在该被禁"，不需要知道额度名、
 *     周期、用量 —— 那些是界面与诊断的事。
 *
 * ## 为什么返回"包名 → 原因"而不是单纯的 `Set<String>`
 *
 * 拦截时用户要看到**为什么**被拦（"「抖音」的 30 分钟已用完"，
 * 而不是"锁机中"）。多带一个 [QuotaExhaustion] 不影响"集合"的用法
 * （`keys` 就是集合），却让提示文案与诊断页不必再查一次库。
 */
interface QuotaEnforcementSource {

    /**
     * [atMillis] 这一刻，因额度用完而应当被禁用的应用。
     *
     * @param atMillis 判定时刻（毫秒）。由调用方给，服务侧传
     *        `System.currentTimeMillis()`，单测里传固定值 —— 这样
     *        "周期边界前一秒/后一秒"这类断言完全可复现。
     * @return 包名 → 触发它的额度说明；没有任何额度用尽时返回空 Map
     */
    suspend fun disabledPackages(atMillis: Long): Map<String, QuotaExhaustion>

    /**
     * [atMillis] 这一刻**被启用额度绑定**的所有包名（不管有没有用尽）。
     *
     * 拦截侧用它来判断"这个前台应用值不值得盯着"：
     * 只盯着 [disabledPackages] 的包名是不够的 —— 应用刚打开时额度还没用完，
     * 它就不在禁用集合里，看门狗于是永远不会启动，用户就会一直用下去
     * （"额度用尽后禁用"变成了"下次打开才禁用"）。
     *
     * 默认实现返回空集合：既有实现与测试不必被迫多实现一个方法，
     * 而"没有任何额度"本来就等价于空集合。
     */
    suspend fun boundPackages(atMillis: Long): Set<String> = emptySet()

    companion object {

        /**
         * 什么额度都不限制 —— 构造参数的默认值。
         *
         * 用它当默认值可以让**既有调用点与测试一行都不用改**
         * （与 `PlanRuleSource.NONE` 同一理由）。
         */
        val NONE: QuotaEnforcementSource = object : QuotaEnforcementSource {
            override suspend fun disabledPackages(atMillis: Long): Map<String, QuotaExhaustion> =
                emptyMap()

            override suspend fun boundPackages(atMillis: Long): Set<String> = emptySet()
        }
    }
}

/**
 * 某个额度在本周期内的用量汇总 —— 界面显示"已用多久 / 还剩多久"用它。
 *
 * 与 [QuotaExhaustion] 的区别：[QuotaExhaustion] 只在**已经用尽**时产生，
 * 而这个是每个额度都有的快照（用尽与否都在 [exhausted] 里）。
 */
data class QuotaUsage(
    val quotaId: Long,
    /** 本周期已用分钟数（向上取整，与判定口径一致）。 */
    val usedMinutes: Int,
    /** 本周期还剩多少分钟；已用尽时为 0。 */
    val remainingMinutes: Int,
    val exhausted: Boolean
) {
    companion object {
        /** 还没算出来时的占位（界面用 `--` 显示）。 */
        val UNKNOWN = QuotaUsage(
            quotaId = 0L,
            usedMinutes = 0,
            remainingMinutes = 0,
            exhausted = false
        )
    }
}
