package com.example.menuui.ui.map

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BaiduMapOptions
import com.baidu.mapapi.map.MapPoi
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.TextureMapView
import com.baidu.mapapi.model.LatLng
import com.example.menuui.geo.CoordTransform
import com.example.menuui.geo.LatLon

/**
 * 全屏地图选点。入参/回调都是 WGS-84（与 EnvDraft 一致），GCJ-02 只在本文件内部出现。
 *
 * 布局：地图与操作面板分区摆放（不再浮层叠放），整页按 safeDrawing 内缩——
 * 三键虚拟导航栏、状态栏、刘海都不会盖住按钮；横屏/大屏（宽≥600dp 且宽>高）
 * 面板放右侧，竖屏放底部。地图视图用 movableContentOf 在两种布局间搬移，不重建。
 *
 * 交互：屏幕中心固定图钉，拖动地图对准目标（拖动时图钉抬起）；点击地图 = 移到点击处；
 * 右下角按钮回到原位置；相机移动中"使用此位置"不可点，避免确认到惯性滑动的中间点。
 * 用 TextureMapView 而非 MapView：SurfaceView 叠在 Compose/Dialog 里有层级与黑屏问题。
 */
@Composable
fun MapPickerDialog(
    initialLat: Double,
    initialLon: Double,
    onConfirm: (lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var agreed by remember { mutableStateOf(BaiduMapSdk.isAgreed(context)) }
    val hasKey = remember { BaiduMapSdk.hasApiKey(context) }

    Dialog(
        onDismissRequest = onDismiss,
        // decorFitsSystemWindows=false：系统栏区域交给 Compose 的 WindowInsets 处理
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(fitDialogWindow().fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                when {
                    !hasKey -> NoticeScreen(
                        title = "未配置百度地图 Key",
                        body = "在 local.properties 中填写 BAIDU_MAP_KEY=你的AK 后重新编译。",
                        confirmText = null,
                        onConfirm = {},
                        onDismiss = onDismiss,
                    )
                    !agreed -> NoticeScreen(
                        title = "地图服务隐私说明",
                        body = "地图选点使用百度地图 SDK，加载地图时 SDK 会收集设备标识、网络状态等信息" +
                            "用于提供地图服务，详见《百度地图开放平台隐私政策》" +
                            "（https://lbsyun.baidu.com/index.php?title=openprivacy）。\n\n" +
                            "本页不读取你的真实位置。同意后才会加载地图。",
                        confirmText = "同意并继续",
                        onConfirm = {
                            BaiduMapSdk.agree(context)
                            agreed = true
                        },
                        onDismiss = onDismiss,
                    )
                    else -> PickerContent(initialLat, initialLon, onConfirm, onDismiss)
                }
            }
        }
    }
}

/**
 * 让对话框内容与窗口真实大小一致。Compose 的 Dialog（usePlatformDefaultWidth=false）
 * 按 screenHeightDp 量内容高度，targetSdk 35 起它包含状态栏和导航栏；但不少 ROM
 * 仍把对话框窗口夹在两条系统栏之间——内容比窗口高一截，底部面板被裁掉一半。
 *
 * 两手处理：先请求窗口铺满全屏（系统栏交给 safeDrawing 内缩）；再按窗口实际高度
 * 给内容封顶，ROM 不放行时内容也不会溢出窗口。
 */
@Composable
private fun fitDialogWindow(): Modifier {
    val view = LocalView.current
    val density = LocalDensity.current
    var windowHeightPx by remember { mutableIntStateOf(0) }

    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.attributes = window.attributes.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        val root = view.rootView
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            windowHeightPx = v.height
        }
        root.addOnLayoutChangeListener(listener)
        windowHeightPx = root.height
        onDispose { root.removeOnLayoutChangeListener(listener) }
    }

    return if (windowHeightPx > 0) {
        Modifier.heightIn(max = with(density) { windowHeightPx.toDp() })
    } else {
        Modifier
    }
}

@Composable
private fun PickerContent(
    initialLat: Double,
    initialLon: Double,
    onConfirm: (lat: Double, lon: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialWgs = remember { LatLon(initialLat, initialLon) }
    val initialGcj = remember { CoordTransform.wgs84ToGcj02(initialLat, initialLon) }
    var centerGcj by remember { mutableStateOf(initialGcj) }
    var moving by remember { mutableStateOf(false) }
    val centerWgs = CoordTransform.gcj02ToWgs84(centerGcj.lat, centerGcj.lon)

    val mapView = remember {
        BaiduMapSdk.ensureInitialized(context) // 坐标系设为 GCJ-02，下面进出都是 GCJ-02
        val options = BaiduMapOptions()
            .mapStatus(
                MapStatus.Builder()
                    .target(LatLng(initialGcj.lat, initialGcj.lon))
                    .zoom(17f)
                    .build(),
            )
            .zoomControlsEnabled(false)
            .overlookingGesturesEnabled(false)
            .rotateGesturesEnabled(false)
            .compassEnabled(false)
            .scaleControlEnabled(true)
        TextureMapView(context, options)
    }

    fun bindMap(map: BaiduMap) {
        map.setOnMapStatusChangeListener(object : BaiduMap.OnMapStatusChangeListener {
            override fun onMapStatusChangeStart(s: MapStatus) {
                moving = true
            }

            override fun onMapStatusChangeStart(s: MapStatus, reason: Int) {
                moving = true
            }

            override fun onMapStatusChange(s: MapStatus) {
                moving = true
                centerGcj = LatLon(s.target.latitude, s.target.longitude)
            }

            override fun onMapStatusChangeFinish(s: MapStatus) {
                moving = false
                centerGcj = LatLon(s.target.latitude, s.target.longitude)
            }
        })
        map.setOnMapClickListener(object : BaiduMap.OnMapClickListener {
            override fun onMapClick(latLng: LatLng) {
                map.animateMapStatus(MapStatusUpdateFactory.newLatLng(latLng))
            }

            // 点到地图上的 POI 名称时百度只回调这里，同样移过去
            override fun onMapPoiClick(poi: MapPoi) {
                map.animateMapStatus(MapStatusUpdateFactory.newLatLng(poi.position))
            }
        })
    }

    // MapView 生命周期桥接：addObserver 会补发到当前状态的事件（CREATE→RESUME）
    DisposableEffect(mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    mapView.onCreate(context, null)
                    bindMap(mapView.map)
                }
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            mapView.onDestroy()
        }
    }

    val mapArea = remember {
        movableContentOf { modifier: Modifier ->
            MapArea(
                mapView = mapView,
                moving = moving,
                onBack = onDismiss,
                onRecenter = {
                    mapView.map.animateMapStatus(
                        MapStatusUpdateFactory.newLatLng(LatLng(initialGcj.lat, initialGcj.lon)),
                    )
                },
                modifier = modifier,
            )
        }
    }
    val distance = CoordTransform.distanceMeters(initialWgs, centerWgs)
    val confirm = { onConfirm(centerWgs.lat, centerWgs.lon) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                mapArea(Modifier.weight(1f).fillMaxHeight())
                PickPanel(
                    center = centerWgs, distance = distance, moving = moving, sideBySide = true,
                    onCancel = onDismiss, onConfirm = confirm,
                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                mapArea(Modifier.weight(1f).fillMaxWidth())
                PickPanel(
                    center = centerWgs, distance = distance, moving = moving, sideBySide = false,
                    onCancel = onDismiss, onConfirm = confirm,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MapArea(
    mapView: TextureMapView,
    moving: Boolean,
    onBack: () -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lift by animateDpAsState(if (moving) 10.dp else 0.dp, label = "pinLift")
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 落点阴影：标出精确中心，图钉抬起时也能看清落点
        Box(
            Modifier
                .align(Alignment.Center)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f), CircleShape),
        )
        // LocationOn 图标尖端在 24 格视口的 y≈22 处：40dp 图标上移 17dp 让尖端对准中心
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = "选中位置",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-17).dp - lift)
                .size(40.dp),
        )

        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }

        SmallFloatingActionButton(
            onClick = onRecenter,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) { Icon(Icons.Filled.Refresh, contentDescription = "回到原位置") }
    }
}

@Composable
private fun PickPanel(
    center: LatLon,
    distance: Double,
    moving: Boolean,
    sideBySide: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, tonalElevation = 3.dp) {
        Column(
            Modifier
                // 底部多留一些，按钮离屏幕下沿/导航栏远一点
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 28.dp)
                .then(if (sideBySide) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("地图选点", style = MaterialTheme.typography.titleMedium)
            Text(
                "拖动地图让图钉对准目标，点击地图可快速移过去",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "%.6f, %.6f".format(center.lat, center.lon),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (distance < 1.0) "与当前位置相同" else "距当前位置 ${formatDistance(distance)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sideBySide) Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = onConfirm,
                    enabled = !moving,
                    modifier = Modifier.weight(1f),
                ) { Text("使用此位置") }
            }
        }
    }
}

private fun formatDistance(m: Double): String =
    if (m < 1000) "%.0f m".format(m) else "%.2f km".format(m / 1000)

@Composable
private fun NoticeScreen(
    title: String,
    body: String,
    confirmText: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("返回") }
            if (confirmText != null) {
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmText) }
            }
        }
    }
}
