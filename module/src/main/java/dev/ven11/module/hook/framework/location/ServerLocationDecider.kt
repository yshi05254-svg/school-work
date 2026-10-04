package dev.ven11.module.hook.framework.location

import android.os.Process
import android.os.SystemClock
import dev.ven11.module.Ven11Module
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.ipc.SnapshotStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端决策层（方案B ②）：调用方身份 → 交付模式 / GNSS 抑制，按 uid 缓存。
 *
 * 回调路径上只做查表（约束 2：不阻塞）：策略解析、快照读取全部在缓存未命中时
 * 走内存（PolicyResolver 自带 (pkg,uid) 缓存，SnapshotStore 热路径只读内存），
 * 不发生 binder / 文件 IO / 系统设置查询。快照版本变化时整体清空
 * （SnapshotStore.registerListener），开关切换不吃缓存延迟。
 *
 * 与客户端 LocationFactory.decide 的规则一致（方案B §2.2）：
 *  - uid < 10000（系统服务）永远放行——服务端能看到所有应用，必须先拦住系统调用；
 *  - LOCATION 域未启用 → 放行；
 *  - 无虚拟环境：严格模式 → Block，否则放行；
 *  - 有虚拟环境 → Spoof（路线 > 摇杆 > 静态的构造仍由 LocationFactory.decide 逐次完成，
 *    本层只缓存"要不要伪装"的模式判定）。
 */
object ServerLocationDecider {

    enum class Mode { PASS, SPOOF, BLOCK }

    private class Entry(val at: Long, val mode: Mode)

    private class GnssEntry(val at: Long, val suppress: Boolean)

    /** 缓存 TTL：兜底快照热更新（正常由版本变化监听清空，TTL 只防监听旁路） */
    private const val TTL_MS = 1_000L

    /** (at, mode) 合并为单对象：原双 map（modes/modeAt）是非原子写入，读者可能见到撕裂态 */
    private val modes = ConcurrentHashMap<Int, Entry>()
    private val gnss = ConcurrentHashMap<Int, GnssEntry>()

    init {
        SnapshotStore.registerListener("SRVLOC-DECIDER") { _, _ ->
            modes.clear()
            gnss.clear()
        }
    }

    /** 坐标交付模式判定（uid 无包名可用时按无策略处理 → PASS） */
    fun modeFor(uid: Int, pkg: String?): Mode {
        if (uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return Mode.PASS
        if (uid <= 0) return Mode.PASS
        val now = SystemClock.elapsedRealtime()
        modes[uid]?.let { if (now - it.at < TTL_MS) return it.mode }
        val mode = computeMode(uid, pkg)
        // pkg==null 多为瞬时解析失败：结果（PASS）不入缓存，否则会把该 uid 在 TTL 内
        // 钉死为 PASS，令稍后 pkg 解析成功本应 SPOOF 的交付也命中旧缓存漏伪装
        if (pkg != null) modes[uid] = Entry(now, mode)
        return mode
    }

    private fun computeMode(uid: Int, pkg: String?): Mode {
        // 约束 6：serverLocation=false 时服务端整体放行，回退客户端交付钩（发布配置即生效）
        if (!SnapshotStore.current().serverLocation) return Mode.PASS
        if (pkg == null) return Mode.PASS
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.LOCATION)) return Mode.PASS
        val env = eff.environment
            ?: return if (eff.policy?.strictMode == true) Mode.BLOCK else Mode.PASS
        return Mode.SPOOF
    }

    /**
     * GNSS 抑制判定（方案B §2.2 已确认条件）：LOCATION 域启用，且
     * （有虚拟环境 **或** 严格模式）。客户端此前对原始测量/NMEA 是无条件抑制，
     * 但客户端钩只注入目标应用进程，等价于只影响目标；服务端面对所有应用，
     * 必须按策略限定范围，否则误伤系统服务与无关应用。
     */
    fun suppressGnss(uid: Int, pkg: String?): Boolean {
        if (uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return false
        if (uid <= 0) return false
        val now = SystemClock.elapsedRealtime()
        gnss[uid]?.let { if (now - it.at < TTL_MS) return it.suppress }
        // pkg==null：按不抑制处理且不入缓存（同 modeFor，避免瞬时 null 钉死该 uid）
        if (pkg == null) return false
        val suppress = SnapshotStore.current().serverLocation && run {
            val eff = PolicyResolver.resolve(pkg, uid)
            eff.domainEnabled(PolicyResolver.Domain.LOCATION) &&
                (eff.environment != null || eff.policy?.strictMode == true)
        }
        gnss[uid] = GnssEntry(now, suppress)
        return suppress
    }
}
