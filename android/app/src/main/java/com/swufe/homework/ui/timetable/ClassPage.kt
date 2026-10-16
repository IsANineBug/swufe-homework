package com.swufe.homework.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.swufe.homework.data.api.ClassInfo
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.TimetableRepository
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.ui.nav.Routes
import com.swufe.homework.util.weekdayName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClassHeaderViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: TimetableRepository,
) : ViewModel() {
    val classId: Int = saved.toRoute<Routes.ClassPage>().classId
    var info by mutableStateOf<ClassInfo?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var leaving by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            info = repo.cachedClass(classId)
            repo.classDetail(classId).onSuccess { info = it }.onFailure { if (info == null) error = it.userMessage }
        }
    }

    fun leave(onLeft: () -> Unit) {
        leaving = true
        viewModelScope.launch {
            repo.leave(classId).onSuccess { onLeft() }.onFailure { error = it.userMessage }
            leaving = false
        }
    }
}

/**
 * 班级页外壳。WS4 提供头部（课名、时段、人数）和「不是我的班」；
 * 作业列表、榜单、发布入口由 WS5 通过 [body] 填入（见 NAVIGATION.md）。
 */
@Composable
fun ClassPageScreen(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    body: @Composable (classId: Int) -> Unit = { ClassBodyPlaceholder() },
    vm: ClassHeaderViewModel = hiltViewModel(),
) {
    var confirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar(vm.info?.name ?: "班级", onBack) {
                TextButton(onClick = { confirm = true }, enabled = !vm.leaving) { Text("不是我的班") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ClassHeader(vm.info)
            ErrorText(vm.error)
            Box(Modifier.weight(1f).fillMaxWidth()) { body(vm.classId) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("退出这个班？") },
            text = { Text("课表识别错了、或者你不在这个教学班，可以退出。退出后不再收到这个班的作业提醒，以后重新导入课表也不会自动加回；如需重新加入，在「手动添加」里搜索这门课。") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.leave(onLeft) }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ClassHeader(c: ClassInfo?) {
    if (c == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        c.slots.forEach { Text("${weekdayName(it.weekday)} 第 ${it.start}–${it.end} 节 · ${it.room}", style = MaterialTheme.typography.bodyMedium) }
        Text(listOfNotNull(c.teacher, "${c.memberCount} 位同学").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ClassBodyPlaceholder() {
    LaunchedEffect(Unit) {}
    Text("作业列表开发中（WS5）", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
}
