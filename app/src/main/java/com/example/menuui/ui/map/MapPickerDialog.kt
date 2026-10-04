package com.example.menuui.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.AMapOptions
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.TextureMapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.example.menuui.geo.CoordTransform
import com.example.menuui.geo.LatLon

/**
 * 全屏地图选点。入参/回调都是 WGS-84（与 EnvDraft 一致），GCJ-02 只在本文件内部出现。
 * 交互：屏幕中心固定图钉，拖动地图对准目标；点击地图 = 相机移到点击处。
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
    var agreed by remember { mutableStateOf(AmapPrivacy.isAgreed(context)) }
    val hasKey = remember { AmapPrivacy.hasApiKey(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            when {
                !hasKey -> NoticeScreen(
                    title = "未配置高德 Key",
                    body = "在 local.properties 中填写 AMAP_KEY=你的key 后重新编译。",
                    confirmText = null,
                    onConfirm = {},
                    onDismiss = onDismiss,
                )
                !agreed -> NoticeScreen(
                    title = "地图服务隐私说明",
                    body = "地图选点使用高德地图 SDK，加载地图时 SDK 会收集设备标识、网络状态等信息" +
                        "用于提供地图服务，详见《高德地图开放平台隐私权政策》" +
                        "（https://lbs.amap.com/pages/privacy/）。\n\n" +
                        "本页不读取你的真实位置。同意后才会加载地图。",
                    confirmText = "同意并继续",
                    onConfirm = {
                        AmapPrivacy.agree(context)
                        agreed = true
                    },
                    onDismiss = onDismiss,
                )
                else -> PickerContent(initialLat, initialLon, onConfirm, onDismiss)
            }
        }
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
    val initialGcj = remember { CoordTransform.wgs84ToGcj02(initialLat, initialLon) }
    var centerGcj by remember { mutableStateOf(initialGcj) }
    val centerWgs = CoordTransform.gcj02ToWgs84(centerGcj.lat, centerGcj.lon)

    val mapView = remember {
        AmapPrivacy.applyToSdk(context)
        val options = AMapOptions()
            .camera(CameraPosition.fromLatLngZoom(LatLng(initialGcj.lat, initialGcj.lon), 16f))
            .zoomControlsEnabled(false)
            .tiltGesturesEnabled(false)
            .scaleControlsEnabled(true)
        TextureMapView(context, options)
    }

    // SDK 要求 onCreate 之后再 getMap
    fun bindMap(map: AMap) {
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(p: CameraPosition) {
                centerGcj = LatLon(p.target.latitude, p.target.longitude)
            }

            override fun onCameraChangeFinish(p: CameraPosition) {
                centerGcj = LatLon(p.target.latitude, p.target.longitude)
            }
        })
        map.setOnMapClickListener { latLng ->
            map.animateCamera(CameraUpdateFactory.changeLatLng(latLng))
        }
    }

    // MapView 生命周期桥接：addObserver 会补发到当前状态的事件（CREATE→RESUME）
    DisposableEffect(mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    mapView.onCreate(null)
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // LocationOn 图标尖端在 24 格视口的 y≈22 处：40dp 图标上移 17dp 让尖端对准中心
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = "选中位置",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-17).dp)
                .size(40.dp),
        )

        Card(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("地图选点", style = MaterialTheme.typography.titleMedium)
                Text(
                    "纬度 %.6f，经度 %.6f".format(centerWgs.lat, centerWgs.lon),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "拖动地图让图钉对准目标，点击地图可快速移动；坐标为 WGS-84",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(
                        onClick = { onConfirm(centerWgs.lat, centerWgs.lon) },
                        modifier = Modifier.weight(1f),
                    ) { Text("确认") }
                }
            }
        }
    }
}

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
