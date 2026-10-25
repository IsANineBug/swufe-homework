package com.swufe.homework.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.Leaderboard
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.data.repo.ChatReadStore
import com.swufe.homework.ui.chat.ChatTab
import com.swufe.homework.ui.common.ErrorText
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class ClassBodyViewModel @Inject constructor(
    private val repo: AssignmentRepository,
    chatRead: ChatReadStore,
) : ViewModel() {
    /** 有未读频道消息的班级（「交流」Tab 上的小红点）。 */
    val unreadClasses = chatRead.unreadClasses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    var classId = -1; private set
    var weekStart by mutableStateOf(Ddl.weekStart(Ddl.now().toLocalDate())); private set
    var items by mutableStateOf<List<AssignmentSummary>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var scope by mutableStateOf("week"); private set
    var board by mutableStateOf<Leaderboard?>(null); private set
    var boardError by mutableStateOf<String?>(null); private set

    private var resumed = false
    fun onResume() { if (resumed) load() else resumed = true }

    fun bind(id: Int) {
        if (classId == id) return
        classId = id
        load(); loadBoard()
    }

    fun shiftWeek(delta: Long) { weekStart = weekStart.plusWeeks(delta); load() }
    fun thisWeek() { weekStart = Ddl.weekStart(Ddl.now().toLocalDate()); load() }

    fun load() {
        if (classId < 0) return
        loading = true
        viewModelScope.launch {
            repo.classAssignments(classId, weekStart.toString(), weekStart.plusDays(6).toString())
                .onSuccess { items = it.sortedBy { a -> Ddl.parse(a.ddl)?.toInstant() }; error = null }
                .onFailure { error = it.userMessage }
            loading = false
        }
    }

    fun setDone(a: AssignmentSummary, done: Boolean) {
        val before = items
        items = items.map { if (it.id == a.id) it.copy(done = done) else it }
        viewModelScope.launch { repo.setDone(a.id, done).onFailure { items = before; error = it.userMessage } }
    }

    fun switchScope(s: String) { scope = s; loadBoard() }

    fun loadBoard() {
        if (classId < 0) return
        viewModelScope.launch {
            repo.leaderboard(classId, scope).onSuccess { board = it; boardError = null }.onFailure { boardError = it.userMessage }
        }
    }
}

/**
 * 班级页内容（填进 WS4 的 ClassPageScreen body）：按日期（周）浏览作业、周榜/学期榜、发布入口。
 */
@Composable
fun ClassBody(classId: Int, onOpen: (Int) -> Unit, onPublish: () -> Unit, vm: ClassBodyViewModel = hiltViewModel()) {
    LaunchedEffect(classId) { vm.bind(classId) }
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val unread by vm.unreadClasses.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("作业") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = {
                        BadgedBox(badge = { if (classId in unread && tab != 1) Badge() }) { Text("交流") }
                    },
                )
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("榜单") })
            }
            when (tab) {
                0 -> AssignmentsTab(vm, onOpen)
                // 频道只在这个 Tab 显示时存在于组合中，切走即停止轮询
                1 -> ChatTab(classId)
                else -> BoardTab(vm)
            }
        }
        if (tab == 0) {
            ExtendedFloatingActionButton(
                onClick = onPublish,
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text("发布作业") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun AssignmentsTab(vm: ClassBodyViewModel, onOpen: (Int) -> Unit) {
    val ws = vm.weekStart
    val isThisWeek = ws == Ddl.weekStart(Ddl.now().toLocalDate())
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shiftWeek(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周") }
            Text(
                "${md(ws)} – ${md(ws.plusDays(6))}" + if (isThisWeek) "（本周）" else "",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (!isThisWeek) TextButton(onClick = vm::thisWeek) { Text("回到本周") }
            IconButton(onClick = { vm.shiftWeek(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周") }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        ErrorText(vm.error)
        val byDay = vm.items.groupBy { Ddl.parse(it.ddl)?.toLocalDate() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!vm.loading && vm.items.isEmpty()) {
                item { Text("这一周没有截止的作业。", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
            byDay.forEach { (day, list) ->
                item(key = "d-$day") {
                    Text(
                        day?.let { "${md(it)} ${WEEK[it.dayOfWeek.value - 1]}" } ?: "日期未知",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(list, key = { it.id }) { a ->
                    AssignmentCard(a, showClass = false, onClick = { onOpen(a.id) }, onToggleDone = { vm.setDone(a, it) })
                }
            }
        }
    }
}

private val WEEK = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private fun md(d: LocalDate) = "${d.monthValue}月${d.dayOfMonth}日"

@Composable
private fun BoardTab(vm: ClassBodyViewModel) {
    val b = vm.board
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
            listOf("week" to "周榜", "semester" to "学期榜").forEachIndexed { i, (k, label) ->
                SegmentedButton(
                    selected = vm.scope == k,
                    onClick = { vm.switchScope(k) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        Text(
            if (vm.scope == "week") "每周一 00:00 重置" else "新学期重置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        b?.rep?.let {
            ListItem(
                headlineContent = { Text(it.displayName, fontWeight = FontWeight.SemiBold) },
                overlineContent = { Text("课代表（本学期贡献值第一）") },
            )
            HorizontalDivider()
        }
        ErrorText(vm.boardError)
        LazyColumn(Modifier.fillMaxSize()) {
            if (b != null && b.rows.isEmpty()) {
                item { Text("还没有人上榜。发布或补充作业就能得分。", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp)) }
            }
            items(b?.rows.orEmpty()) { r ->
                ListItem(
                    leadingContent = { Text("${r.rank}", style = MaterialTheme.typography.titleMedium) },
                    headlineContent = { Text(if (r.isMe) "${r.displayName}（我）" else r.displayName) },
                    trailingContent = { Text("${r.points} 分") },
                    modifier = if (r.isMe) Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)) else Modifier,
                )
            }
        }
    }
}
