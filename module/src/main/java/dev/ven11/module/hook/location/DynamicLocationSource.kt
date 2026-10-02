package dev.ven11.module.hook.location

import android.os.SystemClock
import dev.ven11.module.ProbeLog
import dev.ven11.module.model.Route
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 动态位置源（与 LocationFactory 同包，供其按 路线 > 摇杆 > 静态 顺序取 fix）。
 *
 * boot 语义（评审二）：boot < 0（BOOT_COUNT 未知，Application 尚未创建）时
 * 一律返回 null 且不建锚点——避免"未知 0 → 真实 N"被误判为跨重启而重置进度；
 * boot >= 0 后首个 fix 建锚点，锚点 boot 与当前不符（真重启）才重置。
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
     * 摇杆航位推算：输入通道（快照 joystick 段由管理端实时更新）接入前恒返回 null，
     * LocationFactory 回落静态环境 + 抖动。保留签名，接入时实现与路线同样的锚点机制。
     */
    fun joystickFix(pkg: String, uid: Int, boot: Int, baseLat: Double, baseLon: Double): Fix? = null

    // ---------------------------------------------------------------- 折线推进

    private fun walk(route: Route, meters: Double): Fix {
        val pts = route.points
        val segLen = DoubleArray(pts.size - 1) { i -> distanceM(pts[i], pts[i + 1]) }
        var total = 0.0
        for (l in segLen) total += l
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
