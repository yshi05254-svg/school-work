package com.example.menuui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.menuui.config.AppMenus
import com.example.menuui.model.MenuAction
import com.example.menuui.model.MenuPage
import com.example.menuui.ui.components.MenuItemView
import com.example.menuui.ui.theme.AppTheme

/**
 * 通用菜单页：一级、二级都用它渲染，区别只在 [layout]。
 * @param onBack 为 null 时不显示返回按钮（根页面）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(
    page: MenuPage,
    onAction: (MenuAction) -> Unit,
    onSubmit: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    layout: MenuLayoutSpec = page.level.defaultLayout(),
) {
    val form = rememberMenuFormState(page)
    val handleAction: (MenuAction) -> Unit = { action ->
        when (action) {
            is MenuAction.Submit ->
                if (form.validate()) onSubmit(form.snapshot())
            // 自定义按钮随带本页表单快照（审查六：发布按钮需要读到"目标包名"输入框，
            // 且不经过 Submit 的必填/格式校验——那些校验属于其它字段）
            is MenuAction.Custom -> onAction(action.copy(values = form.snapshot()))
            else -> onAction(action)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(MenuTokens.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(MenuTokens.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(MenuTokens.ItemSpacing),
        ) {
            items(
                items = page.items,
                key = { it.id },
                span = { item -> GridItemSpan(minOf(item.span, maxLineSpan)) },
            ) { item ->
                MenuItemView(item = item, buttonStyle = layout.buttonStyle, form = form, onAction = handleAction)
            }
        }
    }
}

@Preview(name = "一级菜单", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun PrimaryMenuPreview() {
    AppTheme { MenuScreen(page = AppMenus.home, onAction = {}, onSubmit = {}) }
}

@Preview(name = "二级菜单", showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun SecondaryMenuPreview() {
    AppTheme { MenuScreen(page = AppMenus.settings, onAction = {}, onSubmit = {}, onBack = {}) }
}
