package com.example.menuui.model

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

@DslMarker
annotation class MenuDsl

@MenuDsl
class MenuPageBuilder internal constructor(
    private val id: String,
    private val title: String,
    private val level: MenuLevel,
) {
    private val items = mutableListOf<MenuItem>()

    /** 普通按钮：给 opens 就跳页，否则作为自定义动作（key = id）交给业务层 */
    fun button(
        id: String,
        label: String,
        icon: ImageVector? = null,
        subtitle: String? = null,
        opens: String? = null,
        span: Int = 1,
    ) {
        val action = if (opens != null) MenuAction.OpenPage(opens) else MenuAction.Custom(id)
        items += ActionItem(id, label, action, icon, subtitle, span)
    }

    fun input(
        id: String,
        label: String,
        hint: String = "",
        icon: ImageVector? = null,
        type: KeyboardType = KeyboardType.Text,
        ime: ImeAction = ImeAction.Next,
        required: Boolean = false,
        validator: ((String) -> String?)? = null,
        span: Int = FULL_SPAN,
    ) {
        items += InputItem(id, label, hint, icon, type, ime, required, validator, span)
    }

    /** 主按钮：校验所有输入后提交 */
    fun submit(label: String = "提交", id: String = "submit") {
        items += ActionItem(id, label, MenuAction.Submit, span = FULL_SPAN)
    }

    internal fun build() = MenuPage(id, title, level, items.toList())
}

fun primaryPage(id: String, title: String, block: MenuPageBuilder.() -> Unit): MenuPage =
    MenuPageBuilder(id, title, MenuLevel.PRIMARY).apply(block).build()

fun secondaryPage(id: String, title: String, block: MenuPageBuilder.() -> Unit): MenuPage =
    MenuPageBuilder(id, title, MenuLevel.SECONDARY).apply(block).build()
