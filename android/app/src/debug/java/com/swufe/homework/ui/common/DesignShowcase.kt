package com.swufe.homework.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.ui.homework.AssignmentCard
import com.swufe.homework.ui.theme.CourseColors
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.OnCourseColor
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.SwufeTheme
import com.swufe.homework.ui.theme.semantic
import com.swufe.homework.util.Ddl

/**
 * 视觉规范一览（只在 debug 构建里）：颜色角色、字号、圆角、间距与通用组件放在一屏。
 * README「视觉规范」的截图由 DesignScreenshotTest 渲染这个页面得到。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DesignShowcase() {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(title = { Text("视觉规范") })
            StatusBanner("课表识别中…", BannerTone.Progress)
            Box(Modifier.padding(top = 2.dp)) {
                StatusBanner("课表识别好了", BannerTone.Success, actionLabel = "去确认", onAction = {})
            }
            Box(Modifier.padding(top = 2.dp)) {
                StatusBanner("识别失败：AI 暂时不可用", BannerTone.Error, actionLabel = "手动添加", onAction = {})
            }

            SectionHeader("颜色角色")
            FlowRow(
                Modifier.padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                val cs = MaterialTheme.colorScheme
                val s = MaterialTheme.semantic
                Swatch("primary", cs.primary, cs.onPrimary)
                Swatch("primaryContainer", cs.primaryContainer, cs.onPrimaryContainer)
                Swatch("secondaryContainer", cs.secondaryContainer, cs.onSecondaryContainer)
                Swatch("tertiaryContainer", cs.tertiaryContainer, cs.onTertiaryContainer)
                Swatch("surfaceContainer", cs.surfaceContainer, cs.onSurface)
                Swatch("errorContainer", cs.errorContainer, cs.onErrorContainer)
                Swatch("Urgent", s.urgentContainer, s.onUrgentContainer)
                Swatch("Warning", s.warningContainer, s.onWarningContainer)
                Swatch("Success", s.successContainer, s.onSuccessContainer)
            }

            SectionHeader("字号（标题 / 正文 / 辅助）")
            Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("页面标题 headlineSmall 22sp", style = MaterialTheme.typography.headlineSmall)
                Text("卡片标题 titleMedium 17sp", style = MaterialTheme.typography.titleMedium)
                Text("正文 bodyMedium 15sp：作业要求、说明文字。", style = MaterialTheme.typography.bodyMedium)
                Text("辅助 bodySmall 13sp：时间、来源、提示", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("标签 labelSmall 12sp（最小字号）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionHeader("卡片 16dp，标签 8dp", actionLabel = "全部", onAction = {})
            val now = Ddl.now()
            Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AssignmentCard(
                    AssignmentSummary(1, 3, "计量经济学", "第三章课后习题 3.2、3.5", Ddl.toApi(now.plusHours(20)), ddlConflict = true, sourceCount = 3),
                    showClass = true, onClick = {}, onToggleDone = {},
                )
                AssignmentCard(
                    AssignmentSummary(2, 4, "公司金融", "小组案例报告（PDF 提交）", Ddl.toApi(now.plusDays(3)), ddlUncertain = true),
                    showClass = true, onClick = {}, onToggleDone = {},
                )
            }

            SectionHeader("按钮 12dp")
            Row(Modifier.padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = {}, shape = Corners.Button) { Text("发布作业") }
                OutlinedButton(onClick = {}, shape = Corners.Button) { Text("作为补充") }
                TextButton(onClick = {}) { Text("取消") }
            }

            SectionHeader("课表配色")
            FlowRow(
                Modifier.padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                val names = listOf("微观经济学", "高等数学", "会计学原理", "统计学", "大学英语", "金融学", "管理学", "线性代数", "思修", "体育")
                CourseColors.forEachIndexed { i, c ->
                    Box(Modifier.width(68.dp).background(c, Corners.Tag).padding(Spacing.xs)) {
                        Text(names[i], color = OnCourseColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            SectionHeader("空 / 加载 / 出错")
            EmptyState("这周还没有截止的作业。发布一条，同班同学都能看到。", title = "这周没有作业", actionLabel = "发布作业", onAction = {}, fill = false)
            LoadingState(skeleton = true, skeletonRows = 1, fill = false)
            ErrorState("网络连接失败，请检查网络后重试", title = "作业没有加载出来", onRetry = {}, fill = false)
        }
    }
}

@Composable
private fun Swatch(name: String, bg: Color, fg: Color) {
    Box(
        Modifier.size(width = 112.dp, height = 44.dp)
            .background(bg, Corners.Tag)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Corners.Tag)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) { Text(name, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Preview(name = "浅色", widthDp = 412, heightDp = 1580)
@Composable
private fun ShowcaseLight() = SwufeTheme(dark = false) { DesignShowcase() }

@Preview(name = "深色", widthDp = 412, heightDp = 1580)
@Composable
private fun ShowcaseDark() = SwufeTheme(dark = true) { DesignShowcase() }
