package com.example.menuui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import com.example.menuui.config.BtDraft
import com.example.menuui.config.CellDraft
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.EnvDraft
import com.example.menuui.config.ManagerConfig
import com.example.menuui.config.Presets
import com.example.menuui.config.WifiDraft

/**
 * 位置页：预设单选载入 + 当前环境编辑器。
 * 编辑器按"常改 → 少改"分组：坐标与精度默认展开；语言/时区、GPS 抖动、基站、
 * WiFi、蓝牙默认折叠，折叠态标题下显示摘要。预设选中即发布；编辑改动按
 * "更多 → 自动发布"设置生效，或点顶部状态条的"发布"。
 */
@Composable
fun LocationPage() {
    val cfg by ConfigBus.state.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        SectionCard(
            title = "位置预设",
            subtitle = "点击载入（含该地区配套的基站 / WiFi / 蓝牙 / SIM）并立即发布",
        ) {
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
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SectionCard(title = "坐标与精度", subtitle = "当前：${cfg.env.name}") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField(
                    "纬度", cfg.env.lat, { v -> editEnv { it.copy(lat = v) } }, Modifier.weight(1f),
                    validate = rangeCheck(-90.0, 90.0),
                )
                DoubleField(
                    "经度", cfg.env.lon, { v -> editEnv { it.copy(lon = v) } }, Modifier.weight(1f),
                    validate = rangeCheck(-180.0, 180.0),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField("高度", cfg.env.alt, { v -> editEnv { it.copy(alt = v) } }, Modifier.weight(1f), suffix = "m")
                DoubleField(
                    "精度", cfg.env.accuracy.toDouble(),
                    { v -> editEnv { it.copy(accuracy = v.toFloat()) } }, Modifier.weight(1f),
                    suffix = "m", validate = rangeCheck(0.1, 5000.0),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DoubleField(
                    "速度", cfg.env.speed.toDouble(),
                    { v -> editEnv { it.copy(speed = v.toFloat()) } }, Modifier.weight(1f),
                    suffix = "m/s", validate = rangeCheck(0.0, 500.0),
                )
                DoubleField(
                    "朝向", cfg.env.bearing.toDouble(),
                    { v -> editEnv { it.copy(bearing = v.toFloat()) } }, Modifier.weight(1f),
                    suffix = "°", validate = rangeCheck(0.0, 359.99),
                )
            }
        }

        SectionCard(
            title = "语言与时区",
            subtitle = listOf(cfg.env.languageTag, cfg.env.timezoneId).filter { it.isNotBlank() }
                .joinToString(" · ").ifEmpty { "未设置（保持系统真实值）" },
            collapsible = true,
            initiallyExpanded = false,
        ) {
            HintText("国外预设会自动带上；留空则目标应用看到真实语言/时区")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("语言", cfg.env.languageTag, { v -> editEnv { it.copy(languageTag = v.trim()) } }, Modifier.weight(1f), hint = "ja-JP")
                StrField("时区", cfg.env.timezoneId, { v -> editEnv { it.copy(timezoneId = v.trim()) } }, Modifier.weight(1f), hint = "Asia/Tokyo")
            }
        }

        SectionCard(
            title = "GPS 抖动",
            subtitle = if (cfg.jitterEnabled) "开启 · 幅度 ${formatDouble(cfg.jitterAmplitudeMeters)} m" else "关闭（坐标固定不动）",
            collapsible = true,
            initiallyExpanded = false,
        ) {
            SwitchRow(
                title = "启用抖动",
                subtitle = "坐标每秒小幅随机偏移，更接近真实 GPS",
                checked = cfg.jitterEnabled,
                onChange = { on -> ConfigBus.update { c -> c.copy(jitterEnabled = on) } },
            )
            if (cfg.jitterEnabled) {
                DoubleField(
                    "抖动幅度", cfg.jitterAmplitudeMeters,
                    { v -> ConfigBus.update { c -> c.copy(jitterAmplitudeMeters = v) } },
                    suffix = "m", validate = rangeCheck(0.0, 200.0),
                )
            }
        }

        SectionCellList(cfg)
        SectionWifiList(cfg)
        SectionBluetooth(cfg)
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
            else MaterialTheme.colorScheme.surface,
        ),
        border = if (selected) null else CardDefaults.outlinedCardBorder(),
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
        }
    }
}

private fun editEnv(transform: (EnvDraft) -> EnvDraft) {
    ConfigBus.update { c -> c.copy(env = transform(c.env)) }
}

/** 列表项卡片：标题 + 删除按钮 + 内容 */
@Composable
private fun ItemCard(title: String, onDelete: () -> Unit, deleteLabel: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, deleteLabel) }
            }
            content()
        }
    }
}

@Composable
private fun AddButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Add, null)
        Text(" $label")
    }
}

// ---------------------------------------------------------------- 基站

private val RADIO_TYPES = listOf("gsm", "wcdma", "lte", "nr")

private fun radioLabel(t: String) = when (t) {
    "gsm" -> "2G GSM"
    "wcdma" -> "3G WCDMA"
    "lte" -> "4G LTE"
    "nr" -> "5G NR"
    else -> t
}

@Composable
private fun SectionCellList(cfg: ManagerConfig) {
    val cells = cfg.env.cells
    val serving = cells.firstOrNull { it.registered } ?: cells.firstOrNull()
    SectionCard(
        title = "基站",
        subtitle = if (cells.isEmpty()) "未配置（保持真实基站）"
        else "${cells.size} 个 · 驻留 ${serving?.let { "${radioLabel(it.radioType)} ${it.mcc}-${it.mnc}" } ?: "-"}",
        collapsible = true,
        initiallyExpanded = false,
    ) {
        HintText("驻留小区作为当前服务小区；MCC/MNC 保留前导零（如中国移动 MNC=00）")
        cells.forEachIndexed { i, cell ->
            ItemCard(
                title = "基站 ${i + 1}" + if (cell.registered) " · 驻留" else "",
                onDelete = { editEnv { e -> e.copy(cells = e.cells.filterIndexed { idx, _ -> idx != i }) } },
                deleteLabel = "删除基站",
            ) {
                ChoiceChips(
                    options = RADIO_TYPES,
                    selected = cell.radioType,
                    label = ::radioLabel,
                    onSelect = { t -> editCell(i) { it.copy(radioType = t) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DigitsField("MCC", cell.mcc, { v -> editCell(i) { c -> c.copy(mcc = v) } }, 3..3, Modifier.weight(1f))
                    DigitsField("MNC", cell.mnc, { v -> editCell(i) { c -> c.copy(mnc = v) } }, 2..3, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LongField(
                        "小区 ID (CI)", cell.ci, { v -> editCell(i) { c -> c.copy(ci = v) } }, Modifier.weight(1f),
                        validate = { if (it < 0) "不能为负数" else null },
                    )
                    IntField("TAC", cell.tac, { v -> editCell(i) { c -> c.copy(tac = v) } }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("PCI", cell.pci, { v -> editCell(i) { c -> c.copy(pci = v) } }, Modifier.weight(1f))
                    IntField("ARFCN", cell.arfcn, { v -> editCell(i) { c -> c.copy(arfcn = v) } }, Modifier.weight(1f))
                }
                IntField(
                    "信号 (dBm)", cell.signalDbm, { v -> editCell(i) { c -> c.copy(signalDbm = v) } },
                    validate = { if (it !in -140..-20) "范围 -140 ~ -20" else null },
                )
                SwitchRow(
                    title = "驻留小区",
                    subtitle = "当前服务小区（只应有一个）",
                    checked = cell.registered,
                    onChange = { on ->
                        // 驻留互斥：设为驻留时清掉其它小区的驻留标记
                        editEnv { e ->
                            e.copy(cells = e.cells.mapIndexed { idx, c ->
                                when {
                                    idx == i -> c.copy(registered = on)
                                    on -> c.copy(registered = false)
                                    else -> c
                                }
                            })
                        }
                    },
                )
            }
        }
        AddButton("添加基站") {
            editEnv { e -> e.copy(cells = e.cells + CellDraft(registered = e.cells.none { it.registered })) }
        }
    }
}

private fun editCell(index: Int, transform: (CellDraft) -> CellDraft) {
    editEnv { e ->
        e.copy(cells = e.cells.mapIndexed { idx, c -> if (idx == index) transform(c) else c })
    }
}

// ---------------------------------------------------------------- WiFi

private val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

private fun macError(v: String): String? =
    if (v.isNotEmpty() && !MAC_RE.matches(v)) "格式如 02:1a:11:00:00:01" else null

@Composable
private fun SectionWifiList(cfg: ManagerConfig) {
    val wifis = cfg.env.wifis
    SectionCard(
        title = "WiFi",
        subtitle = if (wifis.isEmpty()) "未配置（保持真实 WiFi）"
        else "${wifis.size} 个 · 已连接 ${wifis.first().ssid.ifBlank { "（无名）" }}",
        collapsible = true,
        initiallyExpanded = false,
    ) {
        HintText("第一项视为当前已连接的网络，其余出现在扫描列表中")
        wifis.forEachIndexed { i, wifi ->
            ItemCard(
                title = if (i == 0) "WiFi ${i + 1} · 已连接" else "WiFi ${i + 1}",
                onDelete = { editEnv { e -> e.copy(wifis = e.wifis.filterIndexed { idx, _ -> idx != i }) } },
                deleteLabel = "删除 WiFi",
            ) {
                StrField("名称 (SSID)", wifi.ssid, { v -> editWifi(i) { it.copy(ssid = v) } })
                StrField(
                    "BSSID (MAC)", wifi.bssid, { v -> editWifi(i) { it.copy(bssid = v.trim()) } },
                    error = macError(wifi.bssid),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField(
                        "信号 (dBm)", wifi.signalDbm, { v -> editWifi(i) { it.copy(signalDbm = v) } }, Modifier.weight(1f),
                        validate = { if (it !in -100..-20) "范围 -100 ~ -20" else null },
                    )
                    IntField(
                        "频率 (MHz)", wifi.frequencyMhz, { v -> editWifi(i) { it.copy(frequencyMhz = v) } }, Modifier.weight(1f),
                        validate = { if (it !in 2400..7125) "2.4/5/6 GHz 频段" else null },
                    )
                }
            }
        }
        AddButton("添加 WiFi") { editEnv { e -> e.copy(wifis = e.wifis + WifiDraft()) } }
    }
}

private fun editWifi(index: Int, transform: (WifiDraft) -> WifiDraft) {
    editEnv { e ->
        e.copy(wifis = e.wifis.mapIndexed { idx, w -> if (idx == index) transform(w) else w })
    }
}

// ---------------------------------------------------------------- 蓝牙

@Composable
private fun SectionBluetooth(cfg: ManagerConfig) {
    val devices = cfg.env.btDevices
    SectionCard(
        title = "蓝牙",
        subtitle = if (devices.isEmpty() && cfg.env.btAdapterName.isBlank()) "未配置（保持真实蓝牙）"
        else "${devices.size} 台设备" + cfg.env.btAdapterName.takeIf { it.isNotBlank() }?.let { " · 本机名 $it" }.orEmpty(),
        collapsible = true,
        initiallyExpanded = false,
    ) {
        GroupLabel("本机适配器（留空 = 保持真实）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StrField(
                "地址", cfg.env.btAdapterAddress, { v -> editEnv { it.copy(btAdapterAddress = v.trim()) } }, Modifier.weight(1f),
                error = macError(cfg.env.btAdapterAddress),
            )
            StrField("名称", cfg.env.btAdapterName, { v -> editEnv { it.copy(btAdapterName = v) } }, Modifier.weight(1f))
        }
        GroupLabel("已配对设备")
        devices.forEachIndexed { i, bt ->
            ItemCard(
                title = "设备 ${i + 1}",
                onDelete = { editEnv { e -> e.copy(btDevices = e.btDevices.filterIndexed { idx, _ -> idx != i }) } },
                deleteLabel = "删除设备",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StrField(
                        "地址", bt.address, { v -> editBt(i) { it.copy(address = v.trim()) } }, Modifier.weight(1f),
                        error = macError(bt.address),
                    )
                    StrField("名称", bt.name, { v -> editBt(i) { it.copy(name = v) } }, Modifier.weight(1f))
                }
            }
        }
        AddButton("添加设备") { editEnv { e -> e.copy(btDevices = e.btDevices + BtDraft()) } }
    }
}

private fun editBt(index: Int, transform: (BtDraft) -> BtDraft) {
    editEnv { e ->
        e.copy(btDevices = e.btDevices.mapIndexed { idx, b -> if (idx == index) transform(b) else b })
    }
}
