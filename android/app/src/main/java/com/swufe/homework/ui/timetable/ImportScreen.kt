package com.swufe.homework.ui.timetable

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.common.LoadingButton
import com.swufe.homework.ui.theme.ReviewHighlight
import com.swufe.homework.util.SlotDraft
import com.swufe.homework.util.WEEKDAY_NAMES
import com.swufe.homework.util.weekdayName

@Composable
fun ImportScreen(manual: Boolean, onBack: () -> Unit, onDone: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    LaunchedEffect(manual) { if (manual) vm.startManual() else vm.checkAi() }
    val title = if (manual) "手动添加课程" else "导入课表"
    Scaffold(topBar = { BackTopBar(title, onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when (vm.stage) {
                ImportStage.Pick -> PickStage(vm)
                ImportStage.Recognizing -> Busy(vm.progress.ifEmpty { "正在识别…" })
                ImportStage.Confirm -> ConfirmStage(vm, showSearch = true)
                ImportStage.Submitting -> Busy("正在提交…")
                ImportStage.Result -> ResultStage(vm, onDone)
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text)
    }
}

@Composable
private fun PickStage(vm: ImportViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { vm.setPicked(it) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("在易校园打开课表，截 1–2 张图（一屏放不下就上下各截一张），电脑会帮你识别成课程，识别结果需要你确认后才会保存。")
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.images.isEmpty()) "选择截图" else "重新选择") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.images.forEach {
                AsyncImage(it, contentDescription = "截图", contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)))
            }
        }
        if (vm.aiOnline == false) Text("电脑未在线，请手动添加", color = MaterialTheme.colorScheme.outline)
        ErrorText(vm.error)
        LoadingButton("开始识别", loading = false, enabled = vm.images.isNotEmpty() && vm.aiOnline != false) { vm.recognize() }
        if (vm.aiOnline == false || vm.error != null) {
            OutlinedButton(onClick = vm::backToConfirm, modifier = Modifier.fillMaxWidth()) { Text("改为手动添加") }
        }
    }
}

@Composable
private fun ConfirmStage(vm: ImportViewModel, showSearch: Boolean) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "请逐条核对。黄色标记的是课名可能被截断或起节被裁掉的课，请补全。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (showSearch) item { SearchBox(vm) }
        items(vm.drafts, key = { it.key }) { d ->
            DraftCard(d, conflict = d.key in vm.conflictKeys, onChange = vm::update, onDelete = { vm.remove(d.key) })
        }
        item {
            OutlinedButton(onClick = vm::addEmpty, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null); Text("添加一节")
            }
        }
        item { ErrorText(vm.error) }
        item { LoadingButton("确认保存（${vm.drafts.size} 节）", loading = false, enabled = vm.drafts.isNotEmpty()) { vm.submit() } }
    }
}

@Composable
private fun SearchBox(vm: ImportViewModel) {
    Column {
        OutlinedTextField(
            vm.query, vm::onQuery,
            label = { Text("搜索已有课程（同学已导入的）") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (vm.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        vm.searchResults.take(8).forEach { c -> SearchRow(c) { vm.addFromClass(c) } }
    }
}

@Composable
private fun SearchRow(c: ClassInfo, onPick: () -> Unit) {
    ListItem(
        headlineContent = { Text(c.name) },
        supportingContent = {
            Text(c.slots.joinToString("；") { "${weekdayName(it.weekday)} ${it.start}-${it.end}节 ${it.room}" } + "  · ${c.memberCount} 人")
        },
        trailingContent = { OutlinedButton(onClick = onPick) { Text("添加") } },
    )
    HorizontalDivider()
}

@Composable
private fun DraftCard(d: SlotDraft, conflict: Boolean, onChange: (SlotDraft) -> Unit, onDelete: () -> Unit) {
    val border = when {
        conflict -> CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error))
        else -> null
    }
    Card(border = border, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (conflict) Text("这节课的时间和教室与另一门不同名的课重叠，多半是课名识别错了，请核对课名后重提。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    d.name, { onChange(d.copy(name = it, truncated = false)) },
                    label = { Text(if (d.truncated) "课名（可能被截断）" else "课名") },
                    singleLine = true,
                    colors = if (d.truncated) OutlinedTextFieldDefaults.colors(unfocusedContainerColor = ReviewHighlight, focusedContainerColor = ReviewHighlight) else OutlinedTextFieldDefaults.colors(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除这一条") }
            }
            OutlinedTextField(d.room, { onChange(d.copy(room = it)) }, label = { Text("教室（如 经世楼G101）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEKDAY_NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = d.weekday == i + 1, onClick = { onChange(d.copy(weekday = i + 1)) }, label = { Text(n) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper(
                    "起节", d.start, highlight = !d.startVisible,
                    onChange = { v -> onChange(d.copy(start = v, end = maxOf(v, d.end), startVisible = true)) },
                )
                Spacer(Modifier.width(16.dp))
                Stepper("止节", d.end, highlight = false, onChange = { v -> onChange(d.copy(end = v, start = minOf(v, d.start))) })
            }
            if (!d.startVisible) Text("截图上方被裁掉，起节可能更早，请核对。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, highlight: Boolean, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).then(if (highlight) Modifier.background(ReviewHighlight) else Modifier),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        IconButton(onClick = { if (value > 1) onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "减少$label") }
        Text("$value", fontWeight = FontWeight.Bold)
        IconButton(onClick = { if (value < 12) onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = "增加$label") }
    }
}

@Composable
private fun ResultStage(vm: ImportViewModel, onDone: () -> Unit) {
    val r = vm.result ?: return
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("已保存", style = MaterialTheme.typography.headlineSmall)
        if (r.joined.isNotEmpty()) Text("加入已有班级 ${r.joined.size} 个：" + r.joined.joinToString("、") { it.name })
        if (r.created.isNotEmpty()) Text("新建班级 ${r.created.size} 个：" + r.created.joinToString("、") { it.name })
        if (r.merged.isNotEmpty()) Text("自动合并了 ${r.merged.size} 组重复的班级。")
        if (r.conflicts.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.conflicts.size} 节没有保存", fontWeight = FontWeight.Bold)
                    r.conflicts.forEach {
                        Text("「${it.slot.name}」${weekdayName(it.slot.weekday)} ${it.slot.start}-${it.slot.end}节 与已有的「${it.existingClass.name}」时间教室重叠")
                    }
                    Text("请核对课名后重新提交。", style = MaterialTheme.typography.bodySmall)
                }
            }
            LoadingButton("修改后重新提交", loading = false) { vm.backToConfirm() }
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("先不管，回到课表") }
        } else {
            LoadingButton("完成", loading = false, onClick = onDone)
        }
    }
}
