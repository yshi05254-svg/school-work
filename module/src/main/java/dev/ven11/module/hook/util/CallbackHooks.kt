package dev.ven11.module.hook.util

import dev.ven11.module.ProbeLog
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 注册型回调的"交付边界"改写基建（覆盖域扩展共用）。
 *
 * 适用形态：应用调用 XXX.registerCallback(cb) 注册回调，交付数据在 cb 的具体类
 * 方法上流过。不在注册时替换回调对象（抽象基类/匿名子类无法代理），而是：
 *  1. 钩注册方法拿到 cb 实例；
 *  2. 在 **cb 具体类**（含其内部 stub 字段类，如 ITelephonyCallback$Stub 匿名实现）
 *     上按方法名定位交付方法并装钩——虚拟派发天然命中应用自己的覆写；
 *  3. 应用未覆写（交付走基类实现）时回退钩基类方法（进程级一次）。
 * 去重：同一 类#方法 只钩一次（同框架类的所有实例共享钩子，改写在拦截器内按
 * 调用方策略逐次决策，与各域 hook 的行为模型一致）。
 *
 * 注意：Android 的框架内部 stub（ITelephonyCallback$Stub 等）不在公开 SDK 里，
 * 只能经 cb 实例的字段反射发现，typeHint 按"类型名包含"匹配。
 */
object CallbackHooks {

    private val hooked = ConcurrentHashMap<String, Unit>()

    /**
     * @param cb            注册方法参数里的回调实例
     * @param stubHint      可选：cb 内部 binder stub 字段的类型名提示（如 "ITelephonyCallback"）
     * @param methodNames   要改写的交付方法名集合
     * @param baseFallback  可选：cb 未覆写交付方法时兜底钩的基类（进程级）
     * @param interceptor   (chain, methodName) → 返回值；改写用 chain.proceed(newArgs)，
     *                      抑制交付直接返回 null（不 proceed）
     * @return 本次实际新装的钩子数
     */
    fun install(
        module: XposedModule,
        tag: String,
        cb: Any,
        stubHint: String?,
        methodNames: Set<String>,
        baseFallback: Class<*>? = null,
        interceptor: (chain: XposedInterface.Chain, methodName: String) -> Any?,
    ): Int {
        val targets = LinkedHashMap<String, Method>()
        val candidates = ArrayList<Class<*>>()
        candidates.add(cb.javaClass)
        stubHint?.let { hint -> findInnerByTypeName(cb, hint)?.let { candidates.add(it.javaClass) } }
        for (cls in candidates) {
            for (m in cls.declaredMethods) {
                if (m.name in methodNames) {
                    targets.putIfAbsent("${cls.name}#${m.name}#${m.parameterTypes.size}", m)
                }
            }
        }
        if (targets.isEmpty() && baseFallback != null) {
            for (m in baseFallback.declaredMethods) {
                if (m.name in methodNames) {
                    targets.putIfAbsent("${baseFallback.name}#${m.name}#${m.parameterTypes.size}", m)
                }
            }
        }
        if (targets.isEmpty()) {
            ProbeLog.log("$tag: no delivery methods found on ${cb.javaClass.name}")
            return 0
        }
        var n = 0
        for ((key, m) in targets) {
            if (hooked.putIfAbsent(key, Unit) != null) continue
            runCatching {
                module.hook(m).setId("$tag.${m.name}/${m.parameterTypes.size}")
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? =
                            interceptor(chain, m.name)
                    })
            }.onSuccess { n++ }
                .onFailure { ProbeLog.log("$tag-HOOK-FAIL ${m.name}: $it") }
        }
        return n
    }

    /** 沿字段表（含父类）找类型名含 [typeNameContains] 的第一个非空字段值 */
    private fun findInnerByTypeName(cb: Any, typeNameContains: String): Any? {
        var c: Class<*>? = cb.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                val v = runCatching {
                    f.isAccessible = true
                    f.get(cb)
                }.getOrNull() ?: continue
                if (f.type.name.contains(typeNameContains) || v.javaClass.name.contains(typeNameContains)) {
                    return v
                }
            }
            c = c.superclass
        }
        return null
    }
}
