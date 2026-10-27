package com.zilv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PRD §4.9 配色：
 * 首页状态卡是唯一有强色的地方；其余页面用克制的 Material 3 配色。
 */
private val DeepIndigo = Color(0xFF1B2A4A)
private val DeepIndigoLight = Color(0xFF3D5A8A)

/** 状态色（PRD §4.2 状态卡片四态）。 */
object StatusColors {
    val Idle = Color(0xFF6B7280)      // 灰：空闲
    val Warn = Color(0xFFD97706)      // 橙：预警中
    val Locked = Color(0xFF1B2A4A)    // 深靛：锁机中
    val Unlocked = Color(0xFF15803D)  // 绿：已解锁

    /** 遮挡层背景（PRD §4.5） */
    val OverlayBackground = Color(0xFF0A0A0A)

    /**
     * 取某个状态色的柔和底色（用于卡片、徽标背景）。
     *
     * 统一走这里而不是各处手写 `copy(alpha = 0.12f)` ——
     * 深浅色模式下这个透明度需求不同，分散写会导致暗色下过亮、亮色下过淡。
     */
    fun soft(base: Color, darkTheme: Boolean): Color =
        base.copy(alpha = if (darkTheme) 0.22f else 0.12f)
}

/**
 * 圆角统一从 Shapes 取，各处不要再写魔法数字。
 *
 * 原先代码里 RoundedCornerShape(10.dp / 12.dp / 16.dp) 混用，
 * 相邻卡片圆角不一致会显得界面"没对齐"。
 */
private val ZilvShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** 少量排版微调：标题收紧字距、正文放宽行高，纯观感。 */
private val baseTypography = Typography()

private val ZilvTypography = Typography(
    titleLarge = baseTypography.titleLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = baseTypography.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp
    ),
    bodyLarge = baseTypography.bodyLarge.copy(lineHeight = 23.sp),
    bodyMedium = baseTypography.bodyMedium.copy(lineHeight = 21.sp)
)

private val LightScheme = lightColorScheme(
    primary = DeepIndigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5F5),
    onPrimaryContainer = DeepIndigo,
    secondary = DeepIndigoLight,
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFFC4C7CF)
)

private val DarkScheme = darkColorScheme(
    primary = DeepIndigoLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A3E63),
    onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = DeepIndigo,
    onSecondary = Color.White,
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF17191F),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF23252B),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outline = Color(0xFF8E9099)
)

@Composable
fun ZilvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = ZilvShapes,
        typography = ZilvTypography,
        content = content
    )
}
