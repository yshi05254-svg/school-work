package com.example.menuui.config

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.example.menuui.model.MenuRegistry
import com.example.menuui.model.Validators
import com.example.menuui.model.primaryPage
import com.example.menuui.model.secondaryPage

/**
 * ★ 唯一需要经常修改的文件 ★
 * 增删按钮 / 输入框、改文字、改图标、改跳转，都只在这里改。
 * 数量超出范围、id 重复、跳转目标不存在，启动时会直接报错提示。
 */
object AppMenus {

    // ───────── 一级菜单（8–10 项）─────────
    val home = primaryPage(id = "home", title = "工作台") {
        input("keyword", "快速搜索", hint = "输入设备编号或名称", icon = Icons.Filled.Search, ime = ImeAction.Search)
        button("device", "设备管理", Icons.Filled.Build, opens = "device")
        button("collect", "数据采集", Icons.Filled.Edit, opens = "collect")
        button("report", "报表统计", Icons.Filled.DateRange)
        button("message", "消息中心", Icons.Filled.Notifications)
        button("user", "用户管理", Icons.Filled.Person)
        button("task", "任务列表", Icons.AutoMirrored.Filled.List)
        button("settings", "系统设置", Icons.Filled.Settings, opens = "settings")
        button("help", "帮助反馈", Icons.Filled.Info)
    }

    // ───────── 二级菜单（3–6 项）─────────
    val device = secondaryPage(id = "device", title = "设备管理") {
        input("sn", "设备编号", hint = "SN-20261002", required = true, validator = Validators.maxLength(32))
        input("name", "设备名称", hint = "泵站 A-01")
        input("location", "安装位置", hint = "3 号楼 2 层", ime = ImeAction.Done)
        button("scan", "扫码录入", Icons.Filled.Star, subtitle = "用摄像头识别设备二维码")
        submit("保存设备")
    }

    val collect = secondaryPage(id = "collect", title = "数据采集") {
        input("value", "采集数值", hint = "36.5", type = KeyboardType.Decimal, required = true, validator = Validators.number)
        input("remark", "备注", hint = "现场情况说明", ime = ImeAction.Done)
        submit("提交数据")
    }

    val settings = secondaryPage(id = "settings", title = "系统设置") {
        input("server", "服务器地址", hint = "https://api.example.com", type = KeyboardType.Uri, required = true, validator = Validators.url)
        input("interval", "同步间隔（分钟）", hint = "15", type = KeyboardType.Number, validator = Validators.intIn(1..1440), ime = ImeAction.Done)
        // 评审三轮 #1：配置下发入口（构造快照 → /data/local/tmp/ven11/snapshot.json）
        button("publish", "发布配置到模块", Icons.Filled.Refresh, subtitle = "需 root；目标进程 ≤1s 热生效")
        button("clear_cache", "清除缓存", Icons.Filled.Delete)
        button("about", "关于", Icons.Filled.Info)
        submit("保存设置")
    }

    val registry = MenuRegistry(root = home, children = listOf(device, collect, settings))
}
