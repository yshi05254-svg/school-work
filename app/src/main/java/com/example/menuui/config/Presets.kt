package com.example.menuui.config

/**
 * 固定 9 组位置预设（2 组国外 + 7 个不同中国省份/直辖市），单选载入环境与 SIM。
 * 每组之间 GPS / 基站（服务+邻区）/ WiFi×2 / 蓝牙（适配器+设备）/ SIM 完全不同，
 * 且符合当地运营商与使用形态的真实分布；每组附当地语言与时区（国内统一 zh-CN / Asia/Shanghai）。
 * 基站 ci/tac/pci/arfcn 与 iccid/imsi 为形态合理的虚构值（不冒用真实在网数据）。
 */
object Presets {

    data class NamedPreset(
        val id: String,
        val label: String,
        val region: String,
        val env: EnvDraft,
        /** 该组配套的 SIM 段（预设切换时整体载入全局 sim） */
        val sim: SimDraft,
    )

    private fun cell(
        mcc: String, mnc: String, ci: Long, tac: Int, pci: Int, arfcn: Int,
        dbm: Int = -85, registered: Boolean = true,
    ) = CellDraft(
        radioType = "lte", mcc = mcc, mnc = mnc, ci = ci, tac = tac, pci = pci,
        arfcn = arfcn, registered = registered, signalDbm = dbm,
    )

    private fun wifi(ssid: String, bssid: String, freq: Int = 5180, dbm: Int = -55) =
        WifiDraft(ssid = ssid, bssid = bssid, frequencyMhz = freq, signalDbm = dbm)

    private fun bt(adapterMac: String, adapterName: String, vararg devices: BtDraft) =
        BtBundle(adapterMac, adapterName, devices.toList())

    data class BtBundle(val adapterMac: String, val adapterName: String, val devices: List<BtDraft>)

    private fun sim(
        carrier: String, mcc: String, mnc: String, iso: String,
        iccid: String, imsi: String, phone: String,
    ) = SimDraft(
        enabled = true,
        slots = listOf(
            SimSlotDraft(
                subId = 1, slotIndex = 0, active = true,
                iccid = iccid, imsi = imsi, imeiBase = "", phoneNumber = phone,
                mcc = mcc, mnc = mnc, carrierName = carrier, countryIso = iso,
            ),
        ),
    )

    val all: List<NamedPreset> by lazy {
        buildList {
            // ---- 北京 · 移动 ----
            run {
                val b = bt("02:1A:11:01:00:0A", "yanjing-PFT-PC",
                    BtDraft("02:1A:11:01:00:0B", "FreeBuds 5", -58),
                    BtDraft("02:1A:11:01:00:0C", "Redmi AirDots 3", -71))
                add(NamedPreset(
                    "cn_beijing", "北京", "华北 · 移动",
                    EnvDraft(
                        name = "北京", lat = 39.9042, lon = 116.4074, alt = 43.5, accuracy = 12f,
                        cells = listOf(
                            cell("460", "00", 12345001, 22601, 121, 1650),
                            cell("460", "00", 12345102, 22601, 54, 1650, dbm = -96, registered = false),
                        ),
                        wifis = listOf(
                            wifi("ChinaNet-Beijing", "02:1a:11:01:00:01"),
                            wifi("CMCC-Edcu-5G", "02:1a:11:01:00:02", 2412, -63),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860081190250123456", "460003190250123", "13800138000"),
                ))
            }
            // ---- 上海 · 电信 ----
            run {
                val b = bt("02:1A:11:02:00:0A", "SH-DiDi-driver",
                    BtDraft("02:1A:11:02:00:0B", "小米手环 9", -60),
                    BtDraft("02:1A:11:02:00:0C", "BMW 530Li", -76))
                add(NamedPreset(
                    "cn_shanghai", "上海", "华东 · 电信",
                    EnvDraft(
                        name = "上海", lat = 31.2304, lon = 121.4737, alt = 4.5, accuracy = 15f,
                        cells = listOf(
                            cell("460", "01", 21007456, 22117, 88, 100, dbm = -87),
                            cell("460", "01", 21007457, 22117, 219, 100, dbm = -97, registered = false),
                        ),
                        wifis = listOf(
                            wifi("i-Shanghai", "02:1a:11:02:00:01", 2412, -58),
                            wifi("Bund-Visitor-5G", "02:1a:11:02:00:02", 5180, -65),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国电信", "460", "01", "cn",
                        "89860320250345678901", "460012503456789", "13301330000"),
                ))
            }
            // ---- 广州 · 联通 ----
            run {
                val b = bt("02:1A:11:03:00:0A", "GZ-Canton-Tower",
                    BtDraft("02:1A:11:03:00:0B", "OPPO Enco X2", -57),
                    BtDraft("02:1A:11:03:00:0C", "vivo TWS 3", -70))
                add(NamedPreset(
                    "cn_guangzhou", "广州", "华南 · 联通",
                    EnvDraft(
                        name = "广州", lat = 23.1291, lon = 113.2644, alt = 11.0, accuracy = 12f,
                        cells = listOf(
                            cell("460", "01", 40123001, 24301, 301, 1300, dbm = -84),
                            cell("460", "01", 40123002, 24301, 152, 1300, dbm = -95, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Guangzhou-Metro-Free", "02:1a:11:03:00:01"),
                            wifi("Canton-Fair-WiFi", "02:1a:11:03:00:02", 2412, -66),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国联通", "460", "01", "cn",
                        "89860125200456789012", "460012004567890", "18601860000"),
                ))
            }
            // ---- 成都 · 移动 ----
            run {
                val b = bt("02:1A:11:04:00:0A", "panda-chengdu-home",
                    BtDraft("02:1A:11:04:00:0B", "Honor Earbuds 3 Pro", -61),
                    BtDraft("02:1A:11:04:00:0C", "Xiaomi Speaker Mini", -80))
                add(NamedPreset(
                    "cn_chengdu", "成都", "西南 · 移动",
                    EnvDraft(
                        name = "成都", lat = 30.5728, lon = 104.0668, alt = 500.0, accuracy = 14f,
                        cells = listOf(
                            cell("460", "00", 80123002, 26401, 52, 1850, dbm = -83),
                            cell("460", "00", 80123003, 26401, 173, 1850, dbm = -94, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Chengdu-Public-WiFi", "02:1a:11:04:00:01", 2412, -60),
                            wifi("Tianfu-Software-Park", "02:1a:11:04:00:02", 5180, -64),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860077200567890123", "460007205678901", "13901390000"),
                ))
            }
            // ---- 西安 · 联通 ----
            run {
                val b = bt("02:1A:11:05:00:0A", "xian-terracotta",
                    BtDraft("02:1A:11:05:00:0B", "Huawei Watch 4", -62),
                    BtDraft("02:1A:11:05:00:0C", "Nothing Ear (2)", -74))
                add(NamedPreset(
                    "cn_xian", "西安", "西北 · 联通",
                    EnvDraft(
                        name = "西安", lat = 34.3416, lon = 108.9398, alt = 405.0, accuracy = 14f,
                        cells = listOf(
                            cell("460", "01", 90210344, 27521, 177, 100, dbm = -86),
                            cell("460", "01", 90210345, 27521, 233, 100, dbm = -98, registered = false),
                        ),
                        wifis = listOf(
                            wifi("XiAn-Xianyang-Airport", "02:1a:11:05:00:01", 5180, -62),
                            wifi("BellTower-Guest", "02:1a:11:05:00:02", 2412, -68),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国联通", "460", "01", "cn",
                        "89860129200678901234", "460012006789012", "15601560000"),
                ))
            }
            // ---- 哈尔滨 · 移动 ----
            run {
                val b = bt("02:1A:11:06:00:0A", "harbin-ice-snow",
                    BtDraft("02:1A:11:06:00:0B", "AirPods 4", -59),
                    BtDraft("02:1A:11:06:00:0C", "Edifier NeoBuds Pro", -72))
                add(NamedPreset(
                    "cn_harbin", "哈尔滨", "东北 · 移动",
                    EnvDraft(
                        name = "哈尔滨", lat = 45.8038, lon = 126.5350, alt = 151.0, accuracy = 16f,
                        cells = listOf(
                            cell("460", "00", 55123007, 61801, 220, 1650, dbm = -89),
                            cell("460", "00", 55123008, 61801, 31, 1650, dbm = -99, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Harbin-Hotel-5G", "02:1a:11:06:00:01"),
                            wifi("IceSnow-World-Free", "02:1a:11:06:00:02", 2412, -70),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860046200789012345", "460004207890123", "14701470000"),
                ))
            }
            // ---- 乌鲁木齐 · 移动 ----
            run {
                val b = bt("02:1A:11:07:00:0A", "tianshan-Urumqi",
                    BtDraft("02:1A:11:07:00:0B", "Honor Choice 4", -63),
                    BtDraft("02:1A:11:07:00:0C", "JBL Tune Flex", -77))
                add(NamedPreset(
                    "cn_urumqi", "乌鲁木齐", "新疆 · 移动",
                    EnvDraft(
                        name = "乌鲁木齐", lat = 43.8256, lon = 87.6168, alt = 800.0, accuracy = 16f,
                        cells = listOf(
                            cell("460", "00", 65123011, 65101, 88, 1650, dbm = -90),
                            cell("460", "00", 65123012, 65101, 145, 1650, dbm = -99, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Urumqi-City-WiFi", "02:1a:11:07:00:01", 2412, -64),
                            wifi("Tianshan-Airport-5G", "02:1a:11:07:00:02", 5180, -69),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860065200890123456", "460006208901234", "13501350000"),
                ))
            }
            // ---- 东京 · docomo（国外）----
            run {
                val b = bt("02:1A:11:08:00:0A", "shibuya-iPhone",
                    BtDraft("02:1A:11:08:00:0B", "AirPods Pro", -55),
                    BtDraft("02:1A:11:08:00:0C", "Suica Reader-Gate", -68))
                add(NamedPreset(
                    "jp_tokyo", "东京（国外）", "日本 · docomo",
                    EnvDraft(
                        name = "东京", lat = 35.6812, lon = 139.7671, alt = 40.0, accuracy = 10f,
                        cells = listOf(
                            cell("440", "10", 110345012, 12001, 55, 1300, dbm = -78),
                            cell("440", "10", 110345013, 12001, 183, 1300, dbm = -91, registered = false),
                        ),
                        wifis = listOf(
                            wifi("TokyoStation-Free_Wifi", "02:1a:11:08:00:01", 5180, -50),
                            wifi("JR-EAST_Free_WiFi", "02:1a:11:08:00:02", 2412, -59),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "ja-JP", timezoneId = "Asia/Tokyo",
                    ),
                    sim("NTT docomo", "440", "10", "jp",
                        "89811000012345678901", "440100123456789", "09012345678"),
                ))
            }
            // ---- 纽约 · T-Mobile（国外·新增）----
            run {
                val b = bt("02:1A:11:09:00:0A", "nyc-model3-tesla",
                    BtDraft("02:1A:11:09:00:0B", "AirPods Max", -56),
                    BtDraft("02:1A:11:09:00:0C", "JBL Flip 6", -73))
                add(NamedPreset(
                    "us_newyork", "纽约（国外）", "美国 · T-Mobile",
                    EnvDraft(
                        name = "纽约", lat = 40.7128, lon = -74.0060, alt = 10.0, accuracy = 12f,
                        cells = listOf(
                            cell("310", "260", 976543210, 8761, 403, 2175, dbm = -82),
                            cell("310", "260", 976543211, 8761, 118, 2175, dbm = -93, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Starbucks-Free-WiFi", "02:1a:11:09:00:01", 2412, -55),
                            wifi("LinkNYC-Free", "02:1a:11:09:00:02", 5180, -61),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "en-US", timezoneId = "America/New_York",
                    ),
                    sim("T-Mobile", "310", "260", "us",
                        "89012600012345678901", "310260123456789", "2125550142"),
                ))
            }
        }
    }

    fun byId(id: String): NamedPreset? = all.firstOrNull { it.id == id }
}
