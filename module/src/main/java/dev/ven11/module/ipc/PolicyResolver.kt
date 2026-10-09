package dev.ven11.module.ipc

import android.os.SystemClock
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
 *  2. 作用域默认策略：pkg=null 的那条，**只对 LSPosed 作用域内的应用**生效
 *     （[ScopeRegistry.inScope]）——在作用域里勾选即生效，管理端无需再逐个添加；
 *     命中视同精确（exact=true，语言/时区域同样生效）；
 *  3. 都没有 → policy=null，domainEnabled 仍可对"全局开关 + 排除名单"作出判断。
 *     作用域外的应用不再命中 pkg=null 策略：框架钩（system_server / phone）面对
 *     所有调用方，必须限定在作用域内，否则会误伤系统服务与无关应用。
 *
 * 热路径成本：resolve 被 GPS / WiFi 等高频回调逐次调用，带 (pkg,uid) TTL 微缓存；
 * TTL 到期前的快照热更新对该调用方最多延迟 CACHE_TTL_MS 可见。
 * 缓存按 (pkg,uid) 多条存放（方案B）：system_server 侧同一秒内会有多个应用的
 * 定位回调交替进来，此前"只存最近一条"会持续互相挤掉，等效于无缓存——每条
 * 回调都要重扫策略表；改为小容量 map，快照版本变化时整体清空（invalidate）。
 */
object PolicyResolver {

    enum class Domain { LOCATION, CELL, WIFI, SIM, BLUETOOTH, LANGUAGE, TIMEZONE }

    class EffectivePolicy(
        /** 命中该应用的策略：精确 pkg/userId 或作用域默认（true）；无策略（false） */
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

    private const val CACHE_TTL_MS = 250L

    /** 条目上限：正常远小于此（策略数级），超限整体清空防泄漏（防御性上限） */
    private const val CACHE_MAX_ENTRIES = 32

    private class Cached(val at: Long, val value: EffectivePolicy)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    fun resolve(pkg: String, uid: Int): EffectivePolicy {
        val key = "$pkg#$uid"
        val now = SystemClock.elapsedRealtime()
        cache[key]?.let { if (now - it.at < CACHE_TTL_MS) return it.value }

        val snap = SnapshotStore.current()
        val userId = uid / 100_000
        val policy = snap.policies.firstOrNull {
            it.pkg == pkg && (it.userId == null || it.userId == userId)
        } ?: snap.policies.firstOrNull { it.pkg == null }?.takeIf { ScopeRegistry.inScope(uid) }
        val env = policy?.environmentId
            ?.let { id -> snap.environments.firstOrNull { it.id == id } }
        val gateOpen = snap.masterEnabled &&
            pkg !in snap.excludedPackages &&
            uid !in snap.excludedUids

        return EffectivePolicy(
            exact = policy != null,
            policy = policy,
            environment = env,
            jitter = snap.jitter,
            payload = snap,
            gateOpen = gateOpen,
        ).also {
            if (cache.size >= CACHE_MAX_ENTRIES) cache.clear()
            cache[key] = Cached(now, it)
        }
    }

    /** 快照版本变化 / 作用域登记变化时调用：总开关/排除名单/作用域变化不吃 TTL 延迟 */
    fun invalidate() {
        cache.clear()
    }
}
