package dev.ven11.module.ipc

import android.os.SystemClock
import dev.ven11.module.ProbeLog
import dev.ven11.module.hook.framework.UidResolver
import dev.ven11.module.model.Snapshot
import dev.ven11.module.model.SnapshotParser
import dev.ven11.module.publish.ConfigContentProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 快照仓库（hook 进程侧）：向所有钩提供不可变快照，热路径只读内存。
 *
 * 读取机制（P1/P2 修复：system_server 双通道全失败 + 失败原因不可分）：
 *  三通道按序，各通道独立票据与故障原因，任何一条路走通即拿到配置——
 *  不同 SELinux 域的进程（普通应用 / system_server / phone）天然落到各自可读的通道：
 *
 *  1. provider 通道（首选，发布过后生效）：模块 APK 的 [ConfigContentProvider]，
 *     binder 读取不受目录 SELinux 限制；故障三态记录原因（ctx-null / query 异常类），
 *     不再整体吞成 null；
 *  2. 文件通道 · /data/local/tmp/ven11/snapshot.json：普通应用可读
 *     （untrusted_app 域实测），system_server 被 SELinux 挡（实测 EACCES）；
 *  3. 文件通道 · /data/system/ven11/snapshot.json：system_server 可读
 *     （本域私有目录，DAC 属主 system），由发布脚本以 root 落盘——
 *     这就是 P1 的解法：同一份快照落两个路径，各进程按域取所需。
 *
 * 调度（审查八 #1/#2）：
 *  - current() 只做节流判定并把轮询丢到单线程后台执行器：钩的热路径不发生任何
 *    binder/文件 IO 与 JSON 解析；
 *  - 各来源独立"成功已处理"票据：读取/解析/校验全部成功才更新票据，失败绝不更新；
 *  - 失败按指数退避限频重试（3s→30s）、失败日志按原因限频且**原因分通道**
 *    （P2：provider:<cause> 与 file:<cause> 分列，日志直接指出根因）；
 *  - "无配置 / masterEnabled 关闭"是合法状态，与解析失败分开处理；
 *  - 快照替换与 PolicyResolver 失效一致；监听通知在仓库锁外执行；
 *  - SNAP 事件带 via=<通道>，验收时可直接判定各进程实际走了哪条路。
 */
object SnapshotStore {

    /** 文件通道路径（按序尝试；发布侧 scripts/publish-snapshot.sh 双路径同内容落盘） */
    val SNAPSHOT_PATHS = listOf(
        "/data/local/tmp/ven11/snapshot.json",   // 普通应用域可读
        "/data/system/ven11/snapshot.json",      // system_server 域可读（P1 解法）
    )

    /** 旧单路径常量保留引用兼容（app 侧 SnapshotPublisher 注释指向） */
    const val SNAPSHOT_PATH = "/data/local/tmp/ven11/snapshot.json"

    private const val POLL_INTERVAL_MS = 1_000L
    private const val RETRY_MIN_MS = 3_000L
    private const val RETRY_MAX_MS = 30_000L

    @Volatile
    private var current: Snapshot = Snapshot.EMPTY

    @Volatile
    private var lastPollAtMs: Long = 0L

    private val pollLock = Any()

    /** 单线程后台执行器：轮询（binder/文件 IO）与解析都在这里，热路径零 IO */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ven11-snap-poll").apply { isDaemon = true }
    }
    private val pollPending = AtomicBoolean(false)

    // ---- 各来源成功票据（失败绝不更新） ----
    private var providerVer: Long = -1L                          // provider 上次成功解析的版本
    private val fileStamps = ConcurrentHashMap<String, Pair<Long, Long>>() // path → (mtime, size)

    // ---- 失败状态与退避 ----
    private var lastFailAtMs: Long = 0L
    private var retryDelayMs: Long = RETRY_MIN_MS

    // ---- 通道故障限频记录（P2：分通道、可区分根因） ----
    private val faultSeen = ConcurrentHashMap<String, Unit>()

    private fun faultOnce(cause: String) {
        if (faultSeen.putIfAbsent(cause, Unit) == null) {
            ProbeLog.log("SNAP-FAULT $cause")
        }
    }

    fun current(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPollAtMs < POLL_INTERVAL_MS) return current
        // 节流判定在调用线程，轮询/解析在后台：被钩调用只付 volatile 读 + CAS
        if (pollPending.compareAndSet(false, true)) {
            ioExecutor.execute {
                try {
                    val events = synchronized(pollLock) { pollOnce() }
                    // 监听通知在仓库锁外执行（审查八 #2）
                    for ((old, new) in events) notifyListeners(old, new)
                } catch (t: Throwable) {
                    ProbeLog.log("SNAP-POLL-ERR ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    pollPending.set(false)
                }
            }
        }
        return current
    }

    /** 返回需要通知监听器的 (old, new) 列表；在 pollLock 内执行 */
    private fun pollOnce(): List<Pair<Snapshot, Snapshot>> {
        lastPollAtMs = SystemClock.elapsedRealtime()
        // 失败退避期内不重试（成功路径——票据命中——不经过这里的时间成本）
        val now = lastPollAtMs
        if (lastFailAtMs != 0L && now - lastFailAtMs < retryDelayMs) return emptyList()

        when (val pr = readProvider()) {
            is ProviderRead.Ok -> {
                // 版本有新变化才消费；版本未变**继续探文件通道**（脑裂修复：管理端
                // 双写后两通道内容一致，但若 provider 进程曾发布过旧版本、此后管理端
                // 只走文件通道成功，早退会让本进程永远停留旧 provider 版本）
                if (pr.ver != providerVer) {
                    val events = consume(pr.ver.toString(), pr.payload, via = "provider") {
                        providerVer = pr.ver
                    }
                    if (events.isNotEmpty()) return events
                }
            }
            is ProviderRead.Unpublished -> {
                // 管理端从未发布过（合法状态，非故障）：走文件通道
            }
            is ProviderRead.Fault -> {
                // 通道故障：记原因（分通道，P2），文件通道兜底
                faultOnce("provider:${pr.cause}")
            }
        }

        // 文件通道（多路径，按序取第一个有新内容的）
        val fr = readFile()
        return when (fr) {
            is FileRead.Failed -> {
                recordFailure("all-channels (provider=${providerState()}, file:${fr.causes.joinToString("|")})")
                emptyList()
            }
            is FileRead.Unchanged -> emptyList()
            is FileRead.Content -> consume(fr.stamp.toString(), fr.text, via = "file:${fr.tag}") {
                fileStamps[fr.path] = fr.stamp
            }
        }
    }

    private fun providerState(): String = when (providerVer) {
        -1L -> "unpublished"
        else -> "ver=$providerVer"
    }

    /**
     * 消费载荷：解析成功 → 版本变化才替换引用 + 失效策略缓存；解析失败 → 记失败
     * （票据不动，退避后重试同一载荷）。空串载荷归一化为"无配置"。返回待通知事件。
     */
    private fun consume(
        versionKey: String,
        payload: String?,
        via: String,
        markProcessed: () -> Unit,
    ): List<Pair<Snapshot, Snapshot>> {
        val effPayload = payload?.takeIf { it.isNotBlank() }
        val next = effPayload?.let { SnapshotParser.parseOrNull(it) }
        if (effPayload != null && next == null) {
            recordFailure("parse failed ($versionKey via=$via)")
            return emptyList()
        }
        markProcessed()
        if (lastFailAtMs != 0L) {
            // 从失败中恢复：单独记一次事件（审查八 #2 验收）
            ProbeLog.log("SNAP-RECOVERED $versionKey via=$via")
        }
        lastFailAtMs = 0L
        retryDelayMs = RETRY_MIN_MS
        if (next === current || (next != null && next.configVersion == current.configVersion)) {
            // 版本未变（含 EMPTY）：维持旧实例，保证各域缓存票据稳定
            return emptyList()
        }
        if (next != null && current.configVersion > 0L && next.configVersion < current.configVersion) {
            // 过期载荷防回滚（脑裂修复）：provider 通道曾发布过旧版本、此后管理端只
            // 更新了文件通道（或反之），进程重启/通道恢复时旧通道重放旧载荷——
            // configVersion 取自管理端 currentTimeMillis，同机单调，小于当前即过期，
            // 忽略但票据已消费，不会反复重解析
            ProbeLog.log(
                "SNAP-IGNORED stale version ${next.configVersion} < ${current.configVersion} via=$via",
            )
            return emptyList()
        }
        val old = current
        current = next ?: Snapshot.EMPTY
        ProbeLog.log(
            "SNAP version ${old.configVersion} -> ${current.configVersion} via=$via " +
                "(env=${current.environments.size} policies=${current.policies.size} " +
                "simSlots=${current.sim.slots.size} routes=${current.routes.size})"
        )
        PolicyResolver.invalidate() // 总开关/排除名单变化必须立刻生效，不吃 250ms TTL
        return listOf(old to current)
    }

    private fun recordFailure(reason: String) {
        val now = SystemClock.elapsedRealtime()
        lastFailAtMs = now
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        faultOnce("FAIL $reason（${retryDelayMs / 1000}s 后重试）")
    }

    // ---------------------------------------------------------------- provider 通道

    private sealed interface ProviderRead {
        class Ok(val ver: Long, val payload: String?) : ProviderRead
        object Unpublished : ProviderRead
        class Fault(val cause: String) : ProviderRead
    }

    /**
     * provider 轻查询 + 载荷读取。三态：
     *  - [ProviderRead.Ok]：发布过（ver>0）；payload=null 表示"该版本已处理"或"已清空"
     *  - [ProviderRead.Unpublished]：从未发布（合法，走文件通道）
     *  - [ProviderRead.Fault]：通道故障，cause 区分 ctx-null / query 异常类（P2）
     */
    private fun readProvider(): ProviderRead {
        val ctx = UidResolver.anyContext() ?: return ProviderRead.Fault("ctx-null")
        return try {
            val cr = ctx.contentResolver
            val ver = cr.query(
                ConfigContentProvider.payloadUri(),
                arrayOf(ConfigContentProvider.COL_VERSION), null, null, null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else -1L }
                ?: return ProviderRead.Fault("version-cursor-null")
            if (ver <= 0L) return ProviderRead.Unpublished
            if (ver == providerVer) return ProviderRead.Ok(ver, null)   // 已处理版本（轻查询不搬载荷）
            val payload = cr.query(
                ConfigContentProvider.payloadUri(),
                arrayOf(ConfigContentProvider.COL_PAYLOAD), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: return ProviderRead.Fault("payload-cursor-null")
            ProviderRead.Ok(ver, payload)
        } catch (t: Throwable) {
            ProviderRead.Fault(t.javaClass.simpleName)
        }
    }

    // ---------------------------------------------------------------- 文件通道（多路径）

    private sealed interface FileRead {
        object Unchanged : FileRead
        class Failed(val causes: List<String>) : FileRead
        class Content(val path: String, val tag: String, val stamp: Pair<Long, Long>, val text: String) : FileRead
    }

    /**
     * 逐路径尝试（[SNAPSHOT_PATHS] 按序），语义（P1/P2 修正）：
     *  - Content：该路径有新内容 → 立即返回（无论其它路径状态）；
     *  - Unchanged：某路径已有成功票据且无新内容 → 该进程配置源健康，正常态
     *    （此前把"后续路径 missing"误判为 Failed，导致成功加载后仍刷 FAIL+退避）；
     *  - 全部路径 Missing（域不可达，如 system_server 对 /data/local/tmp）→ 合法
     *    空态：faultOnce 留诊断、不进退避（文件随时可能被发布脚本补上，1s 轮询可发现）；
     *  - 存在真实 IO Error → Failed(causes)（进退避）。
     */
    private fun readFile(): FileRead {
        val causes = ArrayList<String>(SNAPSHOT_PATHS.size)
        for (path in SNAPSHOT_PATHS) {
            when (val r = readFileOne(path)) {
                is OneRead.Content -> return FileRead.Content(path, r.tag, r.stamp, r.text)
                is OneRead.Unchanged -> return FileRead.Unchanged
                is OneRead.Missing -> causes.add("missing:${r.tag}")
                is OneRead.Error -> causes.add("error:${r.cause}:${r.tag}")
            }
        }
        if (causes.none { it.startsWith("error") }) {
            // 全部 Missing：域限制而非故障（P2：诊断留痕但不判失败）
            causes.forEach { faultOnce("file:$it") }
            return FileRead.Unchanged
        }
        return FileRead.Failed(causes)
    }

    private sealed interface OneRead {
        object Unchanged : OneRead
        class Missing(val cause: String, val tag: String) : OneRead
        class Error(val cause: String, val tag: String) : OneRead
        class Content(val tag: String, val stamp: Pair<Long, Long>, val text: String) : OneRead
    }

    private fun readFileOne(path: String): OneRead {
        val tag = when {
            path.startsWith("/data/system") -> "system"
            path.startsWith("/data/local/tmp") -> "local"
            else -> path.hashCode().toString(16)
        }
        val f = try {
            File(path)
        } catch (_: Throwable) {
            return OneRead.Error("invalid-path", tag)
        }
        if (!f.isFile) return OneRead.Missing("missing", tag)
        if (!f.canRead()) return OneRead.Missing("unreadable", tag)   // 域限制（P1 实测：system_server 对 local）
        val stamp = runCatching { f.lastModified() to f.length() }.getOrNull()
            ?: return OneRead.Error("stat-failed", tag)
        if (stamp == fileStamps[path]) return OneRead.Unchanged  // 票据只在成功后写入：失败重试不受阻
        val text = try {
            f.readText()
        } catch (t: Throwable) {
            return OneRead.Error("read:${t.javaClass.simpleName}", tag)
        }
        return OneRead.Content(tag, stamp, text)
    }

    // ---------------------------------------------------------------- 配置版本监听

    /** 收到快照版本变化（含 EMPTY ↔ 有效配置的双向切换）；在后台线程、仓库锁外串行调用 */
    fun interface SnapshotListener {
        fun onSnapshotChanged(old: Snapshot, new: Snapshot)
    }

    private val listeners = ConcurrentHashMap<String, SnapshotListener>()

    /** 语言/时区等"安装时定行为"的域靠它获得热更新能力（评审二轮补充：高优先级两条） */
    fun registerListener(tag: String, l: SnapshotListener) {
        listeners[tag] = l
    }

    fun unregisterListener(tag: String) {
        listeners.remove(tag)
    }

    private fun notifyListeners(old: Snapshot, new: Snapshot) {
        for ((tag, l) in listeners) {
            try {
                l.onSnapshotChanged(old, new)
            } catch (t: Throwable) {
                ProbeLog.log("SNAP-LISTENER-ERR $tag ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
