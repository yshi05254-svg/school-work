package com.example.menuui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 管理端骨架：底部 5 页签（首页 / 位置 / 应用 / 摇杆 / 更多），对应自然使用流
 * "看状态 → 选位置 → 选应用 → 摇杆微调 → 高级配置"。页签切换用本地状态即可，
 * 不需要真正的导航栈；各页内容随走随取 ConfigBus 状态。
 */
object ManagerTabs {
    const val HOME = "home"
    const val LOCATION = "location"
    const val APPS = "apps"
    const val JOYSTICK = "joystick"
    const val MORE = "more"
}

private data class TabSpec(val id: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec(ManagerTabs.HOME, "首页", Icons.Filled.Home),
    TabSpec(ManagerTabs.LOCATION, "位置", Icons.Filled.LocationOn),
    TabSpec(ManagerTabs.APPS, "应用", Icons.Filled.List),
    TabSpec(ManagerTabs.JOYSTICK, "摇杆", Icons.Filled.PlayArrow),
    TabSpec(ManagerTabs.MORE, "更多", Icons.Filled.MoreVert),
)

@Composable
fun ManagerApp() {
    var tab by rememberSaveable { mutableStateOf(ManagerTabs.HOME) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar {
                TABS.forEach { spec ->
                    NavigationBarItem(
                        selected = tab == spec.id,
                        onClick = { tab = spec.id },
                        icon = { Icon(spec.icon, contentDescription = spec.label) },
                        label = { Text(spec.label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            when (tab) {
                ManagerTabs.HOME -> HomePage()
                ManagerTabs.LOCATION -> LocationPage()
                ManagerTabs.APPS -> AppsPage()
                ManagerTabs.JOYSTICK -> JoystickPage()
                ManagerTabs.MORE -> MorePage()
            }
        }
    }
}
