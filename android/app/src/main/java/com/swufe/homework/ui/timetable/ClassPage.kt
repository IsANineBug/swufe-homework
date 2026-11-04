package com.swufe.homework.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.StatusBanner
import com.swufe.homework.ui.homework.MetaTag
import com.swufe.homework.ui.homework.MetaTags
import com.swufe.homework.ui.theme.CourseColors
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
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

    init { reload() }

    fun reload() {
        error = null
        viewModelScope.launch {
            if (info == null) info = repo.cachedClass(classId)
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
    /** WS11：顶栏额外操作（「更多 → 举报这个班」），放在「不是我的班」之后。 */
    actions: @Composable (classId: Int) -> Unit = {},
    vm: ClassHeaderViewModel = hiltViewModel(),
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BackTopBar(vm.info?.name ?: "班级", onBack) {
                TextButton(onClick = { confirm = true }, enabled = !vm.leaving) { Text("不是我的班") }
                actions(vm.classId)
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val info = vm.info
            if (info != null) {
                ClassHeader(info)
                vm.error?.let { StatusBanner(it, BannerTone.Error) }
            } else if (vm.error != null) {
                ErrorState(vm.error ?: "", onRetry = vm::reload, fill = false)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { body(vm.classId) }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "退出这个班？",
            text = "课表识别错了、或者你不在这个教学班，可以退出。退出后不再收到这个班的作业提醒，以后重新导入课表也不会自动加回；如需重新加入，在「手动添加」里搜索这门课。",
            confirmLabel = "退出",
            onConfirm = { confirm = false; vm.leave(onLeft) },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun ClassHeader(c: ClassInfo) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 每个时段一行：课表色条 + 「周三 第 3–4 节」+ 教室标签
        val courseColor = CourseColors[Math.floorMod(c.id, CourseColors.size)]
        c.slots.forEach {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Box(Modifier.size(width = 4.dp, height = 20.dp).background(courseColor, Corners.Tag))
                Text("${weekdayName(it.weekday)} 第 ${it.start}–${it.end} 节", style = MaterialTheme.typography.bodyMedium)
                if (it.room.isNotBlank()) MetaTag(it.room)
            }
        }
        MetaTags(listOfNotNull(c.teacher?.takeIf { t -> t.isNotBlank() }, "${c.memberCount} 位同学"))
    }
}

@Composable
private fun ClassBodyPlaceholder() {
    EmptyState("作业列表开发中")
}
