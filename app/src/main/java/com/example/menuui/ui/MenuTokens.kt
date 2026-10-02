package com.example.menuui.ui

import androidx.compose.ui.unit.dp
import com.example.menuui.model.MenuLevel

/** 尺寸规范：统一调整间距、高度只改这里 */
object MenuTokens {
    val ScreenPadding = 16.dp
    val ItemSpacing = 10.dp
    val TileHeight = 92.dp
    val TileIconSize = 28.dp
    val RowIconSize = 22.dp
    val RowMinHeight = 56.dp
    val SubmitHeight = 52.dp
}

enum class ButtonStyle { Tile, Row }

/** 每一级菜单的布局规则 */
data class MenuLayoutSpec(val columns: Int, val buttonStyle: ButtonStyle)

fun MenuLevel.defaultLayout(): MenuLayoutSpec = when (this) {
    MenuLevel.PRIMARY -> MenuLayoutSpec(columns = 3, buttonStyle = ButtonStyle.Tile)
    MenuLevel.SECONDARY -> MenuLayoutSpec(columns = 1, buttonStyle = ButtonStyle.Row)
}
