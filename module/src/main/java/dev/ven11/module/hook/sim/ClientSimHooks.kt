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
 * - 网络侧出口（getNetworkOperator* / getNetworkType 等）在本类 hook，但门控跟随
 *   CELL 域（取值优先服务小区，getNetworkCountryIso 按 MCC 推导）；SIM 配置仅在
 *   sim.enabled 时作回落源（审查五/六）。
 * - getDefault*SubscriptionId：透传真实值（默认数据/语音/短信是独立选择，真机 id
 *   是框架内部通路的硬依赖）；卡身份按订阅拓扑配对对齐（审查八 #5），无默认订阅
 *   保留 INVALID 语义。
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
        // ---- 网络侧出口（覆盖域扩展 3a，参考 VR F8.d 收录）：取值优先服务小区
        // ---- （Cell 域环境），无小区回落 SIM 配置；域门仍是 Domain.SIM
        "getNetworkOperator" to MEntry("netop", ArgKind.NONE),
        "getNetworkOperatorName" to MEntry("netopName", ArgKind.NONE),
        "getNetworkCountryIso" to MEntry("netCountry", ArgKind.NONE),
        "getPhoneType" to MEntry("phoneType", ArgKind.NONE),
        "getNetworkType" to MEntry("netType", ArgKind.NONE),
        "getDataNetworkType" to MEntry("netType", ArgKind.NONE),
        "getVoiceNetworkType" to MEntry("netType", ArgKind.NONE),
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
        val sim = eff.payload.sim
        // 门控分离（审查五 #157）：SIM 身份类出口 = SIM 域开关；网络侧出口
        // （netop/netopName/netCountry/netType）跟随伪装基站走 CELL 域门控——
        // 默认下发配置 sim.enabled=false 且基站已伪装时，若网络侧仍挂在 SIM 门下，
        // 会出现"基站 46000 而 getNetworkOperator/getNetworkType 返回真实值"的矛盾。
        // 两域都未启用时才整体透传；无对应虚拟数据的目标仍各自回落 Origin。
        val simOn = eff.domainEnabled(PolicyResolver.Domain.SIM) && sim.enabled && sim.slots.isNotEmpty()
        val cellOn = eff.domainEnabled(PolicyResolver.Domain.CELL) &&
            !eff.environment?.cells.isNullOrEmpty()
        val isNetTarget = when (e.target) {
            "netop", "netopName", "netCountry", "netType" -> true
            else -> false
        }
        if (isNetTarget) {
            if (!simOn && !cellOn) return Decided.Origin
        } else if (!simOn) {
            return Decided.Origin
        }
        val rt = m.returnType
        val slot = if (simOn) resolveSlot(chain, alignedSlots(sim), e.kind) else null
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
            // ---- 网络侧出口（3a）：proceed-first；无虚拟数据时 Origin 保持真实。
            // ---- 取值优先服务小区（Cell 域环境）；SIM 侧数据仅在 simOn 时作为回落源
            "netop" -> netValue(chain, rt) {
                servingCell(eff)?.let { it.mcc + it.mnc } ?: a?.let { plmnOf(it) }
            }
            // 网络运营商标名无独立 cell 来源，由 SIM 配置承载（carrierName）
            "netopName" -> netValue(chain, rt) { a?.carrierName?.ifEmpty { null } }
            "netCountry" -> netValue(chain, rt) {
                // 只开 CELL 无 SIM 配置时也跟随伪装基站：按服务小区 MCC 推国家码
                servingCell(eff)?.let { countryFromMcc(it.mcc) }
                    ?: a?.countryIso?.ifEmpty { null }
            }
            "phoneType" -> replaceIfAllowed(chain, rt) { 1 }   // PHONE_TYPE_GSM：虚拟卡槽按 GSM 栈
            "netType" -> netValue(chain, rt) { networkTypeOf(eff) }
            else -> Decided.Origin                  // 表驱动安全网
        }
    }

    /** 服务小区（Cell 域环境）优先取 PLMN/网络类型；无小区时调用方回落 SIM 配置 */
    private fun servingCell(eff: dev.ven11.module.ipc.PolicyResolver.EffectivePolicy) =
        eff.environment?.cells?.takeIf { it.isNotEmpty() }
            ?.let { dev.ven11.module.hook.cell.CellInfoFactory.pickServing(it) }

    /** 服务小区制式 → TelephonyManager.NETWORK_TYPE_*；无小区返回 null（Origin） */
    private fun networkTypeOf(eff: dev.ven11.module.ipc.PolicyResolver.EffectivePolicy): Int? {
        val cell = servingCell(eff) ?: return null
        val rat = dev.ven11.module.hook.cell.CellInfoFactory.ratTypeOf(cell.radioType) ?: return null
        return dev.ven11.module.hook.cell.CellInfoFactory.networkTypeOf(rat)
    }

    /** 常用 MCC → ISO 国家码兜底表（MccTable 反射不可用时用；小写，对齐 countryIso 惯例） */
    private val MCC_COUNTRY = mapOf(
        "460" to "cn", "461" to "cn",
        "310" to "us", "311" to "us", "312" to "us", "313" to "us", "314" to "us", "315" to "us", "316" to "us",
        "302" to "ca", "234" to "gb", "235" to "gb", "208" to "fr", "262" to "de", "222" to "it", "214" to "es",
        "440" to "jp", "441" to "jp", "450" to "kr", "466" to "tw", "454" to "hk", "455" to "mo",
        "404" to "in", "405" to "in", "505" to "au", "240" to "se", "242" to "no", "206" to "be",
    )

    /**
     * MCC → ISO 国家码（审查六：只开 CELL 时 getNetworkCountryIso 也应跟随伪装基站）。
     * 优先反射 AOSP 隐藏表 MccTable.countryCodeForMcc（全量、框架维护）；反射被
     * 隐藏 API 策略挡掉时用内置常用表兜底；都查不到返回 null（调用方回落 SIM 配置/真实值）。
     */
    private fun countryFromMcc(mcc: String): String? {
        val code = mcc.trim().takeWhile { it.isDigit() }
        if (code.length < 3) return null
        try {
            val mt = Class.forName("com.android.internal.telephony.MccTable")
            val m = mt.getDeclaredMethod("countryCodeForMcc", Int::class.javaPrimitiveType)
            m.isAccessible = true
            val v = m.invoke(null, code.toInt()) as? String
            if (!v.isNullOrEmpty()) return v
        } catch (_: Throwable) {
        }
        return MCC_COUNTRY[code]
    }

    /** 网络侧取值：虚拟值为 null（无小区且无 SIM 配置）→ Origin 保持真实；否则 proceed-first 替换 */
    private fun netValue(chain: XposedInterface.Chain, rt: Class<*>, virtual: () -> Any?): Decided {
        val v = runCatching { virtual() }.getOrNull() ?: return Decided.Origin
        return replaceIfAllowed(chain, rt) { v }
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
        // 卡身份对齐（审查八 #5）：默认查询透传的真实 subId 与虚拟列表/查询类出口
        // 在同一 id 下才有意义
        val slots = alignedSlots(sim)
        val infos = cachedInfos(sim, slots)
        return when (target) {
            // ---- 权限类出口（评审二/三轮 #6）：gated 先 proceed 复现权限语义；
            // ---- 注入判据是调用方自身的 READ_PHONE_STATE（见 gated 注释），
            // ---- 不再以"真实结果是否为空"推断——无实体 SIM 的真机上也能注入虚拟 SIM
            "list" -> gated(chain, rt, canReadPhoneState(), onOrigin = { o ->
                // 完整拓扑捕获（审查八 #5）：真实订阅列表的 subId 集合
                (o as? List<*>)?.let { l ->
                    noteRealTopology(l.mapNotNull { callInt(it, "getSubscriptionId") }.toIntArray())
                }
            }) { ArrayList(infos.map { it.second }) }
            "available" -> gated(chain, rt, canReadPhoneState()) {
                ArrayList(slots.filter { !it.active }.mapNotNull { buildSubscriptionInfo(it) })
            }
            "count" -> gated(chain, rt, canReadPhoneState()) { infos.size }
            "ids" -> gated(chain, rt, canReadPhoneState(), onOrigin = { o ->
                // 完整拓扑捕获（审查八 #5）：真实有效订阅 id 列表
                (o as? IntArray)?.let { noteRealTopology(it) }
            }) { infos.map { it.first.subId }.toIntArray() }
            "bySubId" -> gated(chain, rt, canReadPhoneState()) { infos.firstOrNull { it.first.subId == arg }?.second }  // 参数语义 = subId（#12），查无匹配返回 null
            "bySlot" -> gated(chain, rt, canReadPhoneState()) { infos.firstOrNull { it.first.slotIndex == arg }?.second }
            // getPhoneNumber 系列：READ_PHONE_NUMBERS 独立授权即可（API 33+）
            "phoneOfSub" -> gated(chain, rt, canReadPhoneNumbers()) {
                infos.firstOrNull { it.first.subId == arg }?.first?.phoneNumber?.ifEmpty { null }
            }
            // ---- 非权限出口：静态工具 / 计数上限，直接替换
            "countMax" -> direct(slots.size, rt)
            "defaultId" -> defaultSubId(chain, rt, sim)
            "slotOfSub" -> direct(slots.firstOrNull { it.subId == arg }?.slotIndex ?: -1, rt)
            "subsOfSlot" -> Decided.Value(slots.firstOrNull { it.slotIndex == arg }?.let { intArrayOf(it.subId) })
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

    private fun callInt(o: Any?, name: String): Int? = try {
        o?.javaClass?.getMethod(name)?.invoke(o) as? Int
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
     * 真机有效订阅拓扑（升序 subId）。由真实出口捕获：ids / list 出口 proceed 得到
     * 完整拓扑；default* 出口 proceed 得到的真实默认 id 作为种子（拓扑子集，随后被
     * 完整拓扑覆盖）。映射只随订阅拓扑变化更新，不随查询顺序更新（审查八 #5）。
     */
    @Volatile
    private var realSubIds: IntArray = IntArray(0)

    /** 拓扑变化才更新（升序比较）；缓存指纹随数组内容变化，infos 自动重建 */
    private fun noteRealTopology(ids: IntArray) {
        val sorted = ids.filter { it >= 0 }.sorted().toIntArray()
        if (realSubIds.contentEquals(sorted)) return
        realSubIds = sorted
        if (sorted.isNotEmpty()) {
            ProbeLog.log("SIM real subscription topology -> ${sorted.joinToString(",")}")
        }
    }

    /**
     * 卡身份对齐（审查八 #5，替代上一轮"默认查询改写首卡 subId"的做法）：
     * 按拓扑位置把激活虚拟卡与真机有效订阅配对（第 i 张 ↔ 第 i 个真实 subId），
     * 虚拟卡**改用配对的真实 subId 作自身 id**（保留真实有效 subId、替换卡资料）——
     * 默认数据/语音/短信各自透传的真实 id 与虚拟列表天然一致，且映射稳定不随查询
     * 漂移。真实订阅覆盖不到的额外虚拟卡保留配置 subId：纯模拟身份，无真实承载
     * （短信/通话/数据不可用），留痕标注。
     */
    private fun alignedSlots(sim: SimSnapshot): List<VirtualSimSlot> {
        val real = realSubIds
        if (real.isEmpty()) return sim.slots
        val active = sim.slots.filter { it.active }.sortedBy { it.slotIndex }
        if (active.isEmpty()) return sim.slots
        val paired = HashMap<Int, Int>()
        active.forEachIndexed { i, s -> if (i < real.size) paired[s.subId] = real[i] }
        if (active.size > real.size) {
            failOnce("sim-extra", "SIM ${active.size - real.size} 张虚拟卡无真实承载（模拟身份）")
        }
        return sim.slots.map { paired[it.subId]?.let { rid -> it.copy(subId = rid) } ?: it }
    }

    /**
     * getDefault*SubscriptionId（审查八 #5）：透传真实值——默认数据/语音/短信是各自
     * 独立的选择，真机 id 也是框架内部通路（短信/数据）的硬依赖；卡身份经
     * [alignedSlots] 按"真实有效 subId"对齐后，透传值与虚拟列表天然一致。真实默认
     * 不存在（无卡/未插卡）时保留 INVALID 语义，不再用虚拟 subId 顶替。proceed 之后
     * 不允许返回 Origin（dispatch 对 Origin 会二次 proceed，#2）。
     */
    private fun defaultSubId(chain: XposedInterface.Chain, rt: Class<*>, sim: SimSnapshot): Decided {
        val origin = try {
            chain.proceed()
        } catch (se: SecurityException) {
            return Decided.Rethrow(se)
        } catch (_: Throwable) {
            return Decided.Value(coerceToSubId(INVALID_SUBSCRIPTION_ID, rt))
        }
        if (origin is Int && origin >= 0) {
            noteRealTopology(intArrayOf(origin))   // 种子拓扑；完整拓扑由 ids/list 出口覆盖
            return Decided.Value(coerceToSubId(origin, rt))
        }
        return Decided.Value(coerceToSubId(INVALID_SUBSCRIPTION_ID, rt))
    }

    /** defaultId 出口返回类型恒为 Int；coerce 只为防 ROM 差异，不符即回落值本身 */
    private fun coerceToSubId(v: Any?, rt: Class<*>): Any? {
        val c = coerce(v, rt)
        return if (c === MISS) v else c
    }

    /**
     * SLOT=slotIndex 型（getImei/getDeviceId/getSimState...）、SUB_ID=subscriptionId 型
     * （getSubscriberId/getLine1Number/getSimOperator 隐藏重载...）。
     * 无参时读 createForSubscriptionId 实例的 subId，再回落默认（首个激活）卡。
     * 显式按索引/subId 命中的卡槽即使非激活也原样返回，"无卡"语义由取值处给出（#7）。
     */
    private fun resolveSlot(chain: XposedInterface.Chain, slots: List<VirtualSimSlot>, kind: ArgKind): VirtualSimSlot? {
        val arg = chain.args.firstOrNull() as? Int      // #1：无参重载安全取参
        return when {
            arg != null && kind == ArgKind.SLOT -> slots.firstOrNull { it.slotIndex == arg }
            arg != null && kind == ArgKind.SUB_ID -> slots.firstOrNull { it.subId == arg }
            else -> {
                val sid = instanceSubId(chain.thisObject)
                slots.firstOrNull { sid != null && it.subId == sid }
                    ?: slots.firstOrNull { it.active }
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
            ?: ("86000000" + String.format(
                java.util.Locale.ROOT, "%06d",
                ((slot.subId.toLong() and 0xFFFFFFF) % 1_000_000).toInt(),
            ))
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
     * SubscriptionManager 权限类出口（评审二/三轮 #6）：先 proceed 复现 Android 权限语义，
     * 注入与否按调用方自身权限判定，而不是按真实结果是否为空——空结果既可能是
     * "无权限被脱敏"，也可能是"真机没有实体 SIM"，二者不可区分；按权限判定后：
     *  - 有权限：无论真实结果是否为空都注入虚拟值 → 无实体 SIM 的设备也能模拟；
     *  - 无权限：原样返回真实结果（系统脱敏视角，通常为 null/空）→ 与真机行为一致，
     *    不会让无权限应用读到完整订阅列表（含号码）。
     * 异常路径（评审三轮 #6）：SecurityException 原样传播；其他异常视为"原方法已执行"，
     * 直接返回中性值/虚拟值，绝不返回 Origin——dispatch 对 Origin 会再次 proceed，
     * 造成原方法执行两次。
     */
    private inline fun gated(
        chain: XposedInterface.Chain,
        rt: Class<*>,
        allowed: Boolean,
        onOrigin: (Any?) -> Unit = {},
        virtual: () -> Any?,
    ): Decided {
        val origin = try {
            chain.proceed()
        } catch (se: SecurityException) {
            return Decided.Rethrow(se)
        } catch (_: Throwable) {
            // 原方法自身异常（非权限类）：已执行过一次，不重放异常、不二次 proceed
            return if (allowed) Decided.Value(spoof(rt, virtual))
            else Decided.Value(neutralOf(rt))
        }
        if (allowed) {
            // 拓扑捕获（审查八 #5）：ids/list 出口的 proceed 结果就是真机订阅拓扑，
            // 仅用于内部映射，不改变本出口的替换行为
            onOrigin(origin)
            return Decided.Value(spoof(rt, virtual))
        }
        return Decided.Value(origin)
    }

    /** 返回类型中性值：异常兜底时替换原方法结果，不返回类型不符的 null */
    private fun neutralOf(rt: Class<*>): Any? = when {
        rt == JAVA_INT -> 0
        rt == JAVA_BOOLEAN -> false
        else -> null
    }

    private val READ_PHONE_STATE = "android.permission.READ_PHONE_STATE"
    private val READ_PHONE_NUMBERS = "android.permission.READ_PHONE_NUMBERS"

    /**
     * 调用方自身是否持有 READ_PHONE_STATE（客户端钩在应用进程内，自查即可）。
     * Context 取不到（Application 未创建等）按无权限处理：透传真实结果，安全侧。
     */
    private fun canReadPhoneState(): Boolean = selfGranted(READ_PHONE_STATE)

    /** getPhoneNumber 系列独立权限：READ_PHONE_NUMBERS 或 READ_PHONE_STATE 任一 */
    private fun canReadPhoneNumbers(): Boolean =
        selfGranted(READ_PHONE_NUMBERS) || selfGranted(READ_PHONE_STATE)

    private fun selfGranted(permission: String): Boolean = try {
        appContext()?.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** hook 进程内拿应用 Context（反射 ActivityThread.currentApplication） */
    private fun appContext(): android.content.Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? android.content.Context
    } catch (_: Throwable) {
        null
    }

    /* ============ per-快照缓存（#15） ============ */

    /** infos 内的 subId 已按真实订阅拓扑对齐：拓扑变化即整表重建（审查八 #5） */
    private class SimCache(val sim: SimSnapshot, val topoFp: Int) {
        var infos: List<Pair<VirtualSimSlot, SubscriptionInfo>>? = null
        var props: Map<String, String>? = null
    }

    @Volatile
    private var cache: SimCache? = null

    /** 以 (SimSnapshot 实例, 拓扑指纹) 为失效票据：快照不可变、任一票据变化即重建 */
    private fun cacheFor(sim: SimSnapshot, topoFp: Int): SimCache {
        val c = cache
        if (c != null && c.sim === sim && c.topoFp == topoFp) return c
        return SimCache(sim, topoFp).also { cache = it }
    }

    private fun cachedInfos(sim: SimSnapshot, slots: List<VirtualSimSlot>): List<Pair<VirtualSimSlot, SubscriptionInfo>> {
        val fp = realSubIds.contentHashCode()
        cacheFor(sim, fp).infos?.let { return it }
        // 构造失败的卡槽整条剔除（评审二：Pair 恒非 null 的写法会留下 null second，
        // 后续 .second 取值即 NPE）
        val built = slots.filter { it.active }
            .mapNotNull { s -> buildSubscriptionInfo(s)?.let { s to it } }
        if (built.isEmpty() && slots.any { it.active }) {
            // #12：构造全线失败要留痕。不回落 proceed——那会把宿主真实订阅信息泄漏给被测 App
            ProbeLog.log("SIM-ERR subinfo build failed sdk=${Build.VERSION.SDK_INT}")
        }
        cacheFor(sim, fp).infos = built
        return built
    }

    /** 多卡按 slotIndex 排序占位拼接，空槽填 ABSENT / 空串，逗号位置与卡槽不错位（#7） */
    private fun cachedProps(sim: SimSnapshot): Map<String, String> {
        val fp = realSubIds.contentHashCode()
        cacheFor(sim, fp).props?.let { return it }
        val sorted = sim.slots.sortedBy { it.slotIndex }
        val p = mapOf(
            "gsm.sim.state" to sorted.joinToString(",") { if (it.active) "READY" else "ABSENT" },
            "gsm.sim.operator.iso-country" to sorted.joinToString(",") { if (it.active) it.countryIso else "" },
            "gsm.sim.operator.numeric" to sorted.joinToString(",") { if (it.active) plmnOf(it) else "" },
            // alpha 含逗号会被消费方按槽拆坏（#7）
            "gsm.sim.operator.alpha" to sorted.joinToString(",") { if (it.active) it.carrierName.replace(',', ' ') else "" },
        )
        cacheFor(sim, fp).props = p
        return p
    }

    /* ============ 日志限频（#15） ============ */

    private val hitSeen: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun hitOnce(msg: String) {
        if (hitSeen.size > 512) hitSeen.clear()
        if (hitSeen.add(msg)) ProbeLog.log("SIM-SPOOF $msg")
    }

    /** 同类失败只记一次（对齐 CellInfoFactory.failOnce 语义） */
    private val failSeen: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    private fun failOnce(key: String, msg: String) {
        if (failSeen.add(key)) ProbeLog.log(msg)
    }
}