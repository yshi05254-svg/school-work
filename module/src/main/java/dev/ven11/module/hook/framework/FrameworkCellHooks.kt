package dev.ven11.module.hook.framework

import android.os.Binder
import android.os.Bundle
import android.os.IInterface
import android.os.Process
import android.os.SystemClock
import android.telephony.CellIdentity
import android.telephony.CellLocation
import android.telephony.gsm.GsmCellLocation
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.hook.cell.CellInfoFactory
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.model.VirtualCell
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 框架侧基站钩（phone 进程，PhoneInterfaceManager）。按 callingPackage 解析策略（非全局环境）：
 *  - getAllCellInfo：Q 为 (String)，R+ 为 (String, String)，P 及更早无参——统一按
 *    "参数全 String（含无参）"匹配（审查 4：原先 isEmpty() 在 10+ 什么都匹配不到）
 *  - getCellLocation：按被 hook 方法声明返回类型分支——CellIdentity（R+，原实现返回
 *    GsmCellLocation 会 ClassCastException）/ Bundle（≤Q，替换其中 CellLocation 值，
 *    key 由扫描得出不硬编码）/ CellLocation（更老版本）
 *  - callingPackage 直接取自参数（审查 4，proceed 内 PIM 已完成包名↔uid 校验），
 *    仅无参签名回落 UidResolver 反查
 *
 * 语义：
 *  - proceed() 先行：SecurityException 原样传回；真实结果为 null/空时保持一致（审查 10）
 *  - 整个决策 runCatching 兜底，异常回落真实值，不以 binder 异常外泄（审查 11）；
 *    构造对象的集合字段由 CellInfoFactory 保证非空，规避 writeToParcel NPE
 *  - 系统调用按 appId 判定放行（审查 7：裸 uid 阈值对 user 10 的 system(1001000) 不成立）
 *
 * 已知未覆盖出口（审查三）：requestCellInfoUpdate、LISTEN_CELL_INFO /
 * TelephonyCallback.CellInfoListener、ServiceState、SignalStrength、getNetworkOperator；
 * 双卡未区分 subId。
 */
object FrameworkCellHooks {

    private val lastLog = ConcurrentHashMap<String, Long>()
    private const val LOG_INTERVAL_MS = 30_000L

    /**
     * 注册时捕获的调用方身份（回调实例 → (uid, pkg)）。回调经 binder 异步交付时
     * 已不在应用的事务里（Binder.getCallingUid() 是 phone 进程自身），而
     * CallbackHooks 按 类#方法 去重、所有注册共享同一拦截器——身份必须按注册
     * 逐次记录，不能固化进闭包（否则后注册的应用沿用首个调用方的身份）。
     * LRU 有界防泄漏（应用可反复注册回调）。
     */
    private val callbackIdentity =
        object : LinkedHashMap<Any, Pair<Int, String>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, Pair<Int, String>>): Boolean =
                size > 32
        }

    fun install(module: XposedModule, cl: ClassLoader): Int {
        var n = 0
        val pim = try {
            cl.loadClass("com.android.internal.telephony.PhoneInterfaceManager")
        } catch (_: Throwable) {
            ProbeLog.log("FW-CELL class not found")
            return 0
        }
        CellInfoFactory.attach(cl)
        for (m in pim.declaredMethods) {
            val target = when {
                m.name == "getAllCellInfo" && m.parameterTypes.all { it == String::class.java } -> "all"
                m.name == "getCellLocation" && m.parameterTypes.all { it == String::class.java } -> "loc"
                m.name == "requestCellInfoUpdate" -> "reqcb"
                else -> null
            } ?: continue
            if (target == "reqcb") {
                hookPimCellInfoRequest(module, m)
                n++
                continue
            }
            hookPimMethod(module, m, target)
            n++
        }
        module.log(Log.INFO, "VEN11", "framework cell hooks installed=$n")
        return n
    }

    /**
     * PIM.requestCellInfoUpdate（覆盖域扩展 5a）：回调经 binder 回到应用进程
     * 交付。phone 进程内拿到的是应用侧 ICellInfoCallback 的 **Proxy**——
     * 经 CallbackHooks 钩 Proxy 具体类的交付方法，改写 List 参数后再由原方法
     * marshal（我们的 CellInfo 子类均可 Parcelable 序列化，mCellConnectionStatus/
     * mRegistered 齐备）。
     */
    private fun hookPimCellInfoRequest(module: XposedModule, m: Method) {
        module.hook(m).setId("ven11.fwcell.reqcb/${m.parameterTypes.size}")
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 调用身份必须在 proceed 前取：PIM 方法体内可能 clearCallingIdentity
                    val uid = Binder.getCallingUid()
                    val registered = chain.proceed()
                    // 回调是 AIDL binder 代理（实现 IInterface）。不能按"首个非 String"
                    // 挑：requestCellInfoUpdate(int subId, String pkg, String featureId,
                    // ICellInfoCallback cb, ...) 里第一个非 String 参数是 subId（Integer）
                    val cb = chain.args.firstOrNull { it is IInterface } ?: return registered
                    synchronized(callbackIdentity) {
                        callbackIdentity[cb] = uid to (UidResolver.pkgOfUid(uid) ?: "")
                    }
                    runCatching {
                        dev.ven11.module.hook.util.CallbackHooks.install(
                            module, "FW-CELL-CB", cb, null,
                            methodNames = setOf("onCellInfoChanged", "onCellInfo"),
                        ) { chain, _ ->
                            // 交付时按回调实例反查注册方身份；查不到（进程重启前旧回调）
                            // 回落当前 callingUid，再查不到 pkg 则透传
                            val ident = synchronized(callbackIdentity) {
                                callbackIdentity[chain.thisObject]
                            }
                            val dUid = ident?.first ?: Binder.getCallingUid()
                            val args = chain.args
                            val listIdx = args.indexOfFirst { it is List<*> }
                            if (listIdx < 0) return@install chain.proceed()
                            val real = args[listIdx] as List<*>
                            // 系统调用与脱敏视角判断沿用 PIM 语义：真实空表=无权限，透传
                            if (real.isEmpty()) return@install chain.proceed()
                            val pkg = ident?.second?.takeIf { it.isNotEmpty() }
                                ?: dev.ven11.module.hook.framework.UidResolver.pkgOfUid(dUid)
                                ?: return@install chain.proceed()
                            val eff = PolicyResolver.resolve(pkg, dUid)
                            if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return@install chain.proceed()
                            val env = eff.environment ?: return@install chain.proceed()
                            val out = CellInfoFactory.build(env.cells)
                            if (out.isEmpty()) return@install chain.proceed()
                            // API 102 的 chain.args 不可变：复制数组后改写再 proceed
                            val newArgs = args.toTypedArray()
                            newArgs[listIdx] = out
                            chain.proceed(newArgs)
                        }
                    }.onFailure { ProbeLog.log("FW-CELL-CB-FAIL $it") }
                    return registered
                }
            })
    }

    private fun hookPimMethod(module: XposedModule, m: Method, target: String) {
        module.hook(m).setId("ven11.fwcell.$target/${m.parameterTypes.size}")
            .intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 调用身份在 proceed 之前取，不依赖被 hook 方法是否正确恢复身份
                    val uid = Binder.getCallingUid()
                    // PIM 的权限校验（含 callingPackage↔uid 一致性）在 proceed 内先跑（审查 10）
                    val real = chain.proceed()
                    // chain.args 返回 List（API 102），决策侧统一转数组
                    val args: Array<Any?> = try {
                        chain.args.toTypedArray()
                    } catch (_: Throwable) {
                        emptyArray()
                    }
                    // 决策整体兜底（审查 11），异常一律回退真实值
                    return runCatching { decide(m, target, real, uid, args) }.getOrElse { real }
                }
            })
    }

    private fun decide(m: Method, target: String, real: Any?, uid: Int, args: Array<Any?>): Any? {
        // 审查 7：多用户安全——按 appId 判系统调用
        // （UserHandle.getAppId 是隐藏 API，appId = uid % PER_USER_RANGE）
        if (uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return real
        // 审查 4：优先用参数里的 callingPackage；仅无参签名（≤P）回落 uid 反查
        val pkg = (args.firstOrNull { it is String } as? String)?.takeIf { it.isNotBlank() }
            ?: UidResolver.pkgOfUid(uid)
            ?: return real
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return real
        val env = eff.environment ?: return real
        val cells = env.cells
        if (cells.isEmpty()) return real
        // 审查 10：无权限/定位关时 PIM 本就返回 null/空表，保持一致，不替换
        if (isEmptyResult(real)) return real
        return when (target) {
            "all" -> spoofAll(m, real, cells)
            "loc" -> spoofLoc(m, real, cells)
            else -> real
        }
    }

    private fun isEmptyResult(real: Any?): Boolean = when (real) {
        null -> true
        is List<*> -> real.isEmpty()
        is Bundle -> real.isEmpty
        else -> false
    }

    private fun spoofAll(m: Method, real: Any?, cells: List<VirtualCell>): Any? {
        val out = CellInfoFactory.build(cells)
        if (out.isEmpty()) return real
        throttleLog("all", "FW-CELL-SPOOF pkg-resolved n=${out.size}")
        // 返回类型以被 hook 方法声明为准（jarjar 改名的 ParceledListSlice 也能对上），
        // 构造失败回退真实值，绝不返回裸 list 让 Stub 抛 ClassCastException
        val rt = m.returnType
        if (List::class.java.isAssignableFrom(rt)) return out
        return try {
            rt.getDeclaredConstructor(List::class.java)
                .apply { isAccessible = true }
                .newInstance(out)
        } catch (_: Throwable) {
            real
        }
    }

    private fun spoofLoc(m: Method, real: Any?, cells: List<VirtualCell>): Any? {
        // 审查 8：服务小区优先
        val serving = CellInfoFactory.pickServing(cells) ?: return real
        val rt = m.returnType
        return when {
            // Android 11+：声明返回 CellIdentity（审查 4）
            CellIdentity::class.java.isAssignableFrom(rt) -> {
                val id = CellInfoFactory.buildIdentity(serving)
                if (id != null && rt.isAssignableFrom(id.javaClass)) id else real
            }
            // Android 10：Bundle 形态。binder 传输的 Bundle 里是 GsmCellLocation
            // fillInNotifierBundle 写入的 int（"lac"/"cid"/"psc"），不是 CellLocation
            // 对象——两种形态都处理：含对象键则替换对象，否则按已有 int 键回填
            Bundle::class.java.isAssignableFrom(rt) -> {
                val b = real as? Bundle ?: return real
                val objKey = b.keySet().firstOrNull { b.get(it) is CellLocation }
                val loc = CellInfoFactory.asGsmCellLocation(serving)
                if (objKey != null) {
                    // SDK 33+ 的 CellLocation stub 不再声明 Parcelable，运行时仍实现——
                    // 运行时安全转换后走公开 putParcelable；转换失败放行真实值
                    val p = loc as? android.os.Parcelable ?: return real
                    b.putParcelable(objKey, p)
                    throttleLog("loc", "FW-CELL-SPOOF bundle-obj")
                    return b
                }
                val gsm = loc as? GsmCellLocation ?: return real
                var touched = false
                if (b.containsKey("lac")) { b.putInt("lac", gsm.lac); touched = true }
                if (b.containsKey("cid")) { b.putInt("cid", gsm.cid); touched = true }
                if (b.containsKey("psc")) { b.putInt("psc", gsm.psc); touched = true }
                if (touched) throttleLog("loc", "FW-CELL-SPOOF bundle-int lac/cid/psc")
                b
            }
            // 更老版本直接返回 CellLocation
            CellLocation::class.java.isAssignableFrom(rt) -> CellInfoFactory.asGsmCellLocation(serving)
            else -> real
        }
    }

    private fun throttleLog(key: String, msg: String) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastLog.put(key, now) ?: 0L
        if (now - prev >= LOG_INTERVAL_MS) ProbeLog.log(msg)
    }
}