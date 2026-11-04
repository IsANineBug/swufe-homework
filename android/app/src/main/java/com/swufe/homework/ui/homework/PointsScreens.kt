package com.swufe.homework.ui.homework

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Description
import com.swufe.homework.ui.theme.Corners
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.PointsEntry
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.common.SectionHeader
import com.swufe.homework.ui.theme.Spacing
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import com.swufe.homework.util.Ddl
import com.swufe.homework.util.RulesBlock
import com.swufe.homework.util.RulesMarkdown
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.swufe.homework.ui.theme.semantic

@HiltViewModel
class PointsViewModel @Inject constructor(private val repo: AssignmentRepository) : ViewModel() {
    var total by mutableStateOf<Int?>(null); private set
    var today by mutableStateOf(0); private set
    var items by mutableStateOf<List<PointsEntry>>(emptyList()); private set
    var cursor by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    init { load(reset = true) }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            repo.points(if (reset) null else cursor).onSuccess { p ->
                total = p.semesterTotal; today = p.todayEarned
                items = if (reset) p.items else items + p.items
                cursor = p.nextCursor
                error = null
            }.onFailure { error = it.userMessage }
            loading = false
        }
    }
}

/** 每天最多得的贡献值（与「今日已得 x / 30 分」一致）。 */
private const val DAILY_CAP = 30

fun reasonLabel(r: String) = when (r) {
    "first_post" -> "首发作业"
    "supplement" -> "补充内容"
    "thanks" -> "收到谢谢"
    "deleted_revoke" -> "内容被删除，收回贡献值"
    "deleted_penalty" -> "内容被删除，扣分"
    "restore_refund" -> "管理员恢复，返还贡献值"
    "merge_adjust" -> "作业合并调整"
    "admin_adjust" -> "管理员调整"
    else -> r
}

fun statusLabel(s: String) = when (s) {
    "pending" -> "待到账（保留 24 小时后）"
    "settled" -> "已到账"
    "void" -> "已作废"
    else -> s
}

@Composable
fun MyPointsScreen(onBack: () -> Unit, onRules: () -> Unit, vm: PointsViewModel = hiltViewModel()) {
    Scaffold(topBar = { BackTopBar("我的贡献值", onBack) { TextButton(onClick = onRules) { Text("贡献值规则") } } }) { pad ->
        val total = vm.total
        if (total == null) {
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (vm.error != null) ErrorState(vm.error ?: "", onRetry = { vm.load(reset = true) })
                else LoadingState(skeleton = true)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.xl)) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = Corners.Card,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                ) {
                    Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("本学期贡献值", style = MaterialTheme.typography.titleSmall)
                        Text("$total", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        // 今日进度：每天最多 30 分
                        LinearProgressIndicator(
                            progress = { (vm.today / DAILY_CAP.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(Corners.Tag),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface,
                        )
                        Text("今日已得 ${vm.today} / $DAILY_CAP 分", style = MaterialTheme.typography.bodyMedium)
                        Text("贡献值只作荣誉展示，不能兑换、不能转让。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            vm.error?.let { e -> item { Box(Modifier.padding(horizontal = Spacing.lg)) { ErrorText(e) } } }
            if (vm.items.isEmpty()) {
                item { EmptyState("还没有贡献值记录。首发或补充作业、收到「谢谢」都会记在这里。", icon = Icons.Filled.Star, fill = false) }
            } else {
                item { SectionHeader("明细") }
            }
            items(vm.items) { e ->
                ListItem(
                    overlineContent = { Text(Ddl.display(e.createdAt)) },
                    headlineContent = { Text(reasonLabel(e.reason)) },
                    supportingContent = {
                        Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            e.assignmentTitle?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            MetaTags(listOfNotNull(e.className.ifEmpty { null }, statusLabel(e.status)))
                        }
                    },
                    trailingContent = {
                        Text(
                            (if (e.delta > 0) "+" else "") + e.delta,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                e.status == "void" -> MaterialTheme.colorScheme.onSurfaceVariant
                                e.delta < 0 -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.semantic.success
                            },
                        )
                    },
                )
                HorizontalDivider()
            }
            if (vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(Spacing.lg)) }
            else if (vm.cursor != null) item {
                Box(Modifier.fillMaxWidth().padding(Spacing.sm), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = { vm.load(reset = false) }, shape = Corners.Button) { Text("加载更多") }
                }
            }
        }
    }
}

private fun loadRules(ctx: Context): List<RulesBlock> =
    runCatching { ctx.assets.open("points-rules.md").bufferedReader().use { it.readText() } }
        .map { RulesMarkdown.parse(RulesMarkdown.upperPart(it)) }
        .getOrDefault(emptyList())

/** 原样展示 docs/points-rules.md 上半部分（构建时随 assets 打包）。 */
@Composable
fun PointsRulesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val blocks = remember { loadRules(ctx) }
    Scaffold(topBar = { BackTopBar("贡献值规则", onBack) }) { pad ->
        if (blocks.isEmpty()) {
            EmptyState("规则暂时无法显示，请更新到最新版本。", Modifier.padding(pad), icon = Icons.Outlined.Description)
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            items(blocks) { b ->
                when (b) {
                    is RulesBlock.Title -> Text(b.text, style = MaterialTheme.typography.headlineSmall)
                    is RulesBlock.Heading -> Text(b.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Spacing.sm))
                    is RulesBlock.Quote -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = Corners.Tag, modifier = Modifier.fillMaxWidth()) {
                        Text(rich(b.text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.md))
                    }
                    is RulesBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(rich(b.text), style = MaterialTheme.typography.bodyMedium)
                    }
                    is RulesBlock.Para -> Text(rich(b.text), style = MaterialTheme.typography.bodyMedium)
                    is RulesBlock.Table -> Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.sm)) {
                            TableRow(b.header, header = true)
                            b.rows.forEach { HorizontalDivider(); TableRow(it, header = false) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        cells.forEachIndexed { i, c ->
            Text(
                rich(c),
                modifier = Modifier.weight(if (i == 0) 1.4f else 1f).padding(horizontal = Spacing.xs),
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun rich(t: String) = buildAnnotatedString {
    RulesMarkdown.spans(t).forEach { (s, bold) -> if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s) } else append(s) }
}
