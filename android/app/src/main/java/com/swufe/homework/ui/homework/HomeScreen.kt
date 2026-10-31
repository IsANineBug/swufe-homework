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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.AssignmentSummary
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.AssignmentRepository
import com.swufe.homework.data.repo.ReminderSync
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.common.StatusBanner
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repo: AssignmentRepository,
    private val reminders: ReminderSync,
    private val prefs: HomeworkPrefs,
) : ViewModel() {
    /** /feed 原始结果（已按 DDL 升序）。 */
    var items by mutableStateOf<List<AssignmentSummary>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var showDone by mutableStateOf(false); private set
    /** 顶部切换，默认「全部」；启动时读回上次的选择。 */
    var range by mutableStateOf(HomeRange.Default); private set
    /** 用于分组与 24 小时标红的「现在」，每次刷新/回到页面/每分钟更新。 */
    var now by mutableStateOf(Ddl.now()); private set

    /** 当前切换下要显示的作业（纯函数 [HomeFilter.apply]，按 DDL 升序）。 */
    val visible: List<AssignmentSummary> get() = HomeFilter.apply(items, range, now)

    private var resumed = false

    init {
        viewModelScope.launch { range = prefs.homeRange() }
        refresh()
        viewModelScope.launch { reminders.sync() }
    }

    /** 从详情/发布页返回时刷新（首次显示已在 init 里加载）。 */
    fun onResume() { tick(); if (resumed) refresh() else resumed = true }

    fun tick() { now = Ddl.now() }

    fun refresh() {
        loading = true
        viewModelScope.launch {
            repo.feed(showDone)
                .onSuccess { items = HomeFilter.apply(it, HomeRange.All, Ddl.now()); error = null }
                .onFailure { error = it.userMessage }
            now = Ddl.now()
            loading = false; loaded = true
        }
    }

    fun selectRange(r: HomeRange) {
        if (r == range) return
        range = r
        tick()
        viewModelScope.launch { prefs.setHomeRange(r) }
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
}

/** 首页：我所有班级的未过期作业（/feed），顶部「今天 / 明天 / 本周 / 全部」切换，按 DDL 升序。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Int) -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        // 页面可见时每分钟更新一次「现在」，让 24 小时标红和「今天」分组跟着时间走；离开即停
        val ticker = scope.launch { while (true) { delay(60_000); vm.tick() } }
        onPauseOrDispose { ticker.cancel() }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("作业") },
            actions = {
                FilterChip(selected = vm.showDone, onClick = vm::toggleShowDone, label = { Text("显示已完成") }, modifier = Modifier.padding(end = Spacing.sm))
            },
        )
        RangeTabs(selected = vm.range, onSelect = vm::selectRange)
        val err = vm.error
        // 已有内容时出错（刷新失败、勾选失败）：顶部状态条 + 重试，不清掉列表
        if (err != null && vm.items.isNotEmpty()) {
            StatusBanner(err, BannerTone.Error, actionLabel = "重试", onAction = vm::refresh)
        }
        PullToRefreshBox(isRefreshing = vm.loading && vm.loaded, onRefresh = vm::refresh, modifier = Modifier.weight(1f)) {
            val list = vm.visible
            when {
                // 首屏：与卡片同构的骨架，避免空白闪一下
                !vm.loaded -> LoadingState(skeleton = true)
                err != null && vm.items.isEmpty() -> ErrorState(err, title = "作业没有加载出来", onRetry = vm::refresh)
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    if (list.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                HomeFilter.emptyMessage(vm.range, vm.showDone),
                                icon = if (vm.range == HomeRange.All) Icons.Filled.Inbox else Icons.Filled.EventAvailable,
                                actionLabel = if (vm.range != HomeRange.All) "看全部" else null,
                                onAction = if (vm.range != HomeRange.All) ({ vm.selectRange(HomeRange.All) }) else null,
                                fill = false,
                                modifier = Modifier.padding(top = Spacing.xl),
                            )
                        }
                    }
                    items(list, key = { it.id }) { a ->
                        SwipeDoneRow(a, onDone = { vm.setDone(a, !a.done) }) {
                            AssignmentCard(
                                a, showClass = true,
                                onClick = { onOpen(a.id) },
                                onToggleDone = { vm.setDone(a, it) },
                                urgent = HomeFilter.isUrgent(a, vm.now),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 顶部「今天 / 明天 / 本周 / 全部」切换。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeTabs(selected: HomeRange, onSelect: (HomeRange) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
        HomeRange.entries.forEachIndexed { i, r ->
            SegmentedButton(
                selected = r == selected,
                onClick = { onSelect(r) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = HomeRange.entries.size),
                label = { Text(r.label) },
            )
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
                Modifier.fillMaxSize().clip(Corners.Card).background(MaterialTheme.semantic.successContainer).padding(horizontal = Spacing.xl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = MaterialTheme.semantic.onSuccessContainer
                    Icon(if (a.done) Icons.AutoMirrored.Filled.Undo else Icons.Filled.Check, contentDescription = null, tint = c)
                    Text(if (a.done) " 取消完成" else " 完成", color = c)
                }
            }
        },
    ) { content() }
}
