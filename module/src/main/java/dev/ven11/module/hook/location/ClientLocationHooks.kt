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
     * 弱键 + SpoofListener 弱引用 orig（评审四/三轮 #4）：键值互不强引用，无环——
     * 应用释放 orig（未调 removeUpdates，如监听器随 Activity 销毁）后条目自然失效，
     * 存活 wrapper 的回调经弱引用取不到 orig 即静默丢弃。
     */
    private val wrappers = synchronizedMap(WeakHashMap<LocationListener, SpoofListener>())

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
            }
        }
        module.log(Log.INFO, "VEN11", "client location hooks installed=$n pkg=$pkg")
        dev.ven11.module.ProbeLog.log("LOC-HOOKS n=$n pkg=$pkg")
        return n
    }

    private fun hasListenerArg(m: Method): Boolean =
        m.parameterTypes.any { LocationListener::class.java.isAssignableFrom(it) }

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
                        // 同一 listener 重复注册复用同一 wrapper，系统侧按同一 transport 更新
                        args[i] = synchronized(wrappers) {
                            wrappers.getOrPut(a) { SpoofListener(a, pkg, uid, provider) }
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
     * 引用关系（评审三轮 #4）：orig 只以 [WeakReference] 持有——此前 orig 强引用 +
     * WeakHashMap 键的组合形成"值强引用键"的环，弱键永不回收，泄漏依旧。断环后：
     * 应用侧释放 orig（如监听器随 Activity 销毁且未 removeUpdates）→ orig 可回收 →
     * 映射条目失效；系统侧仍持有的 wrapper 回调变 no-op（orig 弱引用取不到即丢弃），
     * 语义等价于"监听器已死"。orig 存活期间（应用正常持有）委托行为不变。
     */
    private inner class SpoofListener(
        orig: LocationListener,
        private val pkg: String,
        private val uid: Int,
        private val fallbackProvider: String,
    ) : LocationListener, SpoofWrapper {

        private val origRef = java.lang.ref.WeakReference(orig)

        private fun orig(): LocationListener? = origRef.get()

        private fun map(location: Location): Location? =
            when (val d = LocationFactory.decide(pkg, uid, location.provider ?: fallbackProvider, location)) {
                is Decision.Spoof -> { logHit("listener", d.loc); d.loc }
                Decision.PassThrough -> location
                Decision.Block -> null
            }

        override fun onLocationChanged(location: Location) {
            orig()?.let { o -> map(location)?.let { o.onLocationChanged(it) } }
        }

        /** API 31+ 批量回调：转交原对象的批量入口，保留应用自己的批量处理逻辑 */
        override fun onLocationChanged(locations: List<Location>) {
            val o = orig() ?: return
            val out = locations.mapNotNull { map(it) }
            if (out.isNotEmpty()) o.onLocationChanged(out)
        }

        override fun onProviderEnabled(provider: String) { orig()?.onProviderEnabled(provider) }

        override fun onProviderDisabled(provider: String) { orig()?.onProviderDisabled(provider) }

        override fun onFlushComplete(requestCode: Int) { orig()?.onFlushComplete(requestCode) }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            orig()?.onStatusChanged(provider, status, extras)
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
                    val w = synchronized(wrappers) { wrappers[orig] } ?: continue
                    args[i] = w
                    swapped.add(orig to w)
                }
                return try {
                    if (swapped.isNotEmpty()) chain.proceed(args.toTypedArray())
                    else chain.proceed()
                } catch (t: Throwable) {
                    // 系统注销未确认：恢复映射，保留后续重试注销的翻译能力
                    // （getOrPut：期间同 orig 若被其他线程重新注册则保留新 wrapper）
                    synchronized(wrappers) {
                        swapped.forEach { (o, w) -> wrappers.getOrPut(o) { w } }
                    }
                    throw t
                }.also {
                    swapped.forEach { (o, _) -> synchronized(wrappers) { wrappers.remove(o) } }
                }
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