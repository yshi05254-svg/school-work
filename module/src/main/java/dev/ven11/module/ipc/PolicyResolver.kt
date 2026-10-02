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
 *  2. 全局回落策略：pkg=null 的那条（GPS 等域使用其环境；语言域只认 exact）；
 *  3. 都没有 → policy=null，domainEnabled 仍可对"全局开关 + 排除名单"作出判断。
 *
 * 热路径成本：resolve 被 GPS / WiFi 等高频回调逐次调用，带 (pkg,uid) TTL 微缓存；
 * TTL 到期前的快照热更新对该调用方最多延迟 CACHE_TTL_MS 可见。
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

    private const val CACHE_TTL_MS = 250L

    private class Cached(val key: String, val at: Long, val value: EffectivePolicy)

    @Volatile
    private var cache: Cached? = null

    fun resolve(pkg: String, uid: Int): EffectivePolicy {
        val key = "$pkg#$uid"
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (it.key == key && now - it.at < CACHE_TTL_MS) return it.value }

        val snap = SnapshotStore.current()
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
        ).also { cache = Cached(key, now, it) }
    }

    /** 快照版本变化时由 SnapshotStore 调用：总开关/排除名单变化不吃 TTL 延迟 */
    fun invalidate() {
        cache = null
    }
}
