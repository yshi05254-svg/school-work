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
                    val registered = chain.proceed()
                    val cb = chain.args.lastOrNull() ?: return registered
                    runCatching {
                        CallbackHooks.install(
                            module, "CELL-SS", cb,
                            stubHint = if (legacy) "IPhoneStateListener" else "ITelephonyCallback",
                            methodNames = setOf(
                                "onCellInfoChanged", "onCellLocationChanged",
                                "onServiceStateChanged", "onSignalStrengthsChanged",
                            ),
                        ) { chain, name ->
                            val out = runCatching { rewrite(name, chain, pkg, uid) }.getOrNull()
                            if (out != null) {
                                chain.proceed(out)
                            } else {
                                chain.proceed()
                            }
                        }
                    }.onFailure { ProbeLog.log("CELL-SS-FAIL $it") }
                    return registered
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
                val key = real.keySet().firstOrNull { real.get(it) is android.telephony.CellLocation }
                    ?: return null
                // SDK 33+ 的 CellLocation stub 不再声明 Parcelable（运行时仍实现）：
                // 运行时安全转换；转换失败透传真实值
                val p = loc as? android.os.Parcelable ?: return null
                // binder 派发的 Bundle 是私有副本，可原地替换
                @Suppress("DEPRECATION")
                real.putParcelable(key, p)
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
     * ServiceState：公开拷贝构造 + 公开 setter（setState(0)=IN_SERVICE、
     * setOperatorName(long, short, numeric)、setRoaming(false)）。
     * 运营商名/编号取 SIM 配置兜底，无配置时仅置注册态。
     */
    private fun rewriteServiceState(real: ServiceState, pkg: String, uid: Int): ServiceState? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return null
        val env = eff.environment ?: return null
        val serving = CellInfoFactory.pickServing(env.cells) ?: return null
        val out = ServiceState(real) // 公开拷贝构造：保留真实网络能力字段
        runCatching { out.setState(ServiceState.STATE_IN_SERVICE) }
        runCatching { out.setRoaming(false) }
        val slot = eff.payload.sim.slots.firstOrNull { it.active }
        val alpha = slot?.carrierName?.takeIf { it.isNotBlank() }
        val numeric = slot?.let { it.mcc + it.mnc }
        runCatching { out.setOperatorName(alpha ?: "", alpha ?: "", numeric ?: "") }
        hit("ss", "svc rat=${serving.radioType}")
        return out
    }

    /**
     * SignalStrength：公开拷贝构造 + 按服务小区制式替换 mSignals 数组内同型分量。
     * 数组字段经类型发现（CellSignalStrength[]），类型不匹配的元素保持真实。
     */
    private fun rewriteSignalStrength(real: SignalStrength, pkg: String, uid: Int): SignalStrength? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return null
        val env = eff.environment ?: return null
        val serving = CellInfoFactory.pickServing(env.cells) ?: return null
        val rat = CellInfoFactory.ratTypeOf(serving.radioType) ?: return null
        val sig = CellInfoFactory.buildSignalStrength(rat, serving.signalDbm) ?: return null
        val out = SignalStrength(real)
        val arrField = generateSequence<Class<*>>(out.javaClass) { it.superclass }
            .take(4)
            .flatMap { it.declaredFields.toList() }
            .firstOrNull { it.type.isArray && it.type.componentType.name.endsWith("CellSignalStrength") }
            ?: run {
                failOnce("ss-array", "CELL-SS mSignals field not found")
                return null
            }
        arrField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val arr = arrField.get(out) as? Array<Any> ?: return null
        var replaced = false
        for (i in arr.indices) {
            // 同制式分量替换（基类名前缀相同，如 CellSignalStrengthLte）
            if (arr[i].javaClass.name.substringAfterLast('.') ==
                sig.javaClass.name.substringAfterLast('.')
            ) {
                arr[i] = sig
                replaced = true
            }
        }
        if (!replaced) {
            failOnce("ss-rat", "CELL-SS no matching component rat=${rat.name}")
            return null
        }
        hit("ss", "sig rat=${rat.name} dbm=${serving.signalDbm}")
        return out
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
