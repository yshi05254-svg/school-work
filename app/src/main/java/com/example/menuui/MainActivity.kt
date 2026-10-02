package com.example.menuui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.menuui.config.AppMenus
import com.example.menuui.navigation.MenuNavHost
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
                        // 在这里按 key 分发业务：when (key) { "scan" -> ...; "clear_cache" -> ... }
                        Toast.makeText(this, "点击：$key", Toast.LENGTH_SHORT).show()
                    },
                    onSubmit = { pageId, values ->
                        // 在这里保存 / 上传：values 是 inputId -> 输入内容
                        Toast.makeText(this, "[$pageId] $values", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }
}
