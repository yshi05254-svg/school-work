package dev.ven11.module.hook.sim

import android.os.Build
import android.telephony.SubscriptionInfo
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.hook.regional.RegionalSystemPropertiesHook
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.model.SimSnapshot
import dev.ven11.module.model.VirtualSimSlot
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 客户端 SIM 钩（目标应用进程）。
 * 数据源：快照 sim 段（全局 SIM 配置，per-app simEnabled 门控）。
 *
 * 关键语义：
 * - 标识类方法（IMEI/IMSI/ICCID/号码）先 proceed 再替换，复现 Android 10+ 权限语义：
 *   原方法抛 SecurityException 照抛、返回 null 照返 null，仅正常返回时替换为虚拟值（#9）。
 * - 参数语义按方法区分 slotIndex / subId；无参时读 createForSubscriptionId 实例的
 *   mSubId，再回落默认（首个激活）卡（#6）。
 * - 非激活卡槽按"无卡"处理：SIM_STATE_ABSENT / hasIccCard=false / 标识类返回 null（#7）。
 * - PLMN = mcc + mnc 原样拼接，不做补零；位数由配置保证（#5）。
 * - 网络侧（getNetworkOperator* / getNetworkCountryIso*）归 VirtualCell 域，此处不 hook，
 *   由 Cell 域负责或以 SIM 配置作默认值，避免两域重复 hook 导致结果取决于注册顺序（#17）。
 */
class ClientSimHooks(private val module: XposedModule) {

    /* ============ 常量 ============ */

    private companion object {
        const val SIM_STATE_ABSENT = 1
        const val SIM_STATE_READY = 5
        const val CARD_STATE_ABSENT = 1
        const val CARD_STATE_PRESENT = 11          // getCardStateForSlotIndex 的有卡值（#8）
        const val INVALID_SUBSCRIPTION_ID = -1
        const val UNKNOWN_CARRIER_ID = -1          // SubscriptionManager.UNKNOWN_CARRIER_ID（#13）
        const val PROFILE_CLASS_UNSET = -1         // SubscriptionManager.PROFILE_CLASS_UNSET（#13）
        const val NAME_SOURCE_CARRIER = 1

        val JAVA_INT = java.lang.Integer.TYPE
        val JAVA_BOOLEAN = java.lang.Boolean.TYPE
    }

    /* ============ 方法表（P3：名称 → target/参数语义，便于对照 AOSP 逐项核对） ============ */

    private enum class ArgKind { NONE, SLOT, SUB_ID }

    private class MEntry(val target: String, val kind: ArgKind)

    /** TelephonyManager：SLOT=slotIndex 型参数；SUB_ID=subscriptionId 型（多为隐藏重载） */
    private val tmTable: Map<String, MEntry> = mapOf(
        "getDeviceId" to MEntry("identity", ArgKind.SLOT),
        "getImei" to MEntry("identity", ArgKind.SLOT),
        "getPrimaryImei" to MEntry("identity", ArgKind.NONE),
        "getMeid" to MEntry("meid", ArgKind.SLOT),
        "getEsn" to MEntry("esn", ArgKind.NONE),
        "getNai" to MEntry("nai", ArgKind.SLOT),
        "getTypeAllocationCode" to MEntry("tac", ArgKind.SLOT),   // IMEI 前 8 位 TAC（#10）
        "getSubscriberId" to MEntry("imsi", ArgKind.SUB_ID),
        "getImsi" to MEntry("imsi", ArgKind.SUB_ID),
        "getSimSerialNumber" to MEntry("iccid", ArgKind.SUB_ID),
        "getIccid" to MEntry("iccid", ArgKind.SUB_ID),
        "getLine1Number" to MEntry("number", ArgKind.SUB_ID),
        "getMsisdn" to MEntry("number", ArgKind.SUB_ID),
        "getPhoneNumber" to MEntry("number", ArgKind.SUB_ID),
        "getSimOperator" to MEntry("plmn", ArgKind.SUB_ID),
        "getSimOperatorNumeric" to MEntry("plmn", ArgKind.SUB_ID),
        "getSimOperatorName" to MEntry("carrier", ArgKind.SUB_ID),
        "getSimCarrierIdName" to MEntry("carrier", ArgKind.NONE),
        "getCarrierNameFromSimMccMnc" to MEntry("carrier", ArgKind.NONE),
        "getSimCarrierId" to MEntry("carrierId", ArgKind.NONE),   // 配置无 PLMN→carrierId 映射，返回 UNKNOWN（#17）
        "getSimCountryIso" to MEntry("country", ArgKind.NONE),
        "getSimCountryIsoForPhone" to MEntry("country", ArgKind.SLOT),
        "getSimState" to MEntry("state", ArgKind.SLOT),
        "getSimStateForSlotIndex" to MEntry("state", ArgKind.SLOT),
        "getCardStateForSlotIndex" to MEntry("cardState", ArgKind.SLOT),
        "hasIccCard" to MEntry("hasIcc", ArgKind.SLOT),
        "getPhoneCount" to MEntry("modemCount", ArgKind.NONE),
        "getActiveModemCount" to MEntry("modemCount", ArgKind.NONE),
        "getSupportedModemCount" to MEntry("modemCount", ArgKind.NONE),   // 卡槽/调制解调器数，非激活卡数（#8）
        "getMcc" to MEntry("mcc", ArgKind.NONE),
        "getMnc" to MEntry("mnc", ArgKind.NONE),
    )

    /* ============ 决策结果：保证原方法在整个拦截路径中至多执行一次（#2） ============ */

    private sealed interface Decided {
        /** 返回伪造值（可为 null） */
        data class Value(val v: Any?) : Decided

        /** 回落原方法（由 dispatch 统一调用一次 proceed） */
        object Origin : Decided

        /** 复现原方法异常（如 SecurityException），不吞、不二次执行 */
        class Rethrow(val t: Throwable) : Decided
    }

    fun install(cl: ClassLoader, pkg: String): Int {
        var n = 0
        val uid = android.os.Process.myUid()
        val tmClass = try {
            cl.loadClass("android.telephony.TelephonyManager")
        } catch (_: Throwable) {
            return 0
        }
        for (m in tmClass.declaredMethods) {
            if (m.parameterTypes.size > 1) continue
            val entry = tmTable[m.name] ?: continue
            // id 带参数个数，避免重载之间冲突（#16）
            module.hook(m).setId("ven11.sim.${m.name}/${m.parameterTypes.size}")
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        dispatch(chain, "sim.${m.name}") { decideTm(chain, m, entry, pkg, uid) }
                })
            n++
        }
        n += installSubscriptionManager(cl, pkg, uid)
        n += installSystemProperties(cl, pkg, uid)
        module.log(Log.INFO, "VEN11", "client sim hooks installed=$n pkg=$pkg")
        ProbeLog.log("SIM-HOOKS n=$n pkg=$pkg")
        return n
    }

    /** 兜底包装（#2）：决策阶段出错回落原方法；proceed 只在本函数出现一次，不会重复执行 */
    private fun dispatch(chain: XposedInterface.Chain, tag: String, decide: () -> Decided): Any? {
        val d = try {
            decide()
        } catch (t: Throwable) {
            ProbeLog.log("SIM-ERR $tag ${t.javaClass.simpleName}: ${t.message}")
            Decided.Origin
        }
        return when (d) {
            is Decided.Value -> { hitOnce("$tag=${d.v}"); d.v }
            Decided.Origin -> chain.proceed()
            is Decided.Rethrow -> throw d.t
        }
    }

    /* ============ TelephonyManager ============ */

    private fun decideTm(chain: XposedInterface.Chain, m: Method, e: MEntry, pkg: String, uid: Int): Decided {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.SIM)) return Decided.Origin
        val sim = eff.payload.sim
        if (!sim.enabled || sim.slots.isEmpty()) return Decided.Origin
        val rt = m.returnType
        val slot = resolveSlot(chain, sim, e.kind)
        val a = slot?.takeIf { it.active }          // 非激活卡槽按"无卡"处理（#7）
        return when (e.target) {
            "identity" -> replaceIfAllowed(chain, rt) { a?.let { deriveImei(it) } }
            "tac" -> replaceIfAllowed(chain, rt) { a?.let { deriveImei(it).take(8) } }
            "imsi" -> replaceIfAllowed(chain, rt) { a?.imsi?.ifEmpty { null } }     // 未配置宁可 null，不造 5-6 位畸形 IMSI（#11）
            "iccid" -> replaceIfAllowed(chain, rt) { a?.iccid?.ifEmpty { null } }
            "number" -> replaceIfAllowed(chain, rt) { a?.phoneNumber?.ifEmpty { null } }
            // GSM 卡场景真机通常返回 null（#8）：MEID 14 位十六进制 / ESN 8 位 / NAI user@realm，
            // 配置无对应字段，伪造反而失真
            "meid", "esn", "nai" -> replaceIfAllowed(chain, rt) { null }
            "plmn" -> direct(if (a != null) plmnOf(a) else "", rt)
            "carrier" -> direct(if (a != null) a.carrierName else "", rt)
            "carrierId" -> direct(UNKNOWN_CARRIER_ID, rt)
            "country" -> direct(if (a != null) a.countryIso else "", rt)
            "state" -> direct(if (a != null) SIM_STATE_READY else SIM_STATE_ABSENT, rt)
            "cardState" -> direct(if (a != null) CARD_STATE_PRESENT else CARD_STATE_ABSENT, rt)
            "hasIcc" -> direct(a != null, rt)
            "modemCount" -> direct(sim.slots.size, rt)
            "mcc" -> direct(a?.mcc?.ifEmpty { null }, rt)
            "mnc" -> direct(a?.mnc?.ifEmpty { null }, rt)
            else -> Decided.Origin                  // 表驱动安全网
        }
    }

    /* ============ SubscriptionManager ============ */

    private fun installSubscriptionManager(cl: ClassLoader, pkg: String, uid: Int): Int {
        var n = 0
        val smClass = try {
            cl.loadClass("android.telephony.SubscriptionManager")
        } catch (_: Throwable) {
            return 0
        }
        for (m in smClass.declaredMethods) {
            val target = smTarget(m.name) ?: continue
            module.hook(m).setId("ven11.simsub.${m.name}/${m.parameterTypes.size}")
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        dispatch(chain, "simsub.${m.name}") { decideSm(chain, m, target, pkg, uid) }
                })
            n++
        }
        return n
    }

    private fun smTarget(name: String): String? = when {
        name == "getActiveSubscriptionInfoList" -> "list"
        name == "getAvailableSubscriptionInfoList" -> "available"
        name == "getActiveSubscriptionInfoCount" -> "count"
        name == "getActiveSubscriptionInfoCountMax" -> "countMax"
        name == "getActiveSubscriptionIdList" -> "ids"
        name.startsWith("getDefault") && name.endsWith("SubscriptionId") -> "defaultId"
        name == "getActiveSubscriptionInfo" -> "bySubId"                 // 参数语义 = subId（#12）
        name == "getActiveSubscriptionInfoForSimSlotIndex" -> "bySlot"   // 参数语义 = slotIndex
        name == "getSlotIndex" -> "slotOfSub"
        name == "getSubscriptionIds" -> "subsOfSlot"
        name == "getPhoneNumber" -> "phoneOfSub"                         // API 33+，需 READ_PHONE_NUMBERS
        else -> null
    }

    private fun decideSm(chain: XposedInterface.Chain, m: Method, target: String, pkg: String, uid: Int): Decided {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.SIM)) return Decided.Origin
        val sim = eff.payload.sim
        if (!sim.enabled || sim.slots.isEmpty()) return Decided.Origin
        val rt = m.returnType
        val arg = chain.args.firstOrNull() as? Int
        val infos = cachedInfos(sim)
        return when (target) {
            // ---- 权限类出口（评审二）：gated 先 proceed 复现权限语义，
            // ---- 无 READ_PHONE_STATE 的脱敏视角（null/空结果）原样返回，不注入虚拟数据
            "list" -> gated(chain, rt) { ArrayList(infos.map { it.second }) }
            "available" -> gated(chain, rt) {
                ArrayList(sim.slots.filter { !it.active }.mapNotNull { buildSubscriptionInfo(it) })
            }
            "count" -> gated(chain, rt) { infos.size }
            "ids" -> gated(chain, rt) { infos.map { it.first.subId }.toIntArray() }
            "bySubId" -> gated(chain, rt) { infos.firstOrNull { it.first.subId == arg }?.second }  // 参数语义 = subId（#12），查无匹配返回 null
            "bySlot" -> gated(chain, rt) { infos.firstOrNull { it.first.slotIndex == arg }?.second }
            "phoneOfSub" -> gated(chain, rt) {
                infos.firstOrNull { it.first.subId == arg }?.first?.phoneNumber?.ifEmpty { null }
            }
            // ---- 非权限出口：静态工具 / 计数上限，直接替换
            "countMax" -> direct(sim.slots.size, rt)
            "defaultId" -> direct(infos.firstOrNull()?.first?.subId ?: INVALID_SUBSCRIPTION_ID, rt)
            "slotOfSub" -> direct(sim.slots.firstOrNull { it.subId == arg }?.slotIndex ?: -1, rt)
            "subsOfSlot" -> Decided.Value(infos.firstOrNull { it.first.slotIndex == arg }?.let { intArrayOf(it.first.subId) })
            else -> Decided.Origin
        }
    }

    /* ============ SubscriptionInfo 构造（#13） ============ */

    /**
     * 优先走 Android 14+ 隐藏 SubscriptionInfo$Builder；不可用则按 29-33 已知前缀签名反射构造；
     * 两种路径都做读回校验（id / slotIndex / mcc 与配置一致才采用）。
     */
    private fun buildSubscriptionInfo(slot: VirtualSimSlot): SubscriptionInfo? =
        (buildViaBuilder(slot) ?: buildViaCtor(slot))?.takeIf { verifyInfo(it, slot) }

    private fun buildViaBuilder(slot: VirtualSimSlot): SubscriptionInfo? = try {
        val bCls = Class.forName("android.telephony.SubscriptionInfo\$Builder")
        // AOSP 14 形态 Builder(int id, String iccId, int slotIndex, int cardId)；宽匹配，首个多余 int 视为 slotIndex
        val ctor = bCls.constructors.filter { c ->
            val p = c.parameterTypes
            p.size >= 3 && p[0] == JAVA_INT && p[1] == String::class.java
        }.maxByOrNull { it.parameterTypes.size } ?: return null
        var slotIdxGiven = false
        val args = ArrayList<Any?>(ctor.parameterTypes.size)
        ctor.parameterTypes.forEachIndexed { i, t ->
            args += when {
                i == 0 -> slot.subId
                i == 1 -> slot.iccid
                t == JAVA_INT && !slotIdxGiven -> { slotIdxGiven = true; slot.slotIndex }
                t == JAVA_INT -> 0
                t == String::class.java -> ""
                else -> null
            }
        }
        val builder = ctor.newInstance(*args.toTypedArray()) ?: return null
        for ((name, v) in builderSets(slot)) {
            try {
                bCls.methods.firstOrNull {
                    it.name == name && it.parameterTypes.size == 1 && accepts(it.parameterTypes[0], v)
                }?.invoke(builder, v)
            } catch (_: Throwable) {
            }
        }
        bCls.methods.firstOrNull { it.name == "build" && it.parameterTypes.isEmpty() }
            ?.invoke(builder) as? SubscriptionInfo
    } catch (_: Throwable) {
        null
    }

    private fun builderSets(slot: VirtualSimSlot): List<Pair<String, Any?>> = listOf(
        "setId" to slot.subId,
        "setIccId" to slot.iccid,
        "setSimSlotIndex" to slot.slotIndex,
        "setDisplayName" to slot.carrierName,
        "setCarrierName" to slot.carrierName,
        "setMcc" to slot.mcc,
        "setMnc" to mncOf(slot),
        "setCountryIso" to slot.countryIso,
        "setNumber" to slot.phoneNumber,
        "setEmbedded" to slot.isEmbedded,
        "setCarrierId" to UNKNOWN_CARRIER_ID,
        "setProfileClass" to PROFILE_CLASS_UNSET,
        "setPortIndex" to 0,
    )

    /** 29-33 已知前缀：(int id, String iccId, int slotIdx, CharSequence displayName, CharSequence carrierName,
     *  int nameSource, int iconTint, String number, int roaming, Bitmap icon, String mcc, String mnc,
     *  String countryIso, boolean isEmbedded, ...)；尾部未知参数按类型填中性值 */
    private fun buildViaCtor(slot: VirtualSimSlot): SubscriptionInfo? = try {
        val cls = SubscriptionInfo::class.java
        val CS = CharSequence::class.java
        val SS = String::class.java
        val BM = android.graphics.Bitmap::class.java
        val ctor = cls.declaredConstructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size >= 14 && p[0] == JAVA_INT && p[1] == SS && p[2] == JAVA_INT &&
                (p[3] == CS || p[3] == SS) && (p[4] == CS || p[4] == SS) &&
                p[5] == JAVA_INT && p[6] == JAVA_INT && p[7] == SS && p[8] == JAVA_INT &&
                (p[9] == BM || p[9] == CS || p[9] == SS) &&
                (p[10] == SS || p[10] == JAVA_INT) && (p[11] == SS || p[11] == JAVA_INT) &&
                p[12] == SS && p[13] == JAVA_BOOLEAN
        } ?: return null
        ctor.isAccessible = true
        val p = ctor.parameterTypes
        val args = ArrayList<Any?>(p.size)
        p.forEachIndexed { i, t ->
            args += when {
                i > 13 -> when (t) {   // 尾部：中性值（carrierId=-1、profileClass=-1，#13）
                    JAVA_INT -> -1
                    JAVA_BOOLEAN -> false
                    SS -> ""
                    else -> null
                }
                i == 3 || i == 4 -> slot.carrierName          // displayName / carrierName
                i == 7 -> slot.phoneNumber
                i == 10 -> if (t == SS) slot.mcc else (slot.mcc.toIntOrNull() ?: 0)
                i == 11 -> if (t == SS) mncOf(slot) else (slot.mnc.toIntOrNull() ?: 0)
                i == 12 -> slot.countryIso
                i == 13 -> slot.isEmbedded
                t == JAVA_INT -> when (i) {
                    0 -> slot.subId
                    2 -> slot.slotIndex
                    5 -> NAME_SOURCE_CARRIER
                    else -> 0
                }
                i == 1 -> slot.iccid
                else -> null                                 // icon 等
            }
        }
        ctor.newInstance(*args.toTypedArray()) as? SubscriptionInfo
    } catch (_: Throwable) {
        null
    }

    /** 读回校验（#13）：subscriptionId / simSlotIndex 必须一致；mcc 可读时须一致 */
    private fun verifyInfo(info: SubscriptionInfo, slot: VirtualSimSlot): Boolean = try {
        val id = callInt(info, "getSubscriptionId")
        val idx = callInt(info, "getSimSlotIndex")
        val mcc = callStr(info, "getMccString") ?: callInt(info, "getMcc")?.toString()
        id == slot.subId && idx == slot.slotIndex && (mcc == null || mcc == slot.mcc)
    } catch (_: Throwable) {
        false
    }

    private fun callInt(o: Any, name: String): Int? = try {
        o.javaClass.getMethod(name).invoke(o) as? Int
    } catch (_: Throwable) {
        null
    }

    private fun callStr(o: Any, name: String): String? = try {
        o.javaClass.getMethod(name).invoke(o) as? String
    } catch (_: Throwable) {
        null
    }

    private fun accepts(formal: Class<*>, v: Any?): Boolean {
        if (v == null) return !formal.isPrimitive
        if (formal.isInstance(v)) return true
        if (v is Int) return formal == JAVA_INT
        if (v is Boolean) return formal == JAVA_BOOLEAN
        return false
    }

    /* ============ SystemProperties（gsm.sim.*，并入 Regional 注册表，评审四） ============ */

    /**
     * 不再自装一组 get 拦截器（语言/时区域已各有一组，SystemProperties.get 是框架
     * 热路径，多层拦截徒增开销且钩子 ID 会冲突）。改为向进程级单一钩子
     * [RegionalSystemPropertiesHook] 的注册表登记 gsm.sim.* 解析器：前缀不匹配
     * 立即返回 null（零策略解析开销），命中才走 resolve + 查表。
     */
    private fun installSystemProperties(cl: ClassLoader, pkg: String, uid: Int): Int {
        RegionalSystemPropertiesHook.registerResolver("sim") { key ->
            if (!key.startsWith("gsm.sim.")) return@registerResolver null
            resolveSimProp(pkg, uid, key)
        }
        return RegionalSystemPropertiesHook.install(module, cl)
    }

    private fun resolveSimProp(pkg: String, uid: Int, key: String): String? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.SIM)) return null
        val sim = eff.payload.sim
        if (!sim.enabled || sim.slots.isEmpty()) return null
        return cachedProps(sim)[key]
    }

    /* ============ 卡槽解析（#6） ============ */

    /**
     * SLOT=slotIndex 型（getImei/getDeviceId/getSimState...）、SUB_ID=subscriptionId 型
     * （getSubscriberId/getLine1Number/getSimOperator 隐藏重载...）。
     * 无参时读 createForSubscriptionId 实例的 subId，再回落默认（首个激活）卡。
     * 显式按索引/subId 命中的卡槽即使非激活也原样返回，"无卡"语义由取值处给出（#7）。
     */
    private fun resolveSlot(chain: XposedInterface.Chain, sim: SimSnapshot, kind: ArgKind): VirtualSimSlot? {
        val arg = chain.args.firstOrNull() as? Int      // #1：无参重载安全取参
        return when {
            arg != null && kind == ArgKind.SLOT -> sim.slots.firstOrNull { it.slotIndex == arg }
            arg != null && kind == ArgKind.SUB_ID -> sim.slots.firstOrNull { it.subId == arg }
            else -> {
                val sid = instanceSubId(chain.thisObject)
                sim.slots.firstOrNull { sid != null && it.subId == sid }
                    ?: sim.slots.firstOrNull { it.active }
            }
        }
    }

    /** createForSubscriptionId(subId) 实例的 subId 存于 mSubId 字段 / 隐藏 getSubId()（#6） */
    private fun instanceSubId(any: Any?): Int? {
        if (any == null) return null
        (try {
            any.javaClass.getMethod("getSubId").invoke(any) as? Int
        } catch (_: Throwable) {
            null
        })?.let { return it }
        return try {
            val f = any.javaClass.getDeclaredField("mSubId")
            f.isAccessible = true
            (f.get(any) as? Int)?.takeIf { it >= 0 }     // 排除 INVALID(-1) / DEFAULT(-2)
        } catch (_: Throwable) {
            null
        }
    }

    /* ============ 派生值 ============ */

    /** PLMN = mcc + mnc 原样拼接，不做补零（#5）：前导零由模型 String 字段承载
     *  （mnc="00"、测试 MCC "001"），两域（SIM/Cell）结果一致 */
    private fun plmnOf(slot: VirtualSimSlot): String = slot.mcc + mncOf(slot)

    private fun mncOf(slot: VirtualSimSlot): String = slot.mnc

    /** IMEI：配置 imeiBase 取前 14 位；未配置时由 subId 确定性派生（TAC 86000000 + 序列），
     *  统一补 Luhn 校验位——不再出现固定且校验位错误的兜底值（#10） */
    private fun deriveImei(slot: VirtualSimSlot): String {
        val base = slot.imeiBase.filter { it.isDigit() }.takeIf { it.length >= 14 }?.take(14)
            ?: ("86000000" + "%06d".format(((slot.subId.toLong() and 0xFFFFFFF) % 1_000_000).toInt()))
        return base + luhnCheckDigit(base)
    }

    private fun luhnCheckDigit(d14: String): String {
        var sum = 0
        d14.forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return ((10 - sum % 10) % 10).toString()
    }

    /* ============ 类型对照与 proceed-first ============ */

    private val MISS = Any()

    /** 按方法返回类型转换；对不上返回 MISS → 回落原方法，不硬返回（#3） */
    private fun coerce(v: Any?, rt: Class<*>): Any? = when {
        v == null -> null
        rt.isInstance(v) -> v
        rt == JAVA_INT && v is Int -> v
        rt == JAVA_BOOLEAN && v is Boolean -> v
        v is Int && rt == String::class.java -> v.toString()
        else -> MISS
    }

    private fun Any?.unMiss(): Any? = if (this === MISS) null else this

    private fun direct(v: Any?, rt: Class<*>): Decided {
        val c = coerce(v, rt)
        return if (c === MISS) Decided.Origin else Decided.Value(c)
    }

    /**
     * 先 proceed、再替换（#9）：
     * - 原方法抛 SecurityException → 照样抛出（Android 10+ 权限语义）
     * - 原方法返回 null → 照样返回 null（未授权/无卡）
     * - 原方法正常返回 → 替换为虚拟值；虚拟值为 null 时也覆盖，避免泄漏宿主真实标识
     * 原方法只执行一次：任何失败路径都不会触发二次 proceed（#2）。
     */
    private inline fun replaceIfAllowed(chain: XposedInterface.Chain, rt: Class<*>, virtual: () -> Any?): Decided {
        val origin = try {
            chain.proceed()
        } catch (se: SecurityException) {
            return Decided.Rethrow(se)
        } catch (_: Throwable) {
            return Decided.Value(spoof(rt, virtual))     // 原方法自身异常（非权限类）：不重放异常
        }
        if (origin == null) return Decided.Value(null)
        return Decided.Value(spoof(rt, virtual))
    }

    private inline fun spoof(rt: Class<*>, virtual: () -> Any?): Any? = try {
        coerce(virtual(), rt).unMiss()
    } catch (_: Throwable) {
        null
    }

    /**
     * SubscriptionManager 权限类出口（评审二）：先 proceed 复现 Android 权限语义。
     *  - SecurityException 原样传播；原方法自身异常回落 Origin
     *  - 原结果为 null / 空（无 READ_PHONE_STATE 时系统的脱敏视角）→ 原样返回，
     *    不注入虚拟数据——否则无权限应用也能读到完整订阅列表（含号码），
     *    与真机行为矛盾且极易被检测
     *  - 有可见数据才替换为虚拟值；原方法至多执行一次（#2）
     */
    private inline fun gated(chain: XposedInterface.Chain, rt: Class<*>, virtual: () -> Any?): Decided {
        val origin = try {
            chain.proceed()
        } catch (se: SecurityException) {
            return Decided.Rethrow(se)
        } catch (_: Throwable) {
            return Decided.Origin
        }
        val visible = when (origin) {
            null -> false
            is List<*> -> origin.isNotEmpty()
            is IntArray -> origin.isNotEmpty()
            is CharSequence -> origin.isNotEmpty()
            is Int -> origin != 0            // count：0 = 无卡/无权限视角
            else -> true
        }
        return if (!visible) Decided.Value(origin) else Decided.Value(spoof(rt, virtual))
    }

    /* ============ per-快照缓存（#15） ============ */

    private class SimCache(val sim: SimSnapshot) {
        var infos: List<Pair<VirtualSimSlot, SubscriptionInfo>>? = null
        var props: Map<String, String>? = null
    }

    @Volatile
    private var cache: SimCache? = null

    /** 以 SimSnapshot 实例为失效票据：快照不可变、按 config_version 整体替换，引用不同即重建 */
    private fun cacheFor(sim: SimSnapshot): SimCache {
        val c = cache
        if (c != null && c.sim === sim) return c
        return SimCache(sim).also { cache = it }
    }

    private fun cachedInfos(sim: SimSnapshot): List<Pair<VirtualSimSlot, SubscriptionInfo>> {
        cacheFor(sim).infos?.let { return it }
        // 构造失败的卡槽整条剔除（评审二：Pair 恒非 null 的写法会留下 null second，
        // 后续 .second 取值即 NPE）
        val built = sim.slots.filter { it.active }
            .mapNotNull { s -> buildSubscriptionInfo(s)?.let { s to it } }
        if (built.isEmpty() && sim.slots.any { it.active }) {
            // #12：构造全线失败要留痕。不回落 proceed——那会把宿主真实订阅信息泄漏给被测 App
            ProbeLog.log("SIM-ERR subinfo build failed sdk=${Build.VERSION.SDK_INT}")
        }
        cacheFor(sim).infos = built
        return built
    }

    /** 多卡按 slotIndex 排序占位拼接，空槽填 ABSENT / 空串，逗号位置与卡槽不错位（#7） */
    private fun cachedProps(sim: SimSnapshot): Map<String, String> {
        cacheFor(sim).props?.let { return it }
        val sorted = sim.slots.sortedBy { it.slotIndex }
        val p = mapOf(
            "gsm.sim.state" to sorted.joinToString(",") { if (it.active) "READY" else "ABSENT" },
            "gsm.sim.operator.iso-country" to sorted.joinToString(",") { if (it.active) it.countryIso else "" },
            "gsm.sim.operator.numeric" to sorted.joinToString(",") { if (it.active) plmnOf(it) else "" },
            // alpha 含逗号会被消费方按槽拆坏（#7）
            "gsm.sim.operator.alpha" to sorted.joinToString(",") { if (it.active) it.carrierName.replace(',', ' ') else "" },
        )
        cacheFor(sim).props = p
        return p
    }

    /* ============ 日志限频（#15） ============ */

    private val hitSeen: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun hitOnce(msg: String) {
        if (hitSeen.size > 512) hitSeen.clear()
        if (hitSeen.add(msg)) ProbeLog.log("SIM-SPOOF $msg")
    }
}