package com.example.menuui.publish

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

    data class Result(val ok: Boolean, val via: String, val message: String)

    /** 写缓存 → su 拷贝 → chmod；任一步失败即返回失败结果（带可读原因） */
    fun publish(context: Context, json: String): Result {
        val cache = File(context.cacheDir, "ven11-snapshot.json").apply { writeText(json) }
        val cmd = "mkdir -p $SNAPSHOT_DIR && cp '${cache.absolutePath}' $SNAPSHOT_PATH " +
            "&& chmod 644 $SNAPSHOT_PATH && chmod 771 $SNAPSHOT_DIR"
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
        }.also { r ->
            Log.i(TAG, "publish ok=${r.ok} via=${r.via}: ${r.message}")
        }
    }

    /**
     * 由管理端当前表单构造快照 JSON（schema 见 module 侧 SnapshotParser）。
     * 当前 UI 仍为通用模板（评审一.3 遗留），此处用表单值 + 固定演示策略构成
     * 最小闭环：masterEnabled / 目标包 / 静态环境（经纬度、Wi-Fi、基站、SIM）。
     * UI 改造成真配置页后，本函数替换为从真实字段构造。
     */
    fun buildDraftJson(targetPkg: String, lat: Double, lon: Double): String = """
    {
      "configVersion": ${System.currentTimeMillis() / 1000},
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
        {"pkg": "$targetPkg", "environmentId": 1}
      ],
      "routes": []
    }
    """.trimIndent()
}
