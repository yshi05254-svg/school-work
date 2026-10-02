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
 * 通道（审查八 #1）：
 *  - 框架通道（优先）：模块 APK 的 [ConfigContentProvider]——binder 读取不受
 *    /data/local/tmp 的 SELinux 限制，普通应用 / system_server / phone 读到同一版本；
 *    载荷整体单文件存放，先做版本轻查询，版本未变不搬运载荷；
 *  - 文件通道（兜底）：管理端 su 落盘 /data/local/tmp（app 进程多数可读，
 *    system_server/phone 常被 SELinux 挡），保留用于无 provider 权限的降级场景。
 *
 * 调度（审查八 #1/#2）：
 *  - current() 只做节流判定并把轮询丢到单线程后台执行器：钩的热路径不发生任何
 *    binder/文件 IO 与 JSON 解析，解析与校验在后台完成后整体替换引用；
 *  - 各来源独立"成功已处理"票据：读取/解析/校验全部成功才更新票据，失败绝不更新
 *    ——文件戳没变但上次解析失败时仍会重试（修复"失败被缓存"无法恢复的问题）；
 *  - 失败按指数退避限频重试（3s→30s）、失败日志按原因限频；恢复成功单独记一次
 *    RECOVERED 事件；
 *  - "无配置 / masterEnabled 关闭"是合法状态（载荷为空或解析为合法 JSON），
 *    与解析失败（parseOrNull 返回 null）分开处理；
 *  - 快照替换与 PolicyResolver 失效一致；监听通知在仓库锁外执行。
 */
object SnapshotStore {

    /** 旧文件通道（管理端 su 落盘的兜底路径；与 app 侧 SnapshotPublisher 保持一致） */
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
    private var providerVer: Long = -1L                       // provider 上次成功解析的版本
    private var fileStamp: Pair<Long, Long> = -1L to -1L      // 文件上次成功解析的 (mtime, size)

    // ---- 失败状态与退避 ----
    private var lastFailAtMs: Long = 0L
    private var retryDelayMs: Long = RETRY_MIN_MS
    private var lastFailReason: String? = null

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

        val read = readProvider()
        if (read != null && read.first > 0L) {
            // 框架通道健康且发布过配置：以它为准（payload=null 亦可能是"已清空"的合法状态）
            val (ver, payload) = read
            if (payload == null && ver == providerVer) return emptyList()   // 已处理版本
            return consume(ver.toString(), payload) { providerVer = ver }
        }
        // read == null（通道故障）或 ver<=0（框架通道从未发布）→ 文件兜底通道接管。
        // 文件通道的失败也要记录（审查八 #2：失败不可被当作"已处理"）
        val fr = readFile()
        return when (fr) {
            is FileRead.Failed -> {
                if (read == null) recordFailure("provider+file read failed")
                emptyList()
            }
            is FileRead.Unchanged -> emptyList()
            is FileRead.Content -> consume(fr.stamp.toString(), fr.text) {
                fileStamp = fr.stamp
            }
        }
    }

    /**
     * 消费载荷：解析成功 → 版本变化才替换引用 + 失效策略缓存；解析失败 → 记失败
     * （票据不动，退避后重试同一载荷）。空串载荷归一化为"无配置"。返回待通知事件。
     */
    private fun consume(
        versionKey: String,
        payload: String?,
        markProcessed: () -> Unit,
    ): List<Pair<Snapshot, Snapshot>> {
        val effPayload = payload?.takeIf { it.isNotBlank() }
        val next = effPayload?.let { SnapshotParser.parseOrNull(it) }
        if (effPayload != null && next == null) {
            recordFailure("parse failed ($versionKey)")
            return emptyList()
        }
        markProcessed()
        if (lastFailAtMs != 0L) {
            // 从失败中恢复：单独记一次事件（审查八 #2 验收：不改文件内容/时间戳也能恢复）
            ProbeLog.log("SNAP-RECOVERED $versionKey")
        }
        lastFailAtMs = 0L
        retryDelayMs = RETRY_MIN_MS
        lastFailReason = null
        if (next === current || (next != null && next.configVersion == current.configVersion)) {
            // 版本未变（含 EMPTY）：维持旧实例，保证各域缓存票据稳定
            return emptyList()
        }
        val old = current
        current = next ?: Snapshot.EMPTY
        ProbeLog.log(
            "SNAP version ${old.configVersion} -> ${current.configVersion} " +
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
        if (reason != lastFailReason) {
            lastFailReason = reason
            ProbeLog.log("SNAP-FAIL $reason（${retryDelayMs / 1000}s 后重试）")
        }
    }

    // ---------------------------------------------------------------- 框架通道

    /**
     * provider 轻查询 + 载荷读取。返回 null = 通道故障（记失败并回落文件通道）；
     * (ver, null) = 无载荷或已处理版本——调用方再按 ver == providerVer 细分。
     */
    private fun readProvider(): Pair<Long, String?>? {
        val ctx = UidResolver.anyContext() ?: return null
        return try {
            val cr = ctx.contentResolver
            val ver = cr.query(
                ConfigContentProvider.payloadUri(),
                arrayOf(ConfigContentProvider.COL_VERSION), null, null, null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: return null
            if (ver <= 0L) return -1L to null            // 尚未发布过任何配置
            if (ver == providerVer) return ver to null   // 已处理版本（轻查询不搬载荷）
            val payload = cr.query(
                ConfigContentProvider.payloadUri(),
                arrayOf(ConfigContentProvider.COL_PAYLOAD), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
            ver to payload
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 文件通道（兜底）

    private sealed interface FileRead {
        object Unchanged : FileRead
        object Failed : FileRead
        class Content(val stamp: Pair<Long, Long>, val text: String) : FileRead
    }

    private fun readFile(): FileRead {
        val f = try { File(SNAPSHOT_PATH) } catch (_: Throwable) { return FileRead.Failed }
        if (!f.isFile || !f.canRead()) return FileRead.Failed
        val stamp = runCatching { f.lastModified() to f.length() }.getOrNull() ?: return FileRead.Failed
        if (stamp == fileStamp) return FileRead.Unchanged   // 票据只在成功后写入：失败重试不受阻
        val text = try { f.readText() } catch (_: Throwable) { return FileRead.Failed }
        return FileRead.Content(stamp, text)
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
