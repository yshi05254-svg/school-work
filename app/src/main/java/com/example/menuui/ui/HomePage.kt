package com.example.menuui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.joystick.JoystickOverlayService
import com.example.menuui.publish.Probe
import java.util.Date

/**
 * 首页（状态总览）：总开关、模块通道状态（探测：已连接/未安装/签名不符→su 通道）、
 * 当前环境与目标应用概览（可跳转对应页）、摇杆运行态、最近发布记录。
 * 链路任何一环断开都在这里可见，并给出下一步该做什么，不静默。
 */
@Composable
fun HomePage(onNavigate: (String) -> Unit = {}) {
    val cfg by ConfigBus.state.collectAsState()
    val joystick by ConfigBus.joystick.collectAsState()
    val joystickRunning by JoystickOverlayService.running.collectAsState()
    var probe by remember { mutableStateOf<Probe?>(null) }

    LaunchedEffect(Unit) {
        ConfigBus.probeAsync { p -> probe = p }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        SectionCard(title = "虚拟环境") {
            SwitchRow(
                title = "启用虚拟环境",
                subtitle = if (cfg.masterEnabled) "目标应用读取到的是下方配置的环境"
                else "已关闭：所有应用读取真实位置与设备信息",
                checked = cfg.masterEnabled,
                onChange = { on ->
                    ConfigBus.update { it.copy(masterEnabled = on) }
                    ConfigBus.publishAsync(record = false)
                },
            )
        }

        ModuleStatusCard(
            probe = probe,
            onReprobe = {
                probe = null
                ConfigBus.probeAsync { p -> probe = p }
            },
        )

        SectionCard(title = "当前位置") {
            Text(cfg.env.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${"%.6f".format(cfg.env.lat)}, ${"%.6f".format(cfg.env.lon)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            HintText(
                "精度 ${formatDouble(cfg.env.accuracy.toDouble())} m · 基站 ${cfg.env.cells.size} · " +
                    "WiFi ${cfg.env.wifis.size} · 蓝牙 ${cfg.env.btDevices.size}" +
                    if (cfg.sim.enabled) " · SIM ${cfg.sim.slots.size} 槽" else "",
            )
            TextButton(onClick = { onNavigate(ManagerTabs.LOCATION) }) { Text("切换或编辑位置") }
        }

        SectionCard(title = "目标应用") {
            val enabled = cfg.apps.filter { it.enabled && it.pkg.isNotBlank() }
            if (enabled.isEmpty()) {
                Text("没有生效的应用", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                HintText("虚拟环境只作用于这里添加并启用的应用")
            } else {
                Text("${enabled.size} 个应用生效", style = MaterialTheme.typography.bodyMedium)
                HintText(enabled.joinToString("、") { it.pkg })
            }
            TextButton(onClick = { onNavigate(ManagerTabs.APPS) }) { Text("管理应用") }
        }

        SectionCard(title = "摇杆") {
            val live = joystick
            if (!joystickRunning || live == null) {
                Text("未运行", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "运行中 · ${"%.6f".format(live.lat)}, ${"%.6f".format(live.lon)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HintText("速度 ${"%.1f".format(live.speedMps)} m/s")
            }
            TextButton(onClick = { onNavigate(ManagerTabs.JOYSTICK) }) {
                Text(if (joystickRunning) "打开摇杆控制" else "启动摇杆")
            }
        }

        SectionCard(
            title = "发布记录",
            subtitle = if (cfg.history.isEmpty()) "暂无记录" else "最近 ${minOf(cfg.history.size, 8)} 条手动发布",
            collapsible = true,
            initiallyExpanded = false,
        ) {
            cfg.history.take(8).forEach { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        "${if (r.ok) "成功" else "失败"} · ${formatTime(r.at)} · ${r.via}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    HintText(r.message)
                }
            }
        }
    }
}

/** 模块通道状态：按严重程度着色，并告诉用户下一步怎么做 */
@Composable
private fun ModuleStatusCard(probe: Probe?, onReprobe: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val v = when (probe) {
        is Probe.Ok -> StatusView(
            "模块已连接",
            "配置通道正常 · 模块内配置更新于 ${formatTime(probe.version)}",
            scheme.secondaryContainer, scheme.onSecondaryContainer,
        )
        is Probe.SignatureMismatch -> StatusView(
            "模块签名与管理端不一致",
            "仍可使用：发布会改走 root 文件通道。用同一签名安装两个 APK 可恢复直连。",
            scheme.tertiaryContainer, scheme.onTertiaryContainer,
        )
        is Probe.NotInstalled -> StatusView(
            "未安装模块",
            "请安装 ven11 模块，并在 LSPosed 中启用、勾选目标应用后重启目标应用。",
            scheme.errorContainer, scheme.onErrorContainer,
        )
        is Probe.Fault -> StatusView(
            "模块未响应",
            probe.cause,
            scheme.tertiaryContainer, scheme.onTertiaryContainer,
        )
        null -> StatusView("正在检测模块…", "", scheme.surfaceContainerLow, scheme.onSurface)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = v.container, contentColor = v.content),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(v.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            if (v.detail.isNotEmpty()) Text(v.detail, style = MaterialTheme.typography.bodySmall)
            if (probe != null) {
                TextButton(onClick = onReprobe) { Text("重新检测", color = v.content) }
            }
        }
    }
}

private class StatusView(val title: String, val detail: String, val container: Color, val content: Color)

internal fun formatTime(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(Date(epochMs))
