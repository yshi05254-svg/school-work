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
 *  2. 在 **回调继承链**（cb 具体类 → 各层基类，含 stubHint 指出的内部 stub 字段类）
 *     上按方法名定位交付方法，同名取最派生声明——中间层基类上的覆写也能命中；
 *     ART 虚分发下钩基类拦不到子类覆写，故同名只钩最派生一层，避免双重改写；
 *  3. 继承链上都没有时回退钩 baseFallback 框架基类方法（进程级一次）。
 * 去重：同一 声明类#方法 只钩一次（同框架类的所有实例共享钩子，改写在拦截器内
 * 按调用方策略逐次决策，与各域 hook 的行为模型一致）。
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
        // 沿继承链收集（审查六）：应用的覆写可能声明在自己的中间层基类上，只扫
        // cb 具体类会漏。同名方法按"类#方法#参数数"取最先遇到的最派生声明——
        // ART 虚分发下钩基类拦不到子类覆写，同时钩两层会对同一次交付双重改写。
        var c: Class<*>? = cb.javaClass
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (m.name in methodNames) {
                    targets.putIfAbsent("${m.name}#${m.parameterTypes.size}", m)
                }
            }
            c = c.superclass
        }
        stubHint?.let { hint -> findInnerByTypeName(cb, hint)?.let { inner ->
            for (m in inner.javaClass.declaredMethods) {
                if (m.name in methodNames) {
                    targets.putIfAbsent("${m.name}#${m.parameterTypes.size}", m)
                }
            }
        } }
        if (targets.isEmpty() && baseFallback != null) {
            for (m in baseFallback.declaredMethods) {
                if (m.name in methodNames) {
                    targets.putIfAbsent("${m.name}#${m.parameterTypes.size}", m)
                }
            }
        }
        if (targets.isEmpty()) {
            ProbeLog.log("$tag: no delivery methods found on ${cb.javaClass.name}")
            return 0
        }
        var n = 0
        for ((_, m) in targets) {
            val key = "${m.declaringClass.name}#${m.name}#${m.parameterTypes.size}"
            // 进程级去重按"声明类#方法#参数数"：同框架类的所有实例共享钩子，
            // 改写在拦截器内按调用方策略逐次决策。
            // 只有装钩成功才标记完成（审查八 #3）：失败移除标记，下次注册重试——
            // 否则一次 ROM 抖动会让该交付点永久失效
            if (hooked.putIfAbsent(key, Unit) != null) continue
            runCatching {
                module.hook(m).setId("$tag.${m.name}/${m.parameterTypes.size}")
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? =
                            interceptor(chain, m.name)
                    })
            }.onSuccess { n++ }
                .onFailure {
                    hooked.remove(key)
                    ProbeLog.log("$tag-HOOK-FAIL ${m.name}: $it")
                }
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
