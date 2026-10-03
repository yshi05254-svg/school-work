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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.SimSlotDraft

/**
 * 更多页：路线回放编辑器（航点增删/速度/循环）+ SIM 卡槽编辑器（快照 sim 段全字段）
 * + 自动发布开关 + 恢复默认配置。
 */
@Composable
fun MorePage() {
    val context = LocalContext.current
    val cfg by ConfigBus.state.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "SIM 卡（${cfg.sim.slots.size} 槽）",
            subtitle = "全局 SIM 配置；预设切换会整体载入该组配套的 SIM，mcc/mnc 字符串保留前导零",
        ) {
            SwitchRow(
                title = "启用 SIM 模拟",
                checked = cfg.sim.enabled,
                onChange = { on -> ConfigBus.update { c -> c.copy(sim = c.sim.copy(enabled = on)) } },
            )
            if (cfg.sim.enabled) {
                cfg.sim.slots.forEachIndexed { i, slot ->
                    SimSlotCard(index = i, slot = slot)
                }
                TextButton(onClick = {
                    ConfigBus.update { c ->
                        c.copy(
                            sim = c.sim.copy(
                                slots = c.sim.slots + SimSlotDraft(subId = c.sim.slots.size + 1),
                            ),
                        )
                    }
                }) {
                    Icon(Icons.Filled.Add, null); Text(" 添加卡槽")
                }
            }
        }

        SectionCard(title = "发布方式", subtitle = "自动发布：改动后 800ms 自动生效；关闭则手动发布") {
            SwitchRow(
                title = "自动发布",
                checked = cfg.autoPublish,
                onChange = { on -> ConfigBus.update { c -> c.copy(autoPublish = on) } },
            )
        }

        OutlinedButton(onClick = {
            ConfigBus.update { com.example.menuui.config.ManagerConfig() }
            Toast.makeText(context, "已恢复默认配置（未发布）", Toast.LENGTH_SHORT).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("恢复默认配置") }

        Button(onClick = {
            ConfigBus.publishAsync(record = true) { r ->
                Toast.makeText(
                    context,
                    (if (r.ok) "发布成功（${r.via}）" else "发布失败：") + r.message,
                    if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                ).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("发布配置") }
    }
}

@Composable
private fun SimSlotCard(index: Int, slot: SimSlotDraft) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("卡槽 ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    ConfigBus.update { c ->
                        c.copy(sim = c.sim.copy(slots = c.sim.slots.filterIndexed { idx, _ -> idx != index }))
                    }
                }) { Icon(Icons.Filled.Close, "删除卡槽") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("subId", slot.subId, { v -> editSlot(index) { it.copy(subId = v) } }, Modifier.weight(1f))
                IntField("slotIndex", slot.slotIndex, { v -> editSlot(index) { it.copy(slotIndex = v) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("ICCID", slot.iccid, { v -> editSlot(index) { it.copy(iccid = v) } }, Modifier.weight(1f))
                StrField("IMSI", slot.imsi, { v -> editSlot(index) { it.copy(imsi = v) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("IMEI 前 14 位", slot.imeiBase, { v -> editSlot(index) { it.copy(imeiBase = v) } }, Modifier.weight(1f))
                StrField("号码", slot.phoneNumber, { v -> editSlot(index) { it.copy(phoneNumber = v) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("MCC", slot.mcc, { v -> editSlot(index) { it.copy(mcc = v.take(3)) } }, Modifier.weight(1f))
                StrField("MNC", slot.mnc, { v -> editSlot(index) { it.copy(mnc = v.take(2)) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("运营商", slot.carrierName, { v -> editSlot(index) { it.copy(carrierName = v) } }, Modifier.weight(1f))
                StrField("国家", slot.countryIso, { v -> editSlot(index) { it.copy(countryIso = v) } }, Modifier.weight(1f))
            }
        }
    }
}

private fun editSlot(index: Int, transform: (SimSlotDraft) -> SimSlotDraft) {
    ConfigBus.update { c ->
        c.copy(sim = c.sim.copy(slots = c.sim.slots.mapIndexed { idx, s -> if (idx == index) transform(s) else s }))
    }
}
