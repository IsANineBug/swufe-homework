package com.swufe.homework.ui.guest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swufe.homework.data.api.GuestAssignment
import com.swufe.homework.data.api.GuestSource
import com.swufe.homework.data.api.userMessage
import com.swufe.homework.data.repo.GuestRepository
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.BannerTone
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.ErrorState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.common.StatusBanner
import com.swufe.homework.ui.homework.AssignmentCard
import com.swufe.homework.ui.homework.ConflictRed
import com.swufe.homework.ui.homework.DdlLine
import com.swufe.homework.ui.homework.HomeFilter
import com.swufe.homework.ui.homework.MetaTags
import com.swufe.homework.ui.homework.SourceByline
import com.swufe.homework.ui.homework.Tag
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic
import com.swufe.homework.util.Ddl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * 游客浏览：登录页「先随便看看」进来，不注册也能看一批随机抽取的匿名作业（GET /guest/assignments）。
 * 只读：没有勾选完成、发布、补充、谢谢、投票、举报、交流、贡献值和课表入口；不存 token。
 * 每一屏都给「注册账号」的出口，目的是让人看懂这个 App 能做什么。
 */

@HiltViewModel
class GuestHomeViewModel @Inject constructor(private val repo: GuestRepository) : ViewModel() {
    var items by mutableStateOf(repo.last); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(repo.last.isNotEmpty()); private set
    var error by mutableStateOf<String?>(null); private set

    init { if (items.isEmpty()) refresh() }

    /** 首次加载与「换一批」：服务器每次随机抽，结果直接替换。 */
    fun refresh() {
        if (loading) return
        loading = true
        viewModelScope.launch {
            repo.sample()
                .onSuccess { items = it; error = null }
                .onFailure { error = it.userMessage }
            loading = false; loaded = true
        }
    }
}

@HiltViewModel
class GuestDetailViewModel @Inject constructor(private val repo: GuestRepository) : ViewModel() {
    fun find(id: Int): GuestAssignment? = repo.find(id)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuestHomeScreen(
    onOpen: (Int) -> Unit,
    onRegister: () -> Unit,
    onLogin: () -> Unit,
    vm: GuestHomeViewModel = hiltViewModel(),
) {
    val now = remember(vm.items) { Ddl.now() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("随便看看") },
                navigationIcon = {
                    IconButton(onClick = onLogin) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回登录") }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !vm.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "换一批")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val err = vm.error
            if (vm.loading && vm.loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (err != null && vm.items.isNotEmpty()) {
                StatusBanner(err, BannerTone.Error, actionLabel = "重试", onAction = vm::refresh)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item(key = "intro") { GuestIntro(onRegister = onRegister, onLogin = onLogin) }
                when {
                    !vm.loaded -> item(key = "loading") { LoadingState(skeleton = true, skeletonRows = 3, fill = false) }
                    err != null && vm.items.isEmpty() -> item(key = "error") {
                        ErrorState(err, title = "样例没有加载出来", onRetry = vm::refresh, fill = false)
                    }
                    vm.items.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            "大家发布作业后，这里会随机展示一批。现在注册，就能在自己班发布第一条。",
                            title = "还没有可以展示的作业",
                            actionLabel = "注册账号",
                            onAction = onRegister,
                            fill = false,
                        )
                    }
                    else -> {
                        items(vm.items, key = { it.id }) { g ->
                            val summary = g.toSummary()
                            Column(Modifier.padding(horizontal = Spacing.lg)) {
                                AssignmentCard(
                                    summary,
                                    showClass = true,
                                    onClick = { onOpen(g.id) },
                                    onToggleDone = null,
                                    urgent = HomeFilter.isUrgent(summary, now),
                                )
                            }
                        }
                        item(key = "more") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                TextButton(onClick = vm::refresh, enabled = !vm.loading) { Text("换一批") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 列表顶部的说明：一句标题、一句话、注册与登录两个出口。 */
@Composable
private fun GuestIntro(onRegister: () -> Unit, onLogin: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            "同学们正在共享的作业",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "这是随机抽出的一批真实作业，作者已匿名。注册后能看到自己班的全部作业，还有 DDL 提醒和课表。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.padding(top = Spacing.xs)) {
            Button(onClick = onRegister, shape = Corners.Button) { Text("注册账号") }
            OutlinedButton(onClick = onLogin, shape = Corners.Button) { Text("我有账号，去登录") }
        }
    }
}

@Composable
fun GuestDetailScreen(
    id: Int,
    onBack: () -> Unit,
    onRegister: () -> Unit,
    vm: GuestDetailViewModel = hiltViewModel(),
) {
    val a = remember(id) { vm.find(id) }
    Scaffold(
        topBar = { BackTopBar("作业详情", onBack) },
        bottomBar = { if (a != null) GuestCta(onRegister) },
    ) { pad ->
        if (a == null) {
            // 样例只存在内存里，进程被回收后就找不到了
            EmptyState(
                "样例只在这次浏览里有效。回到列表换一批看看。",
                modifier = Modifier.padding(pad),
                title = "这条样例已经失效",
                icon = Icons.Outlined.SearchOff,
                actionLabel = "回到列表",
                onAction = onBack,
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item(key = "header") { GuestHeader(a) }
            item(key = "sources-title") {
                Text("来源（${a.sourceCount}）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            items(a.sources.size) { i -> GuestSourceCard(a.sources[i], a.ddl) }
            val hidden = a.sourceCount - a.sources.size
            if (hidden > 0) {
                item(key = "hidden") {
                    Text(
                        "另外 $hidden 条补充，注册后可以查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GuestHeader(a: GuestAssignment) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(a.className, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(a.title, style = MaterialTheme.typography.headlineSmall)
        DdlLine(a.ddl, a.ddlConflict, a.ddlUncertain)
        if (a.ddlConflict) {
            Text("补充内容里的 DDL 和这里不一致，请核对老师的通知。", color = ConflictRed, style = MaterialTheme.typography.bodySmall)
        }
        if (a.ddlUncertain) {
            Text("DDL 待确认：发布者或识别结果拿不准，请以老师通知为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.semantic.warning)
        }
        MetaTags(listOfNotNull("首发 ${a.firstPoster}".takeIf { a.firstPoster.isNotEmpty() }))
    }
}

@Composable
private fun GuestSourceCard(s: GuestSource, assignmentDdl: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = Corners.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(if (s.isFirst) "首发" else "补充", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                SourceByline(s.author, s.createdAt, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
            }
            if (s.body.isNotBlank()) SelectionContainer { Text(s.body, style = MaterialTheme.typography.bodyLarge) }
            s.statedDdl?.let { sd ->
                val differs = Ddl.parse(sd)?.toInstant() != Ddl.parse(assignmentDdl)?.toInstant()
                Text(
                    "TA 看到的 DDL：${Ddl.display(sd)}" + if (differs) "（与作业不一致）" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (differs) ConflictRed else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.attachmentCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "附了 ${s.attachmentCount} 个图片或文件，注册后可以查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 详情页底部：说清注册后多了什么，再给一个按钮。 */
@Composable
private fun GuestCta(onRegister: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                "注册后可以补充内容、标记完成，DDL 前还会提醒你。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onRegister, shape = Corners.Button) { Text("注册") }
        }
    }
}
