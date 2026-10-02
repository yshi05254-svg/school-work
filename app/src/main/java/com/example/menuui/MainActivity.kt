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
                    onCustomAction = { key, values ->
                        when (key) {
                            // 发布按钮与保存按钮同源：都读"目标包名"输入框（审查六 #4），
                            // 且发布不经过服务器地址的必填/URL 校验
                            "publish" -> publishSnapshot(targetPackages = parseTargets(values["targets"]))
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

    /**
     * 逗号/空格/分号分隔的包名列表；留空回落 [defaultTargets]。
     * 逐个做包名格式校验（审查六 #4）：包名会原样拼进快照 JSON，带引号/反斜杠的
     * 输入会破坏 JSON，模块侧解析失败回落 EMPTY 而管理端仍提示成功——不合规格的
     * 段直接丢弃并提示。
     */
    private fun parseTargets(raw: String?): List<String> {
        val parsed = raw?.split(',', '，', ' ', ';', '；')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (parsed.isEmpty()) return defaultTargets
        val valid = parsed.filter { PKG_RE.matches(it) }
        val dropped = parsed.size - valid.size
        if (dropped > 0) {
            Toast.makeText(this, "已忽略 $dropped 个不合法的包名", Toast.LENGTH_SHORT).show()
        }
        return valid.ifEmpty { defaultTargets }
    }

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

    private companion object {
        /** Android 包名：点分段，段首为字母，段内字母/数字/下划线 */
        val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
