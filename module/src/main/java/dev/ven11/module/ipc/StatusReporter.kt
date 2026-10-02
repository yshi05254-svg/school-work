package dev.ven11.module.ipc

import dev.ven11.module.ProbeLog

/**
 * hook 进程内运行状态的最小汇总（供排障 / UI 侧展示）。
 * 全部 @Volatile 单写多读，无锁。
 */
object StatusReporter {

    /** 累计拦截替换次数（GPS 路径为主，各域 hit 计数也汇总到这） */
    @Volatile
    var hookCount: Int = 0

    /** 最近一次成功替换的墙钟时间；0 = 尚未命中 */
    @Volatile
    var lastSpoofAtMs: Long = 0L

    fun markSpoof() {
        lastSpoofAtMs = System.currentTimeMillis()
    }

    fun dump(): String =
        "hookCount=$hookCount lastSpoofAt=${if (lastSpoofAtMs == 0L) "-" else lastSpoofAtMs}\n" +
            ProbeLog.tail(16).joinToString("\n")
}
