package com.swufe.homework.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 西财红，品牌主色。浅色模式下就是 primary。 */
val SwufeRed = Color(0xFF8B1A1A)

/*
 * 色板以西财红为种子，按 Material 3 的色调角色补齐（primary 保持品牌原色不做色调偏移）。
 * secondary 是偏灰的暖红，tertiary 是与红相配的金色。
 * 文字/底色对比度（WCAG）：正文 ≥ 7:1，辅助文字（onSurfaceVariant）≥ 7:1，outline ≥ 4.2:1，
 * 语义色文字放在 surface 上 ≥ 5:1；深色模式同样达标，见 README「视觉规范」。
 */
internal val LightColors = lightColorScheme(
    primary = SwufeRed,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    inversePrimary = Color(0xFFFFB4AA),
    secondary = Color(0xFF775652),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDAD5),
    onSecondaryContainer = Color(0xFF2C1512),
    tertiary = Color(0xFF705C2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFCDFA6),
    onTertiaryContainer = Color(0xFF261A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF231918),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF231918),
    surfaceVariant = Color(0xFFF5DDDA),
    onSurfaceVariant = Color(0xFF534341),
    surfaceTint = SwufeRed,
    inverseSurface = Color(0xFF392E2C),
    inverseOnSurface = Color(0xFFFFEDEA),
    outline = Color(0xFF857371),
    outlineVariant = Color(0xFFD8C2BE),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceDim = Color(0xFFE8D6D3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF0EE),
    surfaceContainer = Color(0xFFFCEAE7),
    surfaceContainerHigh = Color(0xFFF6E4E2),
    surfaceContainerHighest = Color(0xFFF1DEDC),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AA),
    onPrimary = Color(0xFF561E19),
    primaryContainer = Color(0xFF73342E),
    onPrimaryContainer = Color(0xFFFFDAD5),
    inversePrimary = SwufeRed,
    secondary = Color(0xFFE7BDB7),
    onSecondary = Color(0xFF442926),
    secondaryContainer = Color(0xFF5D3F3B),
    onSecondaryContainer = Color(0xFFFFDAD5),
    tertiary = Color(0xFFDFC38C),
    onTertiary = Color(0xFF3E2E04),
    tertiaryContainer = Color(0xFF574419),
    onTertiaryContainer = Color(0xFFFCDFA6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A1110),
    onBackground = Color(0xFFF1DEDC),
    surface = Color(0xFF1A1110),
    onSurface = Color(0xFFF1DEDC),
    surfaceVariant = Color(0xFF534341),
    onSurfaceVariant = Color(0xFFD8C2BE),
    surfaceTint = Color(0xFFFFB4AA),
    inverseSurface = Color(0xFFF1DEDC),
    inverseOnSurface = Color(0xFF392E2C),
    outline = Color(0xFFA08C89),
    outlineVariant = Color(0xFF534341),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423735),
    surfaceDim = Color(0xFF1A1110),
    surfaceContainerLowest = Color(0xFF140C0B),
    surfaceContainerLow = Color(0xFF231918),
    surfaceContainer = Color(0xFF271D1C),
    surfaceContainerHigh = Color(0xFF322826),
    surfaceContainerHighest = Color(0xFF3D3231),
)

/**
 * Material 没有的语义色。用法：
 * - urgent：24 小时内截止、DDL 不一致（文字/图标用 [urgent]，标签底用 [urgentContainer] + [onUrgentContainer]）
 * - warning：待确认（ddl_uncertain、识别拿不准）
 * - success：已完成、已开启、加分
 * - reviewHighlight：需要用户核对的输入框/格子底色（上面的文字仍用 onSurface）
 */
@Immutable
data class SemanticColors(
    val urgent: Color,
    val urgentContainer: Color,
    val onUrgentContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val reviewHighlight: Color,
)

internal val LightSemantic = SemanticColors(
    urgent = Color(0xFFC62828),
    urgentContainer = Color(0xFFFFDAD6),
    onUrgentContainer = Color(0xFF410002),
    warning = Color(0xFF7A5900),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF261A00),
    success = Color(0xFF2E6B30),
    successContainer = Color(0xFFB1F1AA),
    onSuccessContainer = Color(0xFF002204),
    reviewHighlight = Color(0xFFFFF3C4),
)

internal val DarkSemantic = SemanticColors(
    urgent = Color(0xFFFFB4AB),
    urgentContainer = Color(0xFF93000A),
    onUrgentContainer = Color(0xFFFFDAD6),
    warning = Color(0xFFF4BF48),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDF9E),
    success = Color(0xFF96D58F),
    successContainer = Color(0xFF0F5219),
    onSuccessContainer = Color(0xFFB1F1AA),
    reviewHighlight = Color(0xFF4A3B00),
)

/*
 * 课表色块：与西财红协调的低饱和色。块上文字一律白色（[OnCourseColor]）：
 * 浅色一套对白字 ≥ 5:1，深色一套对白字 ≥ 5.8:1，且与深色背景能区分开。
 */
internal val LightCourseColors = listOf(
    Color(0xFFA64B45), Color(0xFF3B6EA5), Color(0xFF3D7A4E), Color(0xFF8F6310), Color(0xFF7D4E9A),
    Color(0xFF2C7A78), Color(0xFFA8466E), Color(0xFF5E5CA3), Color(0xFF7A5C4F), Color(0xFF2E7196),
)

internal val DarkCourseColors = listOf(
    Color(0xFF934A44), Color(0xFF34619A), Color(0xFF387048), Color(0xFF7D5A12), Color(0xFF6E4888),
    Color(0xFF276B69), Color(0xFF8E3F60), Color(0xFF55529A), Color(0xFF6B5246), Color(0xFF29647F),
)

/** 课表色块上的文字颜色（深浅模式都是白色）。 */
val OnCourseColor = Color(0xFFFFFFFF)
