package com.example.menuui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.menuui.config.SimDraft
import com.example.menuui.config.SimSlotDraft
import kotlin.random.Random

/**
 * SIM 编辑器（位置页"SIM 卡"分组与更多页共用）。状态上提：[onEdit] 收到 transform，
 * 调用方套进 ConfigBus.update，避免拿旧快照整体覆盖并发改动。
 * 运营商一键填充 MCC/MNC/名称/国家；ICCID/IMSI 可按当前 MCC/MNC 生成形态合理的虚构值。
 */
@Composable
fun SimEditor(sim: SimDraft, onEdit: ((SimDraft) -> SimDraft) -> Unit) {
    val notifier = LocalNotifier.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            title = "启用 SIM 模拟",
            subtitle = if (sim.enabled) "按下方卡槽伪造" else "关闭 = 保持真实 SIM",
            checked = sim.enabled,
            onChange = { on ->
                onEdit { s ->
                    // 首次打开且没有卡槽：直接给一张，免得开了却什么都没有
                    if (on && s.slots.isEmpty()) s.copy(enabled = true, slots = listOf(newSlot(0)))
                    else s.copy(enabled = on)
                }
            },
        )
        if (sim.enabled) {
            sim.slots.forEachIndexed { i, slot ->
                SimSlotCard(
                    index = i,
                    slot = slot,
                    onEdit = { t -> onEdit { s -> s.copy(slots = s.slots.mapIndexed { idx, x -> if (idx == i) t(x) else x }) } },
                    onDelete = {
                        onEdit { s -> s.copy(slots = s.slots.filterIndexed { idx, _ -> idx != i }) }
                        notifier.show("已删除卡槽 ${i + 1}", "撤销") {
                            onEdit { s -> s.copy(slots = s.slots.insertAt(i, slot)) }
                        }
                    },
                )
            }
            if (sim.slots.size < MAX_SLOTS) {
                TextButton(onClick = { onEdit { s -> s.copy(slots = s.slots + newSlot(s.slots.size)) } }) {
                    Icon(Icons.Filled.Add, null); Text(" 添加卡槽")
                }
            }
        }
    }
}

/** 一行 SIM 概要（卡片详情 / 新建卡片对话框用） */
fun simSummary(sim: SimDraft): String =
    if (!sim.enabled || sim.slots.isEmpty()) "保持真实"
    else sim.slots.joinToString("；") { s ->
        "${s.carrierName.ifBlank { "未命名" }} ${s.mcc}-${s.mnc}" + if (s.active) "" else "（未激活）"
    }

private const val MAX_SLOTS = 2

private fun newSlot(index: Int): SimSlotDraft {
    val base = SimSlotDraft(subId = index + 1, slotIndex = index)
    return base.withGeneratedIds()
}

/** 常用运营商一键填充；iccidPrefix 用于生成形态一致的 ICCID */
private data class Carrier(
    val name: String, val mcc: String, val mnc: String, val iso: String, val iccidPrefix: String,
)

private val CARRIERS = listOf(
    Carrier("中国移动", "460", "00", "cn", "898600"),
    Carrier("中国联通", "460", "01", "cn", "898601"),
    Carrier("中国电信", "460", "11", "cn", "898611"),
    Carrier("NTT docomo", "440", "10", "jp", "898110"),
    Carrier("T-Mobile", "310", "260", "us", "8901260"),
)

@Composable
private fun SimSlotCard(
    index: Int,
    slot: SimSlotDraft,
    onEdit: ((SimSlotDraft) -> SimSlotDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("卡槽 ${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("激活", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = slot.active,
                    onCheckedChange = { on -> onEdit { it.copy(active = on) } },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "删除卡槽") }
            }

            Text("运营商", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CARRIERS.forEach { c ->
                    FilterChip(
                        selected = slot.mcc == c.mcc && slot.mnc == c.mnc && slot.carrierName == c.name,
                        onClick = {
                            onEdit {
                                it.copy(carrierName = c.name, mcc = c.mcc, mnc = c.mnc, countryIso = c.iso)
                                    .withGeneratedIds()
                            }
                        },
                        label = { Text(c.name) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("运营商名称", slot.carrierName, { v -> onEdit { it.copy(carrierName = v) } }, Modifier.weight(1f))
                StrField("国家 (ISO)", slot.countryIso, { v -> onEdit { it.copy(countryIso = v.lowercase().take(2)) } }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrField("MCC", slot.mcc, { v -> onEdit { it.copy(mcc = v.digits(3)) } }, Modifier.weight(1f))
                // MNC 2~3 位（美国等为 3 位，如 T-Mobile 260）；保留前导零
                StrField("MNC", slot.mnc, { v -> onEdit { it.copy(mnc = v.digits(3)) } }, Modifier.weight(1f))
            }
            StrField("号码", slot.phoneNumber, { v -> onEdit { it.copy(phoneNumber = v.trim()) } }, hint = "+86 138 0000 0000")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "卡识别码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onEdit { it.withGeneratedIds() } }) { Text("按运营商重新生成") }
            }
            StrField("ICCID", slot.iccid, { v -> onEdit { it.copy(iccid = v.digits(20)) } })
            StrField("IMSI", slot.imsi, { v -> onEdit { it.copy(imsi = v.digits(15)) } })
            val plmn = slot.mcc + slot.mnc
            if (slot.imsi.isNotEmpty() && plmn.length >= 5 && !slot.imsi.startsWith(plmn)) {
                Text(
                    "IMSI 应以 MCC+MNC（$plmn）开头，否则与运营商不一致",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            StrField("IMEI 前 14 位", slot.imeiBase, { v -> onEdit { it.copy(imeiBase = v.digits(14)) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField("subId", slot.subId, { v -> onEdit { it.copy(subId = v) } }, Modifier.weight(1f))
                IntField("卡槽序号 (slotIndex)", slot.slotIndex, { v -> onEdit { it.copy(slotIndex = v) } }, Modifier.weight(1f))
            }
        }
    }
}

private fun String.digits(max: Int): String = filter(Char::isDigit).take(max)

/** 按当前 MCC/MNC 生成虚构 IMSI（15 位）与 ICCID（19 位 + Luhn 校验位） */
private fun SimSlotDraft.withGeneratedIds(): SimSlotDraft {
    val plmn = mcc + mnc
    val imsi = plmn + randomDigits(15 - plmn.length)
    val prefix = CARRIERS.firstOrNull { it.mcc == mcc && it.mnc == mnc }?.iccidPrefix ?: "89"
    val body = prefix + randomDigits(19 - prefix.length)
    return copy(imsi = imsi, iccid = body + luhnDigit(body))
}

private fun randomDigits(n: Int): String = buildString { repeat(n.coerceAtLeast(0)) { append(Random.nextInt(10)) } }

private fun luhnDigit(body: String): Int {
    var sum = 0
    body.reversed().forEachIndexed { i, ch ->
        var d = ch - '0'
        if (i % 2 == 0) { d *= 2; if (d > 9) d -= 9 }
        sum += d
    }
    return (10 - sum % 10) % 10
}
