package dev.ven11.module.ipc

import android.os.SystemClock
import dev.ven11.module.ProbeLog
import dev.ven11.module.model.Snapshot
import dev.ven11.module.model.SnapshotParser
import java.io.File

/**
 * 快照仓库（hook 进程侧）：轮询读取管理端写入的 JSON 快照文件。
 *
 * 通道选择说明：目标应用进程与模块管理端是不同 uid，Android 10+ 无 world-readable
 * 数据目录，跨进程文件共享最简单可靠的落点是 /data/local/tmp（管理端以 root/shell
 * 写入，所有应用可读）。后续换 content provider 通道时只改本文件。
 *
 * 语义：
 *  - configVersion 不变 → 保持原实例引用（各域以对象身份为缓存票据，不重建）；
 *  - 文件缺失 / 解析失败 → 回落 Snapshot.EMPTY（masterEnabled=false，全部透传真实值，
 *    宁可失效不可伪造错）；
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
                ProbeLog.log(
                    "SNAP version ${current.configVersion} -> ${next.configVersion} " +
                        "(env=${next.environments.size} policies=${next.policies.size} " +
                        "simSlots=${next.sim.slots.size} routes=${next.routes.size})"
                )
                current = next
            } else if (next !== current && next.configVersion == current.configVersion) {
                // 版本未变但文件内容重建了实例：维持旧实例，保证各域缓存票据稳定
            }
        }
        return current
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
