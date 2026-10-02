package dev.ven11.module.hook.regional

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap

/**
 * 语言域 + 时区域共用的 SystemProperties 读钩子（进程级单例）。
 *
 * 旧结构：两个域各自 hookSystemProperties() 各装一组 get(String)/get(String,String)
 * 拦截器，每次调用要穿两层拦截器，且两域钩子 ID 相互重复。
 * 新结构：进程内只装一组拦截器，各域向注册表登记 key → 伪装值（Map 查表）：
 *  - register()/unregister() 任意时机可调，热更新只改表、不重装钩子；
 *  - install() 幂等（@Synchronized + 计数门），进程级防重复安装；
 *  - 拦截器内只做一次 ConcurrentHashMap 查找，未命中即放行，热路径开销最小；
 *  - 钩子 ID 全局唯一：get(String) → ven11.regional.sp.get，
 *    get(String, String) → ven11.regional.sp.get.def。
 *
 * 只覆盖 android.os.SystemProperties 的两个读接口，命中伪装 key 时无视 def
 * 参数直接返回伪装值（伪装值恒非空，def 分支不会走到）。native 层经
 * __system_property_get 的直读（bionic localtime 等）不经过这里，
 * 由各域自行处理（时区域用 TZ 环境变量覆盖）。
 */
object RegionalSystemPropertiesHook {

    private const val TAG = "VEN11"

    /** 各域共用的属性 key，集中定义避免拼写漂移 */
    const val KEY_TIMEZONE = "persist.sys.timezone"
    const val KEY_LOCALE = "persist.sys.locale"
    const val KEY_LOCALEVAR = "persist.sys.localevar"

    private val overrides = ConcurrentHashMap<String, String>()

    /**
     * 动态解析器（评审四）：值需要随快照/进程状态刷新的域（SIM 的 gsm.sim.*、
     * 语言的 persist.sys.locale 家族）不再各自装一组 get 拦截器，向本注册表登记
     * key → 值 的解析函数。拦截器内先查静态表，再依序问解析器；解析器必须自带
     * key 前缀门（前缀不匹配立即返回 null），保证无关 key 的热路径开销可忽略。
     */
    private val resolvers = ConcurrentHashMap<String, (String) -> String?>()

    @Volatile
    private var hookCount = 0

    fun register(key: String, value: String) {
        overrides[key] = value
    }

    fun unregister(key: String) {
        overrides.remove(key)
    }

    fun overrideOf(key: String): String? = overrides[key]

    /** tag 用于卸载/去重；重复注册同 tag 覆盖旧解析器 */
    fun registerResolver(tag: String, resolver: (String) -> String?) {
        resolvers[tag] = resolver
    }

    fun unregisterResolver(tag: String) {
        resolvers.remove(tag)
    }

    /** 幂等：首次调用安装钩子，之后调用仅返回已装数量（进程级防重复安装） */
    @Synchronized
    fun install(module: XposedModule, cl: ClassLoader): Int {
        if (hookCount > 0) return hookCount
        var n = 0
        try {
            val spClass = cl.loadClass("android.os.SystemProperties")
            for (m in spClass.declaredMethods) {
                if (m.name != "get" || m.parameterTypes.firstOrNull() != String::class.java) continue
                val id = if (m.parameterTypes.size == 2) "ven11.regional.sp.get.def"
                         else "ven11.regional.sp.get"
                module.hook(m).setId(id).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        val key = chain.getArg(0) as? String ?: return chain.proceed()
                        overrides[key]?.let { return it }
                        for (r in resolvers.values) {
                            r(key)?.let { return it }
                        }
                        return chain.proceed()
                    }
                })
                n++
            }
            if (n == 0) {
                module.log(Log.WARN, TAG, "regional SP hook: 未找到 SystemProperties.get(String)")
            }
        } catch (t: Throwable) {
            // 不静默：安装失败要能在模块日志里定位
            module.log(Log.ERROR, TAG,
                "regional SP hook 安装失败: ${android.util.Log.getStackTraceString(t)}")
        }
        hookCount = n
        return hookCount
    }
}