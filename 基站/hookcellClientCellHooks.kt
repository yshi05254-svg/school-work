package dev.ven11.module.hook.cell

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.ipc.PolicyResolver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 客户端基站钩（目标应用进程）：
 *  - TelephonyManager.getAllCellInfo()：虚拟小区列表（构造收敛在 CellInfoFactory）
 *  - TelephonyManager.getCellLocation()：按制式构造 GsmCellLocation（NR 返回空对象，对齐 AOSP）
 *
 * 语义（审查 9/10，与 WiFi 域统一）：
 *  - proceed() 先行：SecurityException 原样传回；真实结果为 null/空（无
 *    ACCESS_FINE_LOCATION 或定位关闭，系统已脱敏）时保持一致，不注入虚拟数据
 *  - 兼容 = 透传真实结果：策略禁用 / 无环境 / 无小区 / 构造失败一律回落 real
 *
 * 语义边界（不模拟，审查三）：requestCellInfoUpdate、LISTEN_CELL_INFO /
 * TelephonyCallback.CellInfoListener、ServiceState、getSignalStrength、
 * getNetworkOperator 均为未覆盖的真实出口；双卡未区分 subId。被测 App
 * 交叉比对可发现矛盾，需后续版本补齐。
 *
 * 隐藏 API：CellIdentity 私有字段写入在应用进程受灰名单约束，是否放行取决于
 * 宿主（LSPosed 等）的豁免机制，需实机验证（审查四）。
 */
@Suppress("DEPRECATION")
class ClientCellHooks(private val module: XposedModule) {

    fun install(cl: ClassLoader, pkg: String): Int {
        var n = 0
        val uid = Process.myUid()
        val tmClass = try {
            cl.loadClass("android.telephony.TelephonyManager")
        } catch (_: Throwable) {
            null
        } ?: return 0
        CellInfoFactory.attach(cl)
        for (m in tmClass.declaredMethods) {
            // 客户端方法签名跨版本稳定，无参匹配即可（框架侧才需要放宽，见 FrameworkCellHooks）
            val target = when {
                m.name == "getAllCellInfo" && m.parameterTypes.isEmpty() -> "all"
                m.name == "getCellLocation" && m.parameterTypes.isEmpty() -> "loc"
                else -> null
            } ?: continue
            module.hook(m).setId("ven11.cell.$target/${m.parameterTypes.size}")
                .intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        // 权限/定位语义（审查 10）：proceed 抛出的 SecurityException 原样传回，
                        // 真实结果为空时保持系统脱敏行为
                        val real = chain.proceed()
                        return runCatching { decide(target, real, pkg, uid) }.getOrElse { real }
                    }
                })
            n++
        }
        module.log(Log.INFO, "VEN11", "client cell hooks installed=$n pkg=$pkg")
        ProbeLog.log("CELL-HOOKS n=$n pkg=$pkg")
        return n
    }

    private fun decide(target: String, real: Any?, pkg: String, uid: Int): Any? {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.CELL)) return real
        val env = eff.environment ?: return real
        val cells = env.cells
        if (cells.isEmpty()) return real
        when (target) {
            "all" -> {
                val realList = real as? List<*> ?: return real
                if (realList.isEmpty()) return real // 无权限/定位关时系统本就返回空表
                val out = CellInfoFactory.build(cells)
                if (out.isEmpty()) return real
                hit("all", "cell-all n=${out.size}")
                return out
            }
            "loc" -> {
                if (real == null) return real
                // 审查 8：服务小区优先，按 radioType 分支构造（含 NR 空对象、WCDMA psc）
                val serving = CellInfoFactory.pickServing(cells) ?: return real
                hit("loc", "cell-loc rat=${serving.radioType}")
                return CellInfoFactory.asGsmCellLocation(serving)
            }
        }
        return real
    }

    private val lastHit = ConcurrentHashMap<String, Long>()

    /** 限流：同 key 30s 只记一次，防应用轮询刷爆 ProbeLog（审查四） */
    private fun hit(key: String, msg: String) {
        val now = SystemClock.elapsedRealtime()
        val prev = lastHit.put(key, now) ?: 0L
        if (now - prev >= HIT_INTERVAL_MS) ProbeLog.log("CELL-SPOOF $msg")
    }

    private companion object {
        const val HIT_INTERVAL_MS = 30_000L
    }
}