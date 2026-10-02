package com.example.menuui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.menuui.config.AppMenus
import com.example.menuui.navigation.MenuNavHost
import com.example.menuui.publish.SnapshotPublisher
import com.example.menuui.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    /** 目标包名缺省值（与 LSPosed 作用域一致）；可在"系统设置 → 目标包名"输入框覆盖。
     *  此前写死 com.example.target——包不存在，策略永远解析不到（审查五）。 */
    private val defaultTargets = listOf("com.tencent.mm", "com.autonavi.minimap")

    /** su 需等待 root 授权，同步在主线程执行易 ANR：发布固定走后台线程 */
    private val publishExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MenuNavHost(
                    registry = AppMenus.registry,
                    onCustomAction = { key ->
                        when (key) {
                            "publish" -> publishSnapshot(targetPackages = defaultTargets)
                            else -> Toast.makeText(this, "点击：$key", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSubmit = { pageId, values ->
                        // 只在系统设置页提交时发布（审查五：此前任何页面的保存按钮都会
                        // 触发发布，且"数据采集"页输入的数值会被当成纬度用）
                        if (pageId == "settings") {
                            publishSnapshot(targetPackages = parseTargets(values["targets"]))
                        } else {
                            Toast.makeText(this, "已保存（本地演示，不发布配置）", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            }
        }
    }

    /** 逗号/空格/分号分隔的包名列表；留空回落 [defaultTargets] */
    private fun parseTargets(raw: String?): List<String> =
        raw?.split(',', '，', ' ', ';', '；')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: defaultTargets

    /**
     * 配置下发的唯一入口：构造快照 JSON → SnapshotPublisher 原子写入
     * /data/local/tmp/ven11/snapshot.json → 目标进程 SnapshotStore ≤1s 轮询生效。
     * 结果（含失败原因与 adb 兜底指引）回 UI 线程 Toast 反馈，链路断开不静默。
     */
    private fun publishSnapshot(
        targetPackages: List<String>,
        lat: Double = 39.9042,
        lon: Double = 116.4074,
    ) {
        val json = SnapshotPublisher.buildDraftJson(
            targetPackages = targetPackages, lat = lat, lon = lon,
        )
        publishExecutor.execute {
            val r = SnapshotPublisher.publish(this, json)
            runOnUiThread {
                Toast.makeText(this, r.message, if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
            }
        }
    }
}
