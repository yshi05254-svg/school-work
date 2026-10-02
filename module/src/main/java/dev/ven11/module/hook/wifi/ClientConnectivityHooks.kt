package dev.ven11.module.hook.wifi

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.hook.util.CallbackHooks
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.model.VirtualWifi
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Connectivity 域客户端钩（覆盖域扩展 2b）：API 31+ 应用获取已连接 WiFi 信息的
 * 主路径不在 WifiManager，而在 ConnectivityManager 的 NetworkCapabilities
 * TransportInfo。此前的覆盖缺口：轮询与回调两条路都拿真实 WiFi。
 *
 *  - getNetworkCapabilities(Network)：proceed 后检查 transport==WIFI 且
 *    TransportInfo 是可见 WifiInfo（未脱敏、已连接，语义同 WifiInfoSpoofer），
 *    原地改写为虚拟 AP 信息。binder 派发的 NC/WifiInfo 是调用方私有副本，改写安全。
 *  - registerNetworkCallback(NetworkRequest, NetworkCallback[, Handler]) /
 *    registerDefaultNetworkCallback(NetworkCallback[, Handler])：注册照常 proceed，
 *    对回调对象经 CallbackHooks 在其具体类（未覆写则基类）上改写
 *    onCapabilitiesChanged 的 NC 参数（同一改写语义）。
 *  - PendingIntent 变体（registerNetworkCallback(NR, PI)）不做：投递经系统
 *    PendingIntent 通道，客户端拦不到，留系统侧（评审覆盖计划 2b 备注）。
 *
 * 权限语义：真实 NC 的 WifiInfo 已被系统脱敏（bssid 占位/unknown ssid）或 NC 无
 * WIFI transport 时一律透传——不凭空造出"已连接 WiFi"状态。
 */
class ClientConnectivityHooks(private val module: XposedModule) {

    private val lastHit = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun install(cl: ClassLoader, pkg: String): Int {
        var n = 0
        val uid = android.os.Process.myUid()
        val cmClass = try {
            cl.loadClass("android.net.ConnectivityManager")
        } catch (_: Throwable) {
            return 0
        }
        val cbBase = try {
            cl.loadClass("android.net.ConnectivityManager\$NetworkCallback")
        } catch (_: Throwable) {
            null
        }
        for (m in cmClass.declaredMethods) {
            val isRegister = m.name == "registerNetworkCallback" &&
                m.parameterTypes.any { cbBase?.isAssignableFrom(it) == true }
            val isRegisterDefault = m.name == "registerDefaultNetworkCallback"
            val isGetCaps = m.name == "getNetworkCapabilities" && m.parameterTypes.size == 1
            when {
                isRegister -> {
                    installRegister(m, pkg, uid, cbBase); n++
                }
                isRegisterDefault -> {
                    installRegister(m, pkg, uid, cbBase); n++
                }
                isGetCaps -> {
                    installGetCaps(m, pkg, uid); n++
                }
            }
        }
        module.log(Log.INFO, "VEN11", "client connectivity hooks installed=$n pkg=$pkg")
        ProbeLog.log("CONN-HOOKS n=$n pkg=$pkg")
        return n
    }

    // -------------------------------------------------------------- getNetworkCapabilities

    private fun installGetCaps(m: Method, pkg: String, uid: Int) {
        module.hook(m).setId("ven11.conn.getcaps/${m.parameterTypes.size}")
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val real = chain.proceed()
                    return runCatching { spoofCaps(real, pkg, uid) }.getOrElse { real }
                }
            })
    }

    // -------------------------------------------------------------- register*

    private fun installRegister(m: Method, pkg: String, uid: Int, cbBase: Class<*>?) {
        module.hook(m).setId("ven11.conn.reg.${sig(m)}").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                // 先装钩后注册（审查八 #3）：系统可能在注册调用内同步回第一条
                // onCapabilitiesChanged（缓存网络命中时）；回调识别只用 chain.args
                val cb = chain.args.firstOrNull { arg ->
                    cbBase?.isInstance(arg) == true ||
                        arg.javaClass.name.contains("NetworkCallback")
                } ?: return chain.proceed()
                runCatching {
                    CallbackHooks.install(
                        module, "CONN-CB", cb, null,
                        methodNames = setOf("onCapabilitiesChanged"),
                        baseFallback = cbBase,
                    ) { chain, _ ->
                        // API 102 的 chain.args 返回不可变 List：先复制数组再改写
                        val newArgs = chain.args.toTypedArray()
                        val caps = newArgs.getOrNull(1)
                        if (caps != null) {
                            newArgs[1] = runCatching { spoofCaps(caps, pkg, uid) }
                                .getOrNull() ?: caps
                        }
                        chain.proceed(newArgs)
                    }
                }.onFailure { ProbeLog.log("CONN-CB-FAIL $it") }
                // 原注册只执行一次，异常原样传播；客户端钩身份进程级固定
                return chain.proceed()
            }
        })
    }

    // -------------------------------------------------------------- 改写核心

    /**
     * WIFI transport + 可见（未脱敏/已连接）→ 原地改写 TransportInfo 的 WifiInfo
     * 为环境首项（与 getConnectionInfo 同一"首项=当前连接"约定）。
     * 其余情况原样返回（不注入、不造连接状态）。
     *
     * 返回值必须是 [NetworkCapabilities] 本身（本方法同时服务 getNetworkCapabilities
     * 返回值与 onCapabilitiesChanged 参数两条路；WifiInfoSpoofer.spoof 是原地改写，
     * 返回的 WifiInfo 只用于确认改写发生，不能替代 NC 返回给调用方）。
     */
    private fun spoofCaps(real: Any?, pkg: String, uid: Int): Any? {
        val caps = real as? NetworkCapabilities ?: return real
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.WIFI)) return caps
        val env = eff.environment ?: return caps
        if (env.wifis.isEmpty()) return caps
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return caps
        val info = caps.transportInfo as? WifiInfo ?: return caps
        if (WifiInfoSpoofer.isRedacted(info)) return caps
        if (!WifiInfoSpoofer.isConnected(info)) return caps
        val w: VirtualWifi = env.wifis.first()
        hit("conn ssid=${w.ssid}")
        // 原地改写后必须返回 caps 本身；此前 return spoof(info, w) 返回的是
        // WifiInfo，调用方按 NetworkCapabilities 接住会 ClassCastException
        WifiInfoSpoofer.spoof(info, w)
        return caps
    }

    private fun sig(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

    /** 限流日志：onCapabilitiesChanged 在网络切换时可高频 */
    private fun hit(msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastHit.put("conn", now) ?: 0L
        if (now - prev >= 30_000L) ProbeLog.log("CONN-SPOOF $msg")
    }
}
