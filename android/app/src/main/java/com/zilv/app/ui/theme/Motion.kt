package com.zilv.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 统一的动效参数。
 *
 * 集中定义是为了**全 App 手感一致** —— 各处自己写 `tween(300)` 的结果
 * 就是同一次操作在不同页面快慢不一，看起来像没做完。
 *
 * 原则（也是 Material 的建议）：
 *  - 进入比退出慢：出现要"被看见"，消失要"快点让开"
 *  - 位移用缓动、透明度用线性，二者叠加才不会显得发飘
 *  - 列表增删用弹簧，带一点点回弹，比纯 tween 更有"实体感"
 */
object Motion {

    /** 短：点按反馈、状态徽标切换 */
    const val FAST = 140

    /** 常规：多数进场/退场 */
    const val MEDIUM = 260

    /** 长：整页转场 */
    const val SLOW = 340

    val standard: Easing = FastOutSlowInEasing

    /** 退场用：先快后慢，显得"让开得干脆" */
    val exit: Easing = CubicBezierEasing(0.4f, 0.0f, 1f, 1f)

    /** 进场用：先慢后快，显得"稳稳落位" */
    val enter: Easing = LinearOutSlowInEasing

    fun <T> enterSpec(durationMillis: Int = MEDIUM): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = enter)

    fun <T> exitSpec(durationMillis: Int = FAST): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = exit)

    /** 列表项增删：轻微回弹 */
    fun <T> listSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    /** 整页转场位移（水平滑入/滑出） */
    fun slideSpec(): FiniteAnimationSpec<IntOffset> =
        tween(durationMillis = SLOW, easing = standard)
}
