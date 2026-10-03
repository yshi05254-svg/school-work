package com.example.menuui.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * 管理端配置 → 模块快照 JSON。字段名与模块侧 SnapshotParser 的 opt 读取一一对应
 * （改动需两处同步）。org.json 构建，杜绝字符串模板拼 JSON 的引号/转义问题。
 *
 * 结构契约：
 *  - 只发布一个 environment（id=1）= ManagerConfig.env；全部 per-app 策略绑定它；
 *  - pkg=null 的全局策略不生成：会命中 phone/system_server 框架钩的所有调用方；
 *    空白包名 / enabled=false 的应用**整条跳过**（无策略 = 该应用全域透传真实值）；
 *  - languageTag/timezoneId 取环境建议值（国外预设），仍空则省略字段；
 *  - joystick 段由摇杆服务实时写入（[joystickJson]），非摇杆发布时整体省略。
 */
object SnapshotBuilder {

    fun build(cfg: ManagerConfig, joystick: JSONObject? = null): String {
        val root = JSONObject()
        root.put("configVersion", System.currentTimeMillis())
        root.put("masterEnabled", cfg.masterEnabled)
        root.put("excludedPackages", JSONArray(cfg.excludedPackages.distinct()))
        root.put("excludedUids", JSONArray(cfg.excludedUids.distinct()))

        root.put(
            "jitter",
            JSONObject().put("enabled", cfg.jitterEnabled)
                .put("amplitudeMeters", cfg.jitterAmplitudeMeters),
        )

        root.put("sim", simJson(cfg.sim))
        root.put("environments", JSONArray().put(envJson(cfg.env)))
        root.put("policies", policiesJson(cfg))
        if (joystick != null) root.put("joystick", joystick)
        return root.toString()
    }

    /** 摇杆段（enabled=false 时仅显式关闭，其余字段省略——模块按安全缺省处理） */
    fun joystickJson(
        enabled: Boolean,
        baseLat: Double,
        baseLon: Double,
        vNorthMps: Double,
        vEastMps: Double,
        anchoredAtElapsedMs: Long,
        expiresAtElapsedMs: Long,
        sessionEpoch: Long,
    ): JSONObject {
        val j = JSONObject().put("enabled", enabled)
        if (!enabled) return j
        return j.put("baseLat", baseLat)
            .put("baseLon", baseLon)
            .put("vNorthMps", vNorthMps)
            .put("vEastMps", vEastMps)
            .put("anchoredAtElapsedMs", anchoredAtElapsedMs)
            .put("expiresAtElapsedMs", expiresAtElapsedMs)
            .put("sessionEpoch", sessionEpoch)
    }

    private fun envJson(e: EnvDraft): JSONObject {
        val o = JSONObject()
            .put("id", 1L)
            .put("lat", e.lat)
            .put("lon", e.lon)
            .put("alt", e.alt)
            .put("accuracy", e.accuracy.toDouble())
            .put("speed", e.speed.toDouble())
            .put("bearing", e.bearing.toDouble())
        o.put("cells", JSONArray().apply { e.cells.forEach { put(cellJson(it)) } })
        o.put("wifis", JSONArray().apply { e.wifis.forEach { put(wifiJson(it)) } })
        if (e.btDevices.isNotEmpty()) {
            o.put(
                "btDevices",
                JSONArray().apply {
                    e.btDevices.forEach {
                        put(
                            JSONObject().put("address", it.address)
                                .put("name", it.name)
                                .put("rssi", it.rssi)
                        )
                    }
                },
            )
        }
        if (e.btAdapterAddress.isNotBlank()) o.put("btAdapterAddress", e.btAdapterAddress)
        if (e.btAdapterName.isNotBlank()) o.put("btAdapterName", e.btAdapterName)
        return o
    }

    private fun cellJson(c: CellDraft): JSONObject = JSONObject()
        .put("radioType", c.radioType)
        .put("mcc", c.mcc)
        .put("mnc", c.mnc)
        .put("ci", c.ci)
        .put("lac", c.lac)
        .put("tac", c.tac)
        .put("pci", c.pci)
        .put("psc", c.psc)
        .put("cpid", c.cpid)
        .put("arfcn", c.arfcn)
        .put("registered", c.registered)
        .put("signalDbm", c.signalDbm)

    private fun wifiJson(w: WifiDraft): JSONObject = JSONObject()
        .put("ssid", w.ssid)
        .put("bssid", w.bssid)
        .put("capabilities", "[ESS]")
        .put("signalDbm", w.signalDbm)
        .put("frequencyMhz", w.frequencyMhz)

    /** 仅发 enabled 且包名合法的策略：空白包名会整条变成 pkg=null 全局策略（模块端语义），必须跳过 */
    private fun policiesJson(cfg: ManagerConfig): JSONArray = JSONArray().apply {
        cfg.apps.forEach { app ->
            val pkg = app.pkg.trim()
            if (!app.enabled || pkg.isEmpty()) return@forEach
            val o = JSONObject()
                .put("pkg", pkg)
                .put("environmentId", 1L)
                .put("strictMode", app.strictMode)
            if (cfg.env.languageTag.isNotBlank()) o.put("languageTag", cfg.env.languageTag)
            if (cfg.env.timezoneId.isNotBlank()) o.put("timezoneId", cfg.env.timezoneId)
            put(o)
        }
    }

    private fun simJson(s: SimDraft): JSONObject {
        val o = JSONObject().put("enabled", s.enabled)
        o.put(
            "slots",
            JSONArray().apply {
                s.slots.forEach { slot ->
                    put(
                        JSONObject()
                            .put("subId", slot.subId)
                            .put("slotIndex", slot.slotIndex)
                            .put("active", slot.active)
                            .put("iccid", slot.iccid)
                            .put("imsi", slot.imsi)
                            .put("imeiBase", slot.imeiBase)
                            .put("phoneNumber", slot.phoneNumber)
                            .put("mcc", slot.mcc)
                            .put("mnc", slot.mnc)
                            .put("carrierName", slot.carrierName)
                            .put("countryIso", slot.countryIso),
                    )
                }
            },
        )
        return o
    }
}
