package com.example.menuui.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 管理端配置持久化：filesDir/manager-config.json。读侧逐字段带缺省解析
 * （旧版本文件缺字段不炸，回落默认值）；写侧原子替换（tmp + rename）。
 * 与模块快照 JSON 是两份格式：本文件只服务管理端自身恢复，字段名自洽即可。
 */
object ConfigStore {

    private const val FILE_NAME = "manager-config.json"
    private const val HISTORY_LIMIT = 20

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(context: Context): ManagerConfig = try {
        val f = file(context)
        if (!f.exists()) ManagerConfig() else parse(JSONObject(f.readText()))
    } catch (_: Throwable) {
        ManagerConfig()
    }

    fun save(context: Context, cfg: ManagerConfig) {
        val dst = file(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(toJson(cfg).toString())
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) tmp.copyTo(dst, overwrite = true)
    }

    // ---------------------------------------------------------------- 序列化

    private fun toJson(c: ManagerConfig): JSONObject {
        val o = JSONObject()
        o.put("masterEnabled", c.masterEnabled)
        o.put("serverLocation", c.serverLocation)
        o.put("jitterEnabled", c.jitterEnabled)
        o.put("jitterAmplitudeMeters", c.jitterAmplitudeMeters)
        o.put("env", envJson(c.env))
        o.put(
            "apps",
            JSONArray().apply {
                c.apps.forEach {
                    put(
                        JSONObject().put("pkg", it.pkg)
                            .put("enabled", it.enabled)
                            .put("strictMode", it.strictMode),
                    )
                }
            },
        )
        o.put("excludedPackages", JSONArray(c.excludedPackages))
        o.put("excludedUids", JSONArray(c.excludedUids))
        o.put("sim", simJson(c.sim))
        o.put(
            "history",
            JSONArray().apply {
                c.history.forEach {
                    put(
                        JSONObject().put("at", it.at).put("ok", it.ok)
                            .put("via", it.via).put("message", it.message),
                    )
                }
            },
        )
        o.put("joystickSpeedMps", c.joystickSpeedMps)
        o.put("joystickPresetId", c.joystickPresetId)
        o.put("autoPublish", c.autoPublish)
        o.put(
            "customPresets",
            JSONArray().apply {
                c.customPresets.forEach { p ->
                    put(
                        JSONObject().put("id", p.id).put("label", p.label)
                            .put("region", p.region)
                            .put("env", envJson(p.env))
                            .put("sim", simJson(p.sim)),
                    )
                }
            },
        )
        return o
    }

    private fun simJson(sim: SimDraft): JSONObject = JSONObject().put("enabled", sim.enabled)
        .put(
            "slots",
            JSONArray().apply {
                sim.slots.forEach { s ->
                    put(
                        JSONObject().put("subId", s.subId).put("slotIndex", s.slotIndex)
                            .put("active", s.active).put("iccid", s.iccid)
                            .put("imsi", s.imsi).put("imeiBase", s.imeiBase)
                            .put("phoneNumber", s.phoneNumber).put("mcc", s.mcc)
                            .put("mnc", s.mnc).put("carrierName", s.carrierName)
                            .put("countryIso", s.countryIso),
                    )
                }
            },
        )

    private fun envJson(e: EnvDraft): JSONObject {
        val o = JSONObject()
        o.put("name", e.name)
        o.put("lat", e.lat)
        o.put("lon", e.lon)
        o.put("alt", e.alt)
        o.put("accuracy", e.accuracy.toDouble())
        o.put("speed", e.speed.toDouble())
        o.put("bearing", e.bearing.toDouble())
        o.put(
            "cells",
            JSONArray().apply {
                e.cells.forEach {
                    put(
                        JSONObject().put("radioType", it.radioType).put("mcc", it.mcc)
                            .put("mnc", it.mnc).put("ci", it.ci).put("lac", it.lac)
                            .put("tac", it.tac).put("pci", it.pci).put("psc", it.psc)
                            .put("cpid", it.cpid).put("arfcn", it.arfcn)
                            .put("registered", it.registered).put("signalDbm", it.signalDbm),
                    )
                }
            },
        )
        o.put(
            "wifis",
            JSONArray().apply {
                e.wifis.forEach {
                    put(
                        JSONObject().put("ssid", it.ssid).put("bssid", it.bssid)
                            .put("signalDbm", it.signalDbm).put("frequencyMhz", it.frequencyMhz),
                    )
                }
            },
        )
        o.put(
            "btDevices",
            JSONArray().apply {
                e.btDevices.forEach {
                    put(
                        JSONObject().put("address", it.address).put("name", it.name)
                            .put("rssi", it.rssi),
                    )
                }
            },
        )
        o.put("btAdapterAddress", e.btAdapterAddress)
        o.put("btAdapterName", e.btAdapterName)
        o.put("languageTag", e.languageTag)
        o.put("timezoneId", e.timezoneId)
        return o
    }

    // ---------------------------------------------------------------- 反序列化

    private fun parse(o: JSONObject): ManagerConfig = ManagerConfig(
        masterEnabled = o.optBoolean("masterEnabled", true),
        serverLocation = o.optBoolean("serverLocation", true),
        jitterEnabled = o.optBoolean("jitterEnabled", true),
        jitterAmplitudeMeters = o.optDouble("jitterAmplitudeMeters", 8.0),
        env = o.optJSONObject("env")?.let(::parseEnv) ?: ManagerConfig().env,
        apps = o.optJSONArray("apps")?.mapObj(::parseApp) ?: ManagerConfig().apps,
        excludedPackages = o.optJSONArray("excludedPackages").stringList(),
        excludedUids = o.optJSONArray("excludedUids")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.opt(idx) as? Int }
        } ?: emptyList(),
        sim = o.optJSONObject("sim")?.let(::parseSim) ?: SimDraft(),
        history = o.optJSONArray("history")?.mapObj { h ->
            PublishRecord(
                at = h.optLong("at"),
                ok = h.optBoolean("ok"),
                via = h.optString("via"),
                message = h.optString("message"),
            )
        }?.take(HISTORY_LIMIT) ?: emptyList(),
        joystickSpeedMps = o.optDouble("joystickSpeedMps", 1.4),
        joystickPresetId = o.optString("joystickPresetId", "walk"),
        autoPublish = o.optBoolean("autoPublish", true),
        customPresets = o.optJSONArray("customPresets")?.mapObj { p ->
            val env = p.optJSONObject("env")?.let(::parseEnv) ?: return@mapObj null
            Presets.NamedPreset(
                id = p.optString("id"),
                label = p.optString("label", env.name),
                region = p.optString("region"),
                env = env,
                sim = p.optJSONObject("sim")?.let(::parseSim) ?: SimDraft(),
            )
        }?.filterNotNull() ?: emptyList(),
    )

    private fun parseEnv(o: JSONObject): EnvDraft = EnvDraft(
        name = o.optString("name", "自定义"),
        lat = o.optDouble("lat", 39.9042),
        lon = o.optDouble("lon", 116.4074),
        alt = o.optDouble("alt", 43.5),
        accuracy = o.optDouble("accuracy", 12.0).toFloat(),
        speed = o.optDouble("speed", 0.0).toFloat(),
        bearing = o.optDouble("bearing", 0.0).toFloat(),
        cells = o.optJSONArray("cells")?.mapObj { c ->
            CellDraft(
                radioType = c.optString("radioType", "lte"),
                mcc = c.optString("mcc", "460"),
                mnc = c.optString("mnc", "00"),
                ci = c.optLong("ci"),
                lac = c.optInt("lac"),
                tac = c.optInt("tac"),
                pci = c.optInt("pci"),
                psc = c.optInt("psc"),
                cpid = c.optInt("cpid"),
                arfcn = c.optInt("arfcn"),
                registered = c.optBoolean("registered", true),
                signalDbm = c.optInt("signalDbm", -85),
            )
        } ?: emptyList(),
        wifis = o.optJSONArray("wifis")?.mapObj { w ->
            WifiDraft(
                ssid = w.optString("ssid"),
                bssid = w.optString("bssid"),
                signalDbm = w.optInt("signalDbm", -55),
                frequencyMhz = w.optInt("frequencyMhz", 2412),
            )
        } ?: emptyList(),
        btDevices = o.optJSONArray("btDevices")?.mapObj { b ->
            BtDraft(
                address = b.optString("address"),
                name = b.optString("name"),
                rssi = b.optInt("rssi", -60),
            )
        } ?: emptyList(),
        btAdapterAddress = o.optString("btAdapterAddress"),
        btAdapterName = o.optString("btAdapterName"),
        languageTag = o.optString("languageTag"),
        timezoneId = o.optString("timezoneId"),
    )

    private fun parseApp(o: JSONObject): AppPolicy = AppPolicy(
        pkg = o.optString("pkg"),
        enabled = o.optBoolean("enabled", true),
        strictMode = o.optBoolean("strictMode", false),
    )

    private fun parseSim(o: JSONObject): SimDraft = SimDraft(
        enabled = o.optBoolean("enabled", false),
        slots = o.optJSONArray("slots")?.mapObj { s ->
            SimSlotDraft(
                subId = s.optInt("subId", 1),
                slotIndex = s.optInt("slotIndex", 0),
                active = s.optBoolean("active", true),
                iccid = s.optString("iccid"),
                imsi = s.optString("imsi"),
                imeiBase = s.optString("imeiBase"),
                phoneNumber = s.optString("phoneNumber"),
                mcc = s.optString("mcc", "460"),
                mnc = s.optString("mnc", "00"),
                carrierName = s.optString("carrierName"),
                countryIso = s.optString("countryIso", "cn"),
            )
        } ?: emptyList(),
    )

    // ---- org.json 小工具 ----

    private fun <T> JSONArray.mapObj(f: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { opt(it) as? JSONObject }.map(f)

    private fun JSONArray?.stringList(): List<String> = this?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
    } ?: emptyList()
}
