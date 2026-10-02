package dev.ven11.module.hook.location

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.model.GpsJitter
import dev.ven11.module.model.VirtualEnvironment
import kotlin.math.cos
import kotlin.random.Random

/**
 * 虚拟 Location 工厂：环境 + 抖动 → android.location.Location。
 *
 * 全 provider 统一坐标（gps/fused/network/passive 同一环境）。决策三态（对齐原版 SYNC_QUERY）：
 *  - PassThrough：域关闭 / 非严格模式下无环境 → 调用方交还真实值
 *  - Block：严格模式下无法构造 → 调用方返回 null / 丢弃回调
 *  - Spoof：虚拟位置
 * 时间字段全部取当下，避免应用端陈旧检测。
 */
object LocationFactory {

    sealed interface Decision {
        object PassThrough : Decision
        object Block : Decision
        class Spoof(val loc: Location) : Decision
    }

    private const val M_PER_DEG_LAT = 111_320.0

    fun metersToDegLat(m: Double): Double = m / M_PER_DEG_LAT

    fun metersToDegLon(m: Double, atLat: Double): Double =
        m / (M_PER_DEG_LAT * cos(Math.toRadians(atLat)).coerceAtLeast(1e-6))

    // ---------------------------------------------------------------- 抖动

    /** 单位抖动样本（无量纲，[-1,1]），按 1s 时间桶整体替换；不可变对象 + @Volatile 发布，避免撕裂读 */
    private class JitterSample(val bucket: Long, val north: Double, val east: Double)

    private val jitterLock = Any()
    private val rng = Random(System.nanoTime()) // 仅在 synchronized(jitterLock) 内使用
    @Volatile private var jitterSample = JitterSample(Long.MIN_VALUE, 0.0, 0.0)

    /** 三均匀和近似高斯，归一到 [-1,1] */
    private fun unitGauss(): Double =
        (rng.nextDouble() + rng.nextDouble() + rng.nextDouble() - 1.5) / 1.5

    /** 同秒内样本稳定，避免高频读取时抖动闪烁 */
    private fun currentJitterSample(): JitterSample {
        val bucket = System.currentTimeMillis() / 1000L
        val s = jitterSample
        if (s.bucket == bucket) return s
        synchronized(jitterLock) {
            val again = jitterSample
            if (again.bucket == bucket) return again
            return JitterSample(bucket, unitGauss(), unitGauss()).also { jitterSample = it }
        }
    }

    /** @return (北向米, 东向米)；未启用直接返回 0，不触碰共享样本 */
    private fun jitterMeters(jitter: GpsJitter): Pair<Double, Double> {
        if (!jitter.enabled || jitter.amplitudeMeters <= 0) return 0.0 to 0.0
        val s = currentJitterSample()
        val half = jitter.amplitudeMeters / 2.0
        return s.north * half to s.east * half
    }

    // ---------------------------------------------------------------- 构造

    /** 静态环境 + 抖动 */
    fun build(
        env: VirtualEnvironment,
        jitter: GpsJitter,
        provider: String,
        template: Location? = null,
    ): Location {
        val (dn, de) = jitterMeters(jitter)
        val loc = baseLocation(env, provider, template)
        loc.latitude = env.lat + metersToDegLat(dn)
        loc.longitude = env.lon + metersToDegLon(de, env.lat) // 按环境纬度缩放经度
        // 卫星状态 extras：M5 路线同步时详化
        loc.extras = Bundle().apply {
            putInt("satellites", 12)
            putBoolean("ven11", true)
        }
        return loc
    }

    /** 动态源（路线/摇杆）定位：坐标/速度/航向取 fix，其余字段取环境 */
    private fun fromFix(
        env: VirtualEnvironment,
        provider: String,
        template: Location?,
        fix: DynamicLocationSource.Fix,
    ): Location {
        val loc = baseLocation(env, provider, template)
        loc.latitude = fix.lat
        loc.longitude = fix.lon
        loc.speed = fix.speed
        loc.bearing = fix.bearing
        loc.extras = Bundle().apply {
            putInt("satellites", 12)
            putString("ven11-src", fix.source)
        }
        return loc
    }

    private fun baseLocation(env: VirtualEnvironment, provider: String, template: Location?): Location {
        val loc = template?.let { Location(it) } ?: Location(provider)
        loc.provider = provider
        loc.latitude = env.lat
        loc.longitude = env.lon
        loc.altitude = env.alt
        loc.accuracy = env.accuracy
        loc.speed = env.speed
        loc.bearing = env.bearing
        loc.time = System.currentTimeMillis()
        loc.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= 31) {
            loc.isMock = false
        }
        try {
            loc.verticalAccuracyMeters = env.accuracy
            loc.speedAccuracyMetersPerSecond = 1.0f
            loc.bearingAccuracyDegrees = 5.0f
        } catch (_: Throwable) {
        }
        return loc
    }

    // ---------------------------------------------------------------- bootIdentity

    /** bootIdentity（BOOT_COUNT）进程内缓存——运行时锚点过期判定；-1 = 未取到 */
    @Volatile private var bootIdentity: Int = -1

    /** 只缓存成功读取的值；取不到（Application 尚未创建 / 异常）返回 0 并留待下次重试 */
    private fun bootIdentityNow(): Int {
        val cached = bootIdentity
        if (cached >= 0) return cached
        val cr = appContentResolver() ?: return 0
        return try {
            Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, 0).also { bootIdentity = it }
        } catch (_: Throwable) {
            0
        }
    }

    /** hook 进程内拿 ContentResolver（反射 ActivityThread.currentApplication） */
    private fun appContentResolver(): android.content.ContentResolver? = try {
        val at = Class.forName("android.app.ActivityThread")
        val app = at.getMethod("currentApplication").invoke(null) as? android.content.Context
        app?.contentResolver
    } catch (_: Throwable) {
        null
    }

    // ---------------------------------------------------------------- 统一入口

    /**
     * hook 路径统一决策：解析当前调用方策略 → 透传 / 拦截 / 模拟。
     * 位置源链：路线运行时 > 摇杆航位推算 > 静态环境 + 抖动。
     */
    fun decide(pkg: String, uid: Int, provider: String, realTemplate: Location?): Decision {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.LOCATION)) return Decision.PassThrough
        val env = eff.environment
            ?: return if (eff.policy?.strictIsolation == true) Decision.Block else Decision.PassThrough
        val boot = bootIdentityNow()

        // 1) 路线：绝对坐标（快照 routes 表取策略绑定的路线）
        val route = eff.policy?.routeId?.takeIf { it != 0L }?.let { eff.payload.routes[it] }
        if (route != null && route.points.size >= 2) {
            DynamicLocationSource.routeFix(pkg, uid, boot, route)?.let { fix ->
                return Decision.Spoof(fromFix(env, provider, realTemplate, fix))
            }
        }

        // 2) 摇杆：环境基点 + 累计位移
        DynamicLocationSource.joystickFix(pkg, uid, boot, env.lat, env.lon)?.let { fix ->
            return Decision.Spoof(fromFix(env, provider, realTemplate, fix))
        }

        // 3) 静态环境 + 抖动
        return Decision.Spoof(build(env, eff.jitter, provider, realTemplate))
    }

    /** 兼容旧调用方：Spoof → Location，其余 → null（无法区分透传与拦截，新代码请用 [decide]） */
    fun spoofedLocation(pkg: String, uid: Int, provider: String, realTemplate: Location?): Location? =
        (decide(pkg, uid, provider, realTemplate) as? Decision.Spoof)?.loc
}