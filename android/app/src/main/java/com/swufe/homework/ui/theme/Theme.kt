package com.swufe.homework.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }
private val LocalCourseColors = staticCompositionLocalOf { LightCourseColors }

@Composable
fun SwufeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 不用动态取色，保持西财红为主色
    CompositionLocalProvider(
        LocalSemanticColors provides if (dark) DarkSemantic else LightSemantic,
        LocalCourseColors provides if (dark) DarkCourseColors else LightCourseColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = SwufeTypography,
            shapes = SwufeShapes,
            content = content,
        )
    }
}

/** 语义色：`MaterialTheme.semantic.urgent` 等，随深浅模式切换。 */
val MaterialTheme.semantic: SemanticColors
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current

/** 课表色块的配色，按班级 id 取：`CourseColors[floorMod(id, size)]`，块上文字用 [OnCourseColor]。 */
val CourseColors: List<Color>
    @Composable @ReadOnlyComposable get() = LocalCourseColors.current

/** 需要用户核对的字段（截断课名、起节被裁、识别拿不准）。 */
val ReviewHighlight: Color
    @Composable @ReadOnlyComposable get() = LocalSemanticColors.current.reviewHighlight
