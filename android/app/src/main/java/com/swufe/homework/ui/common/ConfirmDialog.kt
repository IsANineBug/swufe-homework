package com.swufe.homework.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.swufe.homework.ui.theme.Corners
import kotlinx.coroutines.delay

/**
 * 确认弹窗。
 * - 普通确认：只传标题、正文、按钮文字。
 * - [destructive] = true：标题、图标、确认按钮用 error 色（删除、退出班级、处罚等）。
 * - [countdownSeconds] > 0：「倒数 N 秒后可点」变体，确认按钮显示「确认删除（3）」，倒数到 0 才能点；
 *   旋转屏幕不会重新倒数。
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "取消",
    destructive: Boolean = false,
    countdownSeconds: Int = 0,
    icon: ImageVector? = null,
) {
    var left by rememberSaveable { mutableIntStateOf(countdownSeconds.coerceAtLeast(0)) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null, tint = accent) } },
        title = { Text(title, color = if (destructive) accent else MaterialTheme.colorScheme.onSurface) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = left == 0,
                shape = Corners.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = if (destructive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                ),
            ) { Text(countdownLabel(confirmLabel, left)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** 「确认删除（3）」→ 倒数结束后「确认删除」。 */
internal fun countdownLabel(label: String, left: Int): String = if (left > 0) "$label（$left）" else label
