package com.example.menuui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.example.menuui.model.InputItem
import com.example.menuui.model.MenuPage

/** 一页内所有输入框的值和错误信息 */
@Stable
class MenuFormState(private val inputs: List<InputItem>) {
    private val values = mutableStateMapOf<String, String>()
    private val errors = mutableStateMapOf<String, String>()

    fun valueOf(id: String): String = values[id].orEmpty()
    fun errorOf(id: String): String? = errors[id]

    fun update(id: String, value: String) {
        values[id] = value
        errors.remove(id)
    }

    fun validate(): Boolean {
        errors.clear()
        inputs.forEach { item ->
            val v = valueOf(item.id).trim()
            val msg = when {
                item.required && v.isEmpty() -> "请输入${item.label}"
                v.isNotEmpty() -> item.validator?.invoke(v)
                else -> null
            }
            if (msg != null) errors[item.id] = msg
        }
        return errors.isEmpty()
    }

    fun snapshot(): Map<String, String> = inputs.associate { it.id to valueOf(it.id).trim() }
}

@Composable
fun rememberMenuFormState(page: MenuPage): MenuFormState =
    remember(page.id) { MenuFormState(page.items.filterIsInstance<InputItem>()) }
