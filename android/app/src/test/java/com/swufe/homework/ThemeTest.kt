package com.swufe.homework

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.TextUnit
import com.swufe.homework.ui.common.countdownLabel
import com.swufe.homework.ui.theme.DarkColors
import com.swufe.homework.ui.theme.DarkCourseColors
import com.swufe.homework.ui.theme.DarkSemantic
import com.swufe.homework.ui.theme.LightColors
import com.swufe.homework.ui.theme.LightCourseColors
import com.swufe.homework.ui.theme.LightSemantic
import com.swufe.homework.ui.theme.MinTextSize
import com.swufe.homework.ui.theme.OnCourseColor
import com.swufe.homework.ui.theme.SemanticColors
import com.swufe.homework.ui.theme.SwufeRed
import com.swufe.homework.ui.theme.SwufeTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
    private fun contrast(a: Color, b: Color): Double {
        val x = maxOf(a.luminance(), b.luminance()) + 0.05
        val y = minOf(a.luminance(), b.luminance()) + 0.05
        return x.toDouble() / y
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val c = contrast(fg, bg)
        assertTrue("$name 对比度 %.2f < $min".format(c), c >= min)
    }

    private fun checkScheme(tag: String, cs: ColorScheme, s: SemanticColors) {
        // 正文与辅助文字按 WCAG AA 正文（4.5:1）要求
        assertContrast("$tag onSurface", cs.onSurface, cs.surface, 7.0)
        assertContrast("$tag onSurfaceVariant", cs.onSurfaceVariant, cs.surface, 4.5)
        assertContrast("$tag onSurfaceVariant/container", cs.onSurfaceVariant, cs.surfaceContainerHighest, 4.5)
        assertContrast("$tag onPrimary", cs.onPrimary, cs.primary, 4.5)
        assertContrast("$tag primary 文字", cs.primary, cs.surface, 4.5)
        assertContrast("$tag onPrimaryContainer", cs.onPrimaryContainer, cs.primaryContainer, 4.5)
        assertContrast("$tag onSecondaryContainer", cs.onSecondaryContainer, cs.secondaryContainer, 4.5)
        assertContrast("$tag onErrorContainer", cs.onErrorContainer, cs.errorContainer, 4.5)
        assertContrast("$tag error 文字", cs.error, cs.surface, 4.5)
        // outline 是边框/非文字元素，WCAG 非文字 3:1
        assertContrast("$tag outline", cs.outline, cs.surface, 3.0)
        assertContrast("$tag urgent", s.urgent, cs.surface, 4.5)
        assertContrast("$tag warning", s.warning, cs.surface, 4.5)
        assertContrast("$tag success", s.success, cs.surface, 4.5)
        assertContrast("$tag onUrgentContainer", s.onUrgentContainer, s.urgentContainer, 4.5)
        assertContrast("$tag onWarningContainer", s.onWarningContainer, s.warningContainer, 4.5)
        assertContrast("$tag onSuccessContainer", s.onSuccessContainer, s.successContainer, 4.5)
        assertContrast("$tag reviewHighlight 上的字", cs.onSurface, s.reviewHighlight, 4.5)
    }

    @Test fun lightContrast() = checkScheme("浅色", LightColors, LightSemantic)

    @Test fun darkContrast() = checkScheme("深色", DarkColors, DarkSemantic)

    @Test fun brandPrimary() = assertEquals(SwufeRed, LightColors.primary)

    @Test fun courseColors() {
        assertEquals(LightCourseColors.size, DarkCourseColors.size)
        LightCourseColors.forEach { assertContrast("浅色课表块", OnCourseColor, it, 4.5) }
        DarkCourseColors.forEach {
            assertContrast("深色课表块", OnCourseColor, it, 4.5)
            assertContrast("深色课表块与背景", it, DarkColors.surface, 2.5)
        }
    }

    @Test fun minTextSize() {
        val t = SwufeTypography
        val sizes: List<Pair<String, TextUnit>> = listOf(
            "displayLarge" to t.displayLarge.fontSize, "displayMedium" to t.displayMedium.fontSize, "displaySmall" to t.displaySmall.fontSize,
            "headlineLarge" to t.headlineLarge.fontSize, "headlineMedium" to t.headlineMedium.fontSize, "headlineSmall" to t.headlineSmall.fontSize,
            "titleLarge" to t.titleLarge.fontSize, "titleMedium" to t.titleMedium.fontSize, "titleSmall" to t.titleSmall.fontSize,
            "bodyLarge" to t.bodyLarge.fontSize, "bodyMedium" to t.bodyMedium.fontSize, "bodySmall" to t.bodySmall.fontSize,
            "labelLarge" to t.labelLarge.fontSize, "labelMedium" to t.labelMedium.fontSize, "labelSmall" to t.labelSmall.fontSize,
        )
        sizes.forEach { (n, s) -> assertTrue("$n = $s < 12sp", s.value >= MinTextSize.value) }
    }

    @Test fun countdown() {
        assertEquals("确认删除（3）", countdownLabel("确认删除", 3))
        assertEquals("确认删除", countdownLabel("确认删除", 0))
    }
}
