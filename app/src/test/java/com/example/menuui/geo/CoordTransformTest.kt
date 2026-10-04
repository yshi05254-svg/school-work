package com.example.menuui.geo

import com.example.menuui.config.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

class CoordTransformTest {

    /** 两点近似平面距离（米），短距离足够精确 */
    private fun meters(a: LatLon, b: LatLon): Double {
        val dy = (a.lat - b.lat) * 111_320.0
        val dx = (a.lon - b.lon) * 111_320.0 * cos(Math.toRadians(a.lat))
        return sqrt(dx * dx + dy * dy)
    }

    @Test
    fun roundTripWithinOneMeterForAllPresets() {
        Presets.all.forEach { p ->
            val wgs = LatLon(p.env.lat, p.env.lon)
            val gcj = CoordTransform.wgs84ToGcj02(wgs.lat, wgs.lon)
            val back = CoordTransform.gcj02ToWgs84(gcj.lat, gcj.lon)
            assertTrue("${p.label} 往返误差过大", meters(wgs, back) < 1.0)
        }
    }

    @Test
    fun domesticOffsetIsHundredsOfMeters() {
        val wgs = LatLon(39.9042, 116.4074) // 北京预设
        val gcj = CoordTransform.wgs84ToGcj02(wgs.lat, wgs.lon)
        val d = meters(wgs, gcj)
        assertTrue("北京偏移 $d m 不在合理区间", d in 100.0..1000.0)
    }

    @Test
    fun overseasPointsAreUnchanged() {
        listOf(LatLon(35.6812, 139.7671), LatLon(40.7128, -74.0060)).forEach { p ->
            assertEquals(p, CoordTransform.wgs84ToGcj02(p.lat, p.lon))
            assertEquals(p, CoordTransform.gcj02ToWgs84(p.lat, p.lon))
        }
    }
}
