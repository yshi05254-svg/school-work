package dev.ven11.module.hook.framework

import android.os.Bundle
import dev.ven11.module.ProbeLog
import dev.ven11.module.ipc.ScopeRegistry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * system_server 侧作用域登记通道：钩 LocationManagerService.sendExtraCommand，
 * 命令名为 [ScopeRegistry.CMD_REGISTER] / [ScopeRegistry.CMD_QUERY] 时截获
 * （原方法第一步是权限检查，普通应用直调会被拒）交给 [ScopeRegistry]，其余命令原样放行。
 */
object ScopeCommandHook {

    fun install(module: XposedModule, cl: ClassLoader): Boolean {
        ScopeRegistry.becomeServer()
        val cls = listOf(
            "com.android.server.location.LocationManagerService", // S+
            "com.android.server.LocationManagerService",          // ≤R
        ).firstNotNullOfOrNull { runCatching { cl.loadClass(it) }.getOrNull() }
            ?: return false.also { ProbeLog.log("SCOPE-INSTALL miss LocationManagerService") }
        val m = cls.declaredMethods.firstOrNull {
            it.name == "sendExtraCommand" && it.parameterTypes.size == 3
        } ?: return false.also { ProbeLog.log("SCOPE-INSTALL miss sendExtraCommand") }
        val voidRet = m.returnType == Void.TYPE
        module.hook(m).setId("ven11.scope.cmd").intercept(object : XposedInterface.Hooker {
            override fun intercept(chain: XposedInterface.Chain): Any? {
                val cmd = chain.args.getOrNull(1) as? String
                if (cmd != ScopeRegistry.CMD_REGISTER && cmd != ScopeRegistry.CMD_QUERY) {
                    return chain.proceed()
                }
                // 本模块命令：不进原方法；异常不外溢到 system_server
                runCatching { ScopeRegistry.onServerCommand(cmd, chain.args.getOrNull(2) as? Bundle) }
                    .onFailure { ProbeLog.log("SCOPE-CMD-ERR ${it.javaClass.simpleName}: ${it.message}") }
                return if (voidRet) null else true
            }
        })
        ProbeLog.log("SCOPE-INSTALL ok ${cls.name}")
        return true
    }
}
