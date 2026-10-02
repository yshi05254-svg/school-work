package dev.ven11.module.ipc

import android.os.SystemClock
import dev.ven11.module.ProbeLog
import dev.ven11.module.model.Snapshot
import dev.ven11.module.model.SnapshotParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 快照仓库（hook 进程侧）：轮询读取管理端写入的 JSON 快照文件。
 *
 * 通道选择说明：目标应用进程与模块管理端是不同 uid，Android 10+ 无 world-readable
 * 数据目录，跨进程文件共享最简单可靠的落点是 /data/local/tmp（管理端以 root/shell
 * 写入，所有应用可读）。后续换 content provider 通道时只改本文件。
 *
 * 写入端（评审三轮 #1）：管理端 app 的 SnapshotPublisher（app/
 * .../publish/SnapshotPublisher.kt）经 su 以 root 完成放置并 chmod 644——
 * 本侧的 File.canRead() 依赖"文件 0644 + 目录链 others 可穿越"这一权限组合，
 * 不是无条件成立的跨进程契约；部署时若走 adb 推送需保证同等权限。
 *
 * 语义：
 *  - configVersion 不变 → 保持原实例引用（各域以对象身份为缓存票据，不重建）；
 *    ⚠ 管理端写入的 configVersion 必须从 1 开始：0 保留给 EMPTY（无配置），
 *    用于驱动 EMPTY ↔ 有效配置的双向版本变化检测与监听回调；
 *  - 文件缺失 / 解析失败 → 回落 Snapshot.EMPTY（masterEnabled=false，全部透传真实值，
 *    宁可失效不可伪造错），语言/时区监听方随之恢复真实值；
 *  - 版本变化 → PolicyResolver 缓存失效 + 通知注册的监听方（语言/时区热更新），
 *    监听在轮询锁内串行执行，须快速返回；
 *  - 节流：POLL_INTERVAL_MS 内复用上次结果，热路径只付一次时钟读 + volatile 读。
 */
object SnapshotStore {

    /** 管理端写入路径；需与 UI / 下发器约定一致 */
    const val SNAPSHOT_PATH = "/data/local/tmp/ven11/snapshot.json"

    private const val POLL_INTERVAL_MS = 1_000L

    @Volatile
    private var current: Snapshot = Snapshot.EMPTY

    @Volatile
    private var lastPollAtMs: Long = 0L

    private val pollLock = Any()

    fun current(): Snapshot {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPollAtMs < POLL_INTERVAL_MS) return current
        synchronized(pollLock) {
            if (SystemClock.elapsedRealtime() - lastPollAtMs < POLL_INTERVAL_MS) return current
            lastPollAtMs = SystemClock.elapsedRealtime()
            val next = readSnapshot()
            if (next !== current && next.configVersion != current.configVersion) {
                val old = current
                ProbeLog.log(
                    "SNAP version ${old.configVersion} -> ${next.configVersion} " +
                        "(env=${next.environments.size} policies=${next.policies.size} " +
                        "simSlots=${next.sim.slots.size} routes=${next.routes.size})"
                )
                current = next
                PolicyResolver.invalidate() // 总开关/排除名单变化必须立刻生效，不吃 250ms TTL
                notifyListeners(old, next)
            }
            // 版本未变但文件内容重建了实例：维持旧实例，保证各域缓存票据稳定
        }
        return current
    }

    // ---------------------------------------------------------------- 配置版本监听

    /** 收到快照版本变化（含 EMPTY ↔ 有效配置的双向切换）；在轮询锁内串行调用，须快速返回 */
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

    private fun readSnapshot(): Snapshot = try {
        val f = File(SNAPSHOT_PATH)
        if (!f.isFile || !f.canRead()) Snapshot.EMPTY
        else SnapshotParser.parse(f.readText())
    } catch (t: Throwable) {
        ProbeLog.log("SNAP-ERR read: ${t.javaClass.simpleName}: ${t.message}")
        Snapshot.EMPTY
    }
}
