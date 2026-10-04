package dev.ven11.module.hook.framework.location

import java.util.function.Function

/**
 * GNSS 分发过滤器（方案B ④）：包装 ListenerMultiplexer.deliverToListeners(Function)
 * 的参数 Function，按注册身份决定是否抑制（返回 null 跳过该注册——分发点语义，
 * 防泄漏报告 §7.3 核实）。
 *
 * 只对四个 GNSS provider 的分发做处理（其余调用含定位主分发一律放行）——按
 * `this` 类名前缀判定；ColorOS 的 GnssNmeaProviderWrapper 最终经
 * GnssNmeaProvider.access$000 转入 GnssNmeaProvider 实例的分发，前缀命中。
 *
 * 注册身份取自 GnssListenerRegistration.getIdentity()（反射，不挂钩）。
 * 过滤判定任何异常 → 不抑制（调用原 Function），与"出错透传"一致。
 */
object GnssDeliveryFilter {

    private val GNSS_OWNER_PREFIXES = listOf(
        "com.android.server.location.gnss.GnssStatusProvider",
        "com.android.server.location.gnss.GnssNmeaProvider",
        "com.android.server.location.gnss.GnssMeasurementsProvider",
        "com.android.server.location.gnss.GnssNavigationMessageProvider",
    )

    /** 分发点 this 是否为四个 GNSS provider 之一（前缀匹配覆盖其内部子类） */
    fun isGnssOwner(owner: Any?): Boolean {
        val name = owner?.javaClass?.name ?: return false
        return GNSS_OWNER_PREFIXES.any { name.startsWith(it) }
    }

    /** 该注册是否属于抑制目标（LOCATION 域启用且（有环境或严格模式）） */
    fun shouldSuppressRegistration(registration: Any?): Boolean {
        if (registration == null) return false
        val identity = ServerReflect.registrationIdentity(registration) ?: return false
        val uid = ServerReflect.identityUid(identity)
        if (uid <= 0) return false
        val pkg = ServerReflect.identityPkg(identity)
        return ServerLocationDecider.suppressGnss(uid, pkg)
    }

    /**
     * 包装原 Function：属于抑制目标的注册返回 null（分发点跳过该注册），
     * 其余原样调用原 Function。包装自身异常时退回直接调用原 Function（不抑制）。
     */
    fun wrapFunction(original: Function<Any?, Any?>): Function<Any?, Any?> =
        Function { registration ->
            runCatching {
                if (shouldSuppressRegistration(registration)) null
                else original.apply(registration)
            }.getOrElse {
                runCatching { original.apply(registration) }.getOrNull()
            }
        }
}
