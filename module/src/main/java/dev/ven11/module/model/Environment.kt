package dev.ven11.module.model

/**
 * 虚拟环境模型（model 契约，客户端 / 框架两侧共用）。
 *
 * 关键契约（评审 #5 / 审查四）：
 *  - mcc / mnc 一律 String：Int 会丢前导零（中国移动 46000 的 mnc="00"），
 *    SIM 与 Cell 两域共用同一契约，避免同一 PLMN 两处拼出不同结果；
 *  - btDevices 可空：快照未配置蓝牙设备时保持 null，蓝牙钩 orEmpty() 兜底；
 *  - cells[0] 无特殊语义，服务小区由 VirtualCell.registered 标记（CellInfoFactory.pickServing）；
 *  - wifis 首项视为当前连接的网络（ClientWifiHooks 现约定，模型加 connected 字段后应显式标记）。
 */
data class VirtualEnvironment(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double = 0.0,
    val accuracy: Float = 15f,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val cells: List<VirtualCell> = emptyList(),
    val wifis: List<VirtualWifi> = emptyList(),
    val btDevices: List<BtDeviceSpec>? = null,
    val btAdapterAddress: String? = null,
    val btAdapterName: String? = null,
)

/** radioType: "gsm"|"wcdma"|"lte"|"tdscdma"|"nr"（umts/4g/5g 等别名由 CellInfoFactory 解析） */
data class VirtualCell(
    val radioType: String,
    val mcc: String,
    val mnc: String,
    /** 2G/3G/4G 小区 id；NR 为 36 位 NCI，保持 long 不截断 */
    val ci: Long,
    val lac: Int = 0,
    val tac: Int = 0,
    val pci: Int = 0,
    val psc: Int = 0,
    val cpid: Int = 0,
    val arfcn: Int = 0,
    val registered: Boolean = false,
    val signalDbm: Int = -85,
)

data class VirtualWifi(
    val ssid: String,
    val bssid: String,
    val capabilities: String = "[ESS]",
    val signalDbm: Int = -55,
    val frequencyMhz: Int = 2412,
)

/** 快照中一台蓝牙设备；address 未归一化（大写化 + 格式校验由蓝牙钩完成） */
data class BtDeviceSpec(
    val address: String,
    val name: String? = null,
    val bondState: Int? = null,
    val rssi: Int? = null,
)

/** GPS 抖动：静态环境 + 每秒桶高斯样本（LocationFactory） */
data class GpsJitter(
    val enabled: Boolean = false,
    val amplitudeMeters: Double = 0.0,
)

/** 路线回放：points 首尾相接由 loop 决定是否循环；点数校验由消费方完成（<2 视为无路线） */
data class Route(
    val id: Long,
    val points: List<GeoPoint>,
    val speedMps: Double = 8.33,
    val loop: Boolean = true,
)

data class GeoPoint(val lat: Double, val lon: Double)
