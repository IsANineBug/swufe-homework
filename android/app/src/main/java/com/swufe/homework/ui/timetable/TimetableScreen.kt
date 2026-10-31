package com.swufe.homework.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.CalendarMonth
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.StatusBanner
import com.swufe.homework.util.Ddl
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swufe.homework.data.api.Period
import com.swufe.homework.ui.theme.CourseColors
import com.swufe.homework.util.WEEKDAY_NAMES
import com.swufe.homework.ui.theme.OnCourseColor

// 网格尺寸（布局常量，不是间距）：每节一行，左侧时间轴宽度
private val ROW_H = 60.dp
private val AXIS_W = 44.dp
private val BLOCK_GAP = 2.dp
private val ICON = 18.dp

/** 课表 tab：周一到周五 × 12 节网格。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    onImport: () -> Unit,
    onManual: () -> Unit,
    onOpenClass: (Int) -> Unit,
    vm: TimetableViewModel = hiltViewModel(),
) {
    val classes by vm.classes.collectAsStateWithLifecycle()
    val periods by vm.periods.collectAsStateWithLifecycle()
    val aiOnline by vm.aiOnline.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val unread by vm.unreadClasses.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("课表") },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            },
        )
        if (refreshing && classes.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
        ImportBar(aiOnline, onImport, onManual)
        when {
            classes.isNotEmpty() -> {
                // 有旧课表时出错：保留课表，顶部提示 + 重试
                error?.let { StatusBanner(it, BannerTone.Error, actionLabel = "重试", onAction = vm::refresh) }
                TimetableGrid(periods, buildBlocks(classes), onOpenClass, unread)
            }
            refreshing -> LoadingState(message = "正在加载课表…")
            error != null -> ErrorState(error ?: "", onRetry = vm::refresh)
            else -> EmptyState(
                "导入易校园课表截图，或手动添加课程。",
                title = "还没有课程",
                icon = Icons.Filled.CalendarMonth,
                actionLabel = "手动添加",
                onAction = onManual,
            )
        }
    }
}

@Composable
private fun ImportBar(aiOnline: Boolean, onImport: () -> Unit, onManual: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = onImport, enabled = aiOnline, shape = Corners.Button, modifier = Modifier.weight(1f)) { Text("导入课表截图") }
            OutlinedButton(onClick = onManual, shape = Corners.Button, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ICON))
                Spacer(Modifier.width(Spacing.xs))
                Text("手动添加")
            }
        }
        if (!aiOnline) {
            Text(AI_OFFLINE_MESSAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TimetableGrid(periods: List<Period>, blocks: List<GridBlock>, onOpenClass: (Int) -> Unit, unread: Set<Int> = emptySet()) {
    // 今天是周几（1–7）：表头标出来，那一列铺浅底，扫一眼就知道今天有什么课
    val today = remember { Ddl.now().dayOfWeek.value }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = AXIS_W, top = Spacing.xs, bottom = Spacing.xs)) {
            WEEKDAY_NAMES.forEachIndexed { i, name ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i + 1 == today) {
                        Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = Corners.Tag) {
                            Text(
                                name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                                    .semantics { contentDescription = "$name（今天）" },
                            )
                        }
                    } else {
                        Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            val colW: Dp = (maxWidth - AXIS_W) / 5
            Box(Modifier.height(ROW_H * 12).fillMaxWidth()) {
                if (today in 1..WEEKDAY_NAMES.size) {
                    Box(
                        Modifier.offset(x = AXIS_W + colW * (today - 1)).width(colW).height(ROW_H * 12)
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
                // 左侧时间轴 + 横线
                for (i in 0 until 12) {
                    val p = periods.getOrNull(i)
                    Column(
                        Modifier.offset(y = ROW_H * i).width(AXIS_W).height(ROW_H),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                        if (p != null) Text(p.start, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Box(
                        Modifier.offset(x = AXIS_W, y = ROW_H * i).fillMaxWidth().height(Dp.Hairline)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                blocks.forEach { b ->
                    val color = CourseColors[Math.floorMod(b.classId, CourseColors.size)]
                    Box(
                        Modifier
                            .offset(x = AXIS_W + colW * (b.weekday - 1), y = ROW_H * (b.start - 1))
                            .width(colW).height(ROW_H * (b.end - b.start + 1))
                            .padding(BLOCK_GAP)
                            .clip(Corners.Tag)
                            .background(color)
                            .clickable { onOpenClass(b.classId) }
                            .padding(Spacing.xs),
                    ) {
                        Column {
                            Text(b.name, color = OnCourseColor, style = MaterialTheme.typography.labelMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(b.room, color = OnCourseColor, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        // WS11：班级频道有未读消息
                        if (b.classId in unread) Badge(Modifier.align(Alignment.TopEnd))
                    }
                }
            }
        }
    }
}
