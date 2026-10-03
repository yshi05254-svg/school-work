package com.example.menuui.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.menuui.config.AppPolicy
import com.example.menuui.config.ConfigBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用页：列表 → 详情 / 选择器 三个视图（页内状态切换，系统返回键逐级返回）。
 *  - 列表：显示应用名 + 包名，卡片上直接开关"生效"，点卡片进详情；
 *  - 选择器：从已安装应用（桌面可启动的应用）中搜索添加，也可手动输入包名；
 *  - 详情：生效 / 严格模式 / 删除（删除需确认，不再在列表里误触即删）。
 * enabled=false 的应用不发策略（模块端全域透传真实值）。
 */
@Composable
fun AppsPage() {
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val cfg by ConfigBus.state.collectAsState()
    if (editing >= cfg.apps.size) editing = -1 // 列表收缩（删除）时安全回退

    when {
        picking -> {
            BackHandler { picking = false }
            AppPickerPage(
                existing = cfg.apps.map { it.pkg }.toSet(),
                onPick = { pkg ->
                    ConfigBus.update { c -> c.copy(apps = c.apps + AppPolicy(pkg = pkg)) }
                    picking = false
                },
                onManual = {
                    ConfigBus.update { c -> c.copy(apps = c.apps + AppPolicy(pkg = "")) }
                    picking = false
                    editing = cfg.apps.size
                },
                onBack = { picking = false },
            )
        }
        editing >= 0 -> {
            BackHandler { editing = -1 }
            AppDetailPage(index = editing, onBack = { editing = -1 })
        }
        else -> AppListPage(onOpen = { editing = it }, onAdd = { picking = true })
    }
}

/** 包名 → 应用名（未安装 / 不可见返回 null） */
@Composable
private fun rememberAppLabel(pkg: String): String? {
    val pm = LocalContext.current.packageManager
    return remember(pkg) {
        if (pkg.isBlank()) null
        else runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
    }
}

@Composable
private fun AppListPage(onOpen: (Int) -> Unit, onAdd: () -> Unit) {
    val cfg by ConfigBus.state.collectAsState()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        SectionCard(
            title = "目标应用",
            subtitle = "${cfg.apps.count { it.enabled && it.pkg.isNotBlank() }} 个生效 / 共 ${cfg.apps.size} 个 · 点卡片进入设置",
        ) {
            if (cfg.apps.isEmpty()) {
                HintText("还没有目标应用。虚拟环境只作用于这里添加并启用的应用。")
            }
            cfg.apps.forEachIndexed { i, app ->
                AppCard(
                    app = app,
                    onOpen = { onOpen(i) },
                    onToggle = { on -> editApp(i) { it.copy(enabled = on) } },
                )
            }
            FilledTonalButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null)
                Text(" 添加应用")
            }
            HintText("应用还需在 LSPosed 中勾选到本模块的作用域，否则配置了也不生效。")
        }

        ExclusionSection()
    }
}

@Composable
private fun AppCard(app: AppPolicy, onOpen: () -> Unit, onToggle: (Boolean) -> Unit) {
    val label = rememberAppLabel(app.pkg)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        app.pkg.isBlank() -> "（未填写包名）"
                        label != null -> label
                        else -> app.pkg
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (app.pkg.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                val meta = buildList {
                    if (label != null) add(app.pkg)
                    if (app.pkg.isNotBlank() && label == null) add("未安装")
                    if (app.strictMode) add("严格模式")
                }
                if (meta.isNotEmpty()) HintText(meta.joinToString(" · "))
            }
            Switch(checked = app.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 排除名单（高级）：默认折叠 */
@Composable
private fun ExclusionSection() {
    val cfg by ConfigBus.state.collectAsState()
    SectionCard(
        title = "排除名单",
        subtitle = if (cfg.excludedPackages.isEmpty() && cfg.excludedUids.isEmpty()) "无（高级）"
        else "${cfg.excludedPackages.size} 个包名 · ${cfg.excludedUids.size} 个 uid",
        collapsible = true,
        initiallyExpanded = false,
    ) {
        HintText("命中的包名 / uid 始终读取真实环境，优先级高于上方的目标应用")
        GroupLabel("包名")
        cfg.excludedPackages.forEachIndexed { i, pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StrField(
                    "包名 ${i + 1}", pkg, { v ->
                        ConfigBus.update { c ->
                            c.copy(excludedPackages = c.excludedPackages.mapIndexed { idx, old -> if (idx == i) v.trim() else old })
                        }
                    }, Modifier.weight(1f),
                    error = pkgError(pkg),
                )
                IconButton(onClick = {
                    ConfigBus.update { c ->
                        c.copy(excludedPackages = c.excludedPackages.filterIndexed { idx, _ -> idx != i })
                    }
                }) { Icon(Icons.Filled.Close, "删除") }
            }
        }
        TextButton(onClick = { ConfigBus.update { c -> c.copy(excludedPackages = c.excludedPackages + "") } }) {
            Icon(Icons.Filled.Add, null); Text(" 添加包名")
        }
        GroupLabel("uid")
        cfg.excludedUids.forEachIndexed { i, uid ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                IntField(
                    "uid ${i + 1}", uid, { v ->
                        ConfigBus.update { c ->
                            c.copy(excludedUids = c.excludedUids.mapIndexed { idx, old -> if (idx == i) v else old })
                        }
                    }, Modifier.weight(1f),
                    validate = { if (it < 0) "不能为负数" else null },
                )
                IconButton(onClick = {
                    ConfigBus.update { c ->
                        c.copy(excludedUids = c.excludedUids.filterIndexed { idx, _ -> idx != i })
                    }
                }) { Icon(Icons.Filled.Close, "删除") }
            }
        }
        TextButton(onClick = { ConfigBus.update { c -> c.copy(excludedUids = c.excludedUids + 10000) } }) {
            Icon(Icons.Filled.Add, null); Text(" 添加 uid")
        }
    }
}

private val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

private fun pkgError(pkg: String): String? = when {
    pkg.isBlank() -> "请填写包名"
    !PKG_RE.matches(pkg) -> "格式如 com.tencent.mm"
    else -> null
}

// ---------------------------------------------------------------- 选择器

private class InstalledApp(val pkg: String, val label: String)

@Composable
private fun AppPickerPage(
    existing: Set<String>,
    onPick: (String) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    // 桌面可启动的应用（manifest 已声明对应 <queries>，无需 QUERY_ALL_PACKAGES）
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                    .asSequence()
                    .map { it.activityInfo.packageName }
                    .filter { it != context.packageName }
                    .distinct()
                    .map { pkg ->
                        InstalledApp(
                            pkg,
                            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                                .getOrDefault(pkg),
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = PagePadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Text("选择应用", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onManual) { Text("手动输入") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("搜索应用名或包名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        val list = apps
        if (list == null) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        } else {
            val q = query.trim().lowercase()
            val shown = if (q.isEmpty()) list
            else list.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            if (shown.isEmpty()) {
                HintText("没有匹配的应用，可点右上角\"手动输入\"包名", Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.pkg }) { app ->
                    val added = app.pkg in existing
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !added) { onPick(app.pkg) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            HintText(app.pkg)
                        }
                        if (added) {
                            Icon(Icons.Filled.Check, "已添加", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 详情

@Composable
private fun AppDetailPage(index: Int, onBack: () -> Unit) {
    val cfg by ConfigBus.state.collectAsState()
    val app = cfg.apps.getOrNull(index) ?: run { onBack(); return }
    val label = rememberAppLabel(app.pkg)
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Text(label ?: "应用设置", style = MaterialTheme.typography.titleLarge)
        }

        SectionCard(title = "包名", subtitle = "与 LSPosed 作用域中勾选的包一致才生效") {
            StrField(
                "包名", app.pkg, { v -> editApp(index) { it.copy(pkg = v.trim()) } },
                hint = "com.tencent.mm",
                error = pkgError(app.pkg),
            )
            if (app.pkg.isNotBlank() && pkgError(app.pkg) == null && label == null) {
                HintText("本机未找到该应用（未安装或不可见），配置仍会下发")
            }
        }

        SectionCard(title = "设置") {
            SwitchRow(
                title = "对该应用生效",
                subtitle = "关闭后该应用读取真实位置与设备信息",
                checked = app.enabled,
                onChange = { on -> editApp(index) { it.copy(enabled = on) } },
            )
            SwitchRow(
                title = "严格模式",
                subtitle = "无可用虚拟环境时定位直接失败，而不是回落真实位置",
                checked = app.strictMode,
                onChange = { on -> editApp(index) { it.copy(strictMode = on) } },
            )
        }

        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("移除该应用", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "移除应用",
            text = "移除 ${label ?: app.pkg.ifBlank { "该应用" }} 后，它将读取真实位置与设备信息。",
            confirmLabel = "移除",
            onConfirm = {
                ConfigBus.update { c -> c.copy(apps = c.apps.filterIndexed { idx, _ -> idx != index }) }
                onBack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun editApp(index: Int, transform: (AppPolicy) -> AppPolicy) {
    ConfigBus.update { c ->
        c.copy(apps = c.apps.mapIndexed { idx, a -> if (idx == index) transform(a) else a })
    }
}
