package dev.ven11.module.hook.framework

import android.net.wifi.ScanResult
import android.os.Binder
import android.os.Process
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.hook.wifi.ScanResultFactory
import dev.ven11.module.hook.wifi.WifiPermissionGate
import dev.ven11.module.ipc.PolicyResolver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 系统进程桥：按进程分发框架侧钩子（Ven11Module 调用）。
 *  - system_server → FrameworkWifiHooks（WifiServiceImpl 在 12+ 随 Wi-Fi mainline 模块
 *    加载，见 loadServiceImpl 的 classloader 说明）
 *  - com.android.phone → FrameworkCellHooks（PhoneInterfaceManager，独立于本文件）
 */
object SystemServiceBridge {

    fun installInSystemServer(module: XposedModule, cl: ClassLoader): Int =
        FrameworkWifiHooks.install(module, cl)
}

/**
 * 系统侧 WiFi 钩（system_server 内 WifiServiceImpl）：
 *  - getScanResults：按 Binder 调用方 uid 决策替换；返回值按被 hook 方法自身的返回类型
 *    包装——List 直接返回，slice 型按 returnType 反射构造（天然兼容 Wi-Fi Mainline
 *    jarjar 改名后的 ParceledListSlice），构造失败回退真实值
 *  - getConnectionInfo 系统侧改写（跨 user 校验复杂，后续里程碑）未实现：不 hook、
 *    纯透传，避免每次调用空跑 pkgOfUid/resolve
 *  - 调用方放行条件：appId < FIRST_APPLICATION_UID（多用户下系统 uid 形如 1010000+，
 *    不能用 uid<=2000 一刀切）；无包名 uid（isolated / SDK sandbox）放行
 *
 * uid → 包名统一委托 [UidResolver]（评审四：原先 FrameworkWifiHooks 自带一份
 * TTL 60s 的 pkgOfUid，与 UidResolver 的 10s 版本重复实现，热路径两套缓存）。
 */
object FrameworkWifiHooks {

    fun install(module: XposedModule, cl: ClassLoader): Int {
        val implClass = loadServiceImpl(cl)
        if (implClass == null) {
            // 不静默：Android 12+ WifiServiceImpl 随 Wi-Fi mainline APEX 加载，
            // system_server 的 boot classloader 未必直接可见——真机验证时按此日志排查
            ProbeLog.log("FW-WIFI WifiServiceImpl not found in any candidate classloader")
            module.log(Log.WARN, "VEN11", "FW-WIFI WifiServiceImpl not found (mainline CL?)")
            return 0
        }
        var n = 0
        for (m in implClass.declaredMethods) {
            if (m.name != "getScanResults") continue
            hookScan(module, m)
            n++
        }
        module.log(Log.INFO, "VEN11", "framework wifi hooks installed=$n")
        return n
    }

    /**
     * WifiServiceImpl 类定位（评审三：Android 12+ Wi-Fi 栈在 mainline 模块里）：
     * 依次尝试 ①进程 classloader ②线程上下文 classloader ③系统 classloader，
     * 并额外尝试 GMS Wi-Fi 模块的 com.google.android.wifi 命名空间。
     * 任一命中即用；全部失败返回 null（调用方记日志，不装钩）。
     * ⚠ 需真机验证：不同 ROM 的模块 classloader 挂载点不一致，必要时改为
     * 遍历 APEX 目录构造 URLClassLoader。
     */
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
        val pkg = UidResolver.pkgOfUid(uid) ?: return real // isolated / SDK sandbox 等无包名 uid：放行
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
}
