package com.swufe.homework.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SwufeRed = Color(0xFF8B1A1A)

private val Light = lightColorScheme(primary = SwufeRed, secondary = Color(0xFF6D4C41), tertiary = Color(0xFF2E6B5E))
private val Dark = darkColorScheme(primary = Color(0xFFFFB4A9), secondary = Color(0xFFE7BDB0), tertiary = Color(0xFF8ED1BF))

/** 课表色块的配色，按班级 id 取。 */
val CourseColors = listOf(
    Color(0xFFE57373), Color(0xFF64B5F6), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFBA68C8),
    Color(0xFF4DB6AC), Color(0xFFF06292), Color(0xFF9575CD), Color(0xFFA1887F), Color(0xFF4FC3F7),
)

/** 需要用户核对的字段（截断课名、起节被裁）。 */
val ReviewHighlight = Color(0xFFFFF3C4)

@Composable
fun SwufeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // 不用动态取色，保持西财红为主色
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
