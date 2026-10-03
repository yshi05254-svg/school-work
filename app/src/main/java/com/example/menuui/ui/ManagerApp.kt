package com.example.menuui.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.PublishUiState

/**
 * 管理端骨架：顶部发布状态条 + 底部 5 页签（首页 / 位置 / 应用 / 摇杆 / 更多），
 * 对应自然使用流"看状态 → 选位置 → 选应用 → 摇杆微调 → 高级配置"。
 *
 * 发布入口统一在顶部状态条（此前每页各有一个"发布配置"按钮 + Toast）：
 * 状态条始终显示模块是否与当前配置同步（已同步 / 有未发布改动 / 发布中 / 失败），
 * 手动发布与自动发布失败的结果以 Snackbar 提示，失败可点开查看完整原因。
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
    val publishUi by ConfigBus.publishUi.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var failureDetail by remember { mutableStateOf<String?>(null) }

    // 发布结果提示：手动发布成功/失败都提示，自动发布只提示失败
    LaunchedEffect(publishUi.last?.at) {
        val r = publishUi.last ?: return@LaunchedEffect
        if (!r.manual && r.ok) return@LaunchedEffect
        val res = snackbar.showSnackbar(
            message = if (r.ok) "已发布到模块（${r.via}）" else "发布失败",
            actionLabel = if (r.ok) null else "详情",
            duration = if (r.ok) SnackbarDuration.Short else SnackbarDuration.Long,
        )
        if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) failureDetail = r.message
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            PublishStatusBar(
                title = TABS.first { it.id == tab }.label,
                state = publishUi,
                onPublish = { ConfigBus.publishAsync(record = true) },
                onShowFailure = { failureDetail = publishUi.last?.message },
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
            when (tab) {
                ManagerTabs.HOME -> HomePage(onNavigate = { tab = it })
                ManagerTabs.LOCATION -> LocationPage()
                ManagerTabs.APPS -> AppsPage()
                ManagerTabs.JOYSTICK -> JoystickPage()
                ManagerTabs.MORE -> MorePage()
            }
        }
    }

    failureDetail?.let { msg ->
        AlertDialog(
            onDismissRequest = { failureDetail = null },
            title = { Text("发布失败") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    failureDetail = null
                    ConfigBus.publishAsync(record = true)
                }) { Text("重试") }
            },
            dismissButton = { TextButton(onClick = { failureDetail = null }) { Text("关闭") } },
        )
    }
}

/** 顶部状态条：页标题 + 同步状态 + 发布按钮（有改动/失败时突出显示） */
@Composable
private fun PublishStatusBar(
    title: String,
    state: PublishUiState,
    onPublish: () -> Unit,
    onShowFailure: () -> Unit,
) {
    val failed = state.last?.ok == false
    val (statusText, statusColor) = when {
        state.inFlight -> "发布中…" to MaterialTheme.colorScheme.onSurfaceVariant
        failed -> "上次发布失败" to MaterialTheme.colorScheme.error
        state.dirty -> "有未发布的改动" to MaterialTheme.colorScheme.tertiary
        else -> "已与模块同步" to MaterialTheme.colorScheme.secondary
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.inFlight) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
                    if (failed && !state.inFlight) {
                        TextButton(onClick = onShowFailure) { Text("查看原因") }
                    }
                }
            }
            if (state.dirty || failed) {
                FilledTonalButton(onClick = onPublish, enabled = !state.inFlight) { Text("发布") }
            } else {
                TextButton(onClick = onPublish, enabled = !state.inFlight) { Text("重新发布") }
            }
        }
    }
}
