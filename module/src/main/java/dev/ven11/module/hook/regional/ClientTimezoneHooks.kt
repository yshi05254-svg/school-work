package dev.ven11.module.hook.regional

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.ipc.SnapshotStore
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
 * 安装与热更新（评审二轮/三轮补充·高优先级）：SP 钩子无条件安装（幂等、轻量），
 * "是否伪装、伪装成什么"由 [refreshFromSnapshot] 重新判定——
 *  - 启动时已有有效策略 → 立即应用；
 *  - 运行中出现/变更策略 → 应用新值（register 属性 + 重建默认时区 + TZ 环境变量）；
 *    驱动源有二：快照版本变化回调（有其他钩子活动的进程，≤1s）+ 自驱轮询线程
 *    （静止进程也能感知，文件读取经 SnapshotStore 节流 ≤1 次/秒）；
 *  - masterEnabled 关闭 / 包或 UID 进排除名单 / 时区域禁用 / 策略删除 / timezoneId
 *    非法 → [revert] 恢复真实时区：还原装钩时捕获的默认时区 / TZ / user.timezone
 *    原值（不是清空——应用装钩前可能自设过这些值）。
 *    revert 不再是无调用点的死代码，它是刷新路径的常态分支。
 *
 * 状态一致性说明：Java TimeZone、ICU（由 setDefault 内部同步）、native（TZ 环境变量）
 * 三处的一致性依赖第 2 步的运行时校验；apply/revert 后 defaultOk 日志即三处一致性的
 * 探针，需真机各实测一次。
 *
 * 落地步骤（首次应用，顺序敏感）：
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
 * 并发语义（审查六 #5）：setenv 的风险是与**其它线程** native 代码的 getenv /
 * localtime 并发，换线程执行并不能消除；真正的缓解是"值不变时刷新是 no-op"——
 * setenv 只在伪装值真实切换时发生一次。apply/revert 一律在 refreshLock 内同步
 * 执行（不 post）：post 到主线程既不解决上述并发，还引入主线程阻塞时每秒重复
 * post、以及 apply/revert 不再按决策顺序串行的新问题。
 *
 * ID 校验：必须命中 TimeZone.getAvailableIDs()。ZoneId.of() 接受的 "+08:00"、
 * "UTC+8"、"Z" 等写法会导致 getTimeZone 静默回退 GMT / ICU 返回 Etc/Unknown /
 * 属性原样返回非法 ID，三处结果不一致，视为无有效策略（走 revert 分支）。
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

    companion object {
        private const val TAG = "VEN11"

        /** 单飞行门：刷新可能被轮询线程/装钩线程并发触发，全局状态变更须串行 */
        private val refreshLock = Any()

        private const val POLL_INTERVAL_MS = 1_000L

        /**
         * 进程在后台（无前台 Activity）时的轮询间隔。后台每秒一次 provider 查询
         * 纯耗电，还会反复唤醒模块进程；回到前台时 onActivityResumed 立即刷新，
         * 用户可见的界面不受影响。
         */
        private const val BACKGROUND_POLL_INTERVAL_MS = 10_000L

        /**
         * Olson ID 全集（进程内静态数据，装钩时取一次缓存）。此前每次刷新都调
         * TimeZone.getAvailableIDs()：分配 ~600 个字符串再线性查找，纯浪费。
         */
        private val AVAILABLE_IDS: Set<String> = java.util.HashSet<String>(TimeZone.getAvailableIDs().toList())
    }

    // 进程级单 owner（与语言域一致：时区是进程全局状态，不可按包区分）
    @Volatile
    private var ownerPkg: String? = null

    @Volatile
    private var ownerUid: Int = 0

    /** 当前已应用的伪装 ID；null = 未伪装（透传真实时区） */
    @Volatile
    private var appliedTz: String? = null

    // 装钩时捕获的真实状态（revert 还原用；装钩后不再更新——还原目标始终是"装钩前"）
    @Volatile
    private var realDefaultTz: String? = null

    @Volatile
    private var realTzEnv: String? = null

    @Volatile
    private var realUserTimezoneProp: String? = null

    private val pollerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 已恢复（resumed）的 Activity 数；-1 = 尚未注册生命周期回调（前后台未知，按前台处理） */
    private val resumedActivities = java.util.concurrent.atomic.AtomicInteger(-1)

    fun install(cl: ClassLoader, pkg: String): Int {
        ownerPkg = pkg
        ownerUid = Process.myUid()
        captureRealState()

        // 1) SP 钩子无条件安装：轻量（幂等），语言/时区共用同一组拦截器；
        //    无策略时注册表为空，所有查询原样放行
        val spN = RegionalSystemPropertiesHook.install(module, cl)

        // 2) 热更新双驱动（评审三轮 #5）：
        //    a. 快照版本变化回调——有其他域钩子活动（定位/WiFi/SP 查询等）的进程
        //       由轮询触发，延迟 ≤1s；
        //    b. 自驱轮询线程——应用长时间不触发任何被钩方法时也能感知策略变更/
        //       关闭（否则静止进程会永久停留在旧伪装值）。前台 1s、后台 10s，
        //       回到前台时立即刷新一次。
        SnapshotStore.registerListener("timezone") { _, _ -> refreshFromSnapshot() }
        startPoller()

        // 3) 启动时已有有效策略则立即应用；没有则保持透传
        //    （覆盖"装钩完成前快照刚好更新"的竞态窗口）
        refreshFromSnapshot()

        val tz = appliedTz ?: "-"
        module.log(Log.INFO, TAG, "timezone hooks installed pkg=$pkg tz=$tz sp=$spN")
        ProbeLog.log("TZ-HOOKS installed pkg=$pkg tz=$tz sp=$spN")
        return spN + if (appliedTz != null) 1 else 0
    }

    /** 首次伪装前捕获真实状态，revert 按原值还原而不是清空（评审三轮 #5） */
    private fun captureRealState() {
        realDefaultTz = TimeZone.getDefault().id
        realTzEnv = System.getenv("TZ")
        realUserTimezoneProp = System.getProperty("user.timezone")
    }

    /** 自驱轮询：进程存活期间持续感知配置变化；daemon 线程，随进程退出 */
    private fun startPoller() {
        if (pollerStarted.compareAndSet(false, true)) {
            Thread({
                while (true) {
                    if (resumedActivities.get() < 0) tryTrackForeground()
                    val interval = if (resumedActivities.get() == 0) {
                        BACKGROUND_POLL_INTERVAL_MS
                    } else {
                        POLL_INTERVAL_MS
                    }
                    try {
                        Thread.sleep(interval)
                    } catch (_: InterruptedException) {
                        // 进程退出场景，静默结束
                        return@Thread
                    }
                    runCatching { refreshFromSnapshot() }
                        .onFailure { ProbeLog.log("TZ-POLL-ERR ${it.javaClass.simpleName}: ${it.message}") }
                }
            }, "ven11-tz-poll").apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * 注册 Activity 生命周期回调以区分前后台。装钩时 Application 往往还没创建，
     * 所以由轮询线程反复尝试，拿到后只注册一次。
     */
    private fun tryTrackForeground() {
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Application
        }.getOrNull() ?: return
        // 注册前已在前台的 Activity 收不到 onResumed，用一次进程重要性查询补上初值
        val alreadyForeground = runCatching {
            ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }
                .importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(true)
        if (!resumedActivities.compareAndSet(-1, if (alreadyForeground) 1 else 0)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                // 从后台回来：立即刷新，不等后台长间隔（刷新只做内存比对 + 异步轮询，主线程开销极小）
                if (resumedActivities.getAndIncrement() == 0) {
                    runCatching { refreshFromSnapshot() }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                resumedActivities.updateAndGet { (it - 1).coerceAtLeast(0) }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * 快照版本变化入口（也在装钩时直接调用一次）：
     * 重新解析策略，与已应用值比对——不变则 no-op，有效新值走 apply，
     * 无有效值（含域禁用/排除名单/非法 ID）且当前有伪装则 revert。
     */
    private fun refreshFromSnapshot() {
        val pkg = ownerPkg ?: return
        synchronized(refreshLock) {
            val eff = PolicyResolver.resolve(pkg, ownerUid)
            val tzRaw = if (eff.domainEnabled(PolicyResolver.Domain.TIMEZONE)) {
                eff.policy?.timezoneId?.trim().orEmpty()
            } else ""
            val valid = tzRaw.isNotEmpty() && tzRaw in AVAILABLE_IDS
            when {
                valid && tzRaw == appliedTz -> return  // 无变化
                valid -> apply(tzRaw)
                appliedTz != null -> {
                    if (tzRaw.isNotEmpty()) {
                        ProbeLog.log("TZ-HOOKS 非法时区 '$tzRaw'，恢复真实时区 pkg=$pkg")
                    }
                    revert()
                }
                else -> {
                    if (tzRaw.isNotEmpty()) {
                        ProbeLog.log("TZ-HOOKS n=0 pkg=$pkg 非法时区 '$tzRaw'（非 Olson ID），跳过")
                    }
                }
            }
        }
    }

    /** 应用/切换伪装值：注册属性覆盖 → 重建默认时区 → TZ 环境变量 → user.timezone。 */
    private fun apply(tzRaw: String) {
        val pkg = ownerPkg ?: return
        var n = 0
        var via = ""

        // 1) SP 覆盖先注册：后续 setDefault(null) 的重建路径经它读属性
        RegionalSystemPropertiesHook.register(RegionalSystemPropertiesHook.KEY_TIMEZONE, tzRaw)

        // 2) 重建 defaultTimeZone（getDefaultRef 读的同一静态字段）并同步 ICU 缓存
        var rebuilt = false
        runCatching {
            TimeZone.setDefault(null)
            rebuilt = TimeZone.getDefault().id == tzRaw
        }.onFailure {
            module.log(Log.WARN, TAG,
                "TZ-HOOKS setDefault(null) 失败 pkg=$pkg: ${Log.getStackTraceString(it)}")
        }
        if (rebuilt) {
            via = "property"; n++
        } else {
            // 兜底：老版本 libcore 的 setDefault(null) 回退 zygote 缓存值而非读属性
            runCatching { TimeZone.setDefault(TimeZone.getTimeZone(tzRaw)) }
                .onSuccess { via = "explicit"; n++ }
                .onFailure {
                    module.log(Log.WARN, TAG,
                        "TZ-HOOKS 显式 setDefault 失败 pkg=$pkg: ${Log.getStackTraceString(it)}")
                }
        }

        // 3) bionic localtime：TZ 环境变量优先于属性直读
        runCatching { Os.setenv("TZ", tzRaw, true) }
            .onSuccess { n++ }
            .onFailure {
                module.log(Log.WARN, TAG,
                    "TZ-HOOKS setenv(TZ) 失败 pkg=$pkg: ${Log.getStackTraceString(it)}")
            }

        // 4) 兼容直接读 user.timezone 的库
        runCatching { System.setProperty("user.timezone", tzRaw) }.onSuccess { n++ }

        val prev = appliedTz
        appliedTz = tzRaw
        val ok = TimeZone.getDefault().id == tzRaw
        ProbeLog.log("TZ-HOOKS apply pkg=$pkg tz=$prev -> $tzRaw via=$via steps=$n defaultOk=$ok")
        module.log(Log.INFO, TAG,
            "timezone applied pkg=$pkg tz=$tzRaw via=$via steps=$n defaultOk=$ok")
    }

    /**
     * 撤销伪装并恢复真实时区（策略删除/域禁用/非法 ID/快照清空）。
     * 恢复语义（评审三轮 #5）：还原为装钩时捕获的真实状态，而不是清空——
     * 应用在装钩前可能自己设置过 TZ 环境变量 / user.timezone 属性，直接
     * unsetenv/clearProperty 会破坏应用自身语义。顺序敏感：先注销属性覆盖，
     * 后续重建/读回才不经过伪装值。
     */
    private fun revert() {
        val pkg = ownerPkg ?: return
        val prev = appliedTz ?: return
        runCatching { RegionalSystemPropertiesHook.unregister(RegionalSystemPropertiesHook.KEY_TIMEZONE) }
        // 默认时区：显式还原捕获值（TimeZone.setDefault 同步 ICU 缓存）；失败再试
        // setDefault(null) 走属性重建路径
        val realId = realDefaultTz
        runCatching { TimeZone.setDefault(TimeZone.getTimeZone(realId ?: "UTC")) }
            .onFailure { runCatching { TimeZone.setDefault(null) } }
        // TZ 环境变量：原值存在则还原，原本未设置才 unsetenv
        runCatching {
            val env = realTzEnv
            if (env.isNullOrEmpty()) Os.unsetenv("TZ") else Os.setenv("TZ", env, true)
        }
        // user.timezone 属性：同理按原值还原
        runCatching {
            val prop = realUserTimezoneProp
            if (prop.isNullOrEmpty()) System.clearProperty("user.timezone")
            else System.setProperty("user.timezone", prop)
        }
        appliedTz = null
        ProbeLog.log("TZ-HOOKS revert pkg=$pkg $prev -> ${TimeZone.getDefault().id}")
        module.log(Log.INFO, TAG, "timezone reverted pkg=$pkg -> ${TimeZone.getDefault().id}")
    }
}
