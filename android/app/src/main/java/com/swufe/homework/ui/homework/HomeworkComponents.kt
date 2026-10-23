package com.swufe.homework.ui.homework

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swufe.homework.BuildConfig
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.util.Ddl
import com.swufe.homework.ui.theme.Corners
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

/** 10月23日 周五 13:55 · 还剩 2 天；不一致时整行标红。 */
@Composable
fun DdlLine(ddl: String, conflict: Boolean, uncertain: Boolean, modifier: Modifier = Modifier) {
    val t = Ddl.parse(ddl)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (t != null) "${Ddl.display(t)} · ${Ddl.remaining(t)}" else ddl,
            style = MaterialTheme.typography.bodyMedium,
            color = if (conflict) ConflictRed else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (conflict) FontWeight.SemiBold else null,
        )
        if (uncertain) UncertainTag()
        if (conflict) ConflictTag()
    }
}

@Composable
fun AssignmentCard(
    a: AssignmentSummary,
    showClass: Boolean,
    onClick: () -> Unit,
    onToggleDone: ((Boolean) -> Unit)?,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (a.done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onToggleDone != null) Checkbox(checked = a.done, onCheckedChange = onToggleDone)
            Column(Modifier.weight(1f).padding(start = if (onToggleDone == null) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (showClass) Text(a.className, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (a.done) TextDecoration.LineThrough else null,
                )
                DdlLine(a.ddl, a.ddlConflict, a.ddlUncertain)
                if (a.sourceCount > 1) {
                    Text("${a.sourceCount} 条来源", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** 服务器返回的文件 URL 可能是相对路径（/files/...），补成服务器地址。 */
fun absoluteUrl(url: String): String {
    if (url.startsWith("http://") || url.startsWith("https://")) return url
    val base = BuildConfig.SERVER_URL
    val origin = Regex("^(https?://[^/]+)").find(base)?.value ?: return url
    return if (url.startsWith("/")) origin + url else base.trimEnd('/') + "/" + url
}
