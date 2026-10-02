package dev.ven11.module.hook.regional

import android.content.res.Configuration
import android.os.LocaleList
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.ipc.PolicyResolver
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语言域钩（策略字段 languageTag 驱动，如 "en-US"），目标应用进程。
 *
 * 方案：源头改写 + 写回拦截（不钩 getter）。
 *  - Locale.getDefault 这类 getter 只是读静态字段，boot image 中 AOT 编译的调用方
 *    （String.format/toLowerCase、NumberFormat、DateFormat...）可能内联直读，钩 getter 会被绕过；
 *  - 因此装钩时直接 Locale.setDefault / LocaleList.setDefault 改写进程默认值
 *    （DISPLAY/FORMAT 一并覆盖；ICU ULocale 默认值由 libcore 在 setDefault 内同步；
 *    LocaleList.getAdjustedDefault 是默认列表的派生缓存，随之更新），
 *    再拦截 Locale/LocaleList/ULocale 的 setDefault，把 ActivityThread 配置变更的写回
 *    归一化为目标值（参数替换后 proceed，保留 libcore 的 ICU 同步/缓存刷新副作用）；
 *  - 资源选择（values-xx、WebView navigator.language / Accept-Language）走
 *    ResourcesImpl.updateConfiguration → AssetManager.setConfiguration，
 *    在 proceed 前改写传入 Configuration 的 locales（setLocales 同时刷新 layoutDirection）；
 *  - SystemProperties（persist.sys.* / ro.product.locale*）的值一律取自解析后的 Locale，
 *    不对原始 tag 切字符串（zh-Hant-TW、-u- 扩展不会被切错）；
 *  - System user.language / user.region 在 apply 时同步 setProperty。
 *
 * 匹配语义（VEN 0.3.0-rc2 教训）：仅 pkg+userId 精确策略；无策略不装钩。
 * 契约前提：
 *  1) PolicyResolver 的 resolve 结果需暴露 exact（精确命中 "pkg/userId" = true；
 *     回落全局激活环境 = false）——语言域只认 exact 命中，全局环境的 languageTag 不生效；
 *  2) domainEnabled("language") 与 excludedUids/excludedPackages 拦截在 resolve 内部完成
 *     （见基础设施说明）。若未完成，语言域将成为绕过开关/排除名单的唯一入口。
 *
 * 作用域与生命周期：
 *  - Locale/LocaleList/SystemProperties 均为 boot 类，钩子进程级（参数 cl 仅为兼容签名）：
 *    同进程多包（sharedUserId/android:process）只装一次，以首个精确命中的包为准，
 *    被跳过的包记日志；
 *  - 热更新：装钩后策略修改/删除 ≤1s 生效（拦截器内节流重读快照，对齐 SnapshotStore 轮询）；
 *    删除后恢复安装前捕获的真实值；新增策略需重启进程（“无策略不装钩”的代价，有意为之）。
 *
 * 已知局限（Java 钩子无法覆盖，文档化）：native 层 __system_property_get 与 ICU4C 默认
 * locale（NDK / Unity 等引擎直读）不受影响；进程启动极早期已应用过的真实配置不重放，
 * 首个 Activity 的资源配置应用后即以目标 locale 生效。
 *
 * Chain 契约：参数替换统一走 chain.proceed(newArgs)（评审二：libxposed 的 Chain 没有
 * setArg，假定其存在会直接编译不过）。
 */
class ClientLanguageHooks(private val module: XposedModule) {

    fun install(cl: ClassLoader, pkg: String): Int {
        // boot 类钩子是进程级的：同进程第二个包直接跳过并记录（参数 cl 对 boot 类无实际作用）
        if (!INSTALLED.compareAndSet(false, true)) {
            ProbeLog.log("LANG-HOOKS skip pkg=$pkg (进程已由 owner=${ownerPkg} 装钩，进程级全局开关/排除名单不可按包区分)")
            return 0
        }
        val uid = Process.myUid()

        val resolved = PolicyResolver.resolve(pkg, uid)
        // 评审四：总开关/排除名单/策略级域开关统一经 domainEnabled 判定，不再假设
        // "resolve 内部完成"——Language 域此前是绕过总开关的唯一入口
        if (!resolved.domainEnabled(PolicyResolver.Domain.LANGUAGE)) {
            INSTALLED.set(false)
            ProbeLog.log("LANG-HOOKS n=0 pkg=$pkg uid=$uid (域未启用/总开关关闭/在排除名单)")
            return 0
        }
        val tag = if (resolved.exact) resolved.policy?.languageTag?.trim().orEmpty() else ""
        val locale = parseLocale(tag)
        if (locale == null) {
            // 释放守卫，让同进程后续包仍有机会按自己的策略装钩
            INSTALLED.set(false)
            ProbeLog.log("LANG-HOOKS n=0 pkg=$pkg uid=$uid (无精确语言策略或 languageTag 非法: \"$tag\"，不装钩)")
            return 0
        }

        ownerPkg = pkg
        ownerUid = uid
        prepareULocale(cl)
        captureRealState()
        applyOverride(locale)

        var n = 0
        n += hookLocaleSetters()
        n += hookLocaleListSetter()
        n += hookULocaleSetters()
        n += hookResourcesConfig(cl)
        n += hookSystemProperties(cl)

        module.log(Log.INFO, "VEN11", "language hooks installed=$n pkg=$pkg lang=${locale.toLanguageTag()}")
        ProbeLog.log("LANG-HOOKS n=$n pkg=$pkg lang=${locale.toLanguageTag()}")
        return n
    }

    // ------------------------------------------------------------------ hooks

    /** Locale.setDefault(Locale) / setDefault(Category, Locale)：框架写回归一化。 */
    private fun hookLocaleSetters(): Int {
        var n = 0
        n += hookSetter("ven11.lang.locale.set1", 0, { overrideLocale }) {
            Locale::class.java.getDeclaredMethod("setDefault", Locale::class.java)
        }
        n += hookSetter("ven11.lang.locale.set2", 1, { overrideLocale }) {
            Locale::class.java.getDeclaredMethod(
                "setDefault", Locale.Category::class.java, Locale::class.java
            )
        }
        return n
    }

    /** LocaleList.setDefault(LocaleList)：配置变更写回拦截（getAdjustedDefault 的派生源）。 */
    private fun hookLocaleListSetter(): Int =
        hookSetter("ven11.lang.localelist.set", 0, { overrideList }) {
            LocaleList::class.java.getDeclaredMethod("setDefault", LocaleList::class.java)
        }

    /**
     * ULocale.setDefault(ULocale) / setDefault(Category, ULocale)：直写 ICU 会打破
     * java.util.Locale ↔ ICU 的同步，一并拦截。不钩 ULocale.getDefault —— setDefault
     * 方案下 libcore 会随 java.util.Locale 默认值同步它，getter 钩在 boot image 内联
     * 面前本就不可靠。ULocale(String) 收的是 ICU locale id 而非 BCP 47 tag，构造一律走 forLocale。
     */
    private fun hookULocaleSetters(): Int {
        val uc = ulocaleClass ?: return 0
        var n = 0
        for (m in uc.declaredMethods) {
            if (m.name != "setDefault") continue
            val ps = m.parameterTypes
            val idx = when {
                ps.size == 1 && ps[0] == uc -> 0
                ps.size == 2 && ps[1] == uc -> 1
                else -> continue
            }
            n += hookSetter("ven11.lang.ulocale.set${ps.size}", idx, { overrideULocale }) { m }
        }
        return n
    }

    /** ResourcesImpl.updateConfiguration：资源选择入口，proceed 前改写传入 Configuration。 */
    private fun hookResourcesConfig(cl: ClassLoader): Int {
        val riClass = try {
            cl.loadClass("android.content.res.ResourcesImpl")
        } catch (e: Throwable) {
            ProbeLog.log("LANG-HOOK-FAIL resconf.class: $e")
            return 0
        }
        var n = 0
        for (m in riClass.declaredMethods) {
            if (m.name != "updateConfiguration") continue
            val ps = m.parameterTypes
            if (ps.isEmpty() || ps[0] != Configuration::class.java) continue
            val id = "ven11.lang.resconf.uc${ps.size}"
            val ok = tryHook(id) {
                module.hook(m).setId(id).intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? {
                        if (restoring) return chain.proceed()
                        currentLocale() // 冷路径，顺带驱动热更新
                        val list = overrideList ?: return chain.proceed()
                        val cfg = chain.getArg(0) as? Configuration ?: return chain.proceed()
                        try {
                            cfg.setLocales(list) // proceed 内 AssetManager.setConfiguration 随之拿到目标 locale
                        } catch (e: Throwable) {
                            ProbeLog.log("LANG-HOOK-FAIL $id.setLocales: $e")
                        }
                        return chain.proceed()
                    }
                })
            }
            if (ok) n++
        }
        return n
    }

    /** SystemProperties.get(String) / get(String, String)：值取自解析后的 Locale。 */
    private fun hookSystemProperties(cl: ClassLoader): Int {
        // 评审四：不自装 get 拦截器（SIM/时区域各有重复实现，热路径多层拦截），
        // 向进程级单一钩子 [RegionalSystemPropertiesHook] 登记 resolver；
        // 值动态取自 overrideLocale，热更新/热移除自然生效，无需 register/unregister 簿记
        RegionalSystemPropertiesHook.registerResolver("language") { key ->
            val lc = currentLocale() ?: return@registerResolver null // 热移除后回归真实值
            when (key) {
                "persist.sys.locale", "ro.product.locale" -> lc.toLanguageTag()
                "persist.sys.language", "ro.product.locale.language" -> lc.language
                "persist.sys.country", "ro.product.locale.region" -> lc.country.ifEmpty { null }
                else -> null
            }
        }
        return RegionalSystemPropertiesHook.install(module, cl)
    }

    // ------------------------------------------------------------------ 状态与工具

    /**
     * 拦截 setter：把第 argIdx 个参数替换为当前目标后放行，保留框架副作用；
     * 恢复期（restoring）或无目标（策略已删）时放行原参。
     * 参数替换走 proceed(newArgs)——libxposed Chain 无 setArg（评审二）。
     */
    private fun hookSetter(id: String, argIdx: Int, value: () -> Any?, lookup: () -> Method): Int {
        val ok = tryHook(id) {
            val m = lookup()
            module.hook(m).setId(id).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    if (restoring) return chain.proceed()
                    currentLocale() // setter 均为冷路径，顺带驱动热更新
                    val v = value() ?: return chain.proceed()
                    val args = chain.args.toTypedArray() // API 102：getArgs 返回 List，proceed 收数组
                    args[argIdx] = v
                    return chain.proceed(args)
                }
            })
        }
        return if (ok) 1 else 0
    }

    /** forLanguageTag 对非法输入不抛异常只会给出空 language 的 Locale（如 "en_US" → und），必须校验。 */
    private fun parseLocale(tag: String): Locale? =
        Locale.forLanguageTag(tag.trim().replace('_', '-'))
            .takeIf { it.language.isNotEmpty() }

    /** 装钩前捕获真实值，供策略热移除时恢复。 */
    private fun captureRealState() {
        realDefault = Locale.getDefault()
        realDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        realFormat = Locale.getDefault(Locale.Category.FORMAT)
        realList = LocaleList.getDefault()
        realUserLanguage = System.getProperty("user.language")
        realUserRegion = System.getProperty("user.region")
    }

    /** 源头改写：一次到位地覆盖 Locale(DISPLAY/FORMAT)、LocaleList、ICU、VM user.*。 */
    private fun applyOverride(l: Locale) {
        val list = LocaleList(l) // 公开 API，直接构造并缓存，热路径零反射零分配
        overrideLocale = l
        overrideList = list
        overrideULocale = makeULocale(l)
        Locale.setDefault(l)          // 触发 libcore 同步 ICU 默认值
        LocaleList.setDefault(list)   // Locale 默认值对齐列表首项，getAdjustedDefault 随之派生
        trySetProp("user.language", l.language)
        trySetProp("user.region", l.country.ifEmpty { null })
    }

    /** 策略热移除：恢复真实值（setter 钩子在 restoring 期间放行原参）。 */
    private fun restoreRealState() {
        restoring = true
        try {
            realDisplay?.let { Locale.setDefault(Locale.Category.DISPLAY, it) }
            realFormat?.let { Locale.setDefault(Locale.Category.FORMAT, it) }
            realDefault?.let { Locale.setDefault(it) }
            realList?.let { LocaleList.setDefault(it) }
            trySetProp("user.language", realUserLanguage)
            trySetProp("user.region", realUserRegion)
            overrideLocale = null
            overrideList = null
            overrideULocale = null
            ProbeLog.log("LANG-HOOKS 策略已删除，真实 locale 已恢复 pkg=$ownerPkg")
        } finally {
            restoring = false
        }
    }

    private fun trySetProp(key: String, v: String?) {
        try {
            if (v.isNullOrEmpty()) System.clearProperty(key) else System.setProperty(key, v)
        } catch (e: Throwable) {
            ProbeLog.log("LANG-HOOK-FAIL user.$key: $e")
        }
    }

    /**
     * 热路径入口：节流重读策略快照（≤1 次/秒，对齐 SnapshotStore 轮询），其余调用只付
     * 一次时钟读 + volatile 读的代价；refreshing/restoring 防重入（resolve 过程中再进
     * 任意被钩方法时直接返回当前值）。
     */
    private fun currentLocale(): Locale? {
        if (refreshing || restoring) return overrideLocale
        val now = SystemClock.elapsedRealtime()
        if (now - lastResolveAt < RESOLVE_INTERVAL_MS) return overrideLocale
        lastResolveAt = now
        refreshing = true
        return try {
            refresh()
        } catch (e: Throwable) {
            ProbeLog.log("LANG-HOOK-FAIL refresh: $e")
            overrideLocale
        } finally {
            refreshing = false
        }
    }

    private fun refresh(): Locale? {
        val pkg = ownerPkg ?: return null
        val resolved = PolicyResolver.resolve(pkg, ownerUid)
        // 域被关闭/进排除名单视同策略删除 → 恢复真实值（评审四：总开关统一生效）
        val tag = if (resolved.exact && resolved.domainEnabled(PolicyResolver.Domain.LANGUAGE)) {
            resolved.policy?.languageTag?.trim().orEmpty()
        } else ""
        val next = parseLocale(tag) // tag 为空 → 无 language → null
        if (next == overrideLocale) return overrideLocale
        if (next == null) restoreRealState() else applyOverride(next)
        return overrideLocale
    }

    private fun prepareULocale(cl: ClassLoader) {
        try {
            val uc = cl.loadClass("android.icu.util.ULocale")
            ulocaleForLocale = uc.getDeclaredMethod("forLocale", Locale::class.java).apply { isAccessible = true }
            ulocaleClass = uc
        } catch (e: Throwable) {
            ProbeLog.log("LANG-HOOK-FAIL ulocale.prepare: $e")
        }
    }

    private fun makeULocale(l: Locale): Any? = try {
        ulocaleForLocale?.invoke(null, l)
    } catch (e: Throwable) {
        ProbeLog.log("LANG-HOOK-FAIL ulocale.forLocale: $e")
        null
    }

    private inline fun tryHook(id: String, body: () -> Unit): Boolean = try {
        body()
        true
    } catch (e: Throwable) {
        ProbeLog.log("LANG-HOOK-FAIL $id: $e")
        false
    }

    companion object {
        private val INSTALLED = AtomicBoolean(false)
        private const val RESOLVE_INTERVAL_MS = 1_000L

        // ---- 进程级状态（boot 类钩子作用于整个进程，以首个精确命中的包为 owner）----
        @Volatile private var ownerPkg: String? = null
        @Volatile private var ownerUid: Int = 0

        // 当前模拟目标（null = 未模拟，热移除后各拦截器放行真实值）
        @Volatile private var overrideLocale: Locale? = null
        @Volatile private var overrideList: LocaleList? = null
        @Volatile private var overrideULocale: Any? = null

        // 安装前捕获的真实值
        @Volatile private var realDefault: Locale? = null
        @Volatile private var realDisplay: Locale? = null
        @Volatile private var realFormat: Locale? = null
        @Volatile private var realList: LocaleList? = null
        @Volatile private var realUserLanguage: String? = null
        @Volatile private var realUserRegion: String? = null

        @Volatile private var restoring = false
        @Volatile private var refreshing = false
        @Volatile private var lastResolveAt = 0L

        // android.icu.util.ULocale 反射句柄（ICU locale id ≠ BCP 47 tag，必须走 forLocale）
        @Volatile private var ulocaleClass: Class<*>? = null
        @Volatile private var ulocaleForLocale: Method? = null
    }
}