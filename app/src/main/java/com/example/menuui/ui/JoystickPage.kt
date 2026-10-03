package com.example.menuui.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.JoystickPresets
import com.example.menuui.joystick.JoystickOverlayService

/**
 * 摇杆页：速度档位、悬浮窗权限引导、启停控制与实时状态。
 * 摇杆本体是悬浮窗（任何界面可操作），本页是它的控制台；
 * "停止并停驻"把最终位置写回当前环境，关停后位置不跳回预设起点。
 */
@Composable
fun JoystickPage() {
    val context = LocalContext.current
    val cfg by ConfigBus.state.collectAsState()
    val live by ConfigBus.joystick.collectAsState()
    val running by JoystickOverlayService.running.collectAsState()

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun startService() {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        context.startForegroundService(
            Intent(context, JoystickOverlayService::class.java)
                .setAction(JoystickOverlayService.ACTION_START),
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "速度档位",
            subtitle = "摇杆推满时的最大速度；小幅推动按比例减速（模拟量）",
        ) {
            ChoiceChips(
                options = JoystickPresets.ALL,
                selected = JoystickPresets.byId(cfg.joystickPresetId),
                label = { it.label },
                onSelect = { p ->
                    ConfigBus.update { c ->
                        if (p.id == JoystickPresets.CUSTOM.id) {
                            c.copy(joystickPresetId = p.id)
                        } else {
                            c.copy(joystickPresetId = p.id, joystickSpeedMps = p.speedMps)
                        }
                    }
                },
            )
            if (cfg.joystickPresetId == JoystickPresets.CUSTOM.id) {
                DoubleField(
                    "自定义速度 (m/s)", cfg.joystickSpeedMps,
                    { v -> ConfigBus.update { c -> c.copy(joystickSpeedMps = v.coerceIn(0.0, 200.0)) } },
                )
            }
            Text(
                "当前：${"%.1f".format(cfg.joystickSpeedMps)} m/s（≈${"%.0f".format(cfg.joystickSpeedMps * 3.6)} km/h）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "悬浮窗摇杆",
            subtitle = "开启后在任何应用上叠加摇杆控件；位置以摇杆推算为准连续更新",
        ) {
            if (!running) {
                Button(
                    onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            startService()
                        } else {
                            Toast.makeText(context, "请先授予\"显示在其他应用上层\"权限", Toast.LENGTH_LONG).show()
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("启动悬浮窗摇杆") }
            } else {
                OutlinedButton(
                    onClick = {
                        context.startService(
                            Intent(context, JoystickOverlayService::class.java)
                                .setAction(JoystickOverlayService.ACTION_STOP),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("停止摇杆（回落预设位置）") }
                Button(
                    onClick = {
                        context.startService(
                            Intent(context, JoystickOverlayService::class.java)
                                .setAction(JoystickOverlayService.ACTION_STOP_PARK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("停止并停驻在当前位置") }
            }
            Text(
                "说明：摇杆松手即静止原地；服务被系统杀死时模块会在 6 秒内回落到预设位置，不会按失联前速度继续漂移。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "实时状态") {
            val l = live
            if (l == null || !running) {
                Text("未运行", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "位置 ${"%.6f".format(l.lat)}, ${"%.6f".format(l.lon)}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val dir = when {
                    l.speedMps < 0.05 -> "静止"
                    else -> {
                        val deg = (Math.toDegrees(
                            kotlin.math.atan2(l.vEastMps, l.vNorthMps),
                        ) + 360.0) % 360.0
                        val name = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[
                            ((deg + 22.5) / 45.0).toInt() % 8,
                        ]
                        "速度 ${"%.2f".format(l.speedMps)} m/s · 方向 $name（${"%.0f".format(deg)}°）"
                    }
                }
                Text(
                    dir,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "会话 #${l.epoch} · 心跳失联 6s 自动回落",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
