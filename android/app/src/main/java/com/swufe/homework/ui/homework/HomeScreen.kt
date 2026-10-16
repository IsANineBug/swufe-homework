package com.swufe.homework.ui.homework

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.data.repo.ReminderSync
import com.swufe.homework.ui.common.ErrorText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: AssignmentRepository,
    private val reminders: ReminderSync,
) : ViewModel() {
    var items by mutableStateOf<List<AssignmentSummary>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var showDone by mutableStateOf(false); private set

    private var resumed = false

    init {
        refresh()
        viewModelScope.launch { reminders.sync() }
    }

    /** 从详情/发布页返回时刷新（首次显示已在 init 里加载）。 */
    fun onResume() { if (resumed) refresh() else resumed = true }

    fun refresh() {
        loading = true
        viewModelScope.launch {
            repo.feed(showDone)
                .onSuccess { items = sorted(it); error = null }
                .onFailure { error = it.userMessage }
            loading = false; loaded = true
        }
    }

    fun toggleShowDone() { showDone = !showDone; refresh() }

    /** 勾选或左滑：先在列表里生效，失败再改回来。 */
    fun setDone(a: AssignmentSummary, done: Boolean) {
        val before = items
        items = if (done && !showDone) items.filterNot { it.id == a.id } else items.map { if (it.id == a.id) it.copy(done = done) else it }
        viewModelScope.launch {
            repo.setDone(a.id, done).onFailure { items = before; error = it.userMessage }
        }
    }

    private fun sorted(l: List<AssignmentSummary>) = l.sortedBy { it.ddl.let { d -> com.swufe.homework.util.Ddl.parse(d)?.toInstant() } }
}

/** 首页：我所有班级的未过期作业，按 DDL 升序（/feed）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Int) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("作业") },
            actions = {
                FilterChip(selected = vm.showDone, onClick = vm::toggleShowDone, label = { Text("显示已完成") }, modifier = Modifier.padding(end = 8.dp))
            },
        )
        ErrorText(vm.error)
        PullToRefreshBox(isRefreshing = vm.loading, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.loaded && vm.items.isEmpty()) {
                    item {
                        Text(
                            if (vm.showDone) "还没有作业。" else "没有待完成的作业。\n去课表里点开一门课，可以发布作业。",
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(vm.items, key = { it.id }) { a ->
                    SwipeDoneRow(a, onDone = { vm.setDone(a, !a.done) }) {
                        AssignmentCard(a, showClass = true, onClick = { onOpen(a.id) }, onToggleDone = { vm.setDone(a, it) })
                    }
                }
            }
        }
    }
}

/** 左滑标记完成（已完成的左滑取消完成）。条目不消失，由列表刷新决定。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeDoneRow(a: AssignmentSummary, onDone: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.EndToStart) onDone()
            false
        },
        positionalThreshold = { it * 0.35f },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (a.done) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check, contentDescription = null)
                    Text(if (a.done) " 取消完成" else " 完成")
                }
            }
        },
    ) { content() }
}
