package com.swufe.homework.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/** 圆角：卡片 16dp、按钮 12dp、标签 8dp。 */
@Immutable
object Corners {
    val Tag = RoundedCornerShape(8.dp)
    val Button = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(16.dp)
    /** 底部弹层、全屏对话框等大面板。 */
    val Sheet = RoundedCornerShape(24.dp)
}

/*
 * Material 槽位映射：extraSmall=标签/输入框，small=按钮（自己传 shape 时），medium=卡片，large=卡片/弹窗，extraLarge=大面板。
 * Material 3 的 Button/OutlinedButton 默认用 full（胶囊），所以按钮请用 [Corners.Button]，见 README。
 */
val SwufeShapes = Shapes(
    extraSmall = Corners.Tag,
    small = Corners.Button,
    medium = Corners.Card,
    large = Corners.Card,
    extraLarge = Corners.Sheet,
)
