package dev.ven11.module.hook.framework

import android.os.Binder
import android.os.Process
import android.net.wifi.ScanResult
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.hook.framework.location.ServerLocationHooks
import dev.ven11.module.hook.wifi.ScanResultFactory
import dev.ven11.module.hook.wifi.WifiInfoSpoofer
import dev.ven11.module.hook.wifi.WifiPermissionGate
import dev.ven11.module.ipc.PolicyResolver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 系统进程桥：按进程分发框架侧钩子（Ven11Module 调用）。
 *
 * ⚠ 回滚记录：曾在此处增加 ServiceManager.addService 捕获 + onModuleLoaded
 * 提前挂载（P1-2），在工程机上破坏了 Vector 的应用注入链（仅 system_server
 * 加载、所有应用进程不再注入），已整体回滚。
 *
 * WifiServiceImpl 定位：立即路径按 main classloader 三级候选加载（≤R 命中）；
 * Android 12+ 的 service-wifi.jar 由 SystemServiceManager.startServiceFromJar
 * 在 startOtherServices 后段才以新建 PathClassLoader 加载（审查七 #1b），
 * 延迟路径钩该加载点、在 WifiService 实例创建后用其 classloader 补装。
 * 两条路径经 FrameworkWifiHooks 内 CAS 去重，进程内只装一次。
 */
object SystemServiceBridge {

    fun installInSystemServer(module: XposedModule, cl: ClassLoader): Int {
        // 立即路径：WifiServiceImpl 在主 classloader 的 ROM（≤R / 部分厂商 ROM）
        val n = FrameworkWifiHooks.install(module, cl)
        // 延迟路径：Android 12+ 的 service-wifi.jar 由 SystemServiceManager 在
        // startOtherServices 后段用新建的 PathClassLoader 加载——onSystemServerStarting
        // 时该 classloader 还不存在，立即路径必然 not found（审查七 #1b）
        installDeferredWifiHooks(module, cl)
        // 方案B：服务端定位伪装（定位类都在主 services.jar，开机即可加载；
        // 独立 runCatching，WiFi 链路任何问题不影响定位钩安装，反之亦然）
        runCatching {
            val m = ServerLocationHooks.install(module, cl)
            if (m >= 0) ProbeLog.log("SRVLOC-BRIDGE armed=$m")
        }.onFailure {
            ProbeLog.log("SRVLOC-BRIDGE-ERR ${it.javaClass.simpleName}: ${it.message}")
        }
        return n
    }

    /**
     * 钩 SystemServiceManager.startServiceFromJar（它在主 services.jar / boot 路径上，
     * onSystemServerStarting 时已可加载）：交付结果是 com.android.server.wifi.* 的
     * SystemService 实例时，取实例的 classloader（service-wifi.jar 的 PathClassLoader）
     * 补装钩子。进程内只装一次（FrameworkWifiHooks 内 CAS 去重）；
     * ROM 没有 startServiceFromJar（≤R）时立即路径已覆盖，这里静默跳过。
     */
    private fun installDeferredWifiHooks(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val ssm = cl.loadClass("com.android.server.SystemServiceManager")
            var armed = 0
            for (m in ssm.declaredMethods) {
                if (m.name != "startServiceFromJar") continue
                module.hook(m).setId("ven11.fwwifi.svcjar/${m.parameterTypes.size}")
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            // proceed 至多一次；决策链任何异常不得外溢到 system_server
                            val result = chain.proceed()
                            runCatching {
                                val svc = result ?: return@runCatching
                                if (!svc.javaClass.name.startsWith("com.android.server.wifi.")) {
                                    return@runCatching
                                }
                                val svcCl = svc.javaClass.classLoader ?: return@runCatching
                                // 结果分级（审查八 #7）：成功记一次；已装过静默；
                                // service jar 里仍缺类是真问题，保留 WARN
                                when (val n = FrameworkWifiHooks.install(module, svcCl)) {
                                    -1 -> Unit
                                    0 -> module.log(Log.WARN, "VEN11",
                                        "FW-WIFI service jar loader 仍找不到 WifiServiceImpl")
                                    else -> module.log(Log.INFO, "VEN11",
                                        "framework wifi hooks deferred-installed=$n from service jar " +
                                            "(cl=${svcCl.javaClass.name})")
                                }
                            }.onFailure {
                                ProbeLog.log("FW-WIFI-DEFER-ERR ${it.javaClass.simpleName}: ${it.message}")
                            }
                            return result
                        }
                    })
                armed++
            }
            module.log(Log.INFO, "VEN11", "framework wifi deferred path armed=$armed (startServiceFromJar)")
        }.onFailure {
            ProbeLog.log("FW-WIFI-DEFER skip: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}

/**
 * 系统侧 WiFi 钩（system_server 内 WifiServiceImpl）：
 *  - getScanResults：按 Binder 调用方 uid 决策替换；返回值按被 hook 方法自身的返回类型
 *    包装——List 直接返回，slice 型按 returnType 反射构造（兼容 Mainline jarjar 改名），
 *    构造失败回退真实值
 *  - getConnectionInfo：与客户端同语义（真实结果已脱敏/未连接时透传）
 *  - 调用方放行条件：appId < FIRST_APPLICATION_UID；无包名 uid（isolated / SDK
 *    sandbox）放行
 *  - uid → 包名统一委托 [UidResolver]
 */
object FrameworkWifiHooks {

    /** 立即路径与 startServiceFromJar 延迟路径都会调 install：进程内只装一次 */
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** "主 classloader 没有实现类"的兼容提示只记一次（S+ 上这是预期状态） */
    private val classMissOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 返回值区分结果（审查八 #7）：>0 本次安装成功；0 目标类缺失（兼容性提示）；
     * -1 已安装过（重复进入，调用方静默）；装钩异常由调用方 runCatching 记录原因。
     */
    fun install(module: XposedModule, cl: ClassLoader): Int {
        val implClass = loadServiceImpl(cl)
        if (implClass == null) {
            if (classMissOnce.compareAndSet(false, true)) {
                ProbeLog.log("FW-WIFI WifiServiceImpl not found in candidate classloaders")
                module.log(Log.INFO, "VEN11",
                    "FW-WIFI WifiServiceImpl not in main classloader (S+: 待 startServiceFromJar 延迟路径)")
            }
            return 0
        }
        return hookImplClass(module, implClass)
    }

    private fun hookImplClass(module: XposedModule, implClass: Class<*>): Int {
        // CAS 在装钩前取位：implClass 已定位到，后续 hook 失败属 ROM 异常，
        // 不为此保留重试（避免延迟路径重复装钩）
        if (!installed.compareAndSet(false, true)) return -1
        var n = 0
        for (m in implClass.declaredMethods) {
            when (m.name) {
                "getScanResults" -> { hookScan(module, m); n++ }
                "getConnectionInfo" -> { hookConnectionInfo(module, m); n++ }
            }
        }
        module.log(Log.INFO, "VEN11", "framework wifi hooks installed=$n (${implClass.name})")
        return n
    }

    private fun loadServiceImpl(cl: ClassLoader): Class<*>? {
        val candidates: List<ClassLoader> = buildList {
            add(cl)
            addAll(listOfNotNull(Thread.currentThread().contextClassLoader))
            addAll(listOfNotNull(ClassLoader.getSystemClassLoader()))
        }.distinct()

        for (loader in candidates) {
            for (name in SERVICE_NAMES) {
                try {
                    return loader.loadClass(name)
                } catch (_: Throwable) {
                    // 下一个候选
                }
            }
        }
        return null
    }

    private val SERVICE_NAMES = listOf(
        "com.android.server.wifi.WifiServiceImpl",     // AOSP / com.android.wifi APEX
        "com.google.android.wifi.internal.WifiServiceImpl", // GMS Wi-Fi 模块历史命名
    )

    private fun hookScan(module: XposedModule, m: Method) {
        // id 带参数个数，防同名重载撞 id
        module.hook(m).setId("ven11.fwwifi.scan/${m.parameterTypes.size}")
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 调用身份在 proceed 前取，不依赖被钩方法是否正确恢复调用身份
                    val callingUid = Binder.getCallingUid()
                    val callingPid = Binder.getCallingPid()
                    val real = chain.proceed()
                    // 决策链异常不能经 binder 穿给调用方
                    return runCatching { spoofScan(m, callingPid, callingUid, real) }
                        .getOrElse { real }
                }
            })
    }

    private fun spoofScan(m: Method, pid: Int, uid: Int, real: Any?): Any? {
        if (uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return real
        // 权限脱敏：真机上该调用方本就看不到扫描列表，放行真实（空）结果
        if (!WifiPermissionGate.canSeeScanResults(pid, uid, UidResolver.systemContext())) return real
        val pkg = UidResolver.pkgOfUid(uid) ?: return real  // isolated / SDK sandbox 等无包名 uid：放行
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.WIFI)) return real
        val env = eff.environment ?: return real
        if (env.wifis.isEmpty()) return real
        val out = ScanResultFactory.build(env.wifis)
        ProbeLog.log("FW-WIFI-SPOOF uid=$uid scan n=${out.size}")
        return wrapResult(m, real, out)
    }

    /** 以被 hook 方法自身的返回类型为准：List 直接返回；slice 型按 returnType 反射构造（兼容 Mainline 改名） */
    private fun wrapResult(m: Method, real: Any?, list: List<ScanResult>): Any? {
        val rt = m.returnType
        if (List::class.java.isAssignableFrom(rt)) return list
        return try {
            rt.getDeclaredConstructor(List::class.java)
                .apply { isAccessible = true }.newInstance(list)
        } catch (_: Throwable) {
            real  // 类型不符/构造失败回退真实值，不能把 ClassCastException 穿给调用方
        }
    }

    /**
     * WifiServiceImpl.getConnectionInfo（覆盖域扩展 5b）：与客户端同语义——
     * 真实结果已脱敏/未连接时透传；有可见权限视角且已连接时改写 WifiInfo 字段。
     * system_server 内 WifiInfo 可能被框架缓存共享，改写前反射拷贝（拷贝不可用放弃）。
     */
    private fun hookConnectionInfo(module: XposedModule, m: Method) {
        module.hook(m).setId("ven11.fwwifi.conn/${m.parameterTypes.size}")
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val callingUid = Binder.getCallingUid()
                    val callingPid = Binder.getCallingPid()
                    val real = chain.proceed()
                    return runCatching { spoofConn(callingPid, callingUid, real) }
                        .getOrElse { real }
                }
            })
    }

    private fun spoofConn(pid: Int, uid: Int, real: Any?): Any? {
        if (uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return real
        val info = real as? android.net.wifi.WifiInfo ?: return real
        if (WifiInfoSpoofer.isRedacted(info)) return real
        if (!WifiInfoSpoofer.isConnected(info)) return real
        // 同进程共享对象：改写共享 WifiInfo 会污染系统缓存，构造私有副本
        val copy = runCatching {
            val ctor = info.javaClass.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == info.javaClass
            }?.apply { isAccessible = true }
            ctor?.newInstance(info) as? android.net.wifi.WifiInfo
        }.getOrNull() ?: return real
        val pkg = UidResolver.pkgOfUid(uid) ?: return real
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.WIFI)) return real
        val env = eff.environment ?: return real
        val w = env.wifis.firstOrNull() ?: return real
        ProbeLog.log("FW-WIFI-CONN ssid=${w.ssid}")
        return WifiInfoSpoofer.spoof(copy, w)
    }
}
