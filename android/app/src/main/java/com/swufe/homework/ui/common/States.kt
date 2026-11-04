package com.swufe.homework.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swufe.homework.ui.theme.Corners
import com.swufe.homework.ui.theme.Spacing
import com.swufe.homework.ui.theme.semantic

/**
 * 空状态：图标 + 可选标题 + 一句话 + 可选按钮。列表为空、筛选后没有结果时用，代替一行灰字。
 * 文案写「接下来能做什么」，不写情绪。放在可滚动列表里时用 `item { EmptyState(...) }`，[fill] 传 false。
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    fill: Boolean = true,
    title: String? = null,
) {
    CenteredBlock(modifier, fill) {
        StateIcon(icon, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        StateText(title, message)
        if (actionLabel != null && onAction != null) {
            OutlinedButton(onClick = onAction, shape = Corners.Button) { Text(actionLabel) }
        }
    }
}

/**
 * 加载状态。首屏整页加载用默认的居中进度；列表首屏想保留版式时传 [skeleton] = true 显示骨架行。
 * 已有内容再刷新时不要用它（用下拉刷新或 LinearProgressIndicator）。
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null,
    skeleton: Boolean = false,
    skeletonRows: Int = 4,
    fill: Boolean = true,
) {
    if (skeleton) {
        SkeletonList(skeletonRows, modifier.then(if (fill) Modifier.fillMaxSize() else Modifier))
        return
    }
    CenteredBlock(modifier.semantics { liveRegion = LiveRegionMode.Polite }, fill) {
        CircularProgressIndicator()
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 出错状态：原因 + 重试。[message] 写给用户看的原因（如「网络连接失败」），不要放异常堆栈。 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
    fill: Boolean = true,
    title: String? = null,
) {
    CenteredBlock(modifier.semantics { liveRegion = LiveRegionMode.Polite }, fill) {
        StateIcon(Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        StateText(title, message)
        if (onRetry != null) Button(onClick = onRetry, shape = Corners.Button) { Text(retryLabel) }
    }
}

/** 空/出错状态的图标：放在 72dp 的色块圆里，比一枚裸图标更像一个「状态」。 */
@Composable
private fun StateIcon(icon: ImageVector, container: Color, content: Color) {
    Box(Modifier.size(72.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = content)
    }
}

@Composable
private fun StateText(title: String?, message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CenteredBlock(modifier: Modifier, fill: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier.then(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).padding(Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) { content() }
    }
}

@Composable
private fun SkeletonList(rows: Int, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.45f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // 与作业卡片同构：左侧日期票根 + 右侧三行
        repeat(rows) {
            Row(
                Modifier.fillMaxWidth().alpha(a).background(MaterialTheme.colorScheme.surfaceContainerLow, Corners.Card).padding(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Box(Modifier.size(width = 64.dp, height = 72.dp).background(block, Corners.Tag))
                Column(Modifier.weight(1f).padding(vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Box(Modifier.fillMaxWidth(0.35f).height(12.dp).background(block, RoundedCornerShape(4.dp)))
                    Box(Modifier.fillMaxWidth(0.8f).height(16.dp).background(block, RoundedCornerShape(4.dp)))
                    Box(Modifier.fillMaxWidth(0.55f).height(12.dp).background(block, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/** 状态条的语气，决定底色与图标。 */
enum class BannerTone { Progress, Info, Success, Warning, Error }

/**
 * 页面顶部状态条（后台识图等跨页面的进行中任务）。整条可点（[onClick]），右侧可放一个文字操作和关闭按钮。
 * 例：`StatusBanner("课表识别中…", BannerTone.Progress)`；
 * `StatusBanner("课表识别好了", BannerTone.Success, actionLabel = "去确认", onAction = ...)`；
 * `StatusBanner("识别失败：AI 暂时不可用", BannerTone.Error, actionLabel = "手动添加", onAction = ...)`。
 */
@Composable
fun StatusBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val s = MaterialTheme.semantic
    val cs = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (tone) {
        BannerTone.Progress, BannerTone.Info -> cs.secondaryContainer to cs.onSecondaryContainer
        BannerTone.Success -> s.successContainer to s.onSuccessContainer
        BannerTone.Warning -> s.warningContainer to s.onWarningContainer
        BannerTone.Error -> cs.errorContainer to cs.onErrorContainer
    }
    Surface(
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs).height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (tone) {
                BannerTone.Progress -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
                BannerTone.Info -> Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                BannerTone.Success -> Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                BannerTone.Warning -> Icon(Icons.Filled.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
                BannerTone.Error -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = content) }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
            }
        }
    }
}

/** 分组标题：列表里的「今天」「本周」「设置」等小标题，可带右侧操作。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
