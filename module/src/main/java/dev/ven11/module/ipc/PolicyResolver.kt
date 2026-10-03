package dev.ven11.module.ipc

import dev.ven11.module.model.GpsJitter
import dev.ven11.module.model.Policy
import dev.ven11.module.model.Snapshot
import dev.ven11.module.model.VirtualEnvironment

/**
 * 策略解析：pkg + uid → 生效策略（EffectivePolicy）。
 *
 * 统一门（评审四）：masterEnabled / excludedPackages / excludedUids / 策略级域开关
 * 全部收敛在 [EffectivePolicy.domainEnabled]——各域 hook 必须显式调用它，
 * 不存在"resolve 内部隐式拦截"的旁路（语言 / 时区域此前依赖的假设已废除）。
 *
 * 命中顺序：
 *  1. 精确策略：pkg 相等且（策略未限定 userId 或 userId 匹配 uid/100000）→ exact=true；
 *  2. 全局回落策略：pkg=null 的那条（GPS 等域使用其环境；语言域只认 exact）；
 *  3. 都没有 → policy=null，domainEnabled 仍可对"全局开关 + 排除名单"作出判断。
 *
 * 热路径成本：resolve 被 GPS / WiFi / SystemProperties 等高频路径逐次调用。
 * 快照不可变，同一快照实例 + 同一 (pkg, uid) 的解析结果恒定，故按快照身份缓存：
 *  - 快照未换 → 直接返回缓存结果（无字符串拼接、无策略表线性扫描）；
 *  - 快照换了 → 整表作废（版本切换即时可见，无 TTL 延迟）；
 *  - 多调用方（system_server / phone 按 binder 调用方 uid 解析）各占一项，
 *    条目上限 [CACHE_MAX] 防异常增长。
 */
object PolicyResolver {

    enum class Domain { LOCATION, CELL, WIFI, SIM, BLUETOOTH, LANGUAGE, TIMEZONE }

    class EffectivePolicy(
        /** 精确命中 pkg/userId 策略（true）；全局回落 / 无策略（false） */
        val exact: Boolean,
        val policy: Policy?,
        val environment: VirtualEnvironment?,
        val jitter: GpsJitter,
        val payload: Snapshot,
        private val gateOpen: Boolean,
    ) {
        /** 总开关 + 排除名单 + 策略级域开关；所有域的 hook 显式调用这一个门 */
        fun domainEnabled(domain: Domain): Boolean =
            gateOpen && policy?.disabledDomains?.contains(domain.name) != true
    }

    private const val CACHE_MAX = 256

    private class Entry(val pkg: String, val uid: Int, val value: EffectivePolicy)

    /** 某一快照实例下的解析表；快照替换时整体换新（读侧无锁） */
    private class Table(val snap: Snapshot) {
        /** 客户端进程只有一个调用方：单项快路径，免查表 */
        @Volatile
        var last: Entry? = null
        val byUid = java.util.concurrent.ConcurrentHashMap<Int, Entry>()
    }

    @Volatile
    private var table: Table = Table(Snapshot.EMPTY)

    fun resolve(pkg: String, uid: Int): EffectivePolicy {
        // current() 只做节流判定（volatile 读），同时承担驱动快照轮询的职责
        val snap = SnapshotStore.current()
        var t = table
        if (t.snap !== snap) {
            t = Table(snap)
            table = t
        }
        t.last?.let { if (it.uid == uid && it.pkg == pkg) return it.value }
        t.byUid[uid]?.let {
            if (it.pkg == pkg) {
                t.last = it
                return it.value
            }
        }
        val value = compute(snap, pkg, uid)
        val e = Entry(pkg, uid, value)
        if (t.byUid.size >= CACHE_MAX) t.byUid.clear()
        t.byUid[uid] = e
        t.last = e
        return value
    }

    private fun compute(snap: Snapshot, pkg: String, uid: Int): EffectivePolicy {
        val userId = uid / 100_000
        val exact = snap.policies.firstOrNull {
            it.pkg == pkg && (it.userId == null || it.userId == userId)
        }
        val policy = exact ?: snap.policies.firstOrNull { it.pkg == null }
        val env = policy?.environmentId
            ?.let { id -> snap.environments.firstOrNull { it.id == id } }
        val gateOpen = snap.masterEnabled &&
            pkg !in snap.excludedPackages &&
            uid !in snap.excludedUids

        return EffectivePolicy(
            exact = exact != null,
            policy = policy,
            environment = env,
            jitter = snap.jitter,
            payload = snap,
            gateOpen = gateOpen,
        )
    }

    /** 快照版本变化时由 SnapshotStore 调用（缓存已按快照身份失效，这里只是显式提前作废） */
    fun invalidate() {
        table = Table(SnapshotStore.peek())
    }
}
