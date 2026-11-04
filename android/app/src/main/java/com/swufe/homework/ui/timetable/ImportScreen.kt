package com.swufe.homework.ui.timetable

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.InlineNotice
import com.swufe.homework.ui.homework.MetaTag
import com.swufe.homework.ui.homework.MetaTags
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private val THUMB = 120.dp

@Composable
fun ImportScreen(manual: Boolean, onBack: () -> Unit, onDone: () -> Unit, vm: ImportViewModel = hiltViewModel()) {
    LaunchedEffect(manual) { if (manual) vm.startManual() else vm.startImport() }
    val title = if (manual) "手动添加课程" else "导入课表"
    Scaffold(topBar = { BackTopBar(title, onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            when (vm.stage) {
                ImportStage.Pick -> PickStage(vm)
                ImportStage.Recognizing -> RecognizingStage(vm, onLeave = onBack)
                ImportStage.Confirm -> ConfirmStage(vm, showSearch = true)
                ImportStage.Submitting -> LoadingState(message = "正在提交…")
                ImportStage.Result -> ResultStage(vm, onDone)
            }
        }
    }
}

/** 识别中：建好后台任务后提示可以先去别的页面（状态条会显示进度，好了会提醒）。 */
@Composable
private fun RecognizingStage(vm: ImportViewModel, onLeave: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        LoadingState(Modifier.weight(1f), message = vm.progress.ifEmpty { "正在识别…" })
        if (vm.jobStarted) {
            Column(
                Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!vm.stayHere) {
                    Text("已在后台识别，可以先去做别的", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "识别好了会在顶部提示；App 在后台时会发通知。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = vm::stay, shape = Corners.Button, modifier = Modifier.weight(1f)) { Text("留在这里等") }
                        Button(onClick = onLeave, shape = Corners.Button, modifier = Modifier.weight(1f)) { Text("先去别的页面") }
                    }
                }
                TextButton(onClick = vm::abandon) { Text("放弃识别") }
            }
        }
    }
}

@Composable
private fun PickStage(vm: ImportViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(2)) { vm.setPicked(it) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            "在易校园打开课表，截 1–2 张图（一屏放不下就上下各截一张），AI 会帮你识别成课程，识别结果需要你确认后才会保存。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            shape = Corners.Button,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (vm.images.isEmpty()) "选择截图" else "重新选择") }
        if (vm.images.isEmpty()) {
            EmptyState("一屏放不下就上下各截一张，最多 2 张。", icon = Icons.Filled.Image, fill = false, title = "还没有选截图")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                vm.images.forEach {
                    AsyncImage(it, contentDescription = "截图", contentScale = ContentScale.Crop, modifier = Modifier.size(THUMB).clip(Corners.Tag))
                }
            }
        }
        if (vm.aiOnline == false && vm.error != AI_OFFLINE_MESSAGE) {
            InlineNotice(AI_OFFLINE_MESSAGE, BannerTone.Info)
        }
        ErrorText(vm.error)
        LoadingButton("开始识别", loading = false, enabled = vm.images.isNotEmpty() && vm.aiOnline != false) { vm.recognize() }
        if (vm.aiOnline == false || vm.error != null) {
            OutlinedButton(onClick = vm::backToConfirm, shape = Corners.Button, modifier = Modifier.fillMaxWidth()) { Text("改为手动添加") }
        }
    }
}

@Composable
private fun ConfirmStage(vm: ImportViewModel, showSearch: Boolean) {
    val addPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> vm.addAnother(listOfNotNull(uri)) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (vm.aiCropped) {
            item {
                CroppedHint(
                    canAdd = vm.canAddAnother,
                    onAdd = { addPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }
        }
        item {
            Text(
                "请逐条核对。黄色标记的是课名可能被截断或起节被裁掉的课，请补全。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (showSearch) item { SearchBox(vm) }
        if (vm.drafts.isEmpty()) {
            item { EmptyState("还没有课程。搜索同学已导入的课，或点下面「添加一节」。", icon = Icons.Filled.CalendarMonth, fill = false) }
        }
        items(vm.drafts, key = { it.key }) { d ->
            DraftCard(d, conflict = d.key in vm.conflictKeys, onChange = vm::update, onDelete = { vm.remove(d.key) })
        }
        item {
            OutlinedButton(onClick = vm::addEmpty, shape = Corners.Button, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text("添加一节")
            }
        }
        item { ErrorText(vm.error) }
        item { LoadingButton("确认保存（${vm.drafts.size} 节）", loading = false, enabled = vm.drafts.isNotEmpty()) { vm.submit() } }
    }
}

/** 识别结果里有截断/被裁的课：建议补截另一半一起识别（最多 2 张）。 */
@Composable
private fun CroppedHint(canAdd: Boolean, onAdd: () -> Unit) {
    InlineNotice(
        "截图上方/下方可能被裁掉，建议补截另一半一起识别",
        BannerTone.Warning,
        actionLabel = if (canAdd) "再加一张" else null,
        onAction = if (canAdd) onAdd else null,
    )
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
            Column(Modifier.padding(top = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                c.slots.forEach { Text("${weekdayName(it.weekday)} 第 ${it.start}–${it.end} 节  ${it.room}") }
                MetaTag("${c.memberCount} 人已加入")
            }
        },
        trailingContent = { OutlinedButton(onClick = onPick, shape = Corners.Button) { Text("添加") } },
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
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                WEEKDAY_NAMES.forEachIndexed { i, n ->
                    FilterChip(selected = d.weekday == i + 1, onClick = { onChange(d.copy(weekday = i + 1)) }, label = { Text(n) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stepper(
                    "起节", d.start, highlight = !d.startVisible,
                    onChange = { v -> onChange(d.copy(start = v, end = maxOf(v, d.end), startVisible = true)) },
                )
                Spacer(Modifier.width(Spacing.lg))
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
        modifier = Modifier.clip(Corners.Tag).then(if (highlight) Modifier.background(ReviewHighlight) else Modifier),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = Spacing.xs))
        IconButton(onClick = { if (value > 1) onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "减少$label") }
        Text("$value", fontWeight = FontWeight.Bold)
        IconButton(onClick = { if (value < 12) onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = "增加$label") }
    }
}

@Composable
private fun ResultStage(vm: ImportViewModel, onDone: () -> Unit) {
    val r = vm.result ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.semantic.success, modifier = Modifier.size(32.dp))
            Text("已保存", style = MaterialTheme.typography.headlineSmall)
        }
        if (r.joined.isNotEmpty()) ResultGroup("加入已有班级 ${r.joined.size} 个", r.joined.map { it.name })
        if (r.created.isNotEmpty()) ResultGroup("新建班级 ${r.created.size} 个", r.created.map { it.name })
        if (r.merged.isNotEmpty()) Text("自动合并了 ${r.merged.size} 组重复的班级。", style = MaterialTheme.typography.bodyMedium)
        if (r.conflicts.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("${r.conflicts.size} 节没有保存", fontWeight = FontWeight.Bold)
                    r.conflicts.forEach {
                        Text("「${it.slot.name}」${weekdayName(it.slot.weekday)} ${it.slot.start}-${it.slot.end}节 与已有的「${it.existingClass.name}」时间教室重叠")
                    }
                    Text("请核对课名后重新提交。", style = MaterialTheme.typography.bodySmall)
                }
            }
            LoadingButton("修改后重新提交", loading = false) { vm.backToConfirm() }
            OutlinedButton(onClick = onDone, shape = Corners.Button, modifier = Modifier.fillMaxWidth()) { Text("先不管，回到课表") }
        } else {
            LoadingButton("完成", loading = false, onClick = onDone)
        }
    }
}

/** 结果页的一组班名：小标题 + 班名标签。 */
@Composable
private fun ResultGroup(title: String, names: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        MetaTags(names)
    }
}
