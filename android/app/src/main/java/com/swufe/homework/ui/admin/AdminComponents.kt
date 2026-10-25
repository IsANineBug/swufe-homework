package com.swufe.homework.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.swufe.homework.ui.common.BackTopBar
import com.swufe.homework.ui.common.ConfirmDialog
import com.swufe.homework.ui.common.EmptyState
import com.swufe.homework.ui.common.LoadingState
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing

/**
 * 管理路由的门（路由层拦截）：进入时重新拉 /me，只有 role=admin 才组合 [content]；
 * 非管理员只看到「只有管理员可以进入」，任何管理接口都不会被调用（服务器也会 403）。
 */
@Composable
fun AdminGate(onBack: () -> Unit, content: @Composable () -> Unit) {
    val vm: AdminGateViewModel = hiltViewModel()
    when (vm.access) {
        AdminAccess.Allowed -> content()
        AdminAccess.Checking -> Scaffold(topBar = { BackTopBar("管理", onBack) }) { pad ->
            LoadingState(Modifier.padding(pad))
        }
        AdminAccess.Denied -> Scaffold(topBar = { BackTopBar("管理", onBack) }) { pad ->
            EmptyState(
                ADMIN_DENIED_TEXT,
                Modifier.padding(pad),
                icon = Icons.Filled.Lock,
                actionLabel = "返回",
                onAction = onBack,
            )
        }
    }
}

const val ADMIN_DENIED_TEXT = "只有管理员可以进入这里"

/**
 * 管理里的破坏性操作统一用这个：ui/common 的 [ConfirmDialog]（destructive）+ 必填「原因」输入框。
 * 原因为空（或超过 200 字）时确认按钮禁用；[extraValid] 给额外的必选项（天数、班级等）。
 */
@Composable
fun ReasonConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
    extraValid: Boolean = true,
    destructive: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    val len = reasonLength(reason)
    ConfirmDialog(
        title = title,
        text = text,
        confirmLabel = confirmLabel,
        onConfirm = { if (adminReasonValid(reason) && extraValid) onConfirm(reason.trim()) },
        onDismiss = onDismiss,
        destructive = destructive,
        confirmEnabled = adminReasonValid(reason) && extraValid,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                extra?.invoke()
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("原因（必填）") },
                    minLines = 2,
                    maxLines = 4,
                    isError = len > ADMIN_REASON_MAX,
                    supportingText = { Text("$len/$ADMIN_REASON_MAX，会写进操作日志") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** 一行可选标签（天数、种类等）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

/** 处罚弹窗：选种类（可固定）、天数、必填原因。 */
@Composable
fun SanctionDialog(
    nickname: String,
    onConfirm: (com.swufe.homework.data.api.SanctionCreate) -> Unit,
    onDismiss: () -> Unit,
    initialKind: SanctionKind = SanctionKind.Mute,
) {
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var days by rememberSaveable { mutableStateOf<Int?>(7) }
    ReasonConfirmDialog(
        title = "处罚「$nickname」",
        text = kind.verb + "。到期自动解除，也可以随时在用户详情里解除。",
        confirmLabel = "确认${kind.label}",
        onConfirm = { reason -> buildSanction(kind, days, reason)?.let(onConfirm) },
        onDismiss = onDismiss,
        extraValid = days != null || kind == SanctionKind.Disable,
        extra = {
            Text("处罚方式", style = MaterialTheme.typography.labelMedium)
            ChoiceChips(SanctionKind.entries, kind, { it.label }) { k ->
                kind = k
                if (days == null && k != SanctionKind.Disable) days = 7
            }
            Text("期限", style = MaterialTheme.typography.labelMedium)
            ChoiceChips(sanctionDayOptions(kind), days, ::daysLabel) { days = it }
        },
    )
}

/** 搜索框 + 「搜索」按钮（键盘上的搜索键也触发）。 */
@Composable
fun SearchRow(value: String, onChange: (String) -> Unit, label: String, onSearch: () -> Unit, padded: Boolean = true) {
    androidx.compose.foundation.layout.Row(
        (if (padded) Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs) else Modifier).fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.IconButton(onClick = onSearch) {
            androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = "搜索")
        }
    }
}

/** 显示 ViewModel 里的一次性提示。 */
@Composable
fun AdminToast(vm: AdminBaseViewModel, snack: SnackbarHostState) {
    LaunchedEffect(vm.toast) { vm.toast?.let { snack.showSnackbar(it); vm.toast = null } }
}

/** 引用块：被举报的内容、上下文。 */
@Composable
fun QuoteBlock(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = Corners.Tag, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.padding(Spacing.md)) { content() }
    }
}

@Composable
fun FullBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) { content() }
}
