package dev.ven11.module.hook.cell

import android.os.Bundle
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.hook.util.CallbackHooks
import dev.ven11.module.ipc.PolicyResolver
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * 电话监听回调钩（覆盖域扩展 3c，完整深度）：轮询类出口（getAllCellInfo 等）
 * 之外，注册类回调是应用拿基站/网络状态的另一条主路径。注册入口均为公开 API：
 *  - TelephonyManager.listen(PhoneStateListener, int)（legacy，deprecated）
 *  - TelephonyManager.registerTelephonyCallback(int, Executor, TelephonyCallback)
 *    / registerTelephonyCallback(Executor, TelephonyCallback)（API 31+）
 *
 * 实现方式：注册照常 proceed（不替换回调对象——TelephonyCallback 是含多个子接口
 * 的框架抽象类，无法代理）；经 CallbackHooks 在回调具体类（legacy 路径深入其内部
 * IPhoneStateListener$Stub 匿名实现）上按方法名改写交付：
 *  - onCellInfoChanged(List<CellInfo>) → CellInfoFactory.build（复用 cellListFor 语义）
 *  - onCellLocationChanged(Bundle|CellLocation) → Bundle 内 CellLocation 值替换 /
 *    asGsmCellLocation（框架经 binder 传 Bundle 形态）
 *  - onServiceStateChanged(ServiceState) → 公开拷贝构造 + 公开 setter
 *    （setOperatorName/setState/setRoaming，Android 16 jar 实证公开）
 *  - onSignalStrengthsChanged(SignalStrength) → 公开拷贝构造 + 按服务小区制式替换
 *    CellSignalStrength 分量（复用 CellInfoFactory.buildSignalStrength）
 *
 * 一致性语义：域门 = Domain.CELL；无环境/无小区/改写失败一律透传真实值
 * （改写失败 failOnce 记日志）；类不存在（ROM 差异）记日志跳过。
 */
class ClientTelephonyListenerHooks(private val module: XposedModule) {

    private companion object {
        const val HIT_INTERVAL_MS = 30_000L
        val REG_STATE_SETTERS = arrayOf("setVoiceRegState", "setDataRegState")
        val NET_TYPE_SETTERS = arrayOf("setVoiceNetworkType", "setDataNetworkType")
    }

    private val lastHit = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun install(cl: ClassLoader, pkg: String): Int {
        var n = 0
        val uid = android.os.Process.myUid()
        val tmClass = try {
            cl.loadClass("android.telephony.TelephonyManager")
        } catch (_: Throwable) {
            return 0
        }
        for (m in tmClass.declaredMethods) {
            val isLegacy = m.name == "listen" && m.parameterTypes.size == 2
            val isNew = m.name == "registerTelephonyCallback" &&
                m.parameterTypes.any { it.name.endsWith("TelephonyCallback") }
            if (!isLegacy && !isNew) continue
            installListenerRegistration(m, pkg, uid, legacy = isLegacy)
            n++
        }
        module.log(Log.INFO, "VEN11", "client telephony listener hooks installed=$n pkg=$pkg")
        ProbeLog.log("CELL-LISTEN-HOOKS n=$n pkg=$pkg")
        return n
    }

    private fun installListenerRegistration(m: Method, pkg: String, uid: Int, legacy: Boolean) {
        module.hook(m).setId("ven11.celllisten.${sig(m)}")
            .intercept(object : io.github.libxposed.api.XposedInterface.Hooker {
                override fun intercept(chain: io.github.libxposed.api.XposedInterface.Chain): Any? {
                    // 准备工作必须在原注册之前（审查八 #3）：AOSP TelephonyRegistry 的
                    // 注册路径可能同步交付缓存的服务状态/基站结果，"先注册后装钩"会漏掉
                    // 第一条（缓存命中 + 立即执行 Executor 时必现）。回调识别只用
                    // chain.args，不需要先 proceed。listen(PhoneStateListener, int events)
                    // 回调在首位；registerTelephonyCallback(int?, Executor, cb) 固定末位。
                    val cb = (if (legacy) chain.args.firstOrNull() else chain.args.lastOrNull())
                        ?: return chain.proceed()
                    // 不依赖内部 stub 类名（ROM 间漂移：IPhoneStateListener / IPhoneStateListenerStub
                    // 等）：应用的覆写在回调具体类上必然可枚举；未覆写的交付走基类方法，
                    // 钩基类兜底（stub 内部经虚调用转回公开方法，同一交付点只拦一次）
                    val base: Class<*>? = if (legacy) android.telephony.PhoneStateListener::class.java
                    else android.telephony.TelephonyCallback::class.java
                    runCatching {
                        CallbackHooks.install(
                            module, "CELL-SS", cb,
                            stubHint = null,
                            methodNames = setOf(
                                "onCellInfoChanged", "onCellLocationChanged",
                                "onServiceStateChanged", "onSignalStrengthsChanged",
                            ),
                            baseFallback = base,
                        ) { chain, name ->
                            val out = runCatching { rewrite(name, chain, pkg, uid) }.getOrNull()
                            if (out != null) {
                                chain.proceed(out)
                            } else {
                                chain.proceed()
                            }
                        }
                    }.onFailure { ProbeLog.log("CELL-SS-FAIL $it") }
                    // 原注册只执行一次；异常（含权限）原样传播。客户端钩身份进程级固定，
                    // 无需登记/回滚实例状态
                    return chain.proceed()
                }
            })
    }

    // ------------------------------------------------------------ 各交付点改写

    /** 返回 null = 无改写（proceed 原参）；返回数组 = proceed(newArgs) */
    private fun rewrite(name: String, chain: io.github.libxposed.api.XposedInterface.Chain, pkg: String, uid: Int): Array<Any?>? {
        val args = chain.args
        return when (name) {
            "onCellInfoChanged" -> {
                val real = args.firstOrNull() as? List<*> ?: return null
                val out = ClientCellHooks.cellListFor(real, pkg, uid)
                if (out === real) null else arrayOf(out)
            }
            "onCellLocationChanged" -> {
                val real = args.firstOrNull() ?: return null
                val out = rewriteCellLocation(real, pkg, uid) ?: return null
                arrayOf(out)
            }
            "onServiceStateChanged" -> {
                val real = args.firstOrNull() as? ServiceState ?: return null
                val out = rewriteServiceState(real, pkg, uid) ?: return null
                arrayOf(out)
            }
            "onSignalStrengthsChanged" -> {
                val real = args.firstOrNull() as? SignalStrength ?: return null
                val out = rewriteSignalStrength(real, pkg, uid) ?: return null
                arrayOf(out)
            }
            else -> null
        }
    }

    /** Bundle 形态（binder 序列化）替换其中 CellLocation 值；CellLocation 形态直接换 */
    private fun rewriteCellLocation(real: Any, pkg: String, uid: Int): Any? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return null
        val env = eff.environment ?: return null
        val serving = CellInfoFactory.pickServing(env.cells) ?: return null
        val loc = CellInfoFactory.asGsmCellLocation(serving)
        return when (real) {
            is Bundle -> {
                // binder 形态：registry 经 GsmCellLocation.fillInNotifierBundle 下发的是
                // int 键（"lac"/"cid"/"psc"），不是 CellLocation 对象——对象键形态仅作
                // 兼容保留，否则本分支永远不命中
                val objKey = real.keySet().firstOrNull { real.get(it) is android.telephony.CellLocation }
                if (objKey != null) {
                    // SDK 33+ 的 CellLocation stub 不再声明 Parcelable（运行时仍实现）：
                    // 运行时安全转换；转换失败透传真实值
                    val p = loc as? android.os.Parcelable ?: return null
                    // binder 派发的 Bundle 是私有副本，可原地替换
                    @Suppress("DEPRECATION")
                    real.putParcelable(objKey, p)
                    hit("loc", "cellloc-bundle-obj")
                    return real
                }
                val gsm = loc as? GsmCellLocation ?: return null
                var touched = false
                if (real.containsKey("lac")) { real.putInt("lac", gsm.lac); touched = true }
                if (real.containsKey("cid")) { real.putInt("cid", gsm.cid); touched = true }
                if (real.containsKey("psc")) { real.putInt("psc", gsm.psc); touched = true }
                if (!touched) return null
                hit("loc", "cellloc-bundle")
                real
            }
            is GsmCellLocation -> {
                hit("loc", "cellloc")
                loc
            }
            else -> null
        }
    }

    /**
     * ServiceState：白名单重建（审查八 #4）。不再 ServiceState(real) 拷贝——拷贝构造
     * 会把真实运营商/网络注册信息（含真实 CellIdentity、数据/语音注册态）整体带入，
     * 只改顶层字段必然留有真值残留。从空对象起，仅写入虚拟模型能提供的字段：
     *  - 总注册态 IN_SERVICE、非漫游、语音/数据注册态与制式：跟随同一虚拟服务小区
     *    （数据驻留 IWLAN / 双域注册不建模，文档化）；
     *  - 运营商编号取服务小区 PLMN；名字仅在 SIM 配置提供时使用，否则以编号占位
     *    （既不残留真实名，也不清成空串）；
     *  - CellIdentity 用虚拟服务小区构造（清掉真实小区身份的关键一步）；
     *  - 隐藏 setter 逐项反射写入，失败项 failOnce 留痕——ROM 适配不全时明确记录
     *    未覆盖项，不得仍报告"完整伪装成功"。
     */
    private fun rewriteServiceState(real: ServiceState, pkg: String, uid: Int): ServiceState? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return null
        val env = eff.environment ?: return null
        val serving = CellInfoFactory.pickServing(env.cells) ?: return null
        val out = ServiceState()
        val missed = ArrayList<String>()
        runCatching { out.setState(ServiceState.STATE_IN_SERVICE) }.onFailure { missed.add("setState") }
        runCatching { out.setRoaming(false) }.onFailure { missed.add("setRoaming") }
        // setVoiceRegState/setDataRegState/…是隐藏 API（SDK 36 stub 无，审查八修正）：
        // 与 setCellIdentity 同一反射路径
        for (name in REG_STATE_SETTERS) {
            runCatching {
                ssIntSetter(name)!!.invoke(out, ServiceState.STATE_IN_SERVICE)
            }.onFailure { missed.add(name) }
        }
        val netType = CellInfoFactory.ratTypeOf(serving.radioType)
            ?.let { CellInfoFactory.networkTypeOf(it) }
        if (netType != null) {
            for (name in NET_TYPE_SETTERS) {
                runCatching {
                    ssIntSetter(name)!!.invoke(out, netType)
                }.onFailure { missed.add(name) }
            }
        } else {
            missed.add("networkType(${serving.radioType})")
        }
        val numeric = serving.mcc + serving.mnc
        val alpha = if (eff.domainEnabled(PolicyResolver.Domain.SIM) && eff.payload.sim.enabled) {
            eff.payload.sim.slots.firstOrNull { it.active }?.carrierName?.takeIf { it.isNotBlank() }
        } else null
        runCatching { out.setOperatorName(alpha ?: numeric, alpha ?: numeric, numeric) }
            .onFailure { missed.add("setOperatorName") }
        val identity = CellInfoFactory.buildIdentity(serving)
        if (identity != null) {
            runCatching {
                ssSetCellIdentity!!.invoke(out, identity)
            }.onFailure { missed.add("setCellIdentity") }
        } else {
            missed.add("cellIdentity-build")
        }
        if (missed.isNotEmpty()) {
            failOnce("ss-fields", "CELL-SS ServiceState 未覆盖字段: ${missed.joinToString(",")}")
        }
        hit("ss", "svc rat=${serving.radioType} missed=${missed.size}")
        return out
    }

    /**
     * SignalStrength：公开拷贝构造 + 按服务小区制式替换对应分量。
     * ROM 字段形态差异大，按序尝试三种形态（命中即止，失败透传真实值）：
     *  1. 按制式命名的独立字段（OPLUS Android 16 实机 dex 实证：mGsm/mLte/mNr/
     *     mTdscdma/mWcdma 各自持有 CellSignalStrength 子类，无数组）；
     *  2. AOSP R+ 数组形态 CellSignalStrength[]（mSignals）；
     *  3. List 集合形态（部分 ROM 变体）。
     * 每种形态的查找/写入结果均有 failOnce 日志，便于核对 ROM 差异。
     */
    private fun rewriteSignalStrength(real: SignalStrength, pkg: String, uid: Int): SignalStrength? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return null
        val env = eff.environment ?: return null
        val serving = CellInfoFactory.pickServing(env.cells) ?: return null
        val rat = CellInfoFactory.ratTypeOf(serving.radioType) ?: return null
        val sig = CellInfoFactory.buildSignalStrength(rat, serving.signalDbm) ?: return null
        val out = SignalStrength(real)

        // 形态 1：按制式命名字段（工程机 dex 实证的结构，首选）
        val namedField = mapOf(
            CellInfoFactory.Rat.GSM to "mGsm",
            CellInfoFactory.Rat.WCDMA to "mWcdma",
            CellInfoFactory.Rat.TDSCDMA to "mTdscdma",
            CellInfoFactory.Rat.LTE to "mLte",
            CellInfoFactory.Rat.NR to "mNr",
        )[rat]
        if (namedField != null && writeMatchingField(out, namedField, sig)) {
            hit("ss", "sig named=$namedField rat=${rat.name} dbm=${serving.signalDbm}")
            return out
        }

        // 形态 2：AOSP R+ 数组 mSignals
        val arrField = findField(out.javaClass) {
            it.type.isArray && it.type.componentType.name.endsWith("CellSignalStrength")
        }
        if (arrField != null) {
            arrField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val arr = arrField.get(out) as? Array<Any>
            if (arr != null) {
                for (i in arr.indices) {
                    if (arr[i].javaClass.name.substringAfterLast('.') ==
                        sig.javaClass.name.substringAfterLast('.')
                    ) {
                        arr[i] = sig
                        hit("ss", "sig array[$i] rat=${rat.name} dbm=${serving.signalDbm}")
                        return out
                    }
                }
                failOnce("ss-rat", "CELL-SS array has no ${sig.javaClass.simpleName} component")
            }
        }

        // 形态 3：List 集合形态（可变性不假设，整体替换为新 List）
        val listField = findField(out.javaClass) {
            val tn = it.type.name
            (tn == "java.util.List" || tn == "java.util.Collection") ||
                (tn.contains("CellSignalStrength") && !it.type.isArray)
        }
        if (listField != null) {
            listField.isAccessible = true
            val raw = listField.get(out)
            if (raw is List<*>) {
                val newList = raw.map { el ->
                    if (el != null && el.javaClass.name.substringAfterLast('.') ==
                        sig.javaClass.name.substringAfterLast('.')
                    ) sig else el
                }
                if (newList != raw) {
                    runCatching { listField.set(out, newList) }
                        .onSuccess {
                            hit("ss", "sig list rat=${rat.name} dbm=${serving.signalDbm}")
                            return out
                        }
                }
            }
        }

        failOnce("ss-shape", "CELL-SS no known component shape (named=$namedField) rat=${rat.name}")
        return null
    }

    // ---- ServiceState 隐藏 setter：按名只反射查找一次（缺失记 null，调用处按失败计入 missed）----

    private val ssIntSetters = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Method>>()

    private fun ssIntSetter(name: String): Method? = ssIntSetters.getOrPut(name) {
        java.util.Optional.ofNullable(
            runCatching {
                ServiceState::class.java.getDeclaredMethod(name, Int::class.java)
                    .apply { isAccessible = true }
            }.getOrNull(),
        )
    }.orElse(null)

    private val ssSetCellIdentity: Method? by lazy {
        runCatching {
            ServiceState::class.java
                .getDeclaredMethod("setCellIdentity", android.telephony.CellIdentity::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
    }

    /** 沿父类链找满足 [pred] 的第一个声明字段 */
    private fun findField(cls: Class<*>, pred: (java.lang.reflect.Field) -> Boolean): java.lang.reflect.Field? =
        generateSequence<Class<*>>(cls) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.toList() }
            .firstOrNull(pred)

    /** 按名定位字段并写入（要求字段类型兼容 [value]）；成功 true */
    private fun writeMatchingField(target: Any, name: String, value: Any): Boolean {
        val f = findField(target.javaClass) { it.name == name } ?: return false
        if (!f.type.isInstance(value)) return false
        return try {
            f.isAccessible = true
            f.set(target, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun sig(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

    private val failSeen = java.util.concurrent.ConcurrentHashMap<String, Unit>()

    private fun failOnce(key: String, msg: String) {
        if (failSeen.putIfAbsent(key, Unit) == null) ProbeLog.log(msg)
    }

    private fun hit(key: String, msg: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastHit.put(key, now) ?: 0L
        if (now - prev >= HIT_INTERVAL_MS) ProbeLog.log("CELL-SS-SPOOF $msg")
    }
}
