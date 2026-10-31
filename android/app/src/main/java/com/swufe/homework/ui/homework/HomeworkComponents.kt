package com.swufe.homework.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swufe.homework.BuildConfig
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.util.Ddl
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic

/** DDL 不一致（ddl_conflict）、24 小时内截止统一用这个红（主题语义色 urgent，随深浅模式切换）。 */
val ConflictRed: Color
    @Composable get() = MaterialTheme.semantic.urgent

@Composable
fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = Corners.Tag) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 「待确认」标签（ddl_uncertain）。 */
@Composable
fun UncertainTag() = Tag("待确认", MaterialTheme.semantic.warningContainer, MaterialTheme.semantic.onWarningContainer)

@Composable
fun ConflictTag() = Tag("DDL 不一致", MaterialTheme.semantic.urgentContainer, MaterialTheme.semantic.onUrgentContainer)

/** 中性的信息标签（「首发 同学A」「已合并 2 条」），代替用「·」拼接的说明行。 */
@Composable
fun MetaTag(text: String) = Tag(text, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)

/** 一组 [MetaTag]，自动换行；列表为空时什么都不画。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaTags(items: List<String>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        items.forEach { MetaTag(it) }
    }
}

/** 来源卡片的署名行：作者（较重）与发布时间（较轻）分开排。 */
@Composable
fun SourceByline(author: String, createdAt: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(author, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (createdAt != null) {
            Text(Ddl.display(createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * 截止时间行：「10月23日 周五 13:55」与「还剩 2 天」分开排，后者加粗；
 * DDL 不一致或 24 小时内截止（[urgent]）时整行标红。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DdlLine(ddl: String, conflict: Boolean, uncertain: Boolean, modifier: Modifier = Modifier, urgent: Boolean = false) {
    val t = Ddl.parse(ddl)
    val red = conflict || urgent
    val color = if (red) ConflictRed else MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            if (t != null) Ddl.display(t) else ddl,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        if (t != null) {
            Text(
                Ddl.remaining(t),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (uncertain) Box(Modifier.align(Alignment.CenterVertically)) { UncertainTag() }
        if (conflict) Box(Modifier.align(Alignment.CenterVertically)) { ConflictTag() }
    }
}

private val STUB_WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 卡片左侧的「日历票根」：月份与星期、大号日期、时刻。学生看作业先看哪天交，所以日期是卡片最醒目的东西。
 * 24 小时内截止用 Urgent 底色，已完成退成灰底。
 */
@Composable
fun DateStub(ddl: String, urgent: Boolean, done: Boolean, modifier: Modifier = Modifier) {
    val t = Ddl.parse(ddl)
    val s = MaterialTheme.semantic
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when {
        done -> cs.surfaceVariant to cs.onSurfaceVariant
        urgent -> s.urgentContainer to s.onUrgentContainer
        else -> cs.secondaryContainer to cs.onSecondaryContainer
    }
    Column(
        modifier.width(64.dp).fillMaxHeight().background(bg, Corners.Tag).padding(vertical = Spacing.sm)
            .clearAndSetSemantics {
                contentDescription = (t?.let { "截止 ${Ddl.display(it)}" } ?: "截止时间未知") + if (urgent) "，24 小时内截止" else ""
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (t == null) {
            Text("待定", style = MaterialTheme.typography.labelMedium, color = fg)
        } else {
            Text("${t.monthValue}月 ${STUB_WEEK[t.dayOfWeek.value - 1]}", style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
            Text("${t.dayOfMonth}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = fg)
            Text("%02d:%02d".format(t.hour, t.minute), style = MaterialTheme.typography.labelSmall, color = fg)
        }
    }
}

/**
 * 作业卡片：左侧日期票根，右侧课程、标题、剩余时间与标签，可选勾选完成。
 * [urgent] = 24 小时内截止（见 [HomeFilter.isUrgent]）：票根变 Urgent 色 + 剩余时间变红。
 * [onToggleDone] 为 null 时不显示勾选框（班级页外的只读场景、游客浏览）。
 */
@Composable
fun AssignmentCard(
    a: AssignmentSummary,
    showClass: Boolean,
    onClick: () -> Unit,
    onToggleDone: ((Boolean) -> Unit)?,
    urgent: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = Corners.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (a.done) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            Modifier.height(IntrinsicSize.Min).padding(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            DateStub(a.ddl, urgent = urgent, done = a.done)
            Column(
                Modifier.weight(1f).padding(vertical = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                if (showClass && a.className.isNotEmpty()) {
                    Text(
                        a.className,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (a.done) TextDecoration.LineThrough else null,
                    color = if (a.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                RemainingRow(a, urgent)
            }
            if (onToggleDone != null) {
                Checkbox(
                    checked = a.done,
                    onCheckedChange = onToggleDone,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

/** 卡片底行：剩余时间（加粗，紧急时变红）+ 待确认 / DDL 不一致标签 + 来源数。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemainingRow(a: AssignmentSummary, urgent: Boolean) {
    val t = Ddl.parse(a.ddl)
    val red = a.ddlConflict || urgent
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (t != null) {
            Text(
                if (a.done) "已完成" else Ddl.remaining(t),
                style = MaterialTheme.typography.labelLarge,
                color = if (red && !a.done) ConflictRed else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        if (a.ddlUncertain) Box(Modifier.align(Alignment.CenterVertically)) { UncertainTag() }
        if (a.ddlConflict) Box(Modifier.align(Alignment.CenterVertically)) { ConflictTag() }
        if (a.sourceCount > 1) Box(Modifier.align(Alignment.CenterVertically)) { MetaTag("${a.sourceCount} 条来源") }
    }
}

/** 服务器返回的文件 URL 可能是相对路径（/files/...），补成服务器地址。 */
fun absoluteUrl(url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    val base = BuildConfig.SERVER_URL
    val origin = Regex("^(https?://[^/]+)").find(base)?.value ?: return url
    return if (url.startsWith("/")) origin + url else base.trimEnd('/') + "/" + url
}
