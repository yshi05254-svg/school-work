package com.example.menuui.publish

import android.content.ContentValues
import android.content.Context
import android.util.Log
import java.io.File

/**
 * 快照下发器（管理端 → hook 模块的配置写入端）。
 *
 * 通道与权限契约（评审三轮 #1）：
 *  - 模块侧从 [SNAPSHOT_PATH]（/data/local/tmp/ven11/snapshot.json）轮询读取；
 *  - 普通应用进程无权直写 /data/local/tmp（shell 属主，目录 0771）：本类把 JSON
 *    写进应用缓存目录，再借助 `su`（LSPosed 环境本身要求 root）以 root 身份
 *    mkdir + cp + chmod 644 完成放置；文件全局可读、父目录 others 位有 x，
 *    目标应用进程因此可读（模块侧 File.canRead() 校验依赖这一权限组合）；
 *  - 若 su 不可用（未 root / 无管理授权），直接写该路径会失败——此时返回失败
 *    结果并给出提示，绝不静默：链路断了要让用户知道，而不是以为配置已生效；
 *  - 调用流程：UI 表单 → [buildDraftJson]（与模块 SnapshotParser 的 JSON schema
 *    一一对应）→ [publish] → 目标进程 SnapshotStore 1s 内轮询到新 configVersion。
 *
 * ⚠ 外部必需组件说明：若部署环境没有 root（例如模块经 magisk systemless 挂载但
 * 管理端为普通 app），需由仓库之外的受控通道（adb shell 推送、受权限保护的
 * ContentProvider、或 Binder 服务）完成同路径写入；本类是 root 环境下的内置实现。
 */
object SnapshotPublisher {

    const val TAG = "VEN11-MGR"

    /** 与 module 侧 SnapshotStore.SNAPSHOT_PATH 保持一致（改动需两处同步） */
    const val SNAPSHOT_PATH = "/data/local/tmp/ven11/snapshot.json"
    const val SNAPSHOT_DIR = "/data/local/tmp/ven11"

    // ---- 模块配置通道契约（审查八 #1）：独立 APK 不能共享代码，与
    // module 侧 ConfigContentProvider 的常量同步维护 ----
    private const val PROVIDER_PAYLOAD_URI = "content://dev.ven11.module.config/payload"
    private const val PROVIDER_COL_PAYLOAD = "payload"

    data class Result(val ok: Boolean, val via: String, val message: String)

    /**
     * 发布入口（审查八 #1）：优先写模块 APK 的配置通道（ConfigContentProvider，
     * binder 不受 /data/local/tmp 的 SELinux 限制，system_server / phone / 普通应用
     * 读同一版本）；写侧是签名权限，管理端与模块同签名时可用，签名不符或模块未装
     * 时回落旧的 su 文件通道（仅应用进程可靠可读，框架侧可能被 SELinux 挡）。
     * 两条通道失败都不静默，结果里带可读原因与 adb 兜底指引。
     */
    fun publish(context: Context, json: String): Result {
        try {
            val values = ContentValues(1).apply { put(PROVIDER_COL_PAYLOAD, json) }
            context.contentResolver.insert(android.net.Uri.parse(PROVIDER_PAYLOAD_URI), values)
                ?: return fallbackToFile(context, json, "模块配置通道 insert 返回空")
            return Result(true, "provider", "已写入模块配置通道（system_server/phone 可读，≤1s 热生效）")
        } catch (t: Throwable) {
            // SecurityException = 签名权限不匹配 / 模块未安装；其余按通道故障处理
            return fallbackToFile(context, json, "模块配置通道不可用（${t.javaClass.simpleName}: ${t.message}）")
        }.also { r ->
            Log.i(TAG, "publish ok=${r.ok} via=${r.via}: ${r.message}")
        }
    }

    /** 文件通道兜底（su 落盘 /data/local/tmp；app 进程可读，框架侧可能被 SELinux 挡） */
    private fun fallbackToFile(context: Context, json: String, why: String): Result {
        val r = publishViaSu(context, json)
        val note = "文件通道仅应用进程可靠可读，system_server/phone 可能被 SELinux 挡"
        val prefix = if (r.ok) "$why；已回落文件通道" else why
        return r.copy(message = "$prefix（${r.message}）。$note")
    }

    /**
     * 写缓存 → su 拷贝到同目录临时文件 → chmod → mv 原子替换；任一步失败即返回失败
     * 结果（带可读原因）。此前直接 cp 覆盖目标文件：目标进程可能读到写了一半的 JSON，
     * 解析失败回落 EMPTY，语言/时区跟着闪一次（审查五）；tmp + mv 是同目录 rename，
     * 对读方原子。轮询侧只在解析成功后更新文件戳，失败会重试（审查八 #2）。
     */
    private fun publishViaSu(context: Context, json: String): Result {
        val cache = File(context.cacheDir, "ven11-snapshot.json.tmp").apply { writeText(json) }
        val backup = File(context.cacheDir, "ven11-snapshot.json")
        if (backup.exists()) backup.delete()
        if (!cache.renameTo(backup)) {
            return Result(false, "local", "缓存文件重命名失败（${cache.absolutePath}）")
        }
        val tmp = "$SNAPSHOT_PATH.tmp"
        val cmd = "mkdir -p $SNAPSHOT_DIR && cp '${backup.absolutePath}' '$tmp' " +
            "&& chmod 644 '$tmp' && mv -f '$tmp' '$SNAPSHOT_PATH' && chmod 771 $SNAPSHOT_DIR"
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val code = proc.waitFor()
            if (code == 0) {
                Result(true, "su", "已写入 $SNAPSHOT_PATH（0644）")
            } else {
                Result(
                    false, "su",
                    "su 退出码 $code：$output。设备未 root 或未授予管理端 root 时，" +
                        "请改用 adb shell 推送快照到 $SNAPSHOT_PATH（chmod 644）"
                )
            }
        } catch (t: Throwable) {
            Result(
                false, "su",
                "su 不可用（${t.javaClass.simpleName}: ${t.message}）。" +
                    "请改用 adb shell 推送快照到 $SNAPSHOT_PATH（chmod 644）"
            )
        }
    }

    /**
     * 由管理端当前表单构造快照 JSON（schema 见 module 侧 SnapshotParser）。
     * 目标包名一栏发**精确策略**（每包一条）：此前写死 com.example.target——那个包
     * 不存在，所有钩子的 PolicyResolver 都解析不到策略，全部透传真实值（审查五）。
     * 不要用 pkg=null 的全局策略兜底：全局策略会命中 phone/system_server 框架钩的
     * 所有调用方，作用范围远超 LSPosed 作用域。
     * 包名在此再做一道格式过滤（审查六 #4，与 MainActivity.parseTargets 同一正则）：
     * pkg 原样进 JSON，非法字符会破坏整个快照导致模块侧回落 EMPTY。
     */
    fun buildDraftJson(targetPackages: List<String>, lat: Double, lon: Double): String {
        val pkgRe = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
        val packages = targetPackages.filter { pkgRe.matches(it.trim()) }.distinct()
        val policies = packages.joinToString(",\n        ") { pkg ->
            """{"pkg": "$pkg", "environmentId": 1}"""
        }
        return """
    {
      "configVersion": ${System.currentTimeMillis()},
      "masterEnabled": true,
      "excludedPackages": [],
      "excludedUids": [],
      "jitter": {"enabled": true, "amplitudeMeters": 8},
      "sim": {
        "enabled": false,
        "slots": []
      },
      "environments": [
        {
          "id": 1,
          "lat": $lat,
          "lon": $lon,
          "accuracy": 12.0,
          "cells": [
            {"radioType": "lte", "mcc": "460", "mnc": "00", "ci": 12345001, "tac": 22601,
             "pci": 121, "arfcn": 1650, "registered": true, "signalDbm": -85}
          ],
          "wifis": [
            {"ssid": "Office-5G", "bssid": "02:1a:11:00:00:01", "signalDbm": -55, "frequencyMhz": 5180}
          ]
        }
      ],
      "policies": [
        $policies
      ],
      "routes": []
    }
        """.trimIndent()
    }
}
