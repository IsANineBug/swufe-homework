package com.swufe.homework.ui.homework

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.InlineNotice
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.common.LoadingButton
import com.swufe.homework.ui.theme.ReviewHighlight
import com.swufe.homework.util.Ddl
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** 发布作业 / 补充 / 编辑自己的来源。课程由班级页决定。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublishScreen(onBack: () -> Unit, onDone: (Int) -> Unit, vm: PublishViewModel = hiltViewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PublishViewModel.MAX_IMAGES)) { vm.addImages(it) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }
    val isCreate = vm.mode == PublishMode.Create
    val title = when (vm.mode) { PublishMode.Create -> "发布作业"; PublishMode.Supplement -> "补充内容"; PublishMode.Edit -> "编辑内容" }

    Scaffold(topBar = { BackTopBar(title, onBack) }) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            vm.busy?.let {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (vm.loadingExisting) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (vm.mode != PublishMode.Edit) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    FilledTonalButton(onClick = vm::aiRecognize, enabled = vm.aiOnline && vm.busy == null, shape = Corners.Button) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text("智能识别")
                    }
                    Text(
                        if (vm.aiOnline) "粘贴文字或选图后，自动填表（需核对）" else "AI 暂时不可用，请手动添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (isCreate) {
                OutlinedTextField(
                    vm.title, { vm.title = it.take(80) },
                    label = { Text("标题") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = highlight("title" in vm.uncertain),
                    supportingText = { Text("${vm.title.length}/80") },
                )
            }
            OutlinedTextField(
                vm.body, { vm.body = it.take(5000) },
                label = { Text(if (isCreate) "作业要求（可粘贴老师原话）" else "补充内容") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                colors = highlight("requirements" in vm.uncertain || "submit_method" in vm.uncertain),
            )

            // 图片
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("图片 ${vm.imageCount}/${PublishViewModel.MAX_IMAGES}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = vm.imageCount < PublishViewModel.MAX_IMAGES && vm.busy == null,
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("选择图片")
                }
            }
            if (vm.imageCount > 0) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    items(vm.existing, key = { "e${it.id}" }) { a ->
                        Thumb(model = absoluteUrl(a.thumbUrl ?: a.url), onRemove = { vm.removeExisting(a) })
                    }
                    items(vm.newImages, key = { it.toString() }) { u -> Thumb(model = u, onRemove = { vm.removeNew(u) }) }
                }
            }

            // DDL
            HorizontalDivider()
            Text(
                when {
                    isCreate -> "DDL（必填）"
                    else -> "你看到的 DDL（可选，与作业不同时会标红提醒大家核对）"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            val ddlHi = "ddl" in vm.uncertain
            val ddlColors = if (ddlHi) ButtonDefaults.outlinedButtonColors(containerColor = ReviewHighlight) else ButtonDefaults.outlinedButtonColors()
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDate = true }, colors = ddlColors, shape = Corners.Button) {
                    Text(vm.ddlDate?.let { "${it.year}-${it.monthValue}-${it.dayOfMonth}" } ?: "选择日期")
                }
                OutlinedButton(onClick = { showTime = true }, enabled = vm.ddlDate != null, colors = ddlColors, shape = Corners.Button) {
                    Text(vm.ddlTime?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "时间")
                }
                if (vm.ddlDate != null) TextButton(onClick = vm::clearDdl) { Text("清除") }
            }
            vm.ddlDate?.let { d ->
                val t = Ddl.at(d, vm.ddlTime ?: LocalTime.of(23, 59))
                DdlLine(Ddl.toApi(t), conflict = false, uncertain = false)
            }
            if (isCreate) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = vm.ddlUncertain, onCheckedChange = { vm.ddlUncertain = it })
                    Text("DDL 待确认（拿不准时勾选，大家会看到「待确认」标签）", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (vm.uncertain.isNotEmpty()) {
                InlineNotice("标黄的是识别拿不准的内容，请核对后再发布。", BannerTone.Warning)
            }

            ErrorText(vm.error)
            LoadingButton(
                when (vm.mode) { PublishMode.Create -> "发布"; PublishMode.Supplement -> "发布补充"; PublishMode.Edit -> "保存" },
                loading = vm.busy != null,
                enabled = !vm.loadingExisting,
            ) { vm.publish(onDone) }
        }
    }

    if (showDate) {
        val initial = (vm.ddlDate ?: Ddl.now().toLocalDate()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val st = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } },
        ) { DatePicker(st) }
    }
    if (showTime) {
        val cur = vm.ddlTime ?: LocalTime.of(23, 59)
        val st = rememberTimePickerState(cur.hour, cur.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text("截止时间") },
            text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { vm.setTime(LocalTime.of(st.hour, st.minute)); showTime = false }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("取消") } },
        )
    }

    when (val p = vm.prompt) {
        is PublishPrompt.Spam -> AlertDialog(
            onDismissRequest = { vm.answerSpam(false) },
            title = { Text("确定发布吗？") },
            text = { Text("内容看起来与作业无关，确定发布吗？" + if (p.reason.isNotBlank()) "\n（${p.reason}）" else "") },
            confirmButton = { TextButton(onClick = { vm.answerSpam(true) }) { Text("仍然发布") } },
            dismissButton = { TextButton(onClick = { vm.answerSpam(false) }) { Text("返回修改") } },
        )
        is PublishPrompt.Duplicates -> AlertDialog(
            onDismissRequest = vm::cancelDuplicate,
            title = { Text("可能已经有人发过") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("选中已有作业，你的内容会作为补充发布；都不是就新建。", style = MaterialTheme.typography.bodySmall)
                    p.list.forEach { c ->
                        ListItem(
                            overlineContent = if (c.aiSame) ({ Text("可能是同一份作业，作为补充发布？", color = MaterialTheme.colorScheme.primary) }) else null,
                            headlineContent = { Text(c.a.title) },
                            supportingContent = { DdlLine(c.a.ddl, c.a.ddlConflict, c.a.ddlUncertain) },
                            trailingContent = { TextButton(onClick = { vm.answerDuplicate(c.a.id) }) { Text("作为补充") } },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.answerDuplicate(null) }) { Text("仍然新建") } },
            dismissButton = { TextButton(onClick = vm::cancelDuplicate) { Text("取消") } },
        )
        null -> {}
    }
}

@Composable
private fun highlight(on: Boolean) =
    if (on) OutlinedTextFieldDefaults.colors(unfocusedContainerColor = ReviewHighlight, focusedContainerColor = ReviewHighlight)
    else OutlinedTextFieldDefaults.colors()

@Composable
private fun Thumb(model: Any, onRemove: () -> Unit) {
    Box(Modifier.size(88.dp)) {
        AsyncImage(
            model = model, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(88.dp).clip(Corners.Tag),
        )
        FilledTonalIconButton(onClick = onRemove, modifier = Modifier.size(28.dp).align(Alignment.TopEnd)) {
            Icon(Icons.Filled.Close, contentDescription = "移除", modifier = Modifier.size(16.dp))
        }
    }
}
