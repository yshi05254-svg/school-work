package com.example.menuui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.menuui.config.CellDraft
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.EnvDraft
import com.example.menuui.config.Presets
import com.example.menuui.config.SimDraft
import com.example.menuui.config.WifiDraft
import com.example.menuui.ui.map.MapPickerDialog

/**
 * 位置页，按使用顺序自上而下：
 *  1. 当前位置卡：现在生效的是什么 + 两个主操作（地图选点 / 存为卡片）；
 *  2. 位置卡片：内置预设 + 自建卡片，点击即切换并生效，切错可在提示条里撤销；
 *  3. 高级编辑：坐标、语言时区、抖动、基站、WiFi、蓝牙分组折叠（次要分组默认收起）。
 * 改动随自动发布生效，发布状态见顶栏；删除类操作直接执行并提供撤销，不弹确认框。
 */
@Composable
fun LocationPage() {
    val notifier = LocalNotifier.current
    val cfg by ConfigBus.state.collectAsState()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    val joystick by ConfigBus.joystick.collectAsState()
    // 摇杆运行中禁止选点：服务停止时会把自己积分的位置写回 env，覆盖选点结果
    val joystickActive = joystick?.active == true
    var picking by rememberSaveable { mutableStateOf(false) }

    val allPresets = Presets.all + cfg.customPresets
    val current = allPresets.firstOrNull { isPresetSelected(cfg.env, it) }
    // 当前环境与某张卡片完全一致 = 已保存；否则切换卡片会丢掉这些改动（切换后可撤销）
    val saved = allPresets.any { it.env == cfg.env && it.sim == cfg.sim }
    // 自建卡片被选中后又改了内容：可把改动写回该卡片
    val editedCustom = cfg.customPresets.firstOrNull {
        it.env.name == cfg.env.name && (it.env != cfg.env || it.sim != cfg.sim)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CurrentLocationCard(
            env = cfg.env,
            sim = cfg.sim,
            saved = saved,
            editedCustomLabel = editedCustom?.label,
            joystickActive = joystickActive,
            onPick = { picking = true },
            onSaveNew = { showAddDialog = true },
            onSaveToCustom = {
                val target = editedCustom ?: return@CurrentLocationCard
                ConfigBus.update { c ->
                    c.copy(customPresets = c.customPresets.map { p ->
                        if (p.id == target.id) p.copy(env = c.env, sim = c.sim) else p
                    })
                }
                notifier.show("已保存到卡片「${target.label}」")
            },
        )

        SectionCard(
            title = "位置卡片（${Presets.all.size} 内置 + ${cfg.customPresets.size} 自建）",
            subtitle = "点击切换并立即生效（含该组基站/WiFi/蓝牙/SIM）",
        ) {
            // null = 末尾的"新建卡片"入口
            (allPresets + listOf<Presets.NamedPreset?>(null)).chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { preset ->
                        if (preset == null) {
                            AddPresetCard(Modifier.weight(1f)) { showAddDialog = true }
                        } else {
                            PresetCard(
                                preset = preset,
                                selected = isPresetSelected(cfg.env, preset),
                                modifier = Modifier.weight(1f),
                                onSelect = { selectPreset(preset, notifier) },
                                onDelete = if (preset.id.startsWith(CUSTOM_PREFIX)) {
                                    { deleteCustomPreset(preset, notifier) }
                                } else null,
                            )
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (current != null) {
            SectionCard(
                title = "卡片详情 · ${current.label}",
                subtitle = "该卡片保存的原始内容（只读）",
                collapsible = true,
                initiallyExpanded = false,
            ) { PresetDetail(current) }
        }

        Text(
            "高级编辑（直接修改当前环境）",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )

        SectionCard(title = "坐标与运动", subtitle = "WGS-84；也可用上方\"地图选点\"", collapsible = true) {
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
        }

        SectionCard(
            title = "语言 / 时区",
            subtitle = (cfg.env.languageTag.ifBlank { "跟随系统" }) + " · " + cfg.env.timezoneId.ifBlank { "跟随系统" },
            collapsible = true,
            initiallyExpanded = false,
        ) {
            Text(
                "作为 per-app 建议值写入策略，可在\"应用\"页按应用覆盖",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("语言 (如 ja-JP)", cfg.env.languageTag, { v -> editEnv { it.copy(languageTag = v) } }, Modifier.weight(1f))
                StrField("时区 (如 Asia/Tokyo)", cfg.env.timezoneId, { v -> editEnv { it.copy(timezoneId = v) } }, Modifier.weight(1f))
            }
        }

        SectionCard(
            title = "GPS 抖动",
            subtitle = if (cfg.jitterEnabled) "已开启 · %.0f m".format(cfg.jitterAmplitudeMeters) else "已关闭",
            collapsible = true,
            initiallyExpanded = false,
        ) {
            SwitchRow(
                title = "启用抖动",
                subtitle = "对抗固定坐标检测（全局生效）",
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
    }

    if (showAddDialog) {
        AddPresetDialog(
            envName = cfg.env.name,
            takenNames = allPresets.flatMap { listOf(it.label, it.env.name) }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { label, region ->
                showAddDialog = false
                ConfigBus.update { c ->
                    val env = c.env.copy(name = label)
                    val preset = Presets.NamedPreset(
                        id = CUSTOM_PREFIX + System.currentTimeMillis(),
                        label = label,
                        region = region.ifBlank { "自建" },
                        env = env,
                        sim = c.sim,
                    )
                    c.copy(env = env, customPresets = c.customPresets + preset)
                }
                notifier.show("已新建卡片「$label」")
            },
        )
    }

    if (picking) {
        MapPickerDialog(
            initialLat = cfg.env.lat,
            initialLon = cfg.env.lon,
            onConfirm = { lat, lon ->
                picking = false
                val before = cfg.env
                editEnv { it.copy(name = MAP_PICK_NAME, lat = lat, lon = lon) }
                val stale = before.cells.isNotEmpty() || before.wifis.isNotEmpty() || before.btDevices.isNotEmpty()
                notifier.show(
                    if (stale) "坐标已更新；基站/WiFi/蓝牙仍是原环境的，可在下方调整" else "坐标已更新",
                    actionLabel = "存为卡片",
                ) { showAddDialog = true }
            },
            onDismiss = { picking = false },
        )
    }
}

/** 当前位置：生效中的环境概要 + 主操作 */
@Composable
private fun CurrentLocationCard(
    env: EnvDraft,
    sim: SimDraft,
    saved: Boolean,
    editedCustomLabel: String?,
    joystickActive: Boolean,
    onPick: () -> Unit,
    onSaveNew: () -> Unit,
    onSaveToCustom: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("当前位置", style = MaterialTheme.typography.labelMedium)
            Text(env.name, style = MaterialTheme.typography.titleLarge)
            Text("%.6f, %.6f".format(env.lat, env.lon), style = MaterialTheme.typography.bodyMedium)
            val simText = if (sim.enabled && sim.slots.isNotEmpty()) {
                sim.slots.joinToString("/") { it.carrierName.ifBlank { "${it.mcc}-${it.mnc}" } }
            } else "真实"
            Text(
                "基站 ${env.cells.size} · WiFi ${env.wifis.size} · 蓝牙 ${env.btDevices.size} · SIM $simText",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                joystickActive -> Text(
                    "摇杆运行中：停止摇杆后才能地图选点",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                !saved -> Text(
                    "当前修改还没存为卡片，切换卡片后可撤销找回",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = onPick, enabled = !joystickActive, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp))
                    Text(" 地图选点")
                }
                if (editedCustomLabel != null) {
                    OutlinedButton(onClick = onSaveToCustom, modifier = Modifier.weight(1f)) {
                        Text("保存到「$editedCustomLabel」", maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = onSaveNew, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Text(" 存为卡片")
                    }
                }
            }
        }
    }
}

/** 切换卡片：立即生效，提示条可撤销（恢复切换前的整个环境与 SIM） */
private fun selectPreset(preset: Presets.NamedPreset, notifier: Notifier) {
    val before = ConfigBus.state.value
    if (before.env == preset.env && before.sim == preset.sim) return
    ConfigBus.update { c -> c.copy(env = preset.env, sim = preset.sim) }
    ConfigBus.publishAsync(record = false)
    notifier.show("已切换到「${preset.label}」", "撤销") {
        ConfigBus.update { c -> c.copy(env = before.env, sim = before.sim) }
        ConfigBus.publishAsync(record = false)
    }
}

private fun deleteCustomPreset(preset: Presets.NamedPreset, notifier: Notifier) {
    val index = ConfigBus.state.value.customPresets.indexOfFirst { it.id == preset.id }
    if (index < 0) return
    ConfigBus.update { c -> c.copy(customPresets = c.customPresets.filter { it.id != preset.id }) }
    notifier.show("已删除卡片「${preset.label}」（当前环境不受影响）", "撤销") {
        ConfigBus.update { c -> c.copy(customPresets = c.customPresets.insertAt(index, preset)) }
    }
}

internal fun <T> List<T>.insertAt(index: Int, item: T): List<T> =
    toMutableList().apply { add(index.coerceIn(0, size), item) }

@Composable
private fun PresetCard(
    preset: Presets.NamedPreset,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    /** 非空 = 自建卡片，右上角显示删除 */
    onDelete: (() -> Unit)? = null,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preset.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Close, "删除卡片", Modifier.size(16.dp))
                    }
                }
            }
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

private const val CUSTOM_PREFIX = "custom_"
private const val MAP_PICK_NAME = "地图选点"

@Composable
private fun AddPresetCard(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
            Text("新建卡片", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text("保存当前环境", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 新建卡片：以当前环境（坐标/语言/时区/基站/WiFi/蓝牙/SIM）为内容，起名后保存 */
@Composable
private fun AddPresetDialog(
    envName: String,
    takenNames: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (label: String, region: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    val trimmed = label.trim()
    val error = when {
        trimmed.isEmpty() -> null
        trimmed in takenNames -> "名称已存在"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建位置卡片") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "将当前环境（$envName）的坐标、语言、时区、基站、WiFi、蓝牙与 SIM 保存为新卡片。" +
                        "可先在下方编辑器改好再新建。",
                    style = MaterialTheme.typography.bodySmall,
                )
                StrField("卡片名称", label, { label = it })
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                StrField("备注（如 华东 · 电信）", region, { region = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed, region.trim()) },
                enabled = trimmed.isNotEmpty() && error == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun isPresetSelected(env: EnvDraft, preset: Presets.NamedPreset): Boolean =
    env.name == preset.env.name && env.lat == preset.env.lat && env.lon == preset.env.lon

/** 选中预设的完整内容：坐标 / 语言 / 时区 / 基站 / WiFi / 蓝牙 / SIM（只读，展示预设原值） */
@Composable
private fun PresetDetail(preset: Presets.NamedPreset) {
    val env = preset.env
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${preset.label} · ${preset.region}", style = MaterialTheme.typography.titleSmall)

            DetailGroup("坐标") {
                DetailLine("%.4f, %.4f".format(env.lat, env.lon))
                DetailLine("高度 %.1f m · 精度 %.0f m".format(env.alt, env.accuracy))
            }

            DetailGroup("语言 / 时区") {
                DetailLine("语言：" + env.languageTag.ifBlank { "跟随系统" })
                DetailLine("时区：" + env.timezoneId.ifBlank { "跟随系统" })
            }

            DetailGroup("基站（${env.cells.size}）") {
                env.cells.forEach { c ->
                    DetailLine(
                        "${c.radioType.uppercase()} ${c.mcc}-${c.mnc}" +
                            (if (c.registered) "（驻留）" else "（邻区）") +
                            "\nCI ${c.ci} · TAC ${c.tac} · PCI ${c.pci} · ARFCN ${c.arfcn} · ${c.signalDbm} dBm",
                    )
                }
            }

            DetailGroup("WiFi（${env.wifis.size}）") {
                env.wifis.forEachIndexed { i, w ->
                    DetailLine(
                        w.ssid + (if (i == 0) "（已连接）" else "") +
                            "\n${w.bssid} · ${w.frequencyMhz} MHz · ${w.signalDbm} dBm",
                    )
                }
            }

            DetailGroup("蓝牙（${env.btDevices.size} 台设备）") {
                DetailLine(
                    "本机：" + env.btAdapterName.ifBlank { "保持真实" } +
                        (if (env.btAdapterAddress.isNotBlank()) "（${env.btAdapterAddress}）" else ""),
                )
                env.btDevices.forEach { b ->
                    DetailLine("${b.name}\n${b.address} · ${b.rssi} dBm")
                }
            }

            // SIM 是全局段（不在 EnvDraft 里），随卡片整体载入；编辑入口在"更多"页
            val sim = preset.sim
            val simOn = sim.enabled && sim.slots.isNotEmpty()
            DetailGroup(if (simOn) "SIM（${sim.slots.size} 张卡）" else "SIM") {
                if (!simOn) {
                    DetailLine("保持真实")
                } else {
                    sim.slots.forEach { s ->
                        DetailLine(
                            "卡槽 ${s.slotIndex + 1} · ${s.carrierName.ifBlank { "未命名运营商" }}" +
                                " ${s.mcc}-${s.mnc} · ${s.countryIso.uppercase()}" +
                                (if (s.active) "" else "（未激活）") +
                                "\n号码 ${s.phoneNumber.ifBlank { "—" }} · ICCID ${s.iccid.ifBlank { "—" }}" +
                                "\nIMSI ${s.imsi.ifBlank { "—" }}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun editEnv(transform: (EnvDraft) -> EnvDraft) {
    ConfigBus.update { c -> c.copy(env = transform(c.env)) }
}

// ---------------------------------------------------------------- 基站

@Composable
private fun SectionCellList(cfg: com.example.menuui.config.ManagerConfig) {
    val notifier = LocalNotifier.current
    SectionCard(
        title = "基站（${cfg.env.cells.size}）",
        subtitle = "PLMN 保留前导零（移动 mnc=00）；radioType 决定其余字段的语义",
        collapsible = true,
        initiallyExpanded = false,
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
                            notifier.show("已删除基站 ${i + 1}", "撤销") {
                                editEnv { e -> e.copy(cells = e.cells.insertAt(i, cell)) }
                            }
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
    val notifier = LocalNotifier.current
    SectionCard(
        title = "WiFi（${cfg.env.wifis.size}）",
        subtitle = "首项视为当前连接的网络",
        collapsible = true,
        initiallyExpanded = false,
    ) {
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
                            notifier.show("已删除 WiFi「${wifi.ssid}」", "撤销") {
                                editEnv { e -> e.copy(wifis = e.wifis.insertAt(i, wifi)) }
                            }
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
    val notifier = LocalNotifier.current
    SectionCard(
        title = "蓝牙（${cfg.env.btDevices.size} 台设备）",
        subtitle = "适配器地址/名称为空 = 保持真实",
        collapsible = true,
        initiallyExpanded = false,
    ) {
        cfg.env.btDevices.forEachIndexed { i, bt ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("地址 ${i + 1}", bt.address, { v -> editBt(i) { it.copy(address = v) } }, Modifier.weight(1f))
                StrField("名称", bt.name, { v -> editBt(i) { it.copy(name = v) } }, Modifier.weight(1f))
                IconButton(onClick = {
                    editEnv { e -> e.copy(btDevices = e.btDevices.filterIndexed { idx, _ -> idx != i }) }
                    notifier.show("已删除蓝牙设备「${bt.name}」", "撤销") {
                        editEnv { e -> e.copy(btDevices = e.btDevices.insertAt(i, bt)) }
                    }
                }) {
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
