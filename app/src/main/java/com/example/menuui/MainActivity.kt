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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MenuNavHost(
                    registry = AppMenus.registry,
                    onCustomAction = { key ->
                        when (key) {
                            "publish" -> publishSnapshot()
                            else -> Toast.makeText(this, "点击：$key", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onSubmit = { pageId, values ->
                        // 评审三轮 #1：配置下发闭环——表单提交即触发快照发布
                        // （演示用：采集页数值作纬度，缺省北京坐标）
                        if (pageId == "collect") {
                            val lat = values["value"]?.toDoubleOrNull() ?: 39.9042
                            publishSnapshot(lat = lat, lon = 116.4074)
                        } else {
                            publishSnapshot()
                        }
                    },
                )
            }
        }
    }

    /**
     * 配置下发的唯一入口（评审三轮 #1）：构造快照 JSON → SnapshotPublisher 写入
     * /data/local/tmp/ven11/snapshot.json → 目标进程 SnapshotStore ≤1s 轮询生效。
     * 结果（含失败原因与 adb 兜底指引）以 Toast 反馈，链路断开不静默。
     */
    private fun publishSnapshot(lat: Double = 39.9042, lon: Double = 116.4074) {
        val json = SnapshotPublisher.buildDraftJson(targetPkg = "com.example.target", lat = lat, lon = lon)
        val r = SnapshotPublisher.publish(this, json)
        Toast.makeText(this, r.message, if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }
}
