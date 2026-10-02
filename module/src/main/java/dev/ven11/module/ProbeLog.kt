package dev.ven11.module

import android.os.SystemClock
import android.util.Log

/**
 * 进程内探测日志：logcat + 环形缓冲。
 *
 * 各 hook 域的命中 / 失败日志统一走这里（限频逻辑在各域自己实现——它们才知道
 * 什么粒度算"重复"）。环形缓冲供排障时一次性导出（StatusReporter.dump）。
 */
object ProbeLog {

    private const val TAG = "VEN11"
    private const val CAPACITY = 512

    private val buffer = ArrayDeque<String>(CAPACITY)

    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast("${SystemClock.elapsedRealtime()} $msg")
        }
    }

    fun tail(n: Int = 64): List<String> = synchronized(buffer) {
        buffer.toList().takeLast(n.coerceIn(1, CAPACITY))
    }
}
