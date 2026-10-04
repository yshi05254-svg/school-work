package com.example.menuui.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/**
 * WGS-84 ⇄ GCJ-02 坐标转换。
 *
 * 约定：配置里的 env.lat/lon 一律是 WGS-84（模块原样写进 android.location.Location，
 * 被 hook 的应用自己再转 GCJ-02）；地图选点把百度 SDK 坐标系设成 GCJ-02，显示与回调都是 GCJ-02。
 * 所以地图选点只在入口（WGS→GCJ 定位相机）和出口（GCJ→WGS 写回配置）各转一次。
 *
 * 境外判断用粗矩形（与高德/腾讯公开算法一致）：日本、美国等不偏移；
 * 朝鲜半岛、越南北部等落在矩形内的境外点也会被偏移，选点时偏差约数百米。
 */
object CoordTransform {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lon: Double): LatLon {
        if (outOfChina(lat, lon)) return LatLon(lat, lon)
        val d = offset(lat, lon)
        return LatLon(lat + d.lat, lon + d.lon)
    }

    /** 正向公式没有解析逆，用不动点迭代反解；国内范围 3~4 轮即收敛到 1e-9°（≈0.1mm） */
    fun gcj02ToWgs84(lat: Double, lon: Double): LatLon {
        if (outOfChina(lat, lon)) return LatLon(lat, lon)
        var wLat = lat
        var wLon = lon
        repeat(MAX_ITER) {
            val g = wgs84ToGcj02(wLat, wLon)
            val dLat = g.lat - lat
            val dLon = g.lon - lon
            wLat -= dLat
            wLon -= dLon
            if (abs(dLat) < EPS && abs(dLon) < EPS) return LatLon(wLat, wLon)
        }
        return LatLon(wLat, wLon)
    }

    /** 球面距离（米，haversine），同一坐标系内比较即可 */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_M = 6371008.8
    private const val MAX_ITER = 20
    private const val EPS = 1e-9

    private fun offset(lat: Double, lon: Double): LatLon {
        val x = lon - 105.0
        val y = lat - 35.0
        val radLat = lat / 180.0 * PI
        val s = sin(radLat)
        val magic = 1 - EE * s * s
        val sqrtMagic = sqrt(magic)
        val dLat = transformLat(x, y) * 180.0 / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val dLon = transformLon(x, y) * 180.0 / (A / sqrtMagic * cos(radLat) * PI)
        return LatLon(dLat, dLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return r
    }

    private fun transformLon(x: Double, y: Double): Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        r += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return r
    }
}
