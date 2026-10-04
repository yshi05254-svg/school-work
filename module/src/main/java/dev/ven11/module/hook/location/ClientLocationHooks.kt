package dev.ven11.module.hook.location

import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import android.util.Log
import dev.ven11.module.hook.location.LocationFactory.Decision
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Locale
import java.util.Collections.synchronizedMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * 客户端定位钩（目标应用进程内，本机主路径——Vector fork system 钩不可依赖）。
 *
 * 覆盖面（对齐 docs/spec/01 LOCATION 域客户端部分）：
 *  - getLastKnownLocation / getLastLocation：同步替换（E 后处理），带重入保护
 *  - getCurrentLocation（全部 Consumer 重载）：参数包装（C 参数拦截），不缓存
 *  - requestLocationUpdates 全 Listener 注册路径：包装（B）；
 *    PendingIntent 路径客户端模式不覆盖（对齐 VEN 结论）
 *  - removeUpdates：orig → wrapper 换回，系统按 wrapper 注销
 *
 * LocationManager 内部重载逐层委托（public → hidden/private），因此所有包装器实现
 * [SpoofWrapper]，内层钩见到已包装对象直接放行，保证每个回调只包一层。
 * requestSingleUpdate 无需单独 hook：API ≤29 走 requestLocationUpdates，30+ 走 getCurrentLocation。
 */
class ClientLocationHooks(private val module: XposedModule) {

    /** 已包装标记 */
    private interface SpoofWrapper

    /**
     * orig → wrapper，按对象身份比较；所有读写均在 synchronized(wrappers) 内。
     * 弱键 + **弱值**（审查六 #2）：wrapper 强引用 orig，若映射强持 wrapper 就会
     * 形成"值强引用键"的固定环，条目永不回收——而有些注册是系统自己结束的
     * （requestSingleUpdate；带 setMaxUpdates / setDurationMillis 的 LocationRequest），
     * 应用不会再调 removeUpdates，listener 若是 Activity 内部类就整链泄漏。
     * 弱值下：系统经 binder transport 强引用 wrapper（wrapper 强引用 orig），
     * 注册存活期间弱引用恒可解；系统一释放 wrapper，弱值即可回收，orig 随应用
     * 侧引用一起释放，条目自然失效。映射清理仍由 removeUpdates 钩负责显式删除。
     */
    private val wrappers =
        synchronizedMap(WeakHashMap<LocationListener, java.lang.ref.WeakReference<SpoofListener>>())

    /** 同步读取重入保护：如 getLastLocation → getLastKnownLocation，只在最外层替换 */
    private val inSync = ThreadLocal<Boolean>()

    private val hitCount = AtomicInteger()

    fun install(cl: ClassLoader, pkg: String): Int {
        var n = 0
        val uid = android.os.Process.myUid()
        val lmClass = cl.loadClass("android.location.LocationManager")

        for (m in lmClass.declaredMethods) {
            when {
                m.name == "getLastKnownLocation" && m.parameterTypes.size == 1 -> {
                    installSync(m, pkg, uid); n++
                }
                m.name == "getLastLocation" && m.parameterTypes.isEmpty() -> {
                    installSync(m, pkg, uid); n++
                }
                m.name == "getCurrentLocation" && hasConsumerArg(m) -> {
                    installCurrentLocation(m, pkg, uid); n++
                }
                m.name == "requestLocationUpdates" && hasListenerArg(m) -> {
                    installRequestListener(m, pkg, uid); n++
                }
                m.name == "removeUpdates" && m.parameterTypes.size == 1 &&
                    LocationListener::class.java.isAssignableFrom(m.parameterTypes[0]) -> {
                    installRemove(m); n++
                }
                m.name == "isProviderEnabled" && m.parameterTypes.size == 1 -> {
                    installProviderQuery(m, pkg, uid); n++
                }
                m.name == "getProviders" && m.parameterTypes.size == 1 -> {
                    installProviderList(m, pkg, uid); n++
                }
                m.name == "getAllProviders" && m.parameterTypes.isEmpty() -> {
                    installProviderList(m, pkg, uid); n++
                }
                m.name == "getBestProvider" && m.parameterTypes.size == 2 -> {
                    installBestProvider(m, pkg, uid); n++
                }
                m.name == "registerGnssStatusCallback" -> {
                    installGnssCallback(m, pkg, uid); n++
                }
                // 原始测量通道：AOSP 单数 registerGnssMeasurementCallback，部分 ROM
                // （ColorOS 16 实测）改为复数 registerGnssMeasurementsCallback，另存
                // 遗留 addGpsMeasurementListener——三者都按位置级泄漏抑制
                m.name == "registerGnssMeasurementCallback" ||
                    m.name == "registerGnssMeasurementsCallback" ||
                    m.name == "addGpsMeasurementListener" -> {
                    installGnssMeasurementSuppress(m); n++
                }
                m.name == "addNmeaListener" -> {
                    installNmeaSuppress(m); n++
                }
                // 批量定位交付完整 Location 列表（真实位置），导航电文含星历——
                // 都是位置级泄漏面，与原始测量同策略抑制
                m.name == "registerGnssBatchedLocationCallback" -> {
                    installCbSuppress(m, "LOC-BATCH", "BatchedLocation", "onLocationBatch"); n++
                }
                m.name == "registerGnssNavigationMessageCallback" -> {
                    installCbSuppress(m, "LOC-NAVMSG", "NavigationMessage", "onGnssNavigationMessageReceived"); n++
                }
            }
        }
        module.log(Log.INFO, "VEN11", "client location hooks installed=$n pkg=$pkg")
        dev.ven11.module.ProbeLog.log("LOC-HOOKS n=$n pkg=$pkg")
        return n
    }

    private fun hasListenerArg(m: Method): Boolean =
        m.parameterTypes.any { LocationListener::class.java.isAssignableFrom(it) }

    /**
     * 方案B 开关：serverLocation=true（默认）时坐标交付与 GNSS 抑制由 system_server
     * 负责（system_server 直读快照，不依赖应用能读到模块 provider），客户端交付类
     * 钩子一律透传，避免双重改写；false = 服务端停用，客户端恢复原行为。
     * 在**交付时**判断（不在注册时），运行中切换开关立即对已注册监听生效。
     * 本进程读不到配置（如微信）时 SnapshotStore 为 EMPTY、serverLocation 默认 true
     * → 透传，与服务端已完成的伪装一致。
     */
    private fun serverOwnsDelivery(): Boolean =
        dev.ven11.module.ipc.SnapshotStore.current().serverLocation

    private fun hasConsumerArg(m: Method): Boolean =
        m.parameterTypes.any { it == Consumer::class.java }

    /** 同名重载参数个数会重复，hook id 带完整参数签名保证唯一 */
    private fun sig(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

    // ---------------------------------------------------------------- 同步读取

    private fun installSync(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.sync.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                if (inSync.get() == true) return chain.proceed()
                inSync.set(true)
                val real = try {
                    chain.proceed()
                } finally {
                    inSync.remove()
                }
                // 方案B：交付由服务端负责时客户端透传
                if (serverOwnsDelivery()) return real
                val realLoc = real as? Location
                return when (val d = LocationFactory.decide(pkg, uid, providerArg(chain, realLoc), realLoc)) {
                    is Decision.Spoof -> { logHit(m.name, d.loc); d.loc }
                    Decision.PassThrough -> real
                    Decision.Block -> null
                }
            }
        })
    }

    private fun providerArg(chain: XposedInterface.Chain, real: Location?): String =
        chain.args.firstOrNull() as? String ?: real?.provider ?: "gps"

    // ---------------------------------------------------------------- getCurrentLocation

    private fun installCurrentLocation(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.current.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val args = chain.args.toMutableList()
                val provider = args.firstOrNull() as? String ?: "gps"
                var changed = false
                for (i in args.indices) {
                    val a = args[i]
                    if (a is Consumer<*> && a !is SpoofWrapper) {
                        @Suppress("UNCHECKED_CAST")
                        args[i] = SpoofConsumer(a as Consumer<Location?>, pkg, uid, provider)
                        changed = true
                    }
                }
                // 一次性回调：不入缓存，回调完成后随系统侧引用一起释放
                return if (changed) chain.proceed(args.toTypedArray()) else chain.proceed()
            }
        })
    }

    private inner class SpoofConsumer(
        private val orig: Consumer<Location?>,
        private val pkg: String,
        private val uid: Int,
        private val fallbackProvider: String,
    ) : Consumer<Location?>, SpoofWrapper {
        override fun accept(location: Location?) {
            // 方案B：交付由服务端负责时客户端原样转发
            if (serverOwnsDelivery()) {
                orig.accept(location)
                return
            }
            val out = when (val d = LocationFactory.decide(pkg, uid, location?.provider ?: fallbackProvider, location)) {
                is Decision.Spoof -> { logHit("consumer", d.loc); d.loc }
                Decision.PassThrough -> location
                Decision.Block -> null
            }
            orig.accept(out)
        }
    }

    // ---------------------------------------------------------------- requestLocationUpdates

    private fun installRequestListener(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.req.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val args = chain.args.toMutableList()
                val provider = args.firstOrNull() as? String ?: "gps"
                var changed = false
                for (i in args.indices) {
                    val a = args[i]
                    if (a is LocationListener && a !is SpoofWrapper) {
                        // 同一 listener 重复注册复用同一 wrapper，系统侧按同一 transport 更新；
                        // 弱值可能已被回收（系统结束的注册），此时重建
                        args[i] = synchronized(wrappers) {
                            wrappers[a]?.get() ?: SpoofListener(a, pkg, uid, provider)
                                .also { wrappers[a] = java.lang.ref.WeakReference(it) }
                        }
                        changed = true
                    }
                }
                return if (changed) chain.proceed(args.toTypedArray()) else chain.proceed()
            }
        })
    }

    /**
     * 应用 listener 的包装器。
     *
     * 引用关系（审查五 #6）：orig 由 wrapper **强引用**——不加钩时系统
     * （LocationManagerService 的 transport）就是强引用应用传入的 listener，
     * wrapper 必须维持同等强度，否则常见的不持引用写法（匿名 listener）在 GC 后
     * 会静默丢失全部回调。wrapper 的存活期 = 注册期（系统强引用 wrapper），
     * 映射条目由 removeUpdates 钩在注销成功后清理；应用弃置 listener 不注销时
     * 的驻留与不加钩时的系统侧强引用等价。
     */
    private inner class SpoofListener(
        private val orig: LocationListener,
        private val pkg: String,
        private val uid: Int,
        private val fallbackProvider: String,
    ) : LocationListener, SpoofWrapper {

        private fun map(location: Location): Location? {
            // 方案B：交付由服务端负责时客户端原样透传（不丢回调）
            if (serverOwnsDelivery()) return location
            return when (val d = LocationFactory.decide(pkg, uid, location.provider ?: fallbackProvider, location)) {
                is Decision.Spoof -> { logHit("listener", d.loc); d.loc }
                Decision.PassThrough -> location
                Decision.Block -> null
            }
        }

        override fun onLocationChanged(location: Location) {
            map(location)?.let { orig.onLocationChanged(it) }
        }

        /** API 31+ 批量回调：转交原对象的批量入口，保留应用自己的批量处理逻辑 */
        override fun onLocationChanged(locations: List<Location>) {
            val out = locations.mapNotNull { map(it) }
            if (out.isNotEmpty()) orig.onLocationChanged(out)
        }

        override fun onProviderEnabled(provider: String) { orig.onProviderEnabled(provider) }

        override fun onProviderDisabled(provider: String) { orig.onProviderDisabled(provider) }

        override fun onFlushComplete(requestCode: Int) { orig.onFlushComplete(requestCode) }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            orig.onStatusChanged(provider, status, extras)
        }
    }

    // ---------------------------------------------------------------- removeUpdates

    private fun installRemove(m: Method) {
        module.hook(m).setId("ven11.loc.remove.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val args = chain.args.toMutableList()
                // 只换参不删映射；系统注销确认成功后才清理（评审三轮 #4：
                // 先 remove 再 proceed，一旦 proceed 抛 SecurityException，
                // orig→wrapper 翻译就永久丢失，应用重试注销将失效）
                val swapped = ArrayList<Pair<LocationListener, SpoofListener>>()
                for (i in args.indices) {
                    val orig = args[i] as? LocationListener ?: continue
                    if (orig is SpoofWrapper) continue
                    val w = synchronized(wrappers) { wrappers[orig]?.get() } ?: continue
                    args[i] = w
                    swapped.add(orig to w)
                }
                return try {
                    if (swapped.isNotEmpty()) chain.proceed(args.toTypedArray())
                    else chain.proceed()
                } catch (t: Throwable) {
                    // 系统注销未确认：恢复映射，保留后续重试注销的翻译能力
                    // （期间同 orig 若被其他线程重新注册则保留新 wrapper）
                    synchronized(wrappers) {
                        swapped.forEach { (o, w) ->
                            if (!wrappers.containsKey(o)) wrappers[o] = java.lang.ref.WeakReference(w)
                        }
                    }
                    throw t
                }.also {
                    swapped.forEach { (o, _) -> synchronized(wrappers) { wrappers.remove(o) } }
                }
            }
        })
    }

    // ---------------------------------------------------------------- provider 查询（覆盖域扩展 1b）

    /**
     * proceed-first：权限异常照抛；域未启用/无环境时透传真实值。
     * isProviderEnabled：有环境时把主 provider（gps/fused）报为 enabled——
     * 避免应用"先查开关再定位"时被真实开关（如用户关闭定位）短路。
     */
    private fun installProviderQuery(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.prov.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val real = chain.proceed()
                return runCatching {
                    val eff = dev.ven11.module.ipc.PolicyResolver.resolve(pkg, uid)
                    if (!eff.domainEnabled(dev.ven11.module.ipc.PolicyResolver.Domain.LOCATION)) return real
                    if (eff.environment == null) return real
                    val provider = chain.args.firstOrNull() as? String ?: return real
                    val want = provider in listOf("gps", "fused", "network")
                    (if (want) true else real as? Boolean ?: real)
                }.getOrElse { real }
            }
        })
    }

    /** getProviders/getAllProviders：真实列表为底，补齐 gps/fused（去重，保持原顺序语义） */
    private fun installProviderList(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.provlist.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val real = chain.proceed()
                return runCatching {
                    val eff = dev.ven11.module.ipc.PolicyResolver.resolve(pkg, uid)
                    if (!eff.domainEnabled(dev.ven11.module.ipc.PolicyResolver.Domain.LOCATION)) return real
                    if (eff.environment == null) return real
                    val list = real as? List<*> ?: return real
                    val out = LinkedHashSet(list)
                    out.add("gps")
                    ArrayList(out)
                }.getOrElse { real }
            }
        })
    }

    /** getBestProvider：有环境且真实结果为 null 时兜底 "gps"（其余透传，不覆盖应用的 criteria 选择） */
    private fun installBestProvider(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.best.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val real = chain.proceed()
                return runCatching {
                    val eff = dev.ven11.module.ipc.PolicyResolver.resolve(pkg, uid)
                    if (!eff.domainEnabled(dev.ven11.module.ipc.PolicyResolver.Domain.LOCATION)) return real
                    if (eff.environment == null) return real
                    if (real == null) "gps" else real
                }.getOrElse { real }
            }
        })
    }

    // ---------------------------------------------------------------- GnssStatus 回调（覆盖域扩展 1a）

    /**
     * GnssStatus 无公开构造（合成需深反射隐藏 SatelliteInfo，高险低益），采用
     * "注册包装 + 交付抑制"策略：只要应用有环境伪装，卫星状态回调整体抑制
     * （卫星几何与真实天空一致，可反推真实位置——实测非严格模式也会被高德
     * 引擎利用）。onLocationChanged 不经此回调（走 listener 路径，已另行包装）。
     */
    private fun installGnssCallback(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.loc.gnss.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // 先装钩后注册（审查八 #3）：GNSS 活跃时注册路径可能同步交付首条状态；
                // 回调识别只用 chain.args，无需先 proceed
                val cb = chain.args.firstOrNull { it is android.location.GnssStatus.Callback }
                    ?: return chain.proceed()
                runCatching {
                    dev.ven11.module.hook.util.CallbackHooks.install(
                        module, "LOC-GNSS", cb, null,
                        methodNames = setOf("onSatelliteStatusChanged"),
                        baseFallback = android.location.GnssStatus.Callback::class.java,
                    ) { cbChain, _ ->
                        // 注意：这里必须用交付回调自己的 chain（cbChain）——外层的 chain
                        // 是 registerGnssStatusCallback 注册调用，注册在下方 proceed，
                        // 拿它 proceed 等于重放注册而非交付卫星状态
                        // 方案B：交付由服务端负责时客户端透传（服务端已按注册过滤卫星视图）
                        if (serverOwnsDelivery()) {
                            cbChain.proceed()
                        } else {
                            val eff = dev.ven11.module.ipc.PolicyResolver.resolve(pkg, uid)
                            if (eff.domainEnabled(dev.ven11.module.ipc.PolicyResolver.Domain.LOCATION) &&
                                eff.environment != null
                            ) {
                                null // 有环境伪装即抑制：卫星几何与真实天空一致，可反推真实位置
                            } else {
                                cbChain.proceed()
                            }
                        }
                    }
                }.onFailure { dev.ven11.module.ProbeLog.log("LOC-GNSS-FAIL $it") }
                // 原注册只执行一次，异常原样传播；客户端钩身份进程级固定
                return chain.proceed()
            }
        })
    }

    // ---------------------------------------------------------------- GnssMeasurement / NMEA 抑制

    /**
     * GnssMeasurement（原始伪距）与 NMEA（GGA/RMC 语句）都携带可直接解算的真实
     * 位置——LocationManager 定位对象的改写盖不住这条"应用自解算"路径（实测：
     * 高德引擎用原始测量值算出真实位置，无视被改写成环境坐标的定位对象）。
     * 两者**无条件抑制交付**：应用拿不到原始测量，回落到（已伪装的）
     * LocationManager 定位。与 GnssStatus（仅卫星视图、无位置、严格模式才抑制）
     * 不同，这两个通道是位置级泄漏面。
     */
    private fun installGnssMeasurementSuppress(m: Method) {
        module.hook(m).setId("ven11.loc.gnssmeas.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // 回调类型不在公开 SDK（GnssMeasurementsEvent$Callback 隐藏嵌套类），
                // 按类名鸭子匹配：GnssMeasurement / GpsMeasurement（遗留监听器），
                // 排除同前缀的 Request 请求参数
                val cb = chain.args.firstOrNull {
                    it != null && (it.javaClass.name.contains("GnssMeasurement") ||
                        it.javaClass.name.contains("GpsMeasurement")) &&
                        !it.javaClass.name.contains("Request")
                } ?: return chain.proceed()
                runCatching {
                    dev.ven11.module.hook.util.CallbackHooks.install(
                        module, "LOC-GNSSMEAS", cb, null,
                        methodNames = setOf("onGnssMeasurementsReceived", "onGpsMeasurementReceived"),
                    ) { cbChain, _ ->
                        // 抑制：原始测量值可解算真实位置；方案B 服务端负责时透传（服务端已按注册过滤）
                        if (serverOwnsDelivery()) cbChain.proceed() else null
                    }
                }.onFailure { dev.ven11.module.ProbeLog.log("LOC-GNSSMEAS-FAIL $it") }
                return chain.proceed()
            }
        })
    }

    /** 通用"注册包装 + 交付抑制"：按类名片段鸭子匹配回调参数，交付方法一律抑制 */
    private fun installCbSuppress(m: Method, tag: String, classFragment: String, vararg methodNames: String) {
        module.hook(m).setId("ven11.loc.$tag.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val cb = chain.args.firstOrNull {
                    it != null && it.javaClass.name.contains(classFragment) &&
                        !it.javaClass.name.contains("Request")
                } ?: return chain.proceed()
                runCatching {
                    dev.ven11.module.hook.util.CallbackHooks.install(
                        module, tag, cb, null,
                        methodNames = methodNames.toSet(),
                    ) { cbChain, _ ->
                        // 抑制：交付内容携带真实位置；方案B 服务端负责时透传（服务端已按注册过滤）
                        if (serverOwnsDelivery()) cbChain.proceed() else null
                    }
                }.onFailure { dev.ven11.module.ProbeLog.log("$tag-FAIL $it") }
                return chain.proceed()
            }
        })
    }

    private fun installNmeaSuppress(m: Method) {
        module.hook(m).setId("ven11.loc.nmea.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val cb = chain.args.firstOrNull {
                    it is android.location.OnNmeaMessageListener ||
                        it is android.location.GpsStatus.NmeaListener
                } ?: return chain.proceed()
                runCatching {
                    dev.ven11.module.hook.util.CallbackHooks.install(
                        module, "LOC-NMEA", cb, null,
                        // ColorOS 16 实测回调名为 onNmeaMessage（非 AOSP 的 onNmeaReceived）
                        methodNames = setOf("onNmeaReceived", "onNmeaMessage"),
                        baseFallback = android.location.OnNmeaMessageListener::class.java,
                    ) { cbChain, _ ->
                        // 抑制：NMEA GGA/RMC 语句携带真实经纬度；方案B 服务端负责时透传
                        if (serverOwnsDelivery()) cbChain.proceed() else null
                    }
                }.onFailure { dev.ven11.module.ProbeLog.log("LOC-NMEA-FAIL $it") }
                return chain.proceed()
            }
        })
    }

    // ---------------------------------------------------------------- 诊断

    private fun logHit(path: String, spoof: Location) {
        val n = hitCount.incrementAndGet()
        dev.ven11.module.ipc.StatusReporter.hookCount = n
        // 高频定位下节流：前 N 次全量，之后每 LOG_EVERY 次一条
        if (n <= LOG_FIRST_N || n % LOG_EVERY == 0) {
            dev.ven11.module.ProbeLog.log(
                String.format(
                    Locale.US, "LOC-SPOOF #%d path=%s lat=%.5f lon=%.5f acc=%.1f",
                    n, path, spoof.latitude, spoof.longitude, spoof.accuracy,
                )
            )
        }
    }

    private companion object {
        const val LOG_FIRST_N = 20
        const val LOG_EVERY = 50
    }
}