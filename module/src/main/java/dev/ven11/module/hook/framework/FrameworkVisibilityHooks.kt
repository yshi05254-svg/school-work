package dev.ven11.module.hook.framework

import android.os.Process
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.ipc.SnapshotStore
import dev.ven11.module.publish.ConfigContentProvider
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统侧包可见性精确放行（system_server）：
 *  - Android 11+ 包可见性过滤（AppsFilter）会拦截无 QUERY_ALL_PACKAGES 的目标应用
 *    （如淘宝、微信）解析模块配置 provider（content://dev.ven11.module.config），导致
 *    客户端钩（WiFi / 基站 / SIM / 蓝牙 / 语言 / 时区）永远拿到 Snapshot.EMPTY；
 *  - 仅在解析 dev.ven11.module.config 期间（ContentProviderHelper.getContentProviderImpl
 *    与 ComputerEngine.resolveContentProvider）通过 ThreadLocal 计数放行目标应用对
 *    dev.ven11.module 的查询，不影响 getInstalledPackages / getPackageInfo，不扩大暴露面。
 */
object FrameworkVisibilityHooks {

    private const val MANAGER_PKG = "com.zcshou.gogogo"

    private val installed = AtomicBoolean(false)
    private val allowDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    private val ABSENT = Any()
    private val pkgGetters = ConcurrentHashMap<Class<*>, Any>()
    private val allowedSeen = ConcurrentHashMap.newKeySet<String>()

    fun install(module: XposedModule, cl: ClassLoader): Int {
        if (!installed.compareAndSet(false, true)) return -1
        var n = 0
        val missing = ArrayList<String>()

        // 1. ContentProviderHelper.getContentProviderImpl(..., String name, ...)
        val cph = runCatching {
            cl.loadClass("com.android.server.am.ContentProviderHelper")
                .declaredMethods
                .firstOrNull {
                    it.name == "getContentProviderImpl" &&
                        it.parameterTypes.size >= 2 &&
                        it.parameterTypes[1] == String::class.java
                }
        }.getOrNull()
        if (cph != null) {
            runCatching {
                module.hook(cph).setId("ven11.fwvis.getcph/${cph.parameterTypes.size}")
                    .intercept { chain ->
                        val auth = chain.args.getOrNull(1) as? String
                        if (auth != ConfigContentProvider.AUTHORITY) {
                            chain.proceed()
                        } else {
                            withAuthorityAllowed { chain.proceed() }
                        }
                    }
            }.onSuccess { n++ }.onFailure { missing.add("getcph(${it.javaClass.simpleName})") }
        } else {
            missing.add("getcph")
        }

        // 2. ComputerEngine.resolveContentProvider(String name, long flags, int userId, int callingUid)
        val rcp = runCatching {
            cl.loadClass("com.android.server.pm.ComputerEngine")
                .declaredMethods
                .firstOrNull {
                    it.name == "resolveContentProvider" &&
                        it.parameterTypes.isNotEmpty() &&
                        it.parameterTypes[0] == String::class.java
                }
        }.getOrNull()
        if (rcp != null) {
            runCatching {
                module.hook(rcp).setId("ven11.fwvis.resolvecp/${rcp.parameterTypes.size}")
                    .intercept { chain ->
                        val auth = chain.args.getOrNull(0) as? String
                        if (auth != ConfigContentProvider.AUTHORITY) {
                            chain.proceed()
                        } else {
                            withAuthorityAllowed { chain.proceed() }
                        }
                    }
            }.onSuccess { n++ }.onFailure { missing.add("resolvecp(${it.javaClass.simpleName})") }
        } else {
            missing.add("resolvecp")
        }

        // 3. AppsFilterBase.shouldFilterApplication(PackageDataSnapshot, int callingUid, Object callingSetting, PackageStateInternal targetPkgSetting, int userId)
        val sfa = runCatching {
            cl.loadClass("com.android.server.pm.AppsFilterBase")
                .declaredMethods
                .firstOrNull {
                    it.name == "shouldFilterApplication" &&
                        it.parameterTypes.size == 5 &&
                        it.returnType == java.lang.Boolean.TYPE
                }
        }.getOrNull()
        if (sfa != null) {
            runCatching {
                module.hook(sfa).setId("ven11.fwvis.filter")
                    .intercept { chain ->
                        if ((allowDepth.get() ?: 0) <= 0) {
                            chain.proceed()
                        } else {
                            val allow = runCatching { shouldAllow(chain.args) }.getOrDefault(false)
                            if (allow) false else chain.proceed()
                        }
                    }
            }.onSuccess { n++ }.onFailure { missing.add("filter(${it.javaClass.simpleName})") }
        } else {
            missing.add("filter")
        }

        val missStr = missing.joinToString("|").ifEmpty { "-" }
        ProbeLog.log("FW-VISIBILITY armed=$n/3 missing=$missStr")
        module.log(Log.INFO, Ven11Module.TAG, "FW-VISIBILITY armed=$n/3 missing=$missStr")
        return n
    }

    private inline fun <T> withAuthorityAllowed(block: () -> T): T {
        val prev = allowDepth.get() ?: 0
        allowDepth.set(prev + 1)
        return try {
            block()
        } finally {
            allowDepth.set(prev)
        }
    }

    private fun shouldAllow(args: List<Any?>): Boolean {
        val targetPkg = pkgNameOf(args.getOrNull(3)) ?: return false
        if (targetPkg != Ven11Module.MODULE_PKG) return false
        val callingUid = args.getOrNull(1) as? Int ?: return false
        if (callingUid <= 0 || callingUid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) {
            return false
        }
        val callingPkg = pkgNameOf(args.getOrNull(2))
        val snap = SnapshotStore.current()
        if (callingPkg != null) {
            if (callingPkg == MANAGER_PKG || callingPkg == Ven11Module.MODULE_PKG) {
                allowOnce(callingUid, callingPkg)
                return true
            }
            if (callingPkg in snap.excludedPackages || callingUid in snap.excludedUids) {
                return false
            }
            val matched = snap.policies.any { it.pkg == callingPkg || it.pkg == null }
            if (matched) {
                allowOnce(callingUid, callingPkg)
                return true
            }
            return false
        }
        // callingSetting 非单包（如 SharedUserSetting）：当前明确处于解析 dev.ven11.module.config
        // 路径（allowDepth > 0），且 ConfigContentProvider.callerAllowed() 仍会复核 uid 对应包名 ACL
        if (callingUid !in snap.excludedUids && snap.policies.isNotEmpty()) {
            allowOnce(callingUid, "<shared>")
            return true
        }
        return false
    }

    private fun pkgNameOf(setting: Any?): String? {
        if (setting == null) return null
        val cls = setting.javaClass
        var cached = pkgGetters[cls]
        if (cached == null) {
            val m = findPkgGetter(cls)
            cached = m ?: ABSENT
            pkgGetters[cls] = cached
        }
        val method = cached as? Method ?: return null
        return runCatching { method.invoke(setting) as? String }.getOrNull()
    }

    private fun findPkgGetter(cls: Class<*>): Method? {
        runCatching { cls.getMethod("getPackageName") }.getOrNull()?.let {
            it.isAccessible = true
            return it
        }
        var cur: Class<*>? = cls
        while (cur != null) {
            val m = cur.declaredMethods.firstOrNull {
                it.name == "getPackageName" && it.parameterTypes.isEmpty()
            }
            if (m != null) {
                m.isAccessible = true
                return m
            }
            cur = cur.superclass
        }
        return null
    }

    private fun allowOnce(uid: Int, pkg: String) {
        if (allowedSeen.add("$uid#$pkg")) {
            ProbeLog.log("FW-VISIBILITY-ALLOW uid=$uid pkg=$pkg")
        }
    }
}
