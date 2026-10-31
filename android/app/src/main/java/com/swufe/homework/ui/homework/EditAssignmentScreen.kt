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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.common.LoadingButton
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.util.Ddl
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** 首发者编辑作业：标题、DDL、说明（首发来源正文与图片）。入口在作业详情顶栏「编辑」，仅 is_first_poster 时出现。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAssignmentScreen(onBack: () -> Unit, onDone: () -> Unit, vm: EditAssignmentViewModel = hiltViewModel()) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PublishViewModel.MAX_IMAGES)) { vm.addImages(it) }
    var showDate by rememberSaveable { mutableStateOf(false) }
    var showTime by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("编辑作业", onBack) }) { pad ->
        val base = Modifier.padding(pad)
        when {
            vm.loading -> { LoadingState(base); return@Scaffold }
            vm.forbidden -> {
                EmptyState("只有首发这条作业的同学可以编辑。", base, icon = Icons.Outlined.Lock, actionLabel = "返回", onAction = onBack, title = "不能编辑")
                return@Scaffold
            }
            vm.error != null && vm.ddlDate == null -> { ErrorState(vm.error ?: "", onRetry = vm::load, modifier = base); return@Scaffold }
        }
        Column(
            base.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            vm.busy?.let {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                vm.title, { vm.title = it.take(80) },
                label = { Text("标题") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${vm.title.length}/80") },
            )

            // DDL（必填）
            Text("DDL", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showDate = true }, shape = Corners.Button) {
                    Text(vm.ddlDate?.let { "${it.year}-${it.monthValue}-${it.dayOfMonth}" } ?: "选择日期")
                }
                OutlinedButton(onClick = { showTime = true }, enabled = vm.ddlDate != null, shape = Corners.Button) {
                    Text(vm.ddlTime?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "时间")
                }
            }
            vm.ddlDate?.let { d ->
                val t = Ddl.at(d, vm.ddlTime ?: LocalTime.of(23, 59))
                DdlLine(Ddl.toApi(t), conflict = false, uncertain = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.ddlUncertain, onCheckedChange = { vm.ddlUncertain = it })
                Text("DDL 待确认（拿不准时勾选，大家会看到「待确认」标签）", style = MaterialTheme.typography.bodySmall)
            }
            InlineNotice("改 DDL 后，补充里看到的 DDL 与新 DDL 不同的会标红，大家的提醒按新时间重排。", BannerTone.Info)

            HorizontalDivider()
            Text("说明", style = MaterialTheme.typography.titleSmall)
            if (vm.firstSource == null) {
                InlineNotice("首发内容已不在这条作业下（可能已被合并或删除），这里只能改标题和 DDL。", BannerTone.Warning)
            } else {
                OutlinedTextField(
                    vm.body, { vm.body = it.take(5000) },
                    label = { Text("作业要求（首发内容）") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                            EditThumb(model = absoluteUrl(a.thumbUrl ?: a.url), onRemove = { vm.removeExisting(a) })
                        }
                        items(vm.newImages, key = { it.toString() }) { u -> EditThumb(model = u, onRemove = { vm.removeNew(u) }) }
                    }
                }
            }

            ErrorText(vm.error)
            LoadingButton("保存", loading = vm.busy != null) { vm.save(onDone) }
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
}

@Composable
private fun EditThumb(model: Any, onRemove: () -> Unit) {
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
