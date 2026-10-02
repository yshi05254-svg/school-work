package dev.ven11.module.hook.cell

import android.os.SystemClock
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.gsm.GsmCellLocation  // 注意包名：GsmCellLocation 在 .gsm 遗留包，非 android.telephony
import dev.ven11.module.ProbeLog
import dev.ven11.module.model.VirtualCell
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.LinkedHashSet
import java.util.concurrent.ConcurrentHashMap

// =====================================================================
// 依赖 model/Snapshot.kt 的 VirtualCell 契约（审查四：mcc/mnc 由 Int 改 String）：
//   radioType: String   "gsm"|"wcdma"|"lte"|"tdscdma"|"nr"（umts/4g/5g 等别名可解析）
//   mcc/mnc: 新模型 String；旧 Int 模型经 mccOf/mncOf 反射兼容并补前导零
//   ci: Long             2G/3G/4G 小区 id；NR 为 36 位 NCI，保持 long 不截断
//   lac/tac: Int         2G/3G 用 lac，4G/5G 用 tac
//   pci/psc/cpid: Int    各制式扰码 / WCDMA psc / TD cpid
//   arfcn: Int           频点，按制式写入 mEarfcn/mArfcn/mUarfcn/mNrArfcn
//   registered: Boolean  服务小区标记
//   signalDbm: Int
// =====================================================================

/**
 * 基站快照构造器（客户端 / 框架两侧共用）。
 *
 * 构造策略（审查 2）：不用各版本漂移极大的 CellIdentity 隐藏带参构造器，统一走
 * 隐藏无参构造 + 按字段名回填——字段名跨版本远比构造器签名稳定：
 *  - MCC/MNC（评审二）：28+ 载体是基类 String 字段 mMccStr/mMncStr；mMcc/mMnc int
 *    字段只在 ≤27 存在。两侧都写，setVal 按字段存在性与实际类型降级，失败侧跳过
 *  - mAdditionalPlmns / mBands（30+）：必须为非空引用，否则跨 Binder writeToParcel NPE（审查 11）
 *  - 各制式字段分别填参，不再把 LTE 模板套用给 GSM/WCDMA/NR
 *  - TD 用 CellInfoTdscdma（审查 3：原 CellInfoWcdma + CellIdentityTdscdma 字段类型不兼容）
 *
 * CellInfo 侧补齐关键状态（审查 5 / 评审二）：mTimeStamp=elapsedRealtimeNanos；
 * 28+ 的 mCellConnectionStatus 与 mRegistered 并存且都写（isRegistered() 读后者）；
 * 信号强度按类型定位 CellInfo 子类的 mCellSignalStrength* 字段，GSM/WCDMA 同写
 * ASU（≤Q）与 mRssi dBm（R+ getDbm 优先读）。
 *
 * 单条失败跳过该条并按"类+原因"只记一次 ProbeLog，不整体丢弃（审查 6）。
 * Class/Constructor/Field 全部缓存（审查四：getAllCellInfo 可能被高频轮询）。
 * 所有隐藏类按名反射加载：phone 进程不受限；应用进程依赖宿主框架的
 * 隐藏 API 豁免机制，需实机验证（审查四）。
 */
@Suppress("DEPRECATION")
object CellInfoFactory {

    internal enum class Rat(val infoCls: String, val idCls: String, val sigCls: String) {
        GSM(
            "android.telephony.CellInfoGsm",
            "android.telephony.CellIdentityGsm",
            "android.telephony.CellSignalStrengthGsm"
        ),
        WCDMA(
            "android.telephony.CellInfoWcdma",
            "android.telephony.CellIdentityWcdma",
            "android.telephony.CellSignalStrengthWcdma"
        ),
        LTE(
            "android.telephony.CellInfoLte",
            "android.telephony.CellIdentityLte",
            "android.telephony.CellSignalStrengthLte"
        ),
        TDSCDMA(
            "android.telephony.CellInfoTdscdma",
            "android.telephony.CellIdentityTdscdma",
            "android.telephony.CellSignalStrengthTdscdma"
        ),
        NR(
            "android.telephony.CellInfoNr",
            "android.telephony.CellIdentityNr",
            "android.telephony.CellSignalStrengthNr"
        ),
    }

    @Volatile
    private var loader: ClassLoader? = null

    /** 客户端 / 框架 install 时各调用一次；telephony 类在 boot classpath，两侧通用 */
    fun attach(cl: ClassLoader) {
        if (loader == null) loader = cl
    }

    // ---- 反射缓存 ------------------------------------------------------

    private val NONE = Any()
    private val classCache = ConcurrentHashMap<String, Class<*>>()
    private val ctorCache = ConcurrentHashMap<String, Any?>()
    private val fieldCache = ConcurrentHashMap<String, Any?>()
    private val identityFields = ConcurrentHashMap<Class<*>, Field>()
    private val signalFields = ConcurrentHashMap<Class<*>, Any>()   // 缺失记 NONE，防反复查找

    private fun C(name: String): Class<*>? {
        classCache[name]?.let { return it }
        val v = try {
            loader?.loadClass(name)
        } catch (_: Throwable) {
            null
        } ?: return null
        classCache[name] = v
        return v
    }

    private fun newInstance(cls: Class<*>): Any? {
        (ctorCache[cls.name] as? Constructor<*>)?.let {
            return try { it.newInstance() } catch (_: Throwable) { null }
        }
        if (ctorCache.containsKey(cls.name)) return null
        val ctor = try {
            cls.getDeclaredConstructor().apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
        ctorCache[cls.name] = ctor ?: NONE
        return ctor?.let { c -> try { c.newInstance() } catch (_: Throwable) { null } }
    }

    /** 沿父类链查找（mTimeStamp 等在 CellInfo 基类）；缺失字段静默跳过（版本差异属预期） */
    private fun fieldOf(cls: Class<*>, name: String): Field? {
        val key = "${cls.name}#$name"
        (fieldCache[key] as? Field)?.let { return it }
        if (fieldCache.containsKey(key)) return null
        var c: Class<*>? = cls
        var f: Field? = null
        while (c != null && f == null) {
            f = try { c.getDeclaredField(name) } catch (_: Throwable) { null }
            c = c.superclass
        }
        f?.isAccessible = true
        fieldCache[key] = f ?: NONE
        return f
    }

    /** 按类型定位 CellInfo 子类的 identity 字段（审查 1：各子类字段名不同，仅 NR 是 mCellIdentity） */
    private fun identityFieldOf(infoCls: Class<*>, idCls: Class<*>): Field? {
        identityFields[infoCls]?.let { return it }
        val f = infoCls.declaredFields
            .firstOrNull { idCls.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true } ?: return null
        identityFields[infoCls] = f
        return f
    }

    /** 按类型定位 CellInfo 子类的信号字段（评审二：mCellSignalStrengthLte 等，名字随制式变） */
    private fun signalFieldOf(infoCls: Class<*>, sigCls: Class<*>): Field? {
        (signalFields[infoCls] as? Field)?.let { return it }
        if (signalFields.containsKey(infoCls)) return null
        val f = infoCls.declaredFields
            .firstOrNull { sigCls.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
        signalFields[infoCls] = f ?: NONE
        return f
    }

    /** 按字段实际类型写入，String/int 等形态差异在此降级（如 ≤27 的 mMcc 是 int） */
    private fun setVal(o: Any, name: String, v: Any): Boolean {
        val f = fieldOf(o.javaClass, name) ?: return false
        return try {
            val t = f.type
            when {
                t == Int::class.java -> f.setInt(o, (v as? Number)?.toInt()
                    ?: (v as? String)?.toIntOrNull()
                    ?: return false)
                t == Long::class.java -> f.setLong(o, (v as? Number)?.toLong()
                    ?: (v as? String)?.toLongOrNull()
                    ?: return false)
                t == Boolean::class.java -> f.setBoolean(o, v as? Boolean ?: return false)
                t == String::class.java -> f.set(o, v.toString())
                t.isInstance(v) -> f.set(o, v)
                else -> return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private val failures = ConcurrentHashMap<String, Unit>()

    /** 审查 6：同类失败只记一次，避免轮询刷爆 ProbeLog */
    private fun failOnce(key: String, msg: String) {
        if (failures.putIfAbsent(key, Unit) == null) ProbeLog.log(msg)
    }

    // ---- 对外入口 ------------------------------------------------------

    fun build(cells: List<VirtualCell>): List<CellInfo> {
        val out = ArrayList<CellInfo>(cells.size)
        for (c in cells) {
            val info = try {
                create(c)
            } catch (t: Throwable) {
                failOnce(
                    "create:${t.javaClass.name}",
                    "CELL-BUILD-FAIL rat=${c.radioType} err=${t.javaClass.simpleName}"
                )
                null
            }
            if (info != null) out.add(info)
        }
        return out
    }

    /** R+ 框架侧 getCellLocation 的返回对象（审查 4：返回 GsmCellLocation 会 ClassCastException） */
    fun buildIdentity(c: VirtualCell): CellIdentity? {
        val rat = ratOf(c.radioType) ?: return null
        val idCls = C(rat.idCls) ?: return null
        val id = newInstance(idCls) ?: return null
        fillIdentity(id, rat, c)
        return id as? CellIdentity
    }

    /** 服务小区：registered 优先，未标注保持旧约定取首项（审查 8） */
    fun pickServing(cells: List<VirtualCell>): VirtualCell? =
        cells.firstOrNull { it.registered } ?: cells.firstOrNull()

    /** 按 radioType 明确分支构造，不再用"lac 是否为 0"猜制式（审查 8） */
    fun asGsmCellLocation(c: VirtualCell): GsmCellLocation {
        val loc = GsmCellLocation()
        return when (ratOf(c.radioType)) {
            Rat.LTE -> loc.apply {
                if (c.tac != 0 || c.ci != 0L) setLacAndCid(loc, c.tac, c.ci.toInt())
                if (c.pci != 0) setPsc(loc, c.pci) // AOSP：LTE 的 psc 槽位承载 pci
            }
            Rat.GSM -> loc.apply {
                if (c.lac != 0 || c.ci != 0L) setLacAndCid(loc, c.lac, c.ci.toInt())
            }
            Rat.WCDMA -> loc.apply {
                if (c.lac != 0 || c.ci != 0L) setLacAndCid(loc, c.lac, c.ci.toInt())
                if (c.psc != 0) setPsc(loc, c.psc)
            }
            Rat.TDSCDMA -> loc.apply {
                if (c.lac != 0 || c.ci != 0L) setLacAndCid(loc, c.lac, c.ci.toInt())
                if (c.cpid != 0) setPsc(loc, c.cpid)
            }
            // AOSP CellIdentityNr.asCellLocation() 返回空对象；NCI 36 位也不该截成 int
            Rat.NR, null -> loc
        }
    }

    /**
     * GsmCellLocation.setLacAndCid / setPsc 是隐藏 API（公开 SDK 无此方法，直接调用
     * 编译不过，评审二）：反射调用。应用进程内能否调用成功取决于宿主框架的隐藏 API
     * 豁免机制，失败保持空对象（调用方已有真实值兜底路径）。
     */
    private fun setLacAndCid(loc: GsmCellLocation, lac: Int, cid: Int) {
        try {
            GsmCellLocation::class.java
                .getDeclaredMethod("setLacAndCid", Int::class.java, Int::class.java)
                .apply { isAccessible = true }
                .invoke(loc, lac, cid)
        } catch (_: Throwable) {
        }
    }

    private fun setPsc(loc: GsmCellLocation, psc: Int) {
        try {
            GsmCellLocation::class.java
                .getDeclaredMethod("setPsc", Int::class.java)
                .apply { isAccessible = true }
                .invoke(loc, psc)
        } catch (_: Throwable) {
        }
    }

    // ---- 内部构造 ------------------------------------------------------

    private fun ratOf(radioType: String): Rat? = when (radioType.trim().lowercase()) {
        "gsm", "edge", "gprs" -> Rat.GSM
        "wcdma", "umts", "hsdpa", "hsupa", "hspa", "hspap" -> Rat.WCDMA
        "lte", "4g" -> Rat.LTE
        "tdscdma", "td" -> Rat.TDSCDMA
        "nr", "5g", "nr_sa", "nsa" -> Rat.NR
        else -> null
    }

    private fun create(c: VirtualCell): CellInfo? {
        val rat = ratOf(c.radioType)
        if (rat == null) {
            // 审查 3：未知制式（CDMA 等）显式拒绝并记录，不再静默当作 WCDMA
            failOnce("rat:${c.radioType}", "CELL-RAT-REJECT type=${c.radioType}")
            return null
        }
        val infoCls = C(rat.infoCls)
        val idCls = C(rat.idCls)
        if (infoCls == null || idCls == null) {
            failOnce("cls:${rat.name}", "CELL-CLS-MISS ${rat.name}")
            return null
        }
        val info = newInstance(infoCls)
        val id = newInstance(idCls)
        if (info == null || id == null) {
            failOnce("ctor:${rat.name}", "CELL-CTOR-FAIL ${rat.name}")
            return null
        }
        // 评审三轮 #3：关键字段必填——MCC/MNC、小区 id、信号挂载任一失败即弃整条，
        // 不返回部分初始化的对象（跨 Binder 传输需要完整初始化；宁可少一条也不给
        // 应用一个能被交叉比对识破/崩溃的半成品）
        if (!fillIdentity(id, rat, c)) {
            failOnce("identity:${rat.name}", "CELL-IDENTITY-FAIL ${rat.name}")
            return null
        }
        val idField = identityFieldOf(infoCls, idCls)
        if (idField == null || runCatching { idField.set(info, id) }.isFailure) {
            failOnce("idfield:${rat.name}", "CELL-ID-FIELD-FAIL ${rat.name}")
            return null
        }
        fillInfoState(info, c.registered) // 状态字段全部可选：缺失不影响身份正确性
        if (!fillSignal(info, infoCls, rat, c.signalDbm)) {
            failOnce("signal:${rat.name}", "CELL-SIGNAL-MISS ${rat.name}")
            return null
        }
        // 读回校验：MCC 可读时必须与配置一致（写失败静默丢字段的最后防线）
        if (!verifyIdentity(id, c)) {
            failOnce("verify:${rat.name}", "CELL-VERIFY-FAIL ${rat.name}")
            return null
        }
        return info as CellInfo
    }

    /**
     * 身份字段回填。返回 false = 必填项失败（MCC/MNC 任一、按制式的小区 id）：
     * MCC/MNC 在 28+ 走基类 String 字段、≤27 走子类 int 字段，任一侧写上即可；
     * mAlphaLong/mAlphaShort/mAdditionalPlmns/mBands/pci/tac/arfcn 为可选——
     * bands 与 additionalPlmns 只需非空引用（跨 Binder writeToParcel 防线，审查 11），
     * 写不上仅记一次日志。
     */
    private fun fillIdentity(id: Any, rat: Rat, c: VirtualCell): Boolean {
        val mccOk = setVal(id, "mMccStr", mccOf(c)) || setVal(id, "mMcc", mccOf(c))
        val mncOk = setVal(id, "mMncStr", mncOf(c)) || setVal(id, "mMnc", mncOf(c))
        if (!mccOk || !mncOk) return false
        setVal(id, "mAlphaLong", "")
        setVal(id, "mAlphaShort", "")
        if (!setVal(id, "mAdditionalPlmns", LinkedHashSet<String>()) ||
            !setVal(id, "mBands", IntArray(0))
        ) {
            failOnce("optsets:${rat.name}", "CELL-OPT-COLLECTION-MISS ${rat.name}")
        }
        val cellIdOk = when (rat) {
            Rat.LTE -> setVal(id, "mCi", c.ci.toInt())
            Rat.GSM -> setVal(id, "mLac", c.lac) && setVal(id, "mCid", c.ci.toInt())
            Rat.WCDMA -> setVal(id, "mLac", c.lac) && setVal(id, "mCid", c.ci.toInt())
            Rat.TDSCDMA -> setVal(id, "mLac", c.lac) && setVal(id, "mCid", c.ci.toInt())
            Rat.NR -> setVal(id, "mNci", c.ci) // 36 位 NCI，long 不截断（审查 8）
        }
        if (!cellIdOk) return false
        // 可选制式参数：pci/tac/arfcn/psc/cpid，写不上跳过（读回不影响 getDbm/PLMN 主路径）
        when (rat) {
            Rat.LTE -> {
                setVal(id, "mPci", c.pci)
                setVal(id, "mTac", c.tac)
                setVal(id, "mEarfcn", c.arfcn)
            }
            Rat.GSM -> setVal(id, "mArfcn", c.arfcn)
            Rat.WCDMA -> {
                setVal(id, "mPsc", c.psc)
                setVal(id, "mUarfcn", c.arfcn)
            }
            Rat.TDSCDMA -> {
                setVal(id, "mCpid", c.cpid)
                setVal(id, "mUarfcn", c.arfcn)
            }
            Rat.NR -> {
                setVal(id, "mPci", c.pci)
                setVal(id, "mTac", c.tac)
                setVal(id, "mNrArfcn", c.arfcn)
            }
        }
        return true
    }

    /** 构造后读回：MCC 任意载体可读时必须与配置一致（返回 false = 写入静默丢失） */
    private fun verifyIdentity(id: Any, c: VirtualCell): Boolean = try {
        val mccStr = fieldOf(id.javaClass, "mMccStr")?.get(id) as? String
        if (mccStr != null) {
            mccStr == mccOf(c)
        } else {
            val mccInt = fieldOf(id.javaClass, "mMcc")?.getInt(id)
            mccInt == null || mccInt.toString() == mccOf(c)
        }
    } catch (_: Throwable) {
        false
    }

    private fun fillInfoState(info: Any, registered: Boolean) {
        // 审查 5：开机纳秒基准，避免被应用按 elapsedRealtime 差值过滤为陈旧数据
        setVal(info, "mTimeStamp", SystemClock.elapsedRealtimeNanos())
        // 评审二：28+ 两个字段并存，isRegistered() 读 mRegistered、连接状态读
        // mCellConnectionStatus——原先"写不上 connection 才写 mRegistered"导致 28+
        // 永远 registered=false。两个都写，≤27 缺 mCellConnectionStatus 字段自然跳过
        setVal(info, "mCellConnectionStatus", if (registered) 1 else 0)
        setVal(info, "mRegistered", registered)
    }

    /**
     * per-rat 信号强度对象构造（覆盖域扩展共享：fillSignal 与电话监听钩
     * onSignalStrengthsChanged 共用同一构造语义）。失败返回 null。
     * rsrq/sinr/asu 等分量为可选，dbm 主读数写不上才失败。
     */
    internal fun buildSignalStrength(rat: Rat, dbm: Int): Any? {
        val sigCls = C(rat.sigCls) ?: return null
        val sig = newInstance(sigCls) ?: return null
        val dbmOk = when (rat) {
            Rat.LTE -> setVal(sig, "mRsrp", dbm)
            // ≤Q 主用 ASU 字段；R+ 新增 mRssi（dBm）且 getDbm 优先读它（评审二）。
            // 两个都写：字段不存在的那侧由 setVal 跳过，至少一侧必须写上
            Rat.GSM, Rat.WCDMA, Rat.TDSCDMA ->
                setVal(sig, "mSignalStrength", ((dbm + 113) / 2).coerceIn(0, 31)) ||
                    setVal(sig, "mRssi", dbm)
            Rat.NR -> setVal(sig, "mSsRsrp", dbm)
        }
        if (!dbmOk) return null
        // 可选分量：不算必填。
        // rsrq 用合法范围内的值（LTE/NR 的 RSRQ 是负 dB，合法区间 [-20,-3]；此前
        // 写 15/20 越界，读回校验和按区间判断的应用都会视其为无效）；
        // LTE 的 mSignalStrength 是 RSSI 的 ASU（0..31，99=unknown；审查六：此前
        // 按 rsrp+140 填会超出范围）——无独立 RSSI 源，按 RSSI ≈ RSRP + 10.8dB
        // （TS 36.214，全 RE）换算：asu = (dbm + 124) / 2
        when (rat) {
            Rat.LTE -> {
                setVal(sig, "mRsrq", -10)
                setVal(sig, "mRssnr", 30)
                setVal(sig, "mSignalStrength", ((dbm + 124) / 2).coerceIn(0, 31))
            }
            Rat.GSM, Rat.WCDMA, Rat.TDSCDMA ->
                setVal(sig, "mSignalStrength", ((dbm + 113) / 2).coerceIn(0, 31))
            Rat.NR -> {
                setVal(sig, "mSsRsrq", -10)
                setVal(sig, "mSsSinr", 30)
            }
        }
        // mLevel 回填（审查五）：T+ 的 CellSignalStrength* 子类显式持有 mLevel 且
        // getLevel() 直接返回它——反射无参构造出的实例不回填就恒为 0（无信号）；
        // R- 无此字段、getLevel() 由分量现算，setVal 按字段存在性自然跳过。
        // 阈值取 AOSP 默认（审查六 #阈值）：LTE rsrp [-115,-105,-95,-85]、
        // NR ssRsrp [-110,-90,-80,-65] 由低到高对应 level 1..4
        setVal(sig, "mLevel", levelOf(rat, dbm))
        return sig
    }

    /** dbm → 信号等级（对齐 AOSP 默认 getLevel 阈值）；LTE 按 rsrp、NR 按 ssRsrp、GSM/WCDMA/TD 按 asu */
    private fun levelOf(rat: Rat, dbm: Int): Int = when (rat) {
        Rat.GSM, Rat.WCDMA, Rat.TDSCDMA -> {
            val asu = ((dbm + 113) / 2).coerceIn(0, 31)
            when {
                asu >= 12 -> 4
                asu >= 8 -> 3
                asu >= 5 -> 2
                asu >= 3 -> 1
                else -> 0
            }
        }
        Rat.LTE -> when {
            dbm >= -85 -> 4
            dbm >= -95 -> 3
            dbm >= -105 -> 2
            dbm >= -115 -> 1
            else -> 0
        }
        Rat.NR -> when {
            dbm >= -65 -> 4
            dbm >= -80 -> 3
            dbm >= -90 -> 2
            dbm >= -110 -> 1
            else -> 0
        }
    }

    /** 按 radioType 解析制式（覆盖域扩展共享：电话监听钩按服务小区构造强度用） */
    internal fun ratTypeOf(radioType: String): Rat? = ratOf(radioType)

    /**
     * Rat → TelephonyManager.NETWORK_TYPE_*（审查八 #4：SIM 网络侧出口与
     * ServiceState 白名单重建共用同一映射，避免两处漂移）。
     */
    internal fun networkTypeOf(rat: Rat): Int? = when (rat) {
        Rat.GSM -> 16      // NETWORK_TYPE_GSM
        Rat.WCDMA -> 3     // NETWORK_TYPE_UMTS
        Rat.LTE -> 13      // NETWORK_TYPE_LTE
        Rat.TDSCDMA -> 17  // NETWORK_TYPE_TD_SCDMA
        Rat.NR -> 20       // NETWORK_TYPE_NR
    }

    /**
     * 信号强度对象构造 + 挂载。返回 false = 必填失败（强度类不可用/构造失败/
     * CellInfo 侧字段定位失败/写入失败），create() 弃整条；
     * 强度对象内部的分量字段（rsrq/sinr/asu 等）为可选，写不上不影响 dbm 主读数。
     */
    private fun fillSignal(info: Any, infoCls: Class<*>, rat: Rat, dbm: Int): Boolean {
        val sig = buildSignalStrength(rat, dbm) ?: return false
        // CellInfo 子类的信号字段名按制式各不相同（mCellSignalStrengthLte 等），
        // 按类型定位（同身份字段的做法），不再按名字找 mSignalStrength
        val f = signalFieldOf(infoCls, sig.javaClass) ?: return false
        return try {
            f.set(info, sig)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** mcc/mnc 已统一为模型 String 字段（评审二 #5 / 审查四）：前导零由配置层保证，不再反射兼容 */
    private fun mccOf(c: VirtualCell) = c.mcc
    private fun mncOf(c: VirtualCell) = c.mnc
}