package dev.ven11.module.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 顶层快照：由模块管理端（UI / 服务端下发器）写入，目标进程内 SnapshotStore 轮询载入。
 * 不可变；configVersion 变化时整体替换引用（各域缓存以对象身份为失效票据）。
 *
 * 策略字段命名统一（评审一.4）：
 *  - 单数 `policy`，弃用 `policies`（蓝牙钩原误用）；
 *  - 严格模式统一 `strictMode`，弃用 GPS 钩原 `strictIsolation`；
 *    可空 Boolean：GPS 侧 `== true` 才拦截（默认宽松），蓝牙侧 `?: true`（默认严格），
 *    两侧原有语义各自保留。
 *  - 总开关 / 排除名单 / 策略级域开关统一收敛到 PolicyResolver.domainEnabled()。
 */
data class Snapshot(
    val configVersion: Long,
    val masterEnabled: Boolean,
    val excludedPackages: Set<String>,
    val excludedUids: Set<Int>,
    val jitter: GpsJitter,
    val sim: SimSnapshot,
    val environments: List<VirtualEnvironment>,
    val policies: List<Policy>,
    val routes: Map<Long, Route>,
) {
    companion object {
        val EMPTY = Snapshot(
            configVersion = 0L,
            masterEnabled = false,
            excludedPackages = emptySet(),
            excludedUids = emptySet(),
            jitter = GpsJitter(),
            sim = SimSnapshot(),
            environments = emptyList(),
            policies = emptyList(),
            routes = emptyMap(),
        )
    }
}

/**
 * 策略：pkg=null 为全局回落策略（语言域只认精确命中，见 ClientLanguageHooks）；
 * disabledDomains 为 Domain.name 集合（PolicyResolver.Domain），空 = 全部域开启。
 */
data class Policy(
    val pkg: String?,
    val userId: Int? = null,
    val environmentId: Long? = null,
    val strictMode: Boolean? = null,
    val routeId: Long = 0L,
    val languageTag: String? = null,
    val timezoneId: String? = null,
    val bluetoothEnabled: Boolean? = null,
    /** null = 不模拟；ClientWifiHooks 目前未参与决策（连接状态真实） */
    val wifiEnabled: Boolean? = null,
    val disabledDomains: Set<String> = emptySet(),
)

/** SIM 快照段：全局 SIM 配置，per-app 由 Domain.SIM 门控 */
data class SimSnapshot(
    val enabled: Boolean = false,
    val slots: List<VirtualSimSlot> = emptyList(),
)

/**
 * 虚拟卡槽。mcc / mnc 均为 String（评审二 #5：Int 丢前导零——中国移动 mnc="00"，
 * 测试 MCC "001" 同理）；PLMN = mcc + mnc 原样拼接，位数由配置/解析层保证。
 */
data class VirtualSimSlot(
    val subId: Int,
    val slotIndex: Int,
    val active: Boolean = true,
    val iccid: String = "",
    val imsi: String = "",
    /** 前 14 位（可空串=由 subId 确定性派生并补 Luhn 校验位） */
    val imeiBase: String = "",
    val phoneNumber: String = "",
    val mcc: String = "460",
    val mnc: String = "00",
    val carrierName: String = "",
    val countryIso: String = "cn",
    val isEmbedded: Boolean = false,
)

/**
 * JSON 解析（org.json）。配置错误一律安全缺省而不是抛异常——快照坏一个字段
 * 不应让整个虚拟环境失效。
 */
object SnapshotParser {

    fun parse(text: String): Snapshot = try {
        val root = JSONObject(text)
        Snapshot(
            configVersion = root.optLong("configVersion"),
            masterEnabled = root.optBoolean("masterEnabled", false),
            excludedPackages = root.optJSONArray("excludedPackages").toStringSet() ?: emptySet(),
            excludedUids = root.optJSONArray("excludedUids")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.opt(it) as? Int }.toSet()
            } ?: emptySet(),
            jitter = root.optJSONObject("jitter")?.let {
                GpsJitter(it.optBoolean("enabled"), it.optDouble("amplitudeMeters", 0.0))
            } ?: GpsJitter(),
            sim = root.optJSONObject("sim")?.let(::simSnapshot) ?: SimSnapshot(),
            environments = root.optJSONArray("environments")?.mapObj(::environment).orEmpty(),
            policies = root.optJSONArray("policies")?.mapObj(::policy).orEmpty(),
            routes = root.optJSONArray("routes")
                ?.mapObj(::route)
                ?.associateBy { it.id }
                .orEmpty(),
        )
    } catch (_: Throwable) {
        Snapshot.EMPTY
    }

    private fun simSnapshot(j: JSONObject) = SimSnapshot(
        enabled = j.optBoolean("enabled", false),
        slots = j.optJSONArray("slots")?.mapObj(::slot).orEmpty(),
    )

    private fun slot(j: JSONObject): VirtualSimSlot {
        // mcc/mnc 一律 String：数值化配置经补零回规范位宽（"0"→"00"，"46"→"460"），
        // 字符串配置原样保留前导零（评审二 #5）
        val mccRaw = j.optString("mcc", "460").trim()
        val mncRaw = j.optString("mnc", "00").trim()
        return VirtualSimSlot(
            subId = j.optInt("subId", 1),
            slotIndex = j.optInt("slotIndex", j.optInt("subId", 1) - 1),
            active = j.optBoolean("active", true),
            iccid = j.optString("iccid"),
            imsi = j.optString("imsi"),
            imeiBase = j.optString("imeiBase"),
            phoneNumber = j.optString("phoneNumber"),
            mcc = mccRaw.padStart(3, '0'),
            mnc = mncRaw.padStart(2, '0'),
            carrierName = j.optString("carrierName"),
            countryIso = j.optString("countryIso", "cn"),
            isEmbedded = j.optBoolean("isEmbedded", false),
        )
    }

    private fun environment(j: JSONObject) = VirtualEnvironment(
        id = j.optLong("id"),
        lat = j.optDouble("lat"),
        lon = j.optDouble("lon"),
        alt = j.optDouble("alt", 0.0),
        accuracy = j.optDouble("accuracy", 15.0).toFloat(),
        speed = j.optDouble("speed", 0.0).toFloat(),
        bearing = j.optDouble("bearing", 0.0).toFloat(),
        cells = j.optJSONArray("cells")?.mapObj(::cell).orEmpty(),
        wifis = j.optJSONArray("wifis")?.mapObj(::wifi).orEmpty(),
        btDevices = j.optJSONArray("btDevices")?.mapObj(::btDevice),
        btAdapterAddress = j.optString("btAdapterAddress").takeIf { it.isNotBlank() },
        btAdapterName = j.optString("btAdapterName").takeIf { it.isNotBlank() },
    )

    private fun cell(j: JSONObject): VirtualCell {
        val mcc = j.optString("mcc", "460").trim().padStart(3, '0')
        val mncRaw = j.optString("mnc", "00").trim()
        return VirtualCell(
            radioType = j.optString("radioType", "lte"),
            mcc = mcc,
            mnc = mncRaw.padStart(2, '0'),
            ci = j.optLong("ci"),
            lac = j.optInt("lac"),
            tac = j.optInt("tac"),
            pci = j.optInt("pci"),
            psc = j.optInt("psc"),
            cpid = j.optInt("cpid"),
            arfcn = j.optInt("arfcn"),
            registered = j.optBoolean("registered", false),
            signalDbm = j.optInt("signalDbm", -85),
        )
    }

    private fun wifi(j: JSONObject) = VirtualWifi(
        ssid = j.optString("ssid"),
        bssid = j.optString("bssid"),
        capabilities = j.optString("capabilities", "[ESS]"),
        signalDbm = j.optInt("signalDbm", -55),
        frequencyMhz = j.optInt("frequencyMhz", 2412),
    )

    private fun btDevice(j: JSONObject) = BtDeviceSpec(
        address = j.optString("address"),
        name = j.optString("name").takeIf { it.isNotBlank() },
        bondState = if (j.has("bondState")) j.optInt("bondState") else null,
        rssi = if (j.has("rssi")) j.optInt("rssi") else null,
    )

    private fun policy(j: JSONObject) = Policy(
        pkg = j.optString("pkg").takeIf { it.isNotBlank() },
        userId = if (j.has("userId")) j.optInt("userId") else null,
        environmentId = if (j.has("environmentId")) j.optLong("environmentId") else null,
        strictMode = if (j.has("strictMode")) j.optBoolean("strictMode") else null,
        routeId = j.optLong("routeId", 0L),
        languageTag = j.optString("languageTag").takeIf { it.isNotBlank() },
        timezoneId = j.optString("timezoneId").takeIf { it.isNotBlank() },
        bluetoothEnabled = if (j.has("bluetoothEnabled")) j.optBoolean("bluetoothEnabled") else null,
        wifiEnabled = if (j.has("wifiEnabled")) j.optBoolean("wifiEnabled") else null,
        disabledDomains = j.optJSONArray("disabledDomains").toStringSet() ?: emptySet(),
    )

    private fun route(j: JSONObject) = Route(
        id = j.optLong("id"),
        points = j.optJSONArray("points")?.mapObj { p ->
            GeoPoint(p.optDouble("lat"), p.optDouble("lon"))
        } ?: emptyList(),
        speedMps = j.optDouble("speedMps", 8.33),
        loop = j.optBoolean("loop", true),
    )

    // ---- org.json 小工具 ----

    private inline fun <T> JSONArray.mapObj(f: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { opt(it) as? JSONObject }.map(f)

    private fun JSONArray?.toStringSet(): Set<String>? = this?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.toSet()
    }
}
