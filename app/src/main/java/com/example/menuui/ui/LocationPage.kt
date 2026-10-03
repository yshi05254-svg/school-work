package com.example.menuui.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.menuui.config.CellDraft
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.EnvDraft
import com.example.menuui.config.Presets
import com.example.menuui.config.WifiDraft

/**
 * 位置页：固定 8 组预设（1 国外 + 7 个不同中国省份）单选载入 + 当前环境全量编辑器
 * （坐标/高度/精度/速度/朝向、基站、WiFi、蓝牙、语言/时区建议、GPS 抖动）。
 * 预设选中即发布（动态更新位置）；编辑器改动点"发布配置"生效。
 */
@Composable
fun LocationPage() {
    val context = LocalContext.current
    val cfg by ConfigBus.state.collectAsState()

    val publish: () -> Unit = {
        ConfigBus.publishAsync(record = true) { r ->
            Toast.makeText(
                context,
                (if (r.ok) "发布成功（${r.via}）" else "发布失败：") + r.message,
                if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "位置预设（${Presets.all.size} 组）", subtitle = "点击载入到当前环境（含该组基站/WiFi/蓝牙/SIM）并立即发布") {
            Presets.all.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { preset ->
                        PresetCard(
                            preset = preset,
                            selected = cfg.env.name == preset.env.name &&
                                cfg.env.lat == preset.env.lat,
                            modifier = Modifier.weight(1f),
                            onSelect = {
                                ConfigBus.update { c -> c.copy(env = preset.env, sim = preset.sim) }
                                ConfigBus.publishAsync(record = false)
                            },
                        )
                    }
                    if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionCard(
            title = "环境编辑器（当前生效：${cfg.env.name}）",
            subtitle = "覆盖模块位置域全部可伪造信息",
        ) {
            Text("坐标", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField("纬度", cfg.env.lat, { v -> editEnv { it.copy(lat = v) } }, Modifier.weight(1f))
                DoubleField("经度", cfg.env.lon, { v -> editEnv { it.copy(lon = v) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField("高度 (m)", cfg.env.alt, { v -> editEnv { it.copy(alt = v) } }, Modifier.weight(1f))
                DoubleField("精度 (m)", cfg.env.accuracy.toDouble(), { v -> editEnv { it.copy(accuracy = v.toFloat()) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField("速度 (m/s)", cfg.env.speed.toDouble(), { v -> editEnv { it.copy(speed = v.toFloat()) } }, Modifier.weight(1f))
                DoubleField("朝向 (°)", cfg.env.bearing.toDouble(), { v -> editEnv { it.copy(bearing = ((v % 360.0 + 360.0) % 360.0).toFloat()) } }, Modifier.weight(1f))
            }
            Text("语言/时区建议（国外预设自动带，per-app 可在\"应用\"页覆盖）", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("语言 (如 ja-JP)", cfg.env.languageTag, { v -> editEnv { it.copy(languageTag = v) } }, Modifier.weight(1f))
                StrField("时区 (如 Asia/Tokyo)", cfg.env.timezoneId, { v -> editEnv { it.copy(timezoneId = v) } }, Modifier.weight(1f))
            }
            Text("GPS 抖动（对抗固定坐标检测，快照全局段）", style = MaterialTheme.typography.labelLarge)
            SwitchRow(
                title = "启用抖动",
                checked = cfg.jitterEnabled,
                onChange = { on -> ConfigBus.update { c -> c.copy(jitterEnabled = on) } },
            )
            if (cfg.jitterEnabled) {
                DoubleField(
                    "抖动幅度 (m)", cfg.jitterAmplitudeMeters,
                    { v -> ConfigBus.update { c -> c.copy(jitterAmplitudeMeters = v) } },
                )
            }
        }

        SectionCellList(cfg)

        SectionWifiList(cfg)

        SectionBluetooth(cfg)

        Button(onClick = publish, modifier = Modifier.fillMaxWidth()) { Text("发布配置") }
    }
}

@Composable
private fun PresetCard(
    preset: Presets.NamedPreset,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                preset.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                preset.region,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "%.4f, %.4f".format(preset.env.lat, preset.env.lon),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun editEnv(transform: (EnvDraft) -> EnvDraft) {
    ConfigBus.update { c -> c.copy(env = transform(c.env)) }
}

// ---------------------------------------------------------------- 基站

@Composable
private fun SectionCellList(cfg: com.example.menuui.config.ManagerConfig) {
    SectionCard(
        title = "基站（${cfg.env.cells.size}）",
        subtitle = "PLMN 保留前导零（移动 mnc=00）；radioType 决定其余字段的语义",
    ) {
        cfg.env.cells.forEachIndexed { i, cell ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "基站 ${i + 1}" + if (cell.registered) "（驻留）" else "",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            editEnv { e -> e.copy(cells = e.cells.filterIndexed { idx, _ -> idx != i }) }
                        }) { Icon(Icons.Filled.Close, "删除基站") }
                    }
                    ChoiceChips(
                        options = listOf("gsm", "wcdma", "lte", "nr"),
                        selected = cell.radioType,
                        label = { it },
                        onSelect = { t -> editCell(i) { it.copy(radioType = t) } },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StrField("MCC", cell.mcc, { v -> editCell(i) { c -> c.copy(mcc = v.padEnd(3, '0').take(3)) } }, Modifier.weight(1f))
                        StrField("MNC", cell.mnc, { v -> editCell(i) { c -> c.copy(mnc = v) } }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StrField(
                            "CI", cell.ci.toString(),
                            { v -> v.toLongOrNull()?.let { n -> editCell(i) { c -> c.copy(ci = n) } } },
                            Modifier.weight(1f),
                        )
                        IntField("TAC", cell.tac, { v -> editCell(i) { c -> c.copy(tac = v) } }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("PCI", cell.pci, { v -> editCell(i) { c -> c.copy(pci = v) } }, Modifier.weight(1f))
                        IntField("ARFCN", cell.arfcn, { v -> editCell(i) { c -> c.copy(arfcn = v) } }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("信号 (dBm)", cell.signalDbm, { v -> editCell(i) { c -> c.copy(signalDbm = v) } }, Modifier.weight(1f))
                        TextButton(onClick = { editCell(i) { c -> c.copy(registered = !c.registered) } }) {
                            Text(if (cell.registered) "取消驻留" else "设为驻留")
                        }
                    }
                }
            }
        }
        TextButton(onClick = { editEnv { e -> e.copy(cells = e.cells + CellDraft()) } }) {
            Icon(Icons.Filled.Add, null); Text(" 添加基站")
        }
    }
}

private fun editCell(index: Int, transform: (CellDraft) -> CellDraft) {
    editEnv { e ->
        e.copy(cells = e.cells.mapIndexed { idx, c -> if (idx == index) transform(c) else c })
    }
}

// ---------------------------------------------------------------- WiFi

@Composable
private fun SectionWifiList(cfg: com.example.menuui.config.ManagerConfig) {
    SectionCard(title = "WiFi（${cfg.env.wifis.size}）", subtitle = "首项视为当前连接的网络") {
        cfg.env.wifis.forEachIndexed { i, wifi ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("WiFi ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            editEnv { e -> e.copy(wifis = e.wifis.filterIndexed { idx, _ -> idx != i }) }
                        }) { Icon(Icons.Filled.Close, "删除 WiFi") }
                    }
                    StrField("SSID", wifi.ssid, { v -> editWifi(i) { it.copy(ssid = v) } })
                    StrField("BSSID (MAC)", wifi.bssid, { v -> editWifi(i) { it.copy(bssid = v) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntField("信号 (dBm)", wifi.signalDbm, { v -> editWifi(i) { it.copy(signalDbm = v) } }, Modifier.weight(1f))
                        IntField("频率 (MHz)", wifi.frequencyMhz, { v -> editWifi(i) { it.copy(frequencyMhz = v) } }, Modifier.weight(1f))
                    }
                }
            }
        }
        TextButton(onClick = { editEnv { e -> e.copy(wifis = e.wifis + WifiDraft()) } }) {
            Icon(Icons.Filled.Add, null); Text(" 添加 WiFi")
        }
    }
}

private fun editWifi(index: Int, transform: (WifiDraft) -> WifiDraft) {
    editEnv { e ->
        e.copy(wifis = e.wifis.mapIndexed { idx, w -> if (idx == index) transform(w) else w })
    }
}

// ---------------------------------------------------------------- 蓝牙

@Composable
private fun SectionBluetooth(cfg: com.example.menuui.config.ManagerConfig) {
    SectionCard(
        title = "蓝牙（${cfg.env.btDevices.size} 台设备）",
        subtitle = "适配器地址/名称为空 = 保持真实",
    ) {
        cfg.env.btDevices.forEachIndexed { i, bt ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("地址 ${i + 1}", bt.address, { v -> editBt(i) { it.copy(address = v) } }, Modifier.weight(1f))
                StrField("名称", bt.name, { v -> editBt(i) { it.copy(name = v) } }, Modifier.weight(1f))
                IconButton(onClick = { editEnv { e -> e.copy(btDevices = e.btDevices.filterIndexed { idx, _ -> idx != i }) } }) {
                    Icon(Icons.Filled.Close, "删除设备")
                }
            }
        }
        TextButton(onClick = { editEnv { e -> e.copy(btDevices = e.btDevices + com.example.menuui.config.BtDraft()) } }) {
            Icon(Icons.Filled.Add, null); Text(" 添加设备")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrField("适配器地址", cfg.env.btAdapterAddress, { v -> editEnv { it.copy(btAdapterAddress = v) } }, Modifier.weight(1f))
            StrField("适配器名称", cfg.env.btAdapterName, { v -> editEnv { it.copy(btAdapterName = v) } }, Modifier.weight(1f))
        }
    }
}

private fun editBt(index: Int, transform: (com.example.menuui.config.BtDraft) -> com.example.menuui.config.BtDraft) {
    editEnv { e ->
        e.copy(btDevices = e.btDevices.mapIndexed { idx, b -> if (idx == index) transform(b) else b })
    }
}
