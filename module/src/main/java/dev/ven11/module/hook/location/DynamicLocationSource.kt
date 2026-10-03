package dev.ven11.module.hook.location

import android.os.SystemClock
import dev.ven11.module.ProbeLog
import dev.ven11.module.model.JoystickState
import dev.ven11.module.model.Route
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 动态位置源（与 LocationFactory 同包，供其按 路线 > 摇杆 > 静态 顺序取 fix）。
 *
 * boot 语义（评审二）：boot < 0（BOOT_COUNT 未知，Application 尚未创建）时路线
 * 一律返回 null 且不建锚点——避免"未知 0 → 真实 N"被误判为跨重启而重置进度；
 * boot >= 0 后首个 fix 建锚点，锚点 boot 与当前不符（真重启）才重置。
 * 摇杆为纯函数插值，不依赖 boot / 锚点状态。
 */
object DynamicLocationSource {

    class Fix(
        val lat: Double,
        val lon: Double,
        val speed: Float,
        val bearing: Float,
        val source: String,
    )

    private class Anchor(val boot: Int, val startElapsedNs: Long)

    private val anchors = ConcurrentHashMap<String, Anchor>()

    /** 路线回放：锚点起匀速推进，loop 循环回绕，非 loop 停在终点 */
    fun routeFix(pkg: String, uid: Int, boot: Int, route: Route): Fix? {
        if (boot < 0 || route.points.size < 2) return null
        val key = "route/$pkg/$uid/${route.id}"
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val prev = anchors[key]
        val anchor = if (prev != null && prev.boot == boot) {
            prev
        } else {
            if (prev != null) ProbeLog.log("ROUTE anchor reset pkg=$pkg boot=$boot")
            Anchor(boot, nowNs).also { anchors[key] = it }
        }
        val elapsedSec = ((nowNs - anchor.startElapsedNs) / 1_000_000_000.0).coerceAtLeast(0.0)
        return walk(route, route.speedMps * elapsedSec)
    }

    /**
     * 摇杆航位推算：位置 = 基点 + v × (now − anchoredAt)，纯函数、无模块侧锚点状态。
     * 管理端（前台服务）负责积分：速度/方向变化时 base 与 anchoredAt 成对重发，
     * 心跳只续期 expiresAtElapsedMs——同一会话内坐标对时间是连续函数，所有被
     * hook 的应用读同一快照得到同一坐标（无 per-app 锚点偏差）。
     * 快照轮询 1s 的粒度 gap 由该连续函数补齐，产出的定位序列平滑类真实 GPS。
     * 失效即返回 null（回落静态环境）：过期（心跳失联）、未启用、anchoredAt 非法。
     */
    fun joystickFix(js: JoystickState): Fix? {
        if (!js.enabled || js.anchoredAtElapsedMs <= 0L) return null
        val nowMs = SystemClock.elapsedRealtime()
        // expiresAtElapsedMs <= 0 视为立即过期：没有心跳续期的摇杆段不生效（死人开关默认开）
        if (nowMs > js.expiresAtElapsedMs) return null
        val dtSec = ((nowMs - js.anchoredAtElapsedMs) / 1000.0).coerceAtLeast(0.0)
        val north = js.vNorthMps * dtSec
        val east = js.vEastMps * dtSec
        val speed = sqrt(js.vNorthMps * js.vNorthMps + js.vEastMps * js.vEastMps)
        // 静止时速度/航向归零（与真实 GNSS 静止态一致），避免 atan2(0,0)=0 假航向
        val bearing = if (speed < 0.1) 0f
        else ((Math.toDegrees(atan2(js.vEastMps, js.vNorthMps)) + 360.0) % 360.0).toFloat()
        return Fix(
            lat = js.baseLat + north / 111_320.0,
            lon = js.baseLon + east / (111_320.0 * cos(Math.toRadians(js.baseLat)).coerceAtLeast(1e-6)),
            speed = speed.toFloat(),
            bearing = bearing,
            source = "joystick",
        )
    }

    // ---------------------------------------------------------------- 折线推进

    /** 折线段长与总长：路线对象不可变（随快照替换），按身份缓存，免每次定位重算 haversine */
    private class Geometry(val route: Route, val segLen: DoubleArray, val total: Double)

    @Volatile
    private var geometry: Geometry? = null

    private fun geometryOf(route: Route): Geometry {
        geometry?.let { if (it.route === route) return it }
        val pts = route.points
        val segLen = DoubleArray(pts.size - 1) { i -> distanceM(pts[i], pts[i + 1]) }
        var total = 0.0
        for (l in segLen) total += l
        return Geometry(route, segLen, total).also { geometry = it }
    }

    private fun walk(route: Route, meters: Double): Fix {
        val pts = route.points
        val g = geometryOf(route)
        val segLen = g.segLen
        val total = g.total
        if (total <= 0.0) return Fix(pts[0].lat, pts[0].lon, route.speedMps.toFloat(), 0f, "route")

        val d = if (route.loop) meters % total else meters.coerceIn(0.0, total - 1e-6)
        var acc = 0.0
        var i = 0
        while (i < segLen.size && acc + segLen[i] < d) {
            acc += segLen[i]
            i++
        }
        if (i >= segLen.size || segLen[i] <= 0.0) {
            val last = pts.last()
            return Fix(last.lat, last.lon, route.speedMps.toFloat(), 0f, "route")
        }
        val f = (d - acc) / segLen[i]
        val a = pts[i]
        val b = pts[i + 1]
        return Fix(
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            speed = route.speedMps.toFloat(),
            bearing = bearingDeg(a, b),
            source = "route",
        )
    }

    private fun distanceM(a: dev.ven11.module.model.GeoPoint, b: dev.ven11.module.model.GeoPoint): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(la1) * cos(la2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun bearingDeg(a: dev.ven11.module.model.GeoPoint, b: dev.ven11.module.model.GeoPoint): Float {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}
