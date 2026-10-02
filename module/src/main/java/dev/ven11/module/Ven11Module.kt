package dev.ven11.module

import android.util.Log
import android.os.Process
import dev.ven11.module.ProbeLog
import dev.ven11.module.hook.bluetooth.ClientBluetoothHooks
import dev.ven11.module.hook.cell.CellInfoFactory
import dev.ven11.module.hook.cell.ClientCellHooks
import dev.ven11.module.hook.cell.ClientTelephonyListenerHooks
import dev.ven11.module.hook.framework.FrameworkCellHooks
import dev.ven11.module.hook.framework.SystemServiceBridge
import dev.ven11.module.hook.location.ClientLocationHooks
import dev.ven11.module.hook.regional.ClientLanguageHooks
import dev.ven11.module.hook.regional.ClientTimezoneHooks
import dev.ven11.module.hook.sim.ClientSimHooks
import dev.ven11.module.hook.wifi.ClientConnectivityHooks
import dev.ven11.module.hook.wifi.ClientWifiHooks
import dev.ven11.module.ipc.SnapshotStore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口（META-INF/xposed/java_init.list 指向本类）。
 *
 * libxposed API 102 实际契约（以 jar 内签名为准）：
 *  - 入口类无参构造；生命周期回调 onModuleLoaded / onPackageLoaded / onSystemServerStarting；
 *  - PackageLoadedParam 提供 packageName / isFirstPackage / defaultClassLoader，
 *    不含 processName——phone 进程以 packageName == "com.android.phone" 识别。
 *  - 作用域（META-INF/xposed/scope.list）：system_server 用虚拟包名 system 表示；
 *    勾选 android（旧版管理器的"系统框架"）只会注入 android 包的 :ui 进程，
 *    不会触发 onSystemServerStarting（审查七）。
 *
 * 进程分发：
 *  - system_server → 框架侧 WiFi 钩（WifiServiceImpl；S+ 的 service-wifi.jar
 *    classloader 不存在时由 SystemServiceBridge 的 startServiceFromJar 延迟路径补装）
 *  - com.android.phone → 框架侧基站钩（com.android.phone.PhoneInterfaceManager）
 *  - 普通应用（首个包）→ 客户端钩全套；模块自身与系统 uid 跳过
 */
class Ven11Module : XposedModule() {

    companion object {
        const val TAG = "VEN11"
        const val MODULE_PKG = "dev.ven11.module"

        /** PER_USER_RANGE：UserHandle.getAppId 是隐藏 API（编译不过），appId = uid % 100000 */
        const val PER_USER_RANGE = 100_000
    }

    @Volatile
    var isModuleLoaded: Boolean = false
        private set

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        isModuleLoaded = true
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        val n = SystemServiceBridge.installInSystemServer(this, param.classLoader)
        log(Log.INFO, TAG, "system_server framework hooks installed=$n")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        if (!param.isFirstPackage) return
        val pkg = param.packageName
        val cl = param.defaultClassLoader
        when {
            pkg == MODULE_PKG -> return
            // android 包是旧版"系统框架"勾选对应的 :ui 进程，不是 system_server——
            // system_server 走 onSystemServerStarting。记一条日志，避免"勾了 android
            // 却零输出"被误判为模块未加载（审查七）
            pkg == "android" -> {
                log(Log.INFO, TAG,
                    "android(:ui) loaded; system_server hooks live in onSystemServerStarting " +
                        "(scope.list needs \"system\")")
                return
            }
            pkg == "com.android.phone" -> {
                CellInfoFactory.attach(cl)
                val n = FrameworkCellHooks.install(this, cl)
                log(Log.INFO, TAG, "phone process framework hooks installed=$n")
            }
            // 非应用 uid（各系统服务进程）不装客户端钩
            Process.myUid() % PER_USER_RANGE < Process.FIRST_APPLICATION_UID -> return
            else -> installClientHooks(cl, pkg)
        }
    }

    private fun installClientHooks(cl: ClassLoader, pkg: String) {
        SnapshotStore.current() // 预热一次：首轮文件 IO 不落在首个被拦调用上
        var n = 0
        n += ClientLocationHooks(this).install(cl, pkg)
        n += ClientCellHooks(this).install(cl, pkg)
        n += ClientTelephonyListenerHooks(this).install(cl, pkg)
        n += ClientWifiHooks(this).install(cl, pkg)
        n += ClientConnectivityHooks(this).install(cl, pkg)
        n += ClientSimHooks(this).install(cl, pkg)
        n += ClientBluetoothHooks(this).install(cl, pkg)
        n += ClientTimezoneHooks(this).install(cl, pkg)
        n += ClientLanguageHooks(this).install(cl, pkg)
        log(Log.INFO, TAG, "client hooks installed=$n pkg=$pkg")
    }
}
