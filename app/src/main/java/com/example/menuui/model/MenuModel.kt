package com.example.menuui.model

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/** 占满整行 */
const val FULL_SPAN = Int.MAX_VALUE

/** 菜单层级。数量约束集中在这里，想改范围只改这一处 */
enum class MenuLevel(val itemRange: IntRange) {
    PRIMARY(8..10),
    SECONDARY(3..6),
}

/** 按钮点击后要做的事 */
sealed interface MenuAction {
    data class OpenPage(val pageId: String) : MenuAction // 跳转到另一页
    data class Custom(val key: String) : MenuAction       // 交给业务层处理
    data object Submit : MenuAction                       // 校验并提交本页所有输入
}

sealed interface MenuItem {
    val id: String
    val label: String
    /** 在网格中占几列，超出当前列数时自动按整行处理 */
    val span: Int
}

data class ActionItem(
    override val id: String,
    override val label: String,
    val action: MenuAction,
    val icon: ImageVector? = null,
    val subtitle: String? = null,
    override val span: Int = 1,
) : MenuItem

data class InputItem(
    override val id: String,
    override val label: String,
    val hint: String = "",
    val icon: ImageVector? = null,
    val keyboardType: KeyboardType = KeyboardType.Text,
    /** Done / Search / Go 会触发本页提交；Next 跳到下一个输入框 */
    val imeAction: ImeAction = ImeAction.Next,
    val required: Boolean = false,
    /** 返回 null 表示通过，否则返回错误提示 */
    val validator: ((String) -> String?)? = null,
    override val span: Int = FULL_SPAN,
) : MenuItem

data class MenuPage(
    val id: String,
    val title: String,
    val level: MenuLevel,
    val items: List<MenuItem>,
) {
    init {
        require(items.size in level.itemRange) {
            "页面 [$id] 有 ${items.size} 项，${level.name} 级要求 ${level.itemRange.first}–${level.itemRange.last} 项"
        }
        require(items.map { it.id }.toSet().size == items.size) { "页面 [$id] 内存在重复的 item id" }
    }
}

/** 所有页面的注册表，启动时检查跳转目标是否存在 */
class MenuRegistry(val root: MenuPage, children: List<MenuPage>) {
    private val pages: Map<String, MenuPage> = (listOf(root) + children).associateBy { it.id }

    init {
        require(root.level == MenuLevel.PRIMARY) { "根页面必须是一级菜单" }
        require(pages.size == children.size + 1) { "页面 id 重复" }
        pages.values.flatMap { it.items }
            .filterIsInstance<ActionItem>()
            .mapNotNull { (it.action as? MenuAction.OpenPage)?.pageId }
            .forEach { target -> require(target in pages) { "跳转目标不存在：$target" } }
    }

    fun page(id: String): MenuPage = pages[id] ?: error("未知页面：$id")
}

/** 常用校验器，可直接在配置中复用 */
object Validators {
    val number: (String) -> String? = { if (it.toDoubleOrNull() == null) "请输入有效数字" else null }

    val url: (String) -> String? = {
        if (it.startsWith("http://") || it.startsWith("https://")) null else "地址需以 http:// 或 https:// 开头"
    }

    fun intIn(range: IntRange): (String) -> String? = { v ->
        val n = v.toIntOrNull()
        if (n != null && n in range) null else "请输入 ${range.first}–${range.last} 之间的整数"
    }

    fun maxLength(max: Int): (String) -> String? = { if (it.length > max) "最多 $max 个字符" else null }
}
