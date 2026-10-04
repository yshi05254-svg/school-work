package com.example.menuui.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.publish.Probe
import java.util.Date

/**
 * 首页（状态总览）：总开关、模块通道状态（探测：已连接/未安装/签名不符→su 通道）、
 * 当前环境概览、摇杆运行态、最近发布记录。链路任何一环断开都在这里可见，不静默。
 */
@Composable
fun HomePage() {
    val context = LocalContext.current
    val cfg by ConfigBus.state.collectAsState()
    val joystick by ConfigBus.joystick.collectAsState()
    var probe by remember { mutableStateOf<Probe?>(null) }

    LaunchedEffect(Unit) {
        ConfigBus.probeAsync { p -> probe = p }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "虚拟环境总开关",
            subtitle = "关闭后所有应用透传真实位置（快照 masterEnabled=false）",
        ) {
            SwitchRow(
                title = "masterEnabled",
                checked = cfg.masterEnabled,
                onChange = { on ->
                    ConfigBus.update { it.copy(masterEnabled = on) }
                    ConfigBus.publishAsync(record = false)
                },
            )
        }

        SectionCard(
            title = "定位伪装执行端",
            subtitle = "开启后由 system_server 直接改写交给应用的定位，" +
                "目标应用无需读取模块配置（微信等受限应用也生效）；" +
                "关闭则回退到应用内伪装（应急，免重装模块）",
        ) {
            SwitchRow(
                title = "serverLocation",
                checked = cfg.serverLocation,
                onChange = { on ->
                    ConfigBus.update { it.copy(serverLocation = on) }
                    ConfigBus.publishAsync(record = false)
                },
            )
        }

        SectionCard(title = "模块通道状态", subtitle = "探测模块安装 / provider 存活 / 签名") {
            val text = when (val p = probe) {
                is Probe.Ok ->
                    "✅ 已连接 · payload 版本 ${formatTime(p.version)}" +
                        if (p.sameSignature) "（签名一致，provider 直写）"
                        else ""
                is Probe.SignatureMismatch ->
                    "⚠ 模块已装但签名不符（provider 写入将回落 su 文件通道）"
                is Probe.NotInstalled ->
                    "❌ 未安装模块（dev.ven11.module）"
                is Probe.Fault -> "⚠ provider 无响应：${p.cause}"
                null -> "探测中…"
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                probe = null
                ConfigBus.probeAsync { p -> probe = p }
            }) { Text("重新探测") }
        }

        SectionCard(title = "当前环境", subtitle = "在\"位置\"页选择预设或自定义编辑") {
            Text(
                "${cfg.env.name} · ${"%.6f".format(cfg.env.lat)}, ${"%.6f".format(cfg.env.lon)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "高度 ${cfg.env.alt} m · 精度 ${cfg.env.accuracy} m · " +
                    "基站 ${cfg.env.cells.size} · WiFi ${cfg.env.wifis.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "目标应用（${cfg.apps.size}）：${cfg.apps.joinToString("、") { it.pkg }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "摇杆", subtitle = "悬浮窗实时输入（\"摇杆\"页开启）") {
            val live = joystick
            if (live == null) {
                Text("未运行", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    if (live.active) "运行中 · ${"%.6f".format(live.lat)}, ${"%.6f".format(live.lon)}"
                    else "已停驻 · ${"%.6f".format(live.lat)}, ${"%.6f".format(live.lon)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "速度 ${"%.1f".format(live.speedMps)} m/s · 会话 #${live.epoch}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = {
                ConfigBus.publishAsync(record = true) { r ->
                    Toast.makeText(
                        context,
                        (if (r.ok) "发布成功（${r.via}）：" else "发布失败：") + r.message,
                        if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("立即发布配置到模块") }

        SectionCard(title = "发布记录", subtitle = "仅手动发布留痕（服务节拍不记录）") {
            if (cfg.history.isEmpty()) {
                Text("暂无记录", style = MaterialTheme.typography.bodySmall)
            } else {
                cfg.history.take(8).forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "${if (r.ok) "✓" else "✗"} ${formatTime(r.at)} · ${r.via}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            r.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

internal fun formatTime(epochMs: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(Date(epochMs))
