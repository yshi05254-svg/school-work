package com.example.menuui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.menuui.model.MenuAction
import com.example.menuui.model.MenuRegistry
import com.example.menuui.ui.MenuScreen

private const val ARG_PAGE = "pageId"
private fun route(pageId: String) = "page/$pageId"

/**
 * 整个菜单系统只有一个路由 page/{pageId}，新增页面不用改导航代码。
 * 业务逻辑通过两个回调接入：自定义按钮 [onCustomAction]（带点击时本页表单快照）、
 * 表单提交 [onSubmit]（已通过校验）。
 */
@Composable
fun MenuNavHost(
    registry: MenuRegistry,
    onCustomAction: (key: String, values: Map<String, String>) -> Unit,
    onSubmit: (pageId: String, values: Map<String, String>) -> Unit,
) {
    val nav = rememberNavController()
    val goBack: () -> Unit = { nav.popBackStack() }

    NavHost(navController = nav, startDestination = route(registry.root.id)) {
        composable("page/{$ARG_PAGE}") { entry ->
            val pageId = entry.arguments?.getString(ARG_PAGE) ?: registry.root.id
            val page = registry.page(pageId)
            MenuScreen(
                page = page,
                onBack = if (pageId == registry.root.id) null else goBack,
                onAction = { action ->
                    when (action) {
                        is MenuAction.OpenPage -> nav.navigate(route(action.pageId))
                        is MenuAction.Custom -> onCustomAction(action.key, action.values)
                        MenuAction.Submit -> Unit // 已在 MenuScreen 内处理
                    }
                },
                onSubmit = { values -> onSubmit(page.id, values) },
            )
        }
    }
}
