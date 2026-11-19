package com.example.menuui.config

/**
 * 固定 9 组位置预设（2 组国外 + 7 个不同中国省份/直辖市），单选载入环境与 SIM。
 * 每组之间 GPS / 基站（服务+邻区）/ WiFi×2 / 蓝牙（适配器+设备）/ SIM 完全不同，
 * 且符合当地运营商与使用形态的真实条件：
 *  - 基站：PLMN 对齐当地运营商（移动 460-00、联通 460-01、电信 LTE 460-11、docomo 440-10、T-Mobile 310-260），
 *    TAC 采用对应城市真实规划区段，ECI (ci = eNB_ID * 256 + sector) 满足 28-bit 规范且同站扇区连续，
 *    PCI 满足同站模 3 错开，EARFCN 对应运营商主力频段；
 *  - WiFi / 蓝牙：BSSID 与 MAC 均采用真实硬件厂商 IEEE OUI（华为、H3C、锐捷、TP-Link、中兴、小米、Aruba、Meraki 等
 *    全局单播地址，首字节低 2 位为 00，避免 02:xx 本地管理虚拟 MAC 被定位 SDK 识别过滤），频点对应真实信道；
 *  - SIM：ICCID 符合 ITU-T E.118 各运营商省份编码规范并通过 Luhn 校验位校验，IMSI 与 PLMN/HLR 归属地一致，
 *    手机号采用对应城市真实 HLR 号段。
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
            // ---- 北京 · 移动（东城区正义路/长安街，B40 EARFCN 38950，TAC 4104，eNB 114826）----
            run {
                val b = bt("10:47:80:3A:6C:12", "yanjing-PFT-PC",
                    BtDraft("88:44:77:5B:19:C4", "FreeBuds 5", -58),
                    BtDraft("28:6C:07:9E:42:D1", "Redmi AirDots 3", -71))
                add(NamedPreset(
                    "cn_beijing", "北京", "华北 · 移动",
                    EnvDraft(
                        name = "北京", lat = 39.9042, lon = 116.4074, alt = 43.5, accuracy = 12f,
                        cells = listOf(
                            cell("460", "00", 29395457, 4104, 121, 38950, dbm = -82),
                            cell("460", "00", 29395458, 4104, 122, 38950, dbm = -91, registered = false),
                        ),
                        wifis = listOf(
                            wifi("ChinaNet-Beijing", "48:4c:29:6a:31:c0", 5180, -53),
                            wifi("CMCC-Edcu-5G", "38:91:d5:84:1b:e8", 2412, -62),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860012012360847257", "460001028641953", "13810286419"),
                ))
            }
            // ---- 上海 · 电信（黄浦区人民广场，LTE 460-11 B3 EARFCN 1825，TAC 13573，eNB 331842）----
            run {
                val b = bt("64:CC:2E:41:89:3B", "SH-DiDi-driver",
                    BtDraft("8C:53:C3:72:A4:18", "小米手环 9", -60),
                    BtDraft("A0:56:F3:2D:61:9E", "BMW 530Li", -76))
                add(NamedPreset(
                    "cn_shanghai", "上海", "华东 · 电信",
                    EnvDraft(
                        name = "上海", lat = 31.2304, lon = 121.4737, alt = 4.5, accuracy = 15f,
                        cells = listOf(
                            cell("460", "11", 84951553, 13573, 87, 1825, dbm = -84),
                            cell("460", "11", 84951554, 13573, 88, 1825, dbm = -93, registered = false),
                        ),
                        wifis = listOf(
                            wifi("i-Shanghai", "58:69:6c:3d:92:10", 2437, -56),
                            wifi("Bund-Visitor-5G", "74:a7:8e:5c:41:a8", 5745, -64),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国电信", "460", "11", "cn",
                        "89860323209158403624", "460110321827564", "13311827564"),
                ))
            }
            // ---- 广州 · 联通（越秀区公园前，B3 EARFCN 1650，TAC 42246，eNB 460852）----
            run {
                val b = bt("A4:3B:FA:65:12:8C", "GZ-Canton-Tower",
                    BtDraft("1C:48:CE:83:27:5A", "OPPO Enco X2", -57),
                    BtDraft("30:B1:B5:4E:91:C2", "vivo TWS 3", -70))
                add(NamedPreset(
                    "cn_guangzhou", "广州", "华南 · 联通",
                    EnvDraft(
                        name = "广州", lat = 23.1291, lon = 113.2644, alt = 11.0, accuracy = 12f,
                        cells = listOf(
                            cell("460", "01", 117978113, 42246, 301, 1650, dbm = -81),
                            cell("460", "01", 117978114, 42246, 302, 1650, dbm = -92, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Guangzhou-Metro-Free", "00:74:9c:8b:24:60", 5180, -54),
                            wifi("Canton-Fair-WiFi", "50:bd:5f:39:c6:18", 2462, -65),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国联通", "460", "01", "cn",
                        "89860123851603948158", "460015120085937", "18620085937"),
                ))
            }
            // ---- 成都 · 移动（青羊/锦江天府广场，B39 EARFCN 38400，TAC 37131，eNB 741428）----
            run {
                val b = bt("70:7B:E8:1D:54:A6", "panda-chengdu-home",
                    BtDraft("24:44:27:8A:3C:E1", "Honor Earbuds 3 Pro", -61),
                    BtDraft("64:CC:2E:B7:15:49", "Xiaomi Speaker Mini", -80))
                add(NamedPreset(
                    "cn_chengdu", "成都", "西南 · 移动",
                    EnvDraft(
                        name = "成都", lat = 30.5728, lon = 104.0668, alt = 500.0, accuracy = 14f,
                        cells = listOf(
                            cell("460", "00", 189805569, 37131, 51, 38400, dbm = -83),
                            cell("460", "00", 189805570, 37131, 52, 38400, dbm = -94, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Chengdu-Public-WiFi", "74:25:8a:4e:73:20", 2412, -59),
                            wifi("Tianfu-Software-Park", "dc:d2:fc:91:58:b4", 5745, -64),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860022222351930487", "460008052734168", "13980527341"),
                ))
            }
            // ---- 西安 · 联通（碑林/莲湖钟楼，B3 EARFCN 1650，TAC 57613，eNB 972845）----
            run {
                val b = bt("88:3F:D3:52:79:C8", "xian-terracotta",
                    BtDraft("10:47:80:C4:38:62", "Huawei Watch 4", -62),
                    BtDraft("2C:BE:EB:19:84:D5", "Nothing Ear (2)", -74))
                add(NamedPreset(
                    "cn_xian", "西安", "西北 · 联通",
                    EnvDraft(
                        name = "西安", lat = 34.3416, lon = 108.9398, alt = 405.0, accuracy = 14f,
                        cells = listOf(
                            cell("460", "01", 249048321, 57613, 177, 1650, dbm = -85),
                            cell("460", "01", 249048322, 57613, 178, 1650, dbm = -96, registered = false),
                        ),
                        wifis = listOf(
                            wifi("XiAn-Xianyang-Airport", "38:91:d5:c2:67:40", 5180, -61),
                            wifi("BellTower-Guest", "34:96:72:7b:19:e4", 2437, -68),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国联通", "460", "01", "cn",
                        "89860123884372051961", "460018409206483", "15609206483"),
                ))
            }
            // ---- 哈尔滨 · 移动（松北/道里区，B39 EARFCN 38400，TAC 18952，eNB 315436）----
            run {
                val b = bt("F0:99:B6:34:8E:21", "harbin-ice-snow",
                    BtDraft("D0:81:7A:69:1C:B8", "AirPods 4", -59),
                    BtDraft("40:EF:4C:92:53:7A", "Edifier NeoBuds Pro", -72))
                add(NamedPreset(
                    "cn_harbin", "哈尔滨", "东北 · 移动",
                    EnvDraft(
                        name = "哈尔滨", lat = 45.8038, lon = 126.5350, alt = 151.0, accuracy = 16f,
                        cells = listOf(
                            cell("460", "00", 80751617, 18952, 220, 38400, dbm = -88),
                            cell("460", "00", 80751618, 18952, 221, 38400, dbm = -97, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Harbin-Hotel-5G", "ec:41:18:5a:82:30", 5745, -57),
                            wifi("IceSnow-World-Free", "58:69:6c:a4:39:78", 2412, -69),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860022082346182937", "460003642851792", "13936428517"),
                ))
            }
            // ---- 乌鲁木齐 · 移动（天山区，B39 EARFCN 38400，TAC 39691，eNB 843812）----
            run {
                val b = bt("24:44:27:71:A8:5C", "tianshan-Urumqi",
                    BtDraft("88:3F:D3:1E:64:92", "Honor Choice 4", -63),
                    BtDraft("00:23:4D:8B:2F:16", "JBL Tune Flex", -77))
                add(NamedPreset(
                    "cn_urumqi", "乌鲁木齐", "新疆 · 移动",
                    EnvDraft(
                        name = "乌鲁木齐", lat = 43.8256, lon = 87.6168, alt = 800.0, accuracy = 16f,
                        cells = listOf(
                            cell("460", "00", 216015873, 39691, 87, 38400, dbm = -89),
                            cell("460", "00", 216015874, 39691, 88, 38400, dbm = -98, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Urumqi-City-WiFi", "c8:f9:f9:36:4d:10", 2462, -63),
                            wifi("Tianshan-Airport-5G", "48:4c:29:b5:72:88", 5180, -68),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "zh-CN", timezoneId = "Asia/Shanghai",
                    ),
                    sim("中国移动", "460", "00", "cn",
                        "89860062302373049168", "460007992463815", "13579924638"),
                ))
            }
            // ---- 东京 · docomo（国外，千代田区东京站，B1 EARFCN 300，TAC 8721，eNB 106542）----
            run {
                val b = bt("F0:99:B6:82:41:D3", "shibuya-iPhone",
                    BtDraft("D0:81:7A:2C:95:64", "AirPods Pro", -55),
                    BtDraft("00:1B:DC:74:3A:89", "Suica Reader-Gate", -68))
                add(NamedPreset(
                    "jp_tokyo", "东京（国外）", "日本 · docomo",
                    EnvDraft(
                        name = "东京", lat = 35.6812, lon = 139.7671, alt = 40.0, accuracy = 10f,
                        cells = listOf(
                            cell("440", "10", 27274753, 8721, 54, 300, dbm = -78),
                            cell("440", "10", 27274754, 8721, 55, 300, dbm = -90, registered = false),
                        ),
                        wifis = listOf(
                            wifi("TokyoStation-Free_Wifi", "00:24:6c:58:91:a0", 5180, -50),
                            wifi("JR-EAST_Free_WiFi", "f0:7f:06:3c:74:28", 2412, -59),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "ja-JP", timezoneId = "Asia/Tokyo",
                    ),
                    sim("NTT docomo", "440", "10", "jp",
                        "89811000246183049256", "440103241865927", "09031426815"),
                ))
            }
            // ---- 纽约 · T-Mobile（国外，曼哈顿下城，B4 EARFCN 2100，TAC 21508，eNB 45328）----
            run {
                val b = bt("4C:FC:AA:39:72:B4", "nyc-model3-tesla",
                    BtDraft("D0:81:7A:91:4E:3C", "AirPods Max", -56),
                    BtDraft("00:23:4D:5C:83:A1", "JBL Flip 6", -73))
                add(NamedPreset(
                    "us_newyork", "纽约（国外）", "美国 · T-Mobile",
                    EnvDraft(
                        name = "纽约", lat = 40.7128, lon = -74.0060, alt = 10.0, accuracy = 12f,
                        cells = listOf(
                            cell("310", "260", 11603969, 21508, 402, 2100, dbm = -82),
                            cell("310", "260", 11603970, 21508, 403, 2100, dbm = -93, registered = false),
                        ),
                        wifis = listOf(
                            wifi("Starbucks-Free-WiFi", "88:15:44:6e:29:b0", 2437, -55),
                            wifi("LinkNYC-Free", "00:24:6c:b7:42:d8", 5180, -61),
                        ),
                        btDevices = b.devices, btAdapterAddress = b.adapterMac, btAdapterName = b.adapterName,
                        languageTag = "en-US", timezoneId = "America/New_York",
                    ),
                    sim("T-Mobile", "310", "260", "us",
                        "89012608234719053829", "310260418273956", "2127364829"),
                ))
            }
        }
    }

    fun byId(id: String): NamedPreset? = all.firstOrNull { it.id == id }
}
