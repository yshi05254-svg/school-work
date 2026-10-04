package com.example.menuui.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.menuui.config.ConfigBus

/**
 * 更多页：SIM 编辑器（与位置页共用 SimEditor，编辑的是当前环境的 SIM）
 * + 自动发布开关 + 恢复默认配置。
 */
@Composable
fun MorePage() {
    val notifier = LocalNotifier.current
    val cfg by ConfigBus.state.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "SIM 卡",
            subtitle = "当前环境的 SIM：${simSummary(cfg.sim)}。随位置卡片保存，切换卡片会整体载入",
        ) {
            SimEditor(cfg.sim) { t -> ConfigBus.update { c -> c.copy(sim = t(c.sim)) } }
        }

        SectionCard(title = "发布方式", subtitle = "自动发布：改动后 800ms 自动生效；关闭则手动发布") {
            SwitchRow(
                title = "自动发布",
                checked = cfg.autoPublish,
                onChange = { on -> ConfigBus.update { c -> c.copy(autoPublish = on) } },
            )
        }

        OutlinedButton(onClick = {
            // 自建卡片与发布记录是用户数据，不随"恢复默认"清掉；整体可撤销
            val before = ConfigBus.state.value
            ConfigBus.update {
                com.example.menuui.config.ManagerConfig(
                    customPresets = before.customPresets,
                    history = before.history,
                )
            }
            notifier.show("已恢复默认配置", "撤销") { ConfigBus.update { before } }
        }, modifier = Modifier.fillMaxWidth()) { Text("恢复默认配置（保留自建卡片）") }
    }
}
