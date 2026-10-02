package dev.ven11.module.hook.framework

import android.content.Context
import android.os.Binder
import android.os.SystemClock
import dev.ven11.module.ProbeLog
import java.util.concurrent.ConcurrentHashMap

/**
 * 系统进程内 uid → 包名公共工具（审查四：自 FrameworkWifiHooks.pkgOfUid 抽取，
 * 消除基站域对 WiFi 域的跨依赖；FrameworkWifiHooks 可改为委托此实现，逻辑等价）。
 * - TTL 缓存，避免高频调用走 PMS binder；查询期间恢复系统身份
 * - shared uid 对应多包时取字典序首个，保证策略命中稳定，并记一条日志
 * - 解析不到（isolated / SDK sandbox 等）返回 null，由调用方决定放行/回落
 */
object UidResolver {

    private const val UID_CACHE_TTL_MS = 10_000L

    private class UidEntry(val pkg: String?, val at: Long)

    private val uidCache = ConcurrentHashMap<Int, UidEntry>()

    @Volatile
    private var sysCtx: Context? = null

    @Volatile
    private var appCtx: Context? = null

    fun systemContext(): Context? {
        sysCtx?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            val current = at.getMethod("currentActivityThread").invoke(null)
            at.getMethod("getSystemContext").invoke(current) as? Context
        } catch (_: Throwable) {
            null
        }?.also { sysCtx = it }
    }

    /**
     * 任意被钩进程可用的 Context：应用/phone 进程取 currentApplication，
     * system_server 没有 Application（currentApplication 为 null），退回 SystemContext。
     * 都取不到（极早期）返回 null，调用方按通道故障处理（审查八 #1）。
     */
    fun anyContext(): Context? {
        appCtx?.let { return it }
        try {
            val at = Class.forName("android.app.ActivityThread")
            (at.getMethod("currentApplication").invoke(null) as? Context)?.let {
                appCtx = it
                return it
            }
        } catch (_: Throwable) {
        }
        return systemContext()
    }

    fun pkgOfUid(uid: Int): String? {
        val now = SystemClock.elapsedRealtime()
        uidCache[uid]?.let { if (now - it.at < UID_CACHE_TTL_MS) return it.pkg }
        val token = Binder.clearCallingIdentity()
        try {
            val names = systemContext()?.packageManager?.getPackagesForUid(uid)
            val pkg = when {
                names == null -> null
                names.size <= 1 -> names.firstOrNull()
                else -> {
                    ProbeLog.log("UID-RESOLVE shared-uid uid=$uid pkgs=${names.joinToString(",")}")
                    names.minOrNull()
                }
            }
            uidCache[uid] = UidEntry(pkg, now)
            return pkg
        } catch (_: Throwable) {
            return null
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}