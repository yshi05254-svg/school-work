package com.example.menuui.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.menuui.config.AppPolicy
import com.example.menuui.config.ConfigBus

/**
 * 应用页：应用以卡片列表呈现，点进卡片单独设置该应用——
 * 仅两项目：① 是否对该应用生效 ② 是否启用严格模式。
 * enabled=false 的应用不发策略（模块端全域透传真实值）。
 */
@Composable
fun AppsPage() {
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    val cfg by ConfigBus.state.collectAsState()
    if (editing >= cfg.apps.size) editing = -1 // 列表收缩（删除）时安全回退
    if (editing >= 0) {
        AppDetailPage(
            index = editing,
            onBack = { editing = -1 },
        )
    } else {
        AppListPage(onOpen = { editing = it })
    }
}

@Composable
private fun AppListPage(onOpen: (Int) -> Unit) {
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
            title = "目标应用（${cfg.apps.count { it.enabled }} 生效 / 共 ${cfg.apps.size}）",
            subtitle = "点击卡片单独设置：是否生效、严格模式",
        ) {
            cfg.apps.forEachIndexed { i, app ->
                AppCard(
                    app = app,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable { onOpen(i) },
                    onDelete = {
                        ConfigBus.update { c -> c.copy(apps = c.apps.filterIndexed { idx, _ -> idx != i }) }
                        notifier.show("已移除 ${app.pkg.ifBlank { "应用" }}", "撤销") {
                            ConfigBus.update { c -> c.copy(apps = c.apps.insertAt(i, app)) }
                        }
                    },
                )
            }
            TextButton(onClick = {
                ConfigBus.update { c -> c.copy(apps = c.apps + AppPolicy(pkg = "")) }
                onOpen(cfg.apps.size)
            }) {
                Icon(Icons.Filled.Add, null); Text(" 添加应用")
            }
            Text(
                "提示：LSPosed 作用域内未勾选的应用即使配置了策略也不生效（加载范围由 LSPosed Manager 管理）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "排除名单", subtitle = "命中的包/uid 完全透传真实环境") {
            Text("排除包名（${cfg.excludedPackages.size}）", style = MaterialTheme.typography.labelLarge)
            cfg.excludedPackages.forEachIndexed { i, pkg ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StrField("包名 ${i + 1}", pkg, { v ->
                        ConfigBus.update { c ->
                            c.copy(excludedPackages = c.excludedPackages.mapIndexed { idx, old -> if (idx == i) v else old })
                        }
                    }, Modifier.weight(1f))
                    IconButton(onClick = {
                        ConfigBus.update { c ->
                            c.copy(excludedPackages = c.excludedPackages.filterIndexed { idx, _ -> idx != i })
                        }
                    }) { Icon(Icons.Filled.Close, "删除") }
                }
            }
            TextButton(onClick = {
                ConfigBus.update { c -> c.copy(excludedPackages = c.excludedPackages + "") }
            }) {
                Icon(Icons.Filled.Add, null); Text(" 添加排除包名")
            }
            Text("排除 uid（${cfg.excludedUids.size}）", style = MaterialTheme.typography.labelLarge)
            cfg.excludedUids.forEachIndexed { i, uid ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntField("uid ${i + 1}", uid, { v ->
                        ConfigBus.update { c ->
                            c.copy(excludedUids = c.excludedUids.mapIndexed { idx, old -> if (idx == i) v else old })
                        }
                    }, Modifier.weight(1f))
                    IconButton(onClick = {
                        ConfigBus.update { c ->
                            c.copy(excludedUids = c.excludedUids.filterIndexed { idx, _ -> idx != i })
                        }
                    }) { Icon(Icons.Filled.Close, "删除") }
                }
            }
            TextButton(onClick = {
                ConfigBus.update { c -> c.copy(excludedUids = c.excludedUids + 10000) }
            }) {
                Icon(Icons.Filled.Add, null); Text(" 添加排除 uid")
            }
        }
    }
}

@Composable
private fun AppCard(app: AppPolicy, modifier: Modifier = Modifier, onDelete: () -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    app.pkg.ifBlank { "（未填写包名）" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(if (app.enabled) "生效" else "已停用") },
                    )
                    if (app.strictMode) {
                        AssistChip(onClick = {}, label = { Text("严格模式") })
                    }
                }
            }
            Icon(Icons.Filled.KeyboardArrowRight, "进入设置")
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "删除应用") }
        }
    }
}

/** 单应用详情：仅"是否生效"与"严格模式"两项目 */
@Composable
private fun AppDetailPage(index: Int, onBack: () -> Unit) {
    val cfg by ConfigBus.state.collectAsState()
    val app = cfg.apps.getOrNull(index) ?: run { onBack(); return }
    BackHandler(onBack = onBack) // 系统返回手势/键回到列表，而不是退出应用

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Text("应用设置", style = MaterialTheme.typography.titleLarge)
        }

        SectionCard(title = "包名", subtitle = "与 LSPosed 作用域中勾选的包一致才生效") {
            StrField("包名", app.pkg, { v ->
                editApp(index) { it.copy(pkg = v.trim()) }
            }, hint = "com.tencent.mm")
        }

        SectionCard(title = "该应用的设置") {
            SwitchRow(
                title = "对该应用生效",
                subtitle = "开启 = 该应用使用当前位置环境；关闭 = 不发策略，所有域透传真实值",
                checked = app.enabled,
                onChange = { on -> editApp(index) { it.copy(enabled = on) } },
            )
            SwitchRow(
                title = "启用严格模式",
                subtitle = "开启后该应用在无可用环境时定位直接失败（默认回落真实值）",
                checked = app.strictMode,
                onChange = { on -> editApp(index) { it.copy(strictMode = on) } },
            )
        }

        OutlinedButton(
            onClick = {
                ConfigBus.update { c -> c.copy(apps = c.apps.filterIndexed { idx, _ -> idx != index }) }
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("删除该应用") }

        Text(
            "设置改动后自动发布（更多页可关闭自动发布）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun editApp(index: Int, transform: (AppPolicy) -> AppPolicy) {
    ConfigBus.update { c ->
        c.copy(apps = c.apps.mapIndexed { idx, a -> if (idx == index) transform(a) else a })
    }
}
