package com.example.menuui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.menuui.config.ConfigBus
import com.example.menuui.ui.ManagerApp
import com.example.menuui.ui.theme.AppTheme

/**
 * 管理端入口（薄壳）：初始化配置总线（加载持久化配置）后交给 ManagerApp。
 * 配置/发布/摇杆的所有业务都在 ConfigBus 与各页面中。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConfigBus.init(this)
        setContent {
            AppTheme {
                ManagerApp()
            }
        }
    }
}
