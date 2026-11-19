package dev.ven11.module.hook.framework.location

import android.location.Location
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.hook.location.DynamicLocationSource
import dev.ven11.module.hook.location.LocationFactory
import dev.ven11.module.hook.location.LocationFactory.Decision
import dev.ven11.module.ipc.SnapshotStore
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

/**
 * 服务端主动定位注入（方案B 延伸：摇杆/路线/静态环境室内生效）。
 *
 * 问题：被动改写模式只能改写系统交付给应用的真实 fix。室内无 GPS 时系统不给应用交付
 * 任何定位，导致不仅摇杆/路线无法推进，连高德（不注册 network）、淘宝（500ms 短超时）
 * 在静态环境下也拿不到任何 gps fix。
 *
 * 做法：在 system_server 内，当目标应用有活跃定位注册（连续 LocationRegistration 或
 * 一次性 GetCurrentLocationListenerRegistration）时，按请求间隔主动向该注册交付合成定位。
 * 交付完全复用系统原生入口 `LocationProviderManager.deliverToListeners(Function)`
 * （dexdump 核实：内部持 mMultiplexerLock + ReentrancyGuard，只对 isActive 注册调 fn），
 * fn 仅对目标注册返回 `acceptLocationChange(载体)`、其余返回 null——因此只注入目标应用，
 * 不污染系统组件与无关应用，锁与线程模型与真实 onReportLocation 完全一致。
 *
 * manager 捕获：从已稳定触发的方法钩取 gps/fused/network 的 LocationProviderManager——
 * registerLocationRequest / getCurrentLocation（应用注册时，thisObject 即 manager）为主，
 * acceptLocationChange 的注册对象经 this$0 取外部 manager 为兜底。
 *
 * 行为边界：
 *  - 目标应用处于 SPOOF 模式（静态、摇杆、路线）且无近期真实流时注入；
 *  - 每注册记最近一次**真实**交付时刻，真实流在跑时跳过注入（室外 GPS 正常时自动让路）；
 *  - 注册瞬间由 pokeSoon() 约 100ms 唤醒推首帧，无活跃目标时回落长睡，几乎零功耗；
 *  - serverLocation / masterEnabled 关闭 → 整体停摆（kill-switch）。
 *
 * 安全约束（同 ServerLocationHooks §1.5）：异常不抛进 system_server；回调零 IO/IPC；
 * idle 长睡；注入经我们自己的 acceptLocationChange 钩（会再伪装一次，幂等）。
 */
object ServerLocationInjector {

    private const val STATIC_TICK_MS = 1_000L
    private const val DYNAMIC_TICK_MS = 500L
    private const val IDLE_SLEEP_MS = 5_000L
    private const val POKE_DELAY_MS = 100L

    private const val DEFAULT_INTERVAL_MS = 1_000L
    private const val MIN_INTERVAL_MS = 250L
    private const val MAX_INTERVAL_MS = 5_000L

    /** 真实交付在 REAL_FRESH_MS 内视为"真实流在跑"，本注册本轮跳过注入 */
    private const val REAL_FRESH_MS = 2_000L

    /** 只注入实时/网络定位 provider（排除 passive，避免污染全局 lastLocation 与无关应用） */
    private val REALTIME_PROVIDERS = setOf("gps", "fused", "network")

    /** name -> LocationProviderManager（gps/fused/network）；由钩捕获 */
    private val managers = ConcurrentHashMap<String, Any>()

    /** 注册对象 -> 最近一次真实交付 elapsedRealtime（弱键，注册回收即失效） */
    private val lastReal = Collections.synchronizedMap(WeakHashMap<Any, Long>())

    /** 注册对象 -> 最近一次注入交付 elapsedRealtime（按间隔节流） */
    private val lastInject = Collections.synchronizedMap(WeakHashMap<Any, Long>())

    /** 注入期间置位：onAcceptLocationChange 据此跳过"真实交付"记账 */
    private val injecting: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val wakeLock = Object()
    @Volatile private var pokeRequested = false

    private val started = AtomicBoolean(false)
    private val errSeen = ConcurrentHashMap.newKeySet<String>()

    // ---------------------------------------------------------------- 捕获 manager

    /** registerLocationRequest / getCurrentLocation 钩调用：thisObject 即 LocationProviderManager */
    fun recordManager(manager: Any?) {
        val name = ServerReflect.providerManagerName(manager)
        if (name == null || name !in REALTIME_PROVIDERS) return
        if (managers.put(name, manager!!) == null) ProbeLog.log("SRVINJ manager captured name=$name")
        ensureStarted()
    }

    /** acceptLocationChange 兜底：从 LocationRegistration.this$0 取外部 manager */
    fun recordManagerFromRegistration(registration: Any?) {
        registration ?: return
        if (managers.size >= REALTIME_PROVIDERS.size) return // 已齐，省反射
        val mgr = ServerReflect.enclosingManager(registration) ?: return
        recordManager(mgr)
    }

    /** onAcceptLocationChange 调用：登记一次真实交付（注入自身被 injecting 标志排除） */
    fun noteDelivery(registration: Any?) {
        if (registration == null || injecting.get() == true) return
        lastReal[registration] = SystemClock.elapsedRealtime()
    }

    /** 目标应用刚注册定位请求/getCurrentLocation：唤醒注入线程尽快推首帧 */
    fun pokeSoon() {
        ensureStarted()
        synchronized(wakeLock) {
            pokeRequested = true
            wakeLock.notifyAll()
        }
    }

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({ loop() }, "ven11-srvinj").apply { isDaemon = true; start() }
        ProbeLog.log("SRVINJ thread started")
    }

    // ---------------------------------------------------------------- 调度

    private fun loop() {
        while (true) {
            val poked = synchronized(wakeLock) {
                val p = pokeRequested
                pokeRequested = false
                p
            }
            if (poked) {
                try {
                    Thread.sleep(POKE_DELAY_MS)
                } catch (_: InterruptedException) {
                    return
                }
                synchronized(wakeLock) { pokeRequested = false }
            }
            val sleepMs = runCatching { tickOnce() }.getOrElse { err("tick", it); IDLE_SLEEP_MS }
            val interrupted = synchronized(wakeLock) {
                if (!pokeRequested) {
                    try {
                        wakeLock.wait(sleepMs)
                    } catch (_: InterruptedException) {
                        return@synchronized true
                    }
                }
                false
            }
            if (interrupted) return
        }
    }

    /** 一轮：返回下次睡眠。无可用配置 / 无 manager / 无活跃目标 → idle 长睡 */
    private fun tickOnce(): Long {
        if (managers.isEmpty()) return IDLE_SLEEP_MS
        val snap = SnapshotStore.current()
        if (!snap.masterEnabled || !snap.serverLocation ||
            snap.environments.isEmpty() || snap.policies.isEmpty()
        ) {
            return IDLE_SLEEP_MS
        }

        val joystickOn = DynamicLocationSource.joystickFix(snap.joystick) != null
        val routeConfigured = snap.policies.any { it.routeId != 0L }
        val tickMs = if (joystickOn || routeConfigured) DYNAMIC_TICK_MS else STATIC_TICK_MS

        val activeTargets = AtomicInteger(0)
        for ((name, manager) in managers) {
            runCatching { ServerReflect.deliverToListeners(manager, injectFn(name, activeTargets)) }
                .onFailure { err("deliver", it) }
        }
        return if (activeTargets.get() > 0) tickMs else IDLE_SLEEP_MS
    }

    // ---------------------------------------------------------------- 注入 Function

    private fun injectFn(providerName: String, activeTargets: AtomicInteger): Function<Any?, Any?> =
        Function { reg ->
            try {
                injectOne(providerName, reg, activeTargets)
            } catch (t: Throwable) {
                err("inject-fn", t)
                null
            }
        }

    private fun injectOne(providerName: String, registration: Any?, activeTargets: AtomicInteger): Any? {
        registration ?: return null
        val continuous = isContinuousRegistration(registration)
        val getCurrent = !continuous && isGetCurrentRegistration(registration)
        if (!continuous && !getCurrent) return null

        val identity = ServerReflect.registrationIdentity(registration)
        val uid = ServerReflect.identityUid(identity)
        if (uid <= 0 || uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return null
        val pkg = ServerReflect.identityPkg(identity) ?: return null

        if (ServerLocationDecider.modeFor(uid, pkg) != ServerLocationDecider.Mode.SPOOF) return null
        activeTargets.incrementAndGet()

        val now = SystemClock.elapsedRealtime()
        // 真实流在跑：让路，不注入
        lastReal[registration]?.let { if (now - it < REAL_FRESH_MS) return null }
        // 连续注册按请求间隔节流；一次性 getCurrentLocation 不节流（交付后系统自动 remove）
        if (continuous) {
            val interval = ServerReflect.requestIntervalMillis(registration, DEFAULT_INTERVAL_MS)
                .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            lastInject[registration]?.let { if (now - it < (interval * 0.9).toLong()) return null }
        }

        // 合成载体：决策点复制，provider 用 manager 名，extras 清空（若改写翻转为透传，
        // 载体会原样交付——清空避免载体来源指纹），时间戳新鲜（否则 accept 可能按陈旧丢弃）
        val spoof = (LocationFactory.decide(pkg, uid, providerName, null) as? Decision.Spoof)?.loc ?: return null
        val carrier = Location(spoof).apply {
            provider = providerName
            extras = Bundle()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        val result = ServerReflect.createLocationResult(listOf<Location>(carrier)) ?: return null

        // 经我们自己的 acceptLocationChange 钩交付（改写→原生方法体的门控/限速/app-op）
        injecting.set(true)
        val op = try {
            ServerReflect.acceptLocationChange(registration, result)
        } finally {
            injecting.set(false)
        }
        if (op != null) {
            lastInject[registration] = now
            SrvStat.count("inject:$providerName")
            SrvStat.first("inject", uid, pkg, "inject")
        }
        return op
    }

    /** 是否 LocationRegistration 家族（连续注册）：沿父类链找抽象基类 LocationRegistration */
    private fun isContinuousRegistration(registration: Any): Boolean =
        hasSuperclassNamed(registration.javaClass, "LocationRegistration")

    /** 是否 GetCurrentLocationListenerRegistration（一次性 getCurrentLocation） */
    private fun isGetCurrentRegistration(registration: Any): Boolean =
        hasSuperclassNamed(registration.javaClass, "GetCurrentLocationListenerRegistration")

    private fun hasSuperclassNamed(cls: Class<*>, simpleName: String): Boolean {
        var c: Class<*>? = cls
        while (c != null) {
            if (c.simpleName == simpleName) return true
            c = c.superclass
        }
        return false
    }

    private fun err(where: String, t: Throwable?) {
        val type = (t?.cause ?: t)?.javaClass?.simpleName ?: "?"
        SrvStat.count("inject:err")
        if (errSeen.add("$where/$type")) ProbeLog.log("SRVINJ-ERR $where $type: ${(t?.cause ?: t)?.message ?: "-"}")
    }
}
