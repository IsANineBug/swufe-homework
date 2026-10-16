package com.swufe.homework.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swufe.homework.data.api.Period
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.theme.CourseColors
import com.swufe.homework.util.WEEKDAY_NAMES

private val ROW_H = 56.dp
private val AXIS_W = 40.dp

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

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("课表") },
            actions = {
                IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            },
        )
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        ImportBar(aiOnline, onImport, onManual)
        if (error != null) Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(error) }
        if (classes.isEmpty() && !refreshing) {
            Text(
                "还没有课程。导入易校园课表截图，或手动添加课程。",
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TimetableGrid(periods, buildBlocks(classes), onOpenClass)
    }
}

@Composable
private fun ImportBar(aiOnline: Boolean, onImport: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onImport, enabled = aiOnline, modifier = Modifier.weight(1f)) { Text("导入课表截图") }
            OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("手动添加")
            }
        }
        if (!aiOnline) {
            Text("电脑未在线，请手动添加", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun TimetableGrid(periods: List<Period>, blocks: List<GridBlock>, onOpenClass: (Int) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = AXIS_W)) {
            WEEKDAY_NAMES.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            val colW: Dp = (maxWidth - AXIS_W) / 5
            Box(Modifier.height(ROW_H * 12).fillMaxWidth()) {
                // 左侧时间轴 + 横线
                for (i in 0 until 12) {
                    val p = periods.getOrNull(i)
                    Column(
                        Modifier.offset(y = ROW_H * i).width(AXIS_W).height(ROW_H),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium)
                        if (p != null) Text(p.start, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Box(
                        Modifier.offset(x = AXIS_W, y = ROW_H * i).fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    )
                }
                blocks.forEach { b ->
                    val color = CourseColors[Math.floorMod(b.classId, CourseColors.size)]
                    Box(
                        Modifier
                            .offset(x = AXIS_W + colW * (b.weekday - 1), y = ROW_H * (b.start - 1))
                            .width(colW).height(ROW_H * (b.end - b.start + 1))
                            .padding(1.5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color)
                            .clickable { onOpenClass(b.classId) }
                            .padding(3.dp),
                    ) {
                        Column {
                            Text(b.name, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(b.room, color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
