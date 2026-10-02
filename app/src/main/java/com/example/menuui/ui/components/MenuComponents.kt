package com.example.menuui.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.menuui.model.ActionItem
import com.example.menuui.model.InputItem
import com.example.menuui.model.MenuAction
import com.example.menuui.model.MenuItem
import com.example.menuui.ui.ButtonStyle
import com.example.menuui.ui.MenuFormState
import com.example.menuui.ui.MenuTokens

/** 统一入口：根据 item 类型分发到具体组件。新增控件类型时在这里加一个分支 */
@Composable
fun MenuItemView(
    item: MenuItem,
    buttonStyle: ButtonStyle,
    form: MenuFormState,
    onAction: (MenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (item) {
        is InputItem -> MenuInputField(
            item = item,
            value = form.valueOf(item.id),
            error = form.errorOf(item.id),
            onValueChange = { form.update(item.id, it) },
            onImeSubmit = { onAction(MenuAction.Submit) },
            modifier = modifier,
        )
        is ActionItem ->
            if (item.action is MenuAction.Submit) {
                MenuSubmitButton(item, onClick = { onAction(item.action) }, modifier = modifier)
            } else {
                MenuActionButton(item, buttonStyle, onClick = { onAction(item.action) }, modifier = modifier)
            }
    }
}

@Composable
fun MenuActionButton(
    item: ActionItem,
    style: ButtonStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (style) {
        ButtonStyle.Tile -> TileButton(item, onClick, modifier)
        ButtonStyle.Row -> RowButton(item, onClick, modifier)
    }
}

/** 一级菜单：图标在上、文字在下的方块按钮 */
@Composable
private fun TileButton(item: ActionItem, onClick: () -> Unit, modifier: Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(MenuTokens.TileHeight)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MenuTokens.TileIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            item.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 二级菜单：横向列表行按钮 */
@Composable
private fun RowButton(item: ActionItem, onClick: () -> Unit, modifier: Modifier) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MenuTokens.RowMinHeight)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(MenuTokens.RowIconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.label, style = MaterialTheme.typography.bodyLarge)
                item.subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MenuSubmitButton(item: ActionItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(top = 6.dp).height(MenuTokens.SubmitHeight),
    ) {
        Text(item.label, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun MenuInputField(
    item: InputItem,
    value: String,
    error: String?,
    onValueChange: (String) -> Unit,
    onImeSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = item.hint
    val icon = item.icon
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(if (item.required) "${item.label} *" else item.label) },
        placeholder = if (hint.isNotEmpty()) { { Text(hint) } } else null,
        leadingIcon = if (icon != null) { { Icon(icon, contentDescription = null) } } else null,
        isError = error != null,
        supportingText = if (error != null) { { Text(error) } } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = item.keyboardType, imeAction = item.imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onImeSubmit() },
            onSearch = { onImeSubmit() },
            onGo = { onImeSubmit() },
        ),
    )
}
