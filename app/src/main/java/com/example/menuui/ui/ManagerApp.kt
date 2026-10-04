package com.example.menuui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.PublishStatus

/**
 * 管理端骨架：顶栏（页名 + 常驻发布状态）+ 底部 5 页签（首页 / 位置 / 应用 / 摇杆 / 更多），
 * 对应自然使用流"看状态 → 选位置 → 选应用 → 摇杆微调 → 高级配置"。
 *  - 发布状态常驻顶栏：是否已生效、发布失败一眼可见，点一下即手动发布（各页不再各放一个发布按钮）；
 *  - 提示统一走底部 Snackbar（[LocalNotifier]），可带"撤销"等动作；
 *  - 页签切换保留各页滚动位置与页内状态（SaveableStateHolder）。
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagerApp() {
    var tab by rememberSaveable { mutableStateOf(ManagerTabs.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notifier = remember { Notifier(snackbar, scope) }
    val tabStates = rememberSaveableStateHolder()
    val status by ConfigBus.status.collectAsState()
    val cfg by ConfigBus.state.collectAsState()

    CompositionLocalProvider(LocalNotifier provides notifier) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(TABS.first { it.id == tab }.label) },
                    actions = {
                        PublishStatusChip(status, cfg.autoPublish) {
                            ConfigBus.publishAsync(record = true) { r -> notifier.publishResult(r) }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
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
                tabStates.SaveableStateProvider(tab) {
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
    }
}

/** 顶栏发布状态：点击 = 立即手动发布（留发布记录） */
@Composable
private fun PublishStatusChip(status: PublishStatus, autoPublish: Boolean, onPublish: () -> Unit) {
    val last = status.last
    val (label, icon) = when {
        status.publishing -> "发布中…" to null
        status.pending && !autoPublish -> "未发布 · 点此发布" to Icons.AutoMirrored.Filled.Send
        status.pending -> "待生效…" to null
        last != null && !last.ok -> "发布失败 · 重试" to Icons.Filled.Warning
        last != null -> "已生效 ${formatTime(last.at).substringAfter(' ')}" to Icons.Filled.Refresh
        else -> "发布到模块" to Icons.AutoMirrored.Filled.Send
    }
    val failed = !status.publishing && !status.pending && last != null && !last.ok
    AssistChip(
        onClick = onPublish,
        enabled = !status.publishing,
        label = { Text(label) },
        leadingIcon = {
            if (icon == null) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize))
            }
        },
        colors = if (failed) {
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            AssistChipDefaults.assistChipColors()
        },
        modifier = Modifier.padding(end = 8.dp),
    )
}
