package dev.ven11.module.hook.location

import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import dev.ven11.module.ProbeLog
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

    /** extras 卫星数：沿用真实模板值语义的固定常量，不再写自定义 key（评审三指纹问题） */
    private const val SATELLITE_COUNT = 12

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
        loc.extras = satelliteExtras(template)
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
        loc.extras = satelliteExtras(template)
        return loc
    }

    /**
     * extras 不带任何自定义 key（评审三：`"ven11"`/`"ven11-src"` 是固定指纹，应用一查
     * 一个准）。保留模板原有 extras（真实定位自带的卫星/精度信息），仅补/覆盖卫星数。
     */
    private fun satelliteExtras(template: Location?): Bundle =
        (template?.extras?.let { Bundle(it) } ?: Bundle()).apply {
            putInt("satellites", SATELLITE_COUNT)
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
        // 交付对象可能来自真实模板（模板自带 mock 标记时不清除就是指纹）：
        // 构造完成后清除并读回验证（审查八 #6）
        clearMockFlag(loc)
        try {
            loc.verticalAccuracyMeters = env.accuracy
            loc.speedAccuracyMetersPerSecond = 1.0f
            loc.bearingAccuracyDegrees = 5.0f
        } catch (_: Throwable) {
        }
        return loc
    }

    /**
     * 清除 mock 标记并读回验证（审查八 #6）："没抛异常"不等于清掉了。方法名按 ROM
     * 实测的 dex 实证链尝试（OPLUS Android 16：setMock(Z)，无 setIsMock）；全部
     * 失败时保留限频诊断——此时应避免携带 mock 模板，而不是声称清理成功。
     * 只改本模块交付的对象，不碰全局 isMock 语义。
     */
    @Suppress("DEPRECATION")
    private fun clearMockFlag(loc: Location) {
        val cleared = try {
            // 首选：OPLUS Android 16 实证方法名 setMock(Z)（同步 isMock/isFromMockProvider 双字段）
            Location::class.java
                .getDeclaredMethod("setMock", Boolean::class.java)
                .apply { isAccessible = true }
                .invoke(loc, false)
            !loc.isMock
        } catch (_: Throwable) {
            try {
                // 次选：AOSP 历史方法名 setIsMock(Z)
                Location::class.java
                    .getDeclaredMethod("setIsMock", Boolean::class.java)
                    .apply { isAccessible = true }
                    .invoke(loc, false)
                !loc.isMock
            } catch (_: Throwable) {
                try {
                    // 兜底：直接清字段（OPLUS 16 实测字段名 isMock / isFromMockProvider 并存）
                    Location::class.java.getDeclaredField("isMock")
                        .apply { isAccessible = true }
                        .setBoolean(loc, false)
                    runCatching {
                        Location::class.java.getDeclaredField("isFromMockProvider")
                            .apply { isAccessible = true }
                            .setBoolean(loc, false)
                    }
                    !loc.isMock
                } catch (_: Throwable) {
                    false
                }
            }
        }
        if (!cleared) {
            ProbeLog.log("LOC-MOCK clear failed sdk=${Build.VERSION.SDK_INT}")
        }
    }

    // ---------------------------------------------------------------- bootIdentity

    /** bootIdentity（BOOT_COUNT）进程内缓存——运行时锚点过期判定；UNKNOWN = 未取到 */
    @Volatile private var bootIdentity: Int = UNKNOWN_BOOT

    const val UNKNOWN_BOOT = -1

    /**
     * 取不到（Application 尚未创建 / 异常）返回 [UNKNOWN_BOOT] 且不缓存、不留 0——
     * 0 是合法 BOOT_COUNT 值，若用它建锚，之后真实值 N 会被误判为"跨重启"重置路线（评审二）。
     */
    private fun bootIdentityNow(): Int {
        val cached = bootIdentity
        if (cached != UNKNOWN_BOOT) return cached
        val cr = appContentResolver() ?: return UNKNOWN_BOOT
        return try {
            Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT).also { bootIdentity = it }
        } catch (_: Throwable) {
            UNKNOWN_BOOT
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
            ?: return if (eff.policy?.strictMode == true) Decision.Block else Decision.PassThrough
        // boot < 0（BOOT_COUNT 未知）时不走路线（路线以 boot 建锚），回落摇杆/静态环境，
        // 避免"未知 0 → 真实 N"把首秒路线进度误判为跨重启清零（评审二）；
        // 摇杆是纯函数插值不建锚，boot 无关
        val boot = bootIdentityNow()

        // 1) 路线：绝对坐标（快照 routes 表取策略绑定的路线）
        val route = eff.policy?.routeId?.takeIf { it != 0L }?.let { eff.payload.routes[it] }
        if (route != null && route.points.size >= 2 && boot >= 0) {
            DynamicLocationSource.routeFix(pkg, uid, boot, route)?.let { fix ->
                return Decision.Spoof(fromFix(env, provider, realTemplate, fix))
            }
        }

        // 2) 摇杆：快照 joystick 段（管理端前台服务实时写入）纯函数航位推算；
        //    过期（心跳失联）/未启用回落静态环境 + 抖动
        DynamicLocationSource.joystickFix(eff.payload.joystick)?.let { fix ->
            return Decision.Spoof(fromFix(env, provider, realTemplate, fix))
        }

        // 3) 静态环境 + 抖动
        return Decision.Spoof(build(env, eff.jitter, provider, realTemplate))
    }

    /** 兼容旧调用方：Spoof → Location，其余 → null（无法区分透传与拦截，新代码请用 [decide]） */
    fun spoofedLocation(pkg: String, uid: Int, provider: String, realTemplate: Location?): Location? =
        (decide(pkg, uid, provider, realTemplate) as? Decision.Spoof)?.loc
}