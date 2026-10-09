package com.example.menuui.config

/**
 * 管理端配置模型：与模块侧 SnapshotParser 的 JSON schema 一一对应（见 SnapshotBuilder），
 * 但以"草稿"形态存在——所有可伪造域均可编辑。数值字段一律强类型：非法输入在 UI 层
 * 拦截（文本框本地态 → 解析成功才提交），配置里永远不会出现 NaN/空串坐标。
 *
 * 环境模型：快照只发布一个 environment（id=1）= 当前编辑的环境（预设选中即载入编辑器，
 * 自定义修改直接改它）；作用域默认策略与 per-app 策略全部绑定 environmentId=1。
 *
 * 目标应用：LSPosed 作用域里勾选的应用自动生效（作用域默认策略），[apps] 只用于
 * 单独设置（严格模式 / 停用 / 作用域外应用的定位伪装）。
 */
data class ManagerConfig(
    val masterEnabled: Boolean = true,
    /** 方案B：定位伪装由 system_server 执行（应用无需读到模块配置）；false=回退应用内伪装（应急） */
    val serverLocation: Boolean = true,
    val jitterEnabled: Boolean = true,
    val jitterAmplitudeMeters: Double = 8.0,
    /** 当前环境（预设选中载入 / 自定义编辑就地修改） */
    val env: EnvDraft = Presets.all[0].env,
    /** per-app 单独设置；作用域内应用无需添加（作用域默认策略已覆盖） */
    val apps: List<AppPolicy> = emptyList(),
    val excludedPackages: List<String> = emptyList(),
    val excludedUids: List<Int> = emptyList(),
    /** SIM 全局段（随预设切换整体载入，可在更多页编辑） */
    val sim: SimDraft = Presets.all[0].sim,
    /** 发布记录（最新在前，保留 20 条），持久化 */
    val history: List<PublishRecord> = emptyList(),
    /** 摇杆速度档（m/s）；悬浮窗服务实时读取 */
    val joystickSpeedMps: Double = JoystickPresets.WALK.speedMps,
    val joystickPresetId: String = JoystickPresets.WALK.id,
    /** 改动后自动发布（防抖 800ms）；关闭则只能手动点"发布配置" */
    val autoPublish: Boolean = true,
    /** 用户新建的位置卡片（id 以 custom_ 开头），排在内置预设之后，持久化 */
    val customPresets: List<Presets.NamedPreset> = emptyList(),
) {
    val targetPackages: List<String> get() = apps.map { it.pkg }
}

/** 基站草稿；mcc/mnc 用 String 保留前导零（移动 mnc="00"，评审二 #5 同源契约） */
data class CellDraft(
    val radioType: String = "lte",
    val mcc: String = "460",
    val mnc: String = "00",
    val ci: Long = 12345001,
    val lac: Int = 0,
    val tac: Int = 22601,
    val pci: Int = 121,
    val psc: Int = 0,
    val cpid: Int = 0,
    val arfcn: Int = 1650,
    val registered: Boolean = true,
    val signalDbm: Int = -85,
)

data class WifiDraft(
    val ssid: String = "Office-5G",
    val bssid: String = "02:1a:11:00:00:01",
    val signalDbm: Int = -55,
    val frequencyMhz: Int = 5180,
)

data class BtDraft(
    val address: String = "02:1A:11:00:00:0A",
    val name: String = "ven11-bt",
    val rssi: Int = -60,
)

/** 虚拟环境草稿：模块可伪造的位置域全量字段（GPS/基站/WiFi/蓝牙） */
data class EnvDraft(
    val name: String = "自定义",
    val lat: Double = 39.9042,
    val lon: Double = 116.4074,
    val alt: Double = 43.5,
    val accuracy: Float = 12f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val cells: List<CellDraft> = emptyList(),
    val wifis: List<WifiDraft> = emptyList(),
    val btDevices: List<BtDraft> = emptyList(),
    val btAdapterAddress: String = "",
    val btAdapterName: String = "",
    /** 语言/时区建议值（国外预设带，写入 per-app policy） */
    val languageTag: String = "",
    val timezoneId: String = "",
)

/**
 * per-app 策略草稿（应用页每应用仅两项设置）：
 *  - enabled=false 的应用并入快照 excludedPackages（即使在作用域内也全域透传真实值）；
 *  - strictMode=true 时该应用无环境可用则定位直接失败（默认透传）。
 */
data class AppPolicy(
    val pkg: String,
    val enabled: Boolean = true,
    val strictMode: Boolean = false,
)

data class SimSlotDraft(
    val subId: Int = 1,
    val slotIndex: Int = 0,
    val active: Boolean = true,
    val iccid: String = "",
    val imsi: String = "",
    val imeiBase: String = "",
    val phoneNumber: String = "",
    val mcc: String = "460",
    val mnc: String = "00",
    val carrierName: String = "中国移动",
    val countryIso: String = "cn",
)

data class SimDraft(
    val enabled: Boolean = false,
    val slots: List<SimSlotDraft> = emptyList(),
)

data class PublishRecord(
    val at: Long,
    val ok: Boolean,
    val via: String,
    val message: String,
)

/** 摇杆速度档位 */
data class JoystickPreset(val id: String, val label: String, val speedMps: Double)

object JoystickPresets {
    val WALK = JoystickPreset("walk", "步行", 1.4)
    val RUN = JoystickPreset("run", "跑步", 4.0)
    val RIDE = JoystickPreset("ride", "骑行", 8.0)
    val DRIVE = JoystickPreset("drive", "驾驶", 16.7)
    val CUSTOM = JoystickPreset("custom", "自定义", 0.0)
    val ALL = listOf(WALK, RUN, RIDE, DRIVE, CUSTOM)
    fun byId(id: String): JoystickPreset = ALL.firstOrNull { it.id == id } ?: WALK
}
