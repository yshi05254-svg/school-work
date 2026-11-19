package com.example.menuui.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.menuui.config.AppPolicy
import com.example.menuui.config.ConfigBus
import com.example.menuui.config.InstalledApps
import com.example.menuui.config.ScopeSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

/**
 * 应用页：紧凑列表（图标 + 应用名 + 包名 + 生效开关），点行进入单应用设置。
 * 目标应用随 LSPosed 作用域自动同步（ConfigBus.syncScopeAsync，系统应用不入列），
 * 数量多时靠搜索 / 生效筛选定位；enabled=false 的应用不发策略（模块端全域透传真实值）。
 */
@Composable
fun AppsPage() {
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    val cfg by ConfigBus.state.collectAsState()
    if (editing >= cfg.apps.size) editing = -1 // 列表收缩（删除）时安全回退
    if (editing >= 0) {
        AppDetailPage(
            index = editing,
            onBack = {
                // 手动添加后没填包名就返回：丢弃空条目，避免列表里残留"未填写包名"
                if (cfg.apps.getOrNull(editing)?.pkg?.isBlank() == true) {
                    val i = editing
                    ConfigBus.update { c -> c.copy(apps = c.apps.filterIndexed { idx, _ -> idx != i }) }
                }
                editing = -1
            },
            onDeleted = { editing = -1 },
        )
    } else {
        AppListPage(onOpen = { editing = it })
    }
}

private enum class AppFilter(val label: String) { ALL("全部"), ON("生效"), OFF("已停用") }

/** 按包名批量取应用信息（后台线程；已缓存的立即可用） */
@Composable
private fun rememberAppInfos(pkgs: List<String>): Map<String, InstalledApps.Info> {
    val ctx = LocalContext.current.applicationContext
    val infos by produceState(
        initialValue = pkgs.mapNotNull { p -> InstalledApps.cached(p)?.let { p to it } }.toMap(),
        key1 = pkgs,
    ) {
        value = withContext(Dispatchers.IO) { pkgs.associateWith { InstalledApps.load(ctx, it) } }
    }
    return infos
}

@Composable
private fun AppListPage(onOpen: (Int) -> Unit) {
    val cfg by ConfigBus.state.collectAsState()
    val syncStatus by ConfigBus.scopeSync.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }

    LaunchedEffect(Unit) {
        ConfigBus.syncScopeAsync()
    }

    val infos = rememberAppInfos(cfg.apps.map { it.pkg }.filter { it.isNotBlank() })
    val collator = remember { Collator.getInstance(Locale.CHINA) }
    val q = query.trim()
    val rows = cfg.apps.withIndex()
        .filter { (_, app) ->
            when (filter) {
                AppFilter.ALL -> true
                AppFilter.ON -> app.enabled
                AppFilter.OFF -> !app.enabled
            }
        }
        .filter { (_, app) ->
            q.isEmpty() || app.pkg.contains(q, ignoreCase = true) ||
                infos[app.pkg]?.label?.contains(q, ignoreCase = true) == true
        }
        .sortedWith(compareBy(collator) { (_, app) -> infos[app.pkg]?.label ?: app.pkg })
    val rowKeys = remember(rows) {
        val seen = HashSet<String>(rows.size)
        rows.map { (idx, app) ->
            val base = app.pkg.ifEmpty { "empty_$idx" }
            if (seen.add(base)) base else "$base#$idx"
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
    ) {
        item {
            AppListHeader(
                enabled = cfg.apps.count { it.enabled },
                total = cfg.apps.size,
                query = query,
                onQuery = { query = it },
                filter = filter,
                onFilter = { filter = it },
                syncStatus = syncStatus,
                onSync = { ConfigBus.syncScopeAsync(forceReconcile = true) },
            )
            Spacer(Modifier.height(12.dp))
        }

        if (rows.isEmpty()) {
            item {
                Text(
                    if (cfg.apps.isEmpty()) "还没有目标应用：在 Vector / LSPosed 作用域勾选应用后回到这里即自动加入"
                    else "没有匹配的应用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
        }

        itemsIndexed(rows, key = { pos, _ -> rowKeys[pos] }) { pos, (index, app) ->
            AppRow(
                app = app,
                info = infos[app.pkg],
                shape = groupShape(pos, rows.size),
                showDivider = pos < rows.lastIndex,
                onClick = { onOpen(index) },
                onToggle = { on -> editApp(index) { it.copy(enabled = on) } },
            )
        }

        item {
            TextButton(onClick = {
                ConfigBus.update { c -> c.copy(apps = c.apps + AppPolicy(pkg = "")) }
                onOpen(cfg.apps.size)
            }) {
                Icon(Icons.Filled.Add, null); Text(" 手动添加包名")
            }
            Spacer(Modifier.height(4.dp))
            ExclusionSection()
        }
    }
}

/** 分组列表的圆角：首行上圆角、末行下圆角，中间直角，整体读作一张卡片 */
private fun groupShape(pos: Int, count: Int): RoundedCornerShape {
    val r = 16.dp
    val top = if (pos == 0) r else 0.dp
    val bottom = if (pos == count - 1) r else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun AppListHeader(
    enabled: Int,
    total: Int,
    query: String,
    onQuery: (String) -> Unit,
    filter: AppFilter,
    onFilter: (AppFilter) -> Unit,
    syncStatus: ScopeSyncStatus,
    onSync: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("目标应用", style = MaterialTheme.typography.titleLarge)
            Text(
                "  $enabled 生效 / 共 $total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onSync, enabled = !syncStatus.syncing) {
                if (syncStatus.syncing) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(" 同步中")
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" 同步")
                }
            }
        }
        val statusText = when {
            syncStatus.syncing -> "正在从 Vector 同步作用域…"
            syncStatus.message.isNotBlank() -> syncStatus.message
            else -> "在 Vector / LSPosed 作用域勾选或取消第三方应用，回到本应用即自动加入 / 移除"
        }
        Text(
            statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (!syncStatus.syncing && syncStatus.lastOk == false) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("搜索应用名或包名") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, "清空") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceChips(
            options = AppFilter.entries,
            selected = filter,
            label = { it.label },
            onSelect = onFilter,
        )
    }
}

@Composable
private fun AppRow(
    app: AppPolicy,
    info: InstalledApps.Info?,
    shape: RoundedCornerShape,
    showDivider: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.pkg, info, 40.dp)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        info?.label ?: app.pkg.ifBlank { "（未填写包名）" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (app.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (app.strictMode) Tag("严格", MaterialTheme.colorScheme.primary)
                        if (info?.installed == false && app.pkg.isNotBlank()) {
                            Tag("未安装", MaterialTheme.colorScheme.error)
                        }
                        Text(
                            app.pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Switch(checked = app.enabled, onCheckedChange = onToggle)
            }
            if (showDivider) {
                HorizontalDivider(
                    Modifier.padding(start = 66.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .padding(end = 6.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 应用图标；未安装 / 加载中显示首字母占位 */
@Composable
private fun AppIcon(pkg: String, info: InstalledApps.Info?, size: Dp) {
    val bmp = info?.icon
    if (bmp != null) {
        val image = remember(bmp) { bmp.asImageBitmap() }
        Image(image, contentDescription = null, modifier = Modifier.size(size))
    } else {
        val letter = (info?.label ?: pkg).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ExclusionSection() {
    val cfg by ConfigBus.state.collectAsState()
    SectionCard(
        title = "排除名单（${cfg.excludedPackages.size + cfg.excludedUids.size}）",
        subtitle = "命中的包/uid 完全透传真实环境",
        collapsible = true,
        initiallyExpanded = false,
    ) {
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

/** 单应用详情：头部显示图标/应用名，设置仅"是否生效"与"严格模式"两项 */
@Composable
private fun AppDetailPage(index: Int, onBack: () -> Unit, onDeleted: () -> Unit) {
    val notifier = LocalNotifier.current
    val cfg by ConfigBus.state.collectAsState()
    val app = cfg.apps.getOrNull(index) ?: run { onDeleted(); return }
    // 手动添加的条目始终显示包名输入框（不随输入中途匹配到已安装应用而消失）
    val manual = remember(index) { app.pkg.isBlank() }
    val info = rememberAppInfos(listOfNotNull(app.pkg.takeIf { it.isNotBlank() }))[app.pkg]
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

        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.pkg, info, 52.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    info?.label ?: app.pkg.ifBlank { "新应用" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when {
                        app.pkg.isBlank() -> "填写包名后显示应用信息"
                        info?.installed == false -> "${app.pkg} · 未安装"
                        else -> app.pkg
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (manual || info?.installed == false) {
            SectionCard(title = "包名", subtitle = "与 LSPosed 作用域中勾选的包一致才生效") {
                StrField("包名", app.pkg, { v ->
                    editApp(index) { it.copy(pkg = v.trim()) }
                }, hint = "com.tencent.mm")
            }
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
                if (app.pkg.isNotBlank()) {
                    notifier.show("已移除 ${info?.label ?: app.pkg}", "撤销") {
                        ConfigBus.update { c -> c.copy(apps = c.apps.insertAt(index, app)) }
                    }
                }
                onDeleted()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("删除该应用") }

        Text(
            "设置改动后自动发布（更多页可关闭自动发布）。在 LSPosed 中取消勾选也会自动移除。",
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
