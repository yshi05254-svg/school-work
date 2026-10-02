package dev.ven11.module.hook.regional

import android.system.Os
import android.util.Log
import dev.ven11.module.ipc.PolicyResolver
import io.github.libxposed.api.XposedModule
import java.util.TimeZone

/**
 * 时区域钩（策略字段 timezoneId 驱动，如 "America/New_York"）。
 *
 * 方案：只伪造"系统来源"，让 libcore 自行重建默认时区，不再钩任何 getter
 * （TimeZone.getDefault / ZoneId.systemDefault / ICU getDefault 全部撤除），由此同时解决：
 *  - getDefaultRef() 旁路：libcore 内部路径（new GregorianCalendar()、
 *    Date.toString()、Date.getHours() 等）读的是 defaultTimeZone 静态字段，
 *    本方案直接重建该字段，四种 getter 结果天然一致；
 *  - 广播翻转：ACTION_TIMEZONE_CHANGED → ActivityThread 调 setDefault(null)
 *    → 重建读 persist.sys.timezone（已被钩）→ 仍是伪装值，运行期状态稳定；
 *  - 应用自己的 setDefault(UTC) 语义保留：只伪造系统来源，不拦 getDefault；
 *  - ZoneId.systemDefault 内联风险：钩已不存在；
 *  - 热路径开销：无每次 getTimeZone（synchronized）与反射调用。
 *
 * 落地步骤（顺序敏感）：
 *  1. RegionalSystemPropertiesHook 注册 persist.sys.timezone → tzId，必须先装，
 *     因为后续重建路径经它读属性；
 *  2. TimeZone.setDefault(null)：libcore 读属性重建 defaultTimeZone 并顺带同步
 *     ICU 默认时区；重建结果做运行时校验，老版本 libcore 若回退 zygote 缓存的
 *     真实时区，则显式 setDefault 兜底（显式路径同样会同步 ICU）；
 *  3. Os.setenv("TZ", tzId, true)：bionic 的 localtime 优先读 TZ 环境变量，
 *     覆盖 NDK/Unity/Flutter 等 native 直读属性的路径（Java 钩子拦不到
 *     __system_property_get）；应尽早安装，赶在应用首次 native 时间调用之前；
 *  4. System.setProperty("user.timezone", tzId)：兼容直接读该属性的第三方库。
 *
 * ID 校验：必须命中 TimeZone.getAvailableIDs()。ZoneId.of() 接受的 "+08:00"、
 * "UTC+8"、"Z" 等写法会导致 getTimeZone 静默回退 GMT / ICU 返回 Etc/Unknown /
 * 属性原样返回非法 ID，三处结果不一致，install 时直接拒绝。
 *
 * install() 幂等可重入：SP 钩子进程级只装一次，重复调用（快照热更新）仅更新
 * 注册表并重建默认时区；策略清空调 revert() 撤销伪装、恢复真实时区。
 *
 * 未覆盖（需单独实测，不能假设已覆盖）：
 *  - WebView 渲染进程是 isolated 进程，不加载本模块，JS 侧
 *    Intl.DateTimeFormat().resolvedOptions().timeZone / getTimezoneOffset()
 *    取决于 Chromium 的时区下发机制；
 *  - setDefault(null) 读属性并同步 ICU 属主流版本的 libcore 实现细节，
 *    建议在目标 API 范围内各实测一次（第 2 步已带运行时校验 + 显式兜底，
 *    via=property / via=explicit 可区分实际命中路径）。
 */
class ClientTimezoneHooks(private val module: XposedModule) {

    fun install(cl: ClassLoader, pkg: String): Int {
        val uid = android.os.Process.myUid()
        val policy = PolicyResolver.resolve(pkg, uid)
        val tzRaw = policy.policy?.timezoneId?.trim().orEmpty()
        if (tzRaw.isEmpty()) {
            dev.ven11.module.ProbeLog.log("TZ-HOOKS n=0 pkg=$pkg (无时区策略)")
            return 0
        }
        // 严格校验 Olson ID，非法值拒绝安装而不是静默变成 GMT
        if (tzRaw !in TimeZone.getAvailableIDs()) {
            module.log(Log.WARN, "VEN11", "TZ-HOOKS 非法时区 '$tzRaw' pkg=$pkg，跳过")
            dev.ven11.module.ProbeLog.log(
                "TZ-HOOKS n=0 pkg=$pkg 非法时区 '$tzRaw'（非 Olson ID），跳过")
            return 0
        }

        var n = 0
        var via = ""

        // 1) SP 钩子先装：后续 setDefault(null) 的重建路径经它读属性
        RegionalSystemPropertiesHook.register(
            RegionalSystemPropertiesHook.KEY_TIMEZONE, tzRaw)
        val spN = RegionalSystemPropertiesHook.install(module, cl)

        // 2) 重建 defaultTimeZone（getDefaultRef 读的同一静态字段）并同步 ICU 缓存
        var rebuilt = false
        runCatching {
            TimeZone.setDefault(null)
            rebuilt = TimeZone.getDefault().id == tzRaw
        }.onFailure {
            module.log(Log.WARN, "VEN11",
                "TZ-HOOKS setDefault(null) 失败 pkg=$pkg: ${android.util.Log.getStackTraceString(it)}")
        }
        if (rebuilt) {
            via = "property"; n++
        } else {
            // 兜底：老版本 libcore 的 setDefault(null) 回退 zygote 缓存值而非读属性
            runCatching { TimeZone.setDefault(TimeZone.getTimeZone(tzRaw)) }
                .onSuccess { via = "explicit"; n++ }
                .onFailure {
                    module.log(Log.WARN, "VEN11",
                        "TZ-HOOKS 显式 setDefault 失败 pkg=$pkg: ${android.util.Log.getStackTraceString(it)}")
                }
        }

        // 3) bionic localtime：TZ 环境变量优先于属性直读
        runCatching { Os.setenv("TZ", tzRaw, true) }
            .onSuccess { n++ }
            .onFailure {
                module.log(Log.WARN, "VEN11",
                    "TZ-HOOKS setenv(TZ) 失败 pkg=$pkg: ${android.util.Log.getStackTraceString(it)}")
            }

        // 4) 兼容直接读 user.timezone 的库
        runCatching { System.setProperty("user.timezone", tzRaw) }.onSuccess { n++ }

        val ok = TimeZone.getDefault().id == tzRaw
        module.log(Log.INFO, "VEN11",
            "timezone applied pkg=$pkg tz=$tzRaw via=$via sp=$spN steps=$n defaultOk=$ok")
        dev.ven11.module.ProbeLog.log(
            "TZ-HOOKS n=$n pkg=$pkg tz=$tzRaw via=$via sp=$spN default=${TimeZone.getDefault().id}")
        return n
    }

    /** 热更新/策略清空：撤销时区伪装并恢复真实时区（语言域注册的 key 不受影响） */
    fun revert(cl: ClassLoader, pkg: String) {
        RegionalSystemPropertiesHook.unregister(RegionalSystemPropertiesHook.KEY_TIMEZONE)
        runCatching { TimeZone.setDefault(null) }   // 重建回真实 persist.sys.timezone
        runCatching { Os.unsetenv("TZ") }
        runCatching { System.clearProperty("user.timezone") }
        module.log(Log.INFO, "VEN11",
            "timezone reverted pkg=$pkg → ${TimeZone.getDefault().id}")
        dev.ven11.module.ProbeLog.log(
            "TZ-HOOKS reverted pkg=$pkg → ${TimeZone.getDefault().id}")
    }
}