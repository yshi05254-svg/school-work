package dev.ven11.module.hook.cell

import android.os.SystemClock
import android.telephony.CellIdentity
import android.telephony.CellInfo
import android.telephony.GsmCellLocation
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
 *  - mMcc/mMnc：28+ 为 String，≤27 为 int，由 setVal 按字段实际类型降级写入
 *  - mAdditionalPlmns / mBands（30+）：必须为非空引用，否则跨 Binder writeToParcel NPE（审查 11）
 *  - 各制式字段分别填参，不再把 LTE 模板套用给 GSM/WCDMA/NR
 *  - TD 用 CellInfoTdscdma（审查 3：原 CellInfoWcdma + CellIdentityTdscdma 字段类型不兼容）
 *
 * CellInfo 侧补齐关键状态（审查 5）：mTimeStamp=elapsedRealtimeNanos、
 * mCellConnectionStatus(28+)/mRegistered(≤27)、信号强度（≤P 直接挂类型化强度对象，
 * R+ 字段类型是合并 SignalStrength，无参构造后替换对应制式分量）。
 *
 * 单条失败跳过该条并按"类+原因"只记一次 ProbeLog，不整体丢弃（审查 6）。
 * Class/Constructor/Field 全部缓存（审查四：getAllCellInfo 可能被高频轮询）。
 * 所有隐藏类按名反射加载：phone 进程不受限；应用进程依赖宿主框架的
 * 隐藏 API 豁免机制，需实机验证（审查四）。
 */
@Suppress("DEPRECATION")
object CellInfoFactory {

    private enum class Rat(val infoCls: String, val idCls: String, val sigCls: String) {
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

    /** 按字段实际类型写入，String/int 等形态差异在此降级（如 ≤27 的 mMcc 是 int） */
    private fun setVal(o: Any, name: String, v: Any): Boolean {
        val f = fieldOf(o.javaClass, name) ?: return false
        return try {
            val t = f.type
            when {
                t == Int::class.java -> f.setInt(o, (v as? Number)?.toInt() ?: return false)
                t == Long::class.java -> f.setLong(o, (v as? Number)?.toLong() ?: return false)
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
        val rat = ratOf(c) ?: return null
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
        return when (ratOf(c)) {
            Rat.LTE -> loc.apply {
                if (c.tac != 0 || c.ci != 0L) setLacAndCid(c.tac, c.ci.toInt())
                if (c.pci != 0) setPsc(c.pci) // AOSP：LTE 的 psc 槽位承载 pci
            }
            Rat.GSM -> loc.apply {
                if (c.lac != 0 || c.ci != 0L) setLacAndCid(c.lac, c.ci.toInt())
            }
            Rat.WCDMA -> loc.apply {
                if (c.lac != 0 || c.ci != 0L) setLacAndCid(c.lac, c.ci.toInt())
                if (c.psc != 0) setPsc(c.psc)
            }
            Rat.TDSCDMA -> loc.apply {
                if (c.lac != 0 || c.ci != 0L) setLacAndCid(c.lac, c.ci.toInt())
                if (c.cpid != 0) setPsc(c.cpid)
            }
            // AOSP CellIdentityNr.asCellLocation() 返回空对象；NCI 36 位也不该截成 int
            Rat.NR, null -> loc
        }
    }

    // ---- 内部构造 ------------------------------------------------------

    private fun ratOf(c: VirtualCell): Rat? = when (c.radioType.trim().lowercase()) {
        "gsm", "edge", "gprs" -> Rat.GSM
        "wcdma", "umts", "hsdpa", "hsupa", "hspa", "hspap" -> Rat.WCDMA
        "lte", "4g" -> Rat.LTE
        "tdscdma", "td" -> Rat.TDSCDMA
        "nr", "5g", "nr_sa", "nsa" -> Rat.NR
        else -> null
    }

    private fun create(c: VirtualCell): CellInfo? {
        val rat = ratOf(c)
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
        fillIdentity(id, rat, c)
        val idField = identityFieldOf(infoCls, idCls)
        if (idField == null || runCatching { idField.set(info, id) }.isFailure) {
            failOnce("idfield:${rat.name}", "CELL-ID-FIELD-FAIL ${rat.name}")
            return null
        }
        fillInfoState(info, c.registered)
        fillSignal(info, infoCls, rat, c.signalDbm)
        return info as? CellInfo
    }

    private fun fillIdentity(id: Any, rat: Rat, c: VirtualCell) {
        setVal(id, "mMcc", mccOf(c))
        setVal(id, "mMnc", mncOf(c))
        setVal(id, "mAlphaLong", "")
        setVal(id, "mAlphaShort", "")
        // 30+ 字段：保持非空引用，跨 Binder writeToParcel 才不会 NPE（审查 11）
        setVal(id, "mAdditionalPlmns", LinkedHashSet<String>())
        setVal(id, "mBands", IntArray(0))
        when (rat) {
            Rat.LTE -> {
                setVal(id, "mCi", c.ci.toInt())
                setVal(id, "mPci", c.pci)
                setVal(id, "mTac", c.tac)
                setVal(id, "mEarfcn", c.arfcn)
            }
            Rat.GSM -> {
                setVal(id, "mLac", c.lac)
                setVal(id, "mCid", c.ci.toInt())
                setVal(id, "mArfcn", c.arfcn)
            }
            Rat.WCDMA -> {
                setVal(id, "mLac", c.lac)
                setVal(id, "mCid", c.ci.toInt())
                setVal(id, "mPsc", c.psc)
                setVal(id, "mUarfcn", c.arfcn)
            }
            Rat.TDSCDMA -> {
                setVal(id, "mLac", c.lac)
                setVal(id, "mCid", c.ci.toInt())
                setVal(id, "mCpid", c.cpid)
                setVal(id, "mUarfcn", c.arfcn)
            }
            Rat.NR -> {
                setVal(id, "mNci", c.ci) // 36 位 NCI，long 不截断（审查 8）
                setVal(id, "mPci", c.pci)
                setVal(id, "mTac", c.tac)
                setVal(id, "mNrArfcn", c.arfcn)
            }
        }
    }

    private fun fillInfoState(info: Any, registered: Boolean) {
        // 审查 5：开机纳秒基准，避免被应用按 elapsedRealtime 差值过滤为陈旧数据
        setVal(info, "mTimeStamp", SystemClock.elapsedRealtimeNanos())
        // 28+：mCellConnectionStatus（CONNECTION_PRIMARY_SERVING=1）；≤27：mRegistered
        if (!setVal(info, "mCellConnectionStatus", if (registered) 1 else 0)) {
            setVal(info, "mRegistered", registered)
        }
    }

    private fun fillSignal(info: Any, infoCls: Class<*>, rat: Rat, dbm: Int) {
        val sigCls = C(rat.sigCls) ?: return
        val sig = newInstance(sigCls) ?: return
        when (rat) {
            Rat.LTE -> {
                setVal(sig, "mRsrp", dbm)
                setVal(sig, "mRsrq", 15)
                setVal(sig, "mRssnr", 30)
                setVal(sig, "mSignalStrength", (dbm + 44).coerceIn(0, 97))
            }
            Rat.GSM, Rat.WCDMA, Rat.TDSCDMA ->
                setVal(sig, "mSignalStrength", ((dbm + 113) / 2).coerceIn(0, 31))
            Rat.NR -> {
                setVal(sig, "mSsRsrp", dbm)
                setVal(sig, "mSsRsrq", 20)
                setVal(sig, "mSsSinr", 30)
            }
        }
        val f = fieldOf(infoCls, "mSignalStrength") ?: return
        try {
            if (f.type.isInstance(sig)) {
                // ≤P：CellInfo 子类直接持有类型化 CellSignalStrength
                f.set(info, sig)
                return
            }
            // R+：字段类型是合并的 SignalStrength——无参构造后替换对应制式分量
            val ssCls = C("android.telephony.SignalStrength") ?: return
            val ss = newInstance(ssCls) ?: return
            val comp = ssCls.declaredFields.firstOrNull { it.type == sigCls } ?: return
            comp.isAccessible = true
            comp.set(ss, sig)
            f.set(info, ss)
        } catch (t: Throwable) {
            failOnce("sig:${t.javaClass.name}", "CELL-SIG-FAIL rat=${rat.name} err=${t.javaClass.simpleName}")
        }
    }

    /** mcc/mnc 兼容读：新模型 String 直接用；旧 Int 模型补前导零（3 位 mnc 的前导零无法从 Int 还原，管理器侧应尽快切 String——审查四） */
    private fun codeOf(c: VirtualCell, name: String, pad: Int): String = try {
        val f = c.javaClass.getDeclaredField(name).apply { isAccessible = true }
        when (val v = f.get(c)) {
            is String -> v
            is Int -> v.toString().padStart(pad, '0')
            else -> ""
        }
    } catch (_: Throwable) {
        ""
    }

    private fun mccOf(c: VirtualCell) = codeOf(c, "mcc", 3)
    private fun mncOf(c: VirtualCell) = codeOf(c, "mnc", 2)
}