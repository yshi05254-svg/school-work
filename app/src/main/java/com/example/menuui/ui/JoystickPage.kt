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
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        SectionCard(
            title = "悬浮窗摇杆",
            subtitle = if (running) "运行中：在任何应用上拖动摇杆即可移动位置"
            else "开启后在任何应用上叠加摇杆，推动即连续移动位置",
        ) {
            val l = live
            if (running && l != null) {
                Text(
                    "${"%.6f".format(l.lat)}, ${"%.6f".format(l.lon)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                HintText(directionText(l.speedMps, l.vNorthMps, l.vEastMps))
            }
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
                ) { Text("启动摇杆") }
                if (!Settings.canDrawOverlays(context)) {
                    HintText("首次启动需要授予\"显示在其他应用上层\"权限")
                }
            } else {
                Button(
                    onClick = {
                        context.startService(
                            Intent(context, JoystickOverlayService::class.java)
                                .setAction(JoystickOverlayService.ACTION_STOP_PARK),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("停止，留在当前位置") }
                OutlinedButton(
                    onClick = {
                        context.startService(
                            Intent(context, JoystickOverlayService::class.java)
                                .setAction(JoystickOverlayService.ACTION_STOP),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("停止，回到原位置") }
            }
        }

        SectionCard(
            title = "速度",
            subtitle = "摇杆推满时的速度；小幅推动按比例减速",
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
                    "自定义速度", cfg.joystickSpeedMps,
                    { v -> ConfigBus.update { c -> c.copy(joystickSpeedMps = v) } },
                    suffix = "m/s", validate = rangeCheck(0.0, 200.0),
                )
            }
            HintText("当前 ${"%.1f".format(cfg.joystickSpeedMps)} m/s（约 ${"%.0f".format(cfg.joystickSpeedMps * 3.6)} km/h）")
        }

        SectionCard(title = "说明", collapsible = true, initiallyExpanded = false) {
            HintText("松手即原地静止；静止时自动降低刷新频率以省电。")
            HintText("摇杆被系统关闭时，位置会在移动中 6 秒、静止时 30 秒内回到原位置，不会继续漂移。")
            HintText("\"停止，留在当前位置\"会把摇杆终点保存为新的环境坐标。")
        }
    }
}

/** 速度矢量 → "静止" / "速度 x m/s · 方向 东北（45°）" */
private fun directionText(speedMps: Double, vNorth: Double, vEast: Double): String {
    if (speedMps < 0.05) return "静止"
    val deg = (Math.toDegrees(kotlin.math.atan2(vEast, vNorth)) + 360.0) % 360.0
    val name = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[((deg + 22.5) / 45.0).toInt() % 8]
    return "速度 ${"%.1f".format(speedMps)} m/s · 方向 $name（${"%.0f".format(deg)}°）"
}
