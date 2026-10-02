package dev.ven11.module.hook.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.model.VirtualWifi
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * WifiInfo 改写器（覆盖域扩展共用：ClientWifiHooks / ClientConnectivityHooks /
 * 框架侧 getConnectionInfo 同一语义）。binder 派发的 WifiInfo 是调用方私有副本，
 * 原地改写安全；框架侧同进程共享对象不适用（各调用点自行判断）。
 */
object WifiInfoSpoofer {

    private const val REDACTED_MAC = "02:00:00:00:00:00"

    /** 真实结果已被系统脱敏（无权限视角）→ 保持脱敏不替换 */
    fun isRedacted(info: WifiInfo): Boolean =
        info.bssid == REDACTED_MAC || info.ssid == WifiManager.UNKNOWN_SSID

    /**
     * 未连接（supplicant 未完成）→ 不改写，避免矛盾状态。
     * networkId 在 S+ 上对普通应用可能被系统脱敏为 -1（即使已连接且 SSID/BSSID
     * 可见），所以 networkId=-1 不能直接判"未连接"——调用方先经 [isRedacted]
     * 过滤过脱敏视角，这里以 supplicant COMPLETED 为主判据，networkId 作旁证。
     */
    fun isConnected(info: WifiInfo): Boolean =
        info.supplicantState == SupplicantState.COMPLETED &&
            (info.networkId != -1 || info.ssid != WifiManager.UNKNOWN_SSID)

    /**
     * 反射改写 mWifiSsid/mBSSID/mRssi/mFrequency；WifiSsid 构造失败则整体放弃
     * 改写（避免半伪造状态）。
     */
    fun spoof(info: WifiInfo, w: VirtualWifi): WifiInfo {
        val ssidObj = ScanResultFactory.wifiSsidOf(w.ssid) ?: return info
        setField(info, "mWifiSsid", ssidObj)
        setField(info, "mBSSID", w.bssid)
        setField(info, "mRssi", w.signalDbm)
        setField(info, "mFrequency", w.frequencyMhz)  // 频率与 BSSID 保持对应
        return info
    }

    private fun setField(target: Any, name: String, value: Any) {
        try {
            val f = target.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(target, value)
        } catch (t: Throwable) {
            ProbeLog.log("WIFI-CONN set $name failed: $t")
        }
    }
}

/**
 * 客户端 WiFi 钩（目标应用进程）：
 *  - getScanResults()：整表替换为虚拟扫描列表（ScanResultFactory 统一构造，字段与时间基准只此一处维护）
 *  - getConnectionInfo()：仅当真实状态"已连接且未被权限脱敏"时改写
 *    mWifiSsid/mBSSID/mRssi/mFrequency；否则返回真实值
 *  - isWifiEnabled / getConfiguredNetworks 透传（连接状态真实，符合"连接信息模拟"语义边界）
 *  - 权限边界：无 ACCESS_FINE_LOCATION / NEARBY_WIFI_DEVICES 权限时真机本就只能看到
 *    空列表、<unknown ssid> 与 02:00:00:00:00:00，虚拟环境保持同样边界，不注入数据
 *  - 语义边界（本钩未覆盖，后续里程碑）：NetworkCapabilities.getTransportInfo() 走
 *    ConnectivityService，不在覆盖范围；policy.wifiEnabled 未参与决策，可能出现
 *    "Wi-Fi 关闭但仍可扫描"的组合
 *  - 约定：环境 wifis 首项视为当前连接的网络；模型增加 connected 字段后应改为显式标记
 */
class ClientWifiHooks(private val module: XposedModule) {

    private val hitLogMinIntervalMs = 30_000L

    @Volatile
    private var lastHitAtMs = 0L

    private var cachedAppContext: Context? = null

    fun install(cl: ClassLoader, pkg: String): Int {
        var n = 0
        val uid = android.os.Process.myUid()
        val wmClass = try {
            cl.loadClass("android.net.wifi.WifiManager")
        } catch (_: Throwable) {
            return 0
        }
        for (m in wmClass.declaredMethods) {
            val target = when {
                m.name == "getScanResults" && m.parameterTypes.isEmpty() -> "scan"
                m.name == "getConnectionInfo" && m.parameterTypes.isEmpty() -> "conn"
                else -> null
            } ?: continue
            // id 带参数个数，防同名重载撞 id
            module.hook(m).setId("ven11.wifi.$target/${m.parameterTypes.size}")
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 先 proceed：被钩方法自身的异常原样透传，真实结果作为所有兜底分支的返回值
                        val real = chain.proceed()
                        // 决策链任何异常都不能外溢到目标应用
                        return runCatching { spoof(target, pkg, uid, real) }.getOrElse { real }
                    }
                })
            n++
        }
        module.log(Log.INFO, "VEN11", "client wifi hooks installed=$n pkg=$pkg")
        ProbeLog.log("WIFI-HOOKS n=$n pkg=$pkg")
        return n
    }

    private fun spoof(target: String, pkg: String, uid: Int, real: Any?): Any? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.WIFI)) return real
        val env = eff.environment ?: return real
        if (env.wifis.isEmpty()) return real
        return when (target) {
            "scan" -> {
                // 权限脱敏：无权限时真机拿到的就是空列表，虚拟环境同样不注入
                if (!WifiPermissionGate.selfCanSeeScanResults(appContext())) return real
                val out = ScanResultFactory.build(env.wifis)
                hit("wifi-scan n=${out.size}")
                out
            }
            else -> {
                val info = real as? WifiInfo ?: return real
                // 真实结果已被系统脱敏（无权限视角）时保持脱敏，不替换
                if (WifiInfoSpoofer.isRedacted(info)) return real
                // 未连接时不改写，避免"未连接却有 SSID/BSSID"的矛盾状态
                if (!WifiInfoSpoofer.isConnected(info)) return real
                val w = env.wifis.first()
                hit("wifi-conn ssid=${w.ssid}")
                WifiInfoSpoofer.spoof(info, w)
            }
        }
    }

    private fun appContext(): Context? {
        cachedAppContext?.let { return it }
        val ctx = try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
        if (ctx != null) cachedAppContext = ctx
        return ctx
    }

    /** 限流日志：应用轮询扫描时避免刷屏——首次命中立即记录，其后每 30s 至多一条 */
    private fun hit(msg: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (lastHitAtMs != 0L && now - lastHitAtMs < hitLogMinIntervalMs) return
            lastHitAtMs = now
        }
        ProbeLog.log("WIFI-SPOOF $msg")
    }
}

/** VirtualWifi → ScanResult 的唯一构造点（客户端/系统侧共用） */
object ScanResultFactory {

    /** timestamp 语义是"开机以来的微秒数"；用墙钟会被应用按 elapsedRealtime 判为过期负值 */
    fun build(list: List<VirtualWifi>): ArrayList<ScanResult> {
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val out = ArrayList<ScanResult>(list.size)
        for (w in list) {
            val sr = newScanResult() ?: continue  // 失败已在内部记日志
            try {
                sr.SSID = w.ssid
                sr.BSSID = w.bssid
                sr.capabilities = w.capabilities
                sr.level = w.signalDbm
                sr.frequency = w.frequencyMhz
                sr.timestamp = nowUs
                // 频宽形态（覆盖域扩展 2a）：保守取 20MHz + centerFreq0=frequency——
                // 与 capabilities 不含 40PLUS/80 标记的自洽形态；centerFreq1 仅 80+80 用
                sr.channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ
                sr.centerFreq0 = w.frequencyMhz
                sr.centerFreq1 = 0
                // API 33+ getWifiSsid() 读隐藏字段 wifiSsid，只设 SSID 时它返回 null；低版本无此字段，失败忽略
                wifiSsidOf(w.ssid)?.let {
                    try {
                        val f = ScanResult::class.java.getDeclaredField("wifiSsid")
                        f.isAccessible = true
                        f.set(sr, it)
                    } catch (_: Throwable) {
                    }
                }
                out.add(sr)
            } catch (t: Throwable) {
                ProbeLog.log("WIFI-SCANFACT fill failed ssid=${w.ssid}: $t")
            }
        }
        return out
    }

    /**
     * SSID → WifiSsid：API 33+ 优先公开的 fromBytes（裸字节，无引号）；低版本退回
     * 隐藏 fromString（带引号 UTF-8 形式）与 createFromAsciiEncoded；都失败返回 null。
     */
    fun wifiSsidOf(ssid: String): WifiSsid? {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                return WifiSsid::class.java
                    .getMethod("fromBytes", ByteArray::class.java)
                    .invoke(null, ssid.toByteArray(Charsets.UTF_8)) as? WifiSsid
            } catch (_: Throwable) {
            }
        }
        return try {
            WifiSsid::class.java.getDeclaredMethod("fromString", String::class.java)
                .invoke(null, "\"$ssid\"") as? WifiSsid
        } catch (_: Throwable) {
            try {
                WifiSsid::class.java.getDeclaredMethod("createFromAsciiEncoded", String::class.java)
                    .invoke(null, ssid) as? WifiSsid
            } catch (_: Throwable) {
                null
            }
        }
    }

    /** 无参构造在 API 30 前是隐藏 API，直接 new 失败时反射兜底 */
    private fun newScanResult(): ScanResult? = try {
        ScanResult()
    } catch (_: Throwable) {
        try {
            ScanResult::class.java.getDeclaredConstructor()
                .apply { isAccessible = true }.newInstance()
        } catch (t: Throwable) {
            ProbeLog.log("WIFI-SCANFACT new ScanResult failed: $t")
            null
        }
    }
}

/**
 * getScanResults 的权限门：模拟系统对该 API 的权限脱敏语义。
 *
 * 判据（评审三轮 #2）：getScanResults() 在所有版本上都要求 ACCESS_FINE_LOCATION
 * 且定位服务开启（Android 13+ 亦然——NEARBY_WIFI_DEVICES 不是它的替代品，官方文档
 * 明确该 API 仍有独立的定位权限要求并受定位开关影响）。因此本门不看 NEARBY：
 *  - 判"无权可见"时只放行真实（已被系统脱敏的）结果，绝不注入虚拟数据——保守侧；
 *  - 应用带 NEARBY_WIFI_DEVICES + neverForLocation 时真机可能在定位关闭下看到结果，
 *    本门仍判不可见 → 放行真实结果（不注入、不伪造，只是少一个伪装点，安全侧）。
 * 权限名用字符串字面量，避免低编译 SDK 常量缺失。
 */
object WifiPermissionGate {
    private const val FINE = "android.permission.ACCESS_FINE_LOCATION"

    /** 应用进程侧：判定目标应用自身是否有权看到扫描列表 */
    fun selfCanSeeScanResults(ctx: Context?): Boolean {
        if (ctx == null) return false
        if (ctx.checkSelfPermission(FINE) != PackageManager.PERMISSION_GRANTED) return false
        return locationEnabled(ctx)
    }

    /** 系统侧：按 Binder 调用方 (pid, uid) 判定；systemCtx 取不到时按无权限处理（放行真实结果，安全侧） */
    fun canSeeScanResults(pid: Int, uid: Int, systemCtx: Context?): Boolean {
        val ctx = systemCtx ?: return false
        if (ctx.checkPermission(FINE, pid, uid) != PackageManager.PERMISSION_GRANTED) return false
        return locationEnabledFor(pid, uid, ctx)
    }

    /** 定位总开关开启才可见（与真机一致）；查询失败按不可见处理（不注入，安全侧） */
    private fun locationEnabled(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        lm.isLocationEnabled
    } catch (_: Throwable) {
        false
    }

    /** 系统侧定位开关按调用方查询；查询失败按不可见处理 */
    private fun locationEnabledFor(pid: Int, uid: Int, ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        // isLocationEnabledForUser(UserHandle) 是隐藏 API（公开 SDK 无此方法）：
        // 反射按调用方所属 user 查询，反射不可用退回本进程视角
        val handle = UserHandle.getUserHandleForUid(uid)
        val viaReflection = runCatching {
            LocationManager::class.java
                .getMethod("isLocationEnabledForUser", UserHandle::class.java)
                .invoke(lm, handle) as? Boolean
        }.getOrNull()
        viaReflection ?: lm.isLocationEnabled
    } catch (_: Throwable) {
        false
    }
}