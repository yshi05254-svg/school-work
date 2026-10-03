package com.example.menuui.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.ManagerConfig
import com.example.menuui.config.SimSlotDraft

/**
 * 更多页：SIM 卡槽编辑器（快照 sim 段全字段）+ 发布方式 + 恢复默认配置（需确认）。
 */
@Composable
fun MorePage() {
    val cfg by ConfigBus.state.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        SectionCard(
            title = "SIM 卡",
            subtitle = if (!cfg.sim.enabled) "关闭（目标应用读取真实 SIM）"
            else "${cfg.sim.slots.count { it.active }} 张启用 / ${cfg.sim.slots.size} 个卡槽 · 切换位置预设会一并替换",
        ) {
            SwitchRow(
                title = "模拟 SIM 卡",
                subtitle = "运营商、号码、IMSI、ICCID、IMEI 等",
                checked = cfg.sim.enabled,
                onChange = { on -> ConfigBus.update { c -> c.copy(sim = c.sim.copy(enabled = on)) } },
            )
            if (cfg.sim.enabled) {
                cfg.sim.slots.forEachIndexed { i, slot ->
                    SimSlotCard(index = i, slot = slot)
                }
                TextButton(onClick = {
                    ConfigBus.update { c ->
                        val nextSlot = (c.sim.slots.maxOfOrNull { it.slotIndex } ?: -1) + 1
                        val nextSub = (c.sim.slots.maxOfOrNull { it.subId } ?: 0) + 1
                        c.copy(sim = c.sim.copy(slots = c.sim.slots + SimSlotDraft(subId = nextSub, slotIndex = nextSlot)))
                    }
                }) {
                    Icon(Icons.Filled.Add, null); Text(" 添加卡槽")
                }
            }
        }

        SectionCard(title = "发布") {
            SwitchRow(
                title = "自动发布",
                subtitle = if (cfg.autoPublish) "改动后约 1 秒自动同步到模块"
                else "改动后需点顶部\"发布\"才会生效",
                checked = cfg.autoPublish,
                onChange = { on -> ConfigBus.update { c -> c.copy(autoPublish = on) } },
            )
        }

        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("恢复默认配置", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "恢复默认配置",
            text = "位置、应用、SIM、排除名单等全部设置将恢复为默认值，此操作不可撤销。",
            confirmLabel = "恢复默认",
            onConfirm = { ConfigBus.update { ManagerConfig() } },
            onDismiss = { confirmReset = false },
        )
    }
}

private fun digitsError(v: String, lengths: IntRange, name: String): String? = when {
    v.isEmpty() -> null // 留空 = 不伪造该字段
    v.any { !it.isDigit() } -> "$name 只能是数字"
    v.length !in lengths -> if (lengths.first == lengths.last) "$name 应为 ${lengths.first} 位"
    else "$name 应为 ${lengths.first}~${lengths.last} 位"
    else -> null
}

@Composable
private fun SimSlotCard(index: Int, slot: SimSlotDraft) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "卡槽 ${slot.slotIndex + 1}" + if (slot.carrierName.isNotBlank()) " · ${slot.carrierName}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    ConfigBus.update { c ->
                        c.copy(sim = c.sim.copy(slots = c.sim.slots.filterIndexed { idx, _ -> idx != index }))
                    }
                }) { Icon(Icons.Filled.Close, "删除卡槽") }
            }
            SwitchRow(
                title = "插入此卡",
                subtitle = "关闭 = 该卡槽显示为无卡",
                checked = slot.active,
                onChange = { on -> editSlot(index) { it.copy(active = on) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("运营商", slot.carrierName, { v -> editSlot(index) { it.copy(carrierName = v) } }, Modifier.weight(1f))
                StrField("国家代码", slot.countryIso, { v -> editSlot(index) { it.copy(countryIso = v.trim().lowercase()) } }, Modifier.weight(1f), hint = "cn")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DigitsField("MCC", slot.mcc, { v -> editSlot(index) { it.copy(mcc = v) } }, 3..3, Modifier.weight(1f))
                DigitsField("MNC", slot.mnc, { v -> editSlot(index) { it.copy(mnc = v) } }, 2..3, Modifier.weight(1f))
            }
            StrField("手机号", slot.phoneNumber, { v -> editSlot(index) { it.copy(phoneNumber = v.trim()) } })
            StrField(
                "IMSI", slot.imsi, { v -> editSlot(index) { it.copy(imsi = v.trim()) } },
                error = digitsError(slot.imsi, 6..15, "IMSI"),
            )
            StrField(
                "ICCID", slot.iccid, { v -> editSlot(index) { it.copy(iccid = v.trim()) } },
                error = digitsError(slot.iccid, 18..20, "ICCID"),
            )
            StrField(
                "IMEI 前 14 位", slot.imeiBase, { v -> editSlot(index) { it.copy(imeiBase = v.trim()) } },
                hint = "留空自动生成",
                error = digitsError(slot.imeiBase, 14..15, "IMEI"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField(
                    "subId", slot.subId, { v -> editSlot(index) { it.copy(subId = v) } }, Modifier.weight(1f),
                    validate = { if (it < 1) "须 ≥ 1" else null },
                )
                IntField(
                    "卡槽序号", slot.slotIndex, { v -> editSlot(index) { it.copy(slotIndex = v) } }, Modifier.weight(1f),
                    validate = { if (it < 0) "须 ≥ 0" else null },
                )
            }
        }
    }
}

private fun editSlot(index: Int, transform: (SimSlotDraft) -> SimSlotDraft) {
    ConfigBus.update { c ->
        c.copy(sim = c.sim.copy(slots = c.sim.slots.mapIndexed { idx, s -> if (idx == index) transform(s) else s }))
    }
}
