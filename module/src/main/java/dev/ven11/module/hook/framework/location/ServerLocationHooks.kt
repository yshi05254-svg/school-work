package dev.ven11.module.hook.framework.location

import android.location.Location
import android.os.Process
import android.util.Log
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.hook.framework.UidResolver
import dev.ven11.module.hook.location.LocationFactory
import dev.ven11.module.hook.location.LocationFactory.Decision
import dev.ven11.module.ipc.SnapshotStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import java.util.function.Function

/**
 * 服务端定位钩（方案B ①，system_server 内，Ven11Module.onSystemServerStarting 路径）：
 *
 *  - 主通道坐标替换（改写模式 B）：LocationProviderManager 两个 Registration 的
 *    acceptLocationChange(LocationResult)——按注册身份决策，伪装时为该应用复制一份
 *    新的 LocationResult 换参 proceed（覆盖 listener / PendingIntent / getCurrentLocation
 *    / 批量定位全部注册路径）；
 *  - getLastLocation（模式 A）：proceed 后按决策替换返回的 Location；
 *  - GNSS 分发过滤（模式 C）：ListenerMultiplexer.deliverToListeners 两个重载——
 *    Function 重载换参包装，按注册抑制；ListenerOperation 重载改为以 Function 重载
 *    分发（AOSP 原生委托语义），绕开 provider 方法被 AOT 内联的失效点（防泄漏报告 §7）。
 *
 * 架构约束（方案B §1.5，每个适配器必须遵守）：
 *  1. 异常不抛进 system_server——我们的错误透传原值（proceed 原参数）；原方法自身的
 *     异常保持原语义上抛；proceed 绝不执行两次（proceeded 标志）；
 *  2. 回调路径零阻塞——身份反射已缓存、决策读内存缓存、构造纯计算，无 IPC / 文件 / 设置查询；
 *  3. 不修改共享对象——LocationResult 整份复制改写（LocationResultRewriter）；
 *  4. 身份只从 CallerIdentity / Registration 取，不用 Binder.getCallingUid()
 *     （分发回调运行在系统线程上， Binder 身份不是目标应用）；
 *  5. uid < 10000 立即放行（最便宜的判断在最前）；
 *  6. serverLocation=false（快照）→ 钩子整体放行，回退客户端交付钩；
 *  7. 日志限频——SRVLOC-FIRST 每钩点×uid 一次、SRVLOC-STAT 每 60s 一行汇总、
 *     SRVLOC-ERR 每钩点×异常类型一次。
 */
object ServerLocationHooks {

    private const val STAT_PERIOD_MS = 60_000L

    private val installed = AtomicBoolean(false)

    /** 安装结果（STAT 行随带，防开机早期日志被 128KB 缓冲冲掉） */
    @Volatile private var installSummary = "installing"

    fun install(module: XposedModule, cl: ClassLoader): Int {
        if (!installed.compareAndSet(false, true)) return -1
        var n = 0
        val missing = ArrayList<String>()

        // ---- 主通道 1/2：两个 Registration 内部类的 acceptLocationChange(LocationResult)
        for ((clsName, tag) in listOf(
            "com.android.server.location.provider.LocationProviderManager\$LocationRegistration" to "accept-reg",
            "com.android.server.location.provider.LocationProviderManager\$GetCurrentLocationListenerRegistration" to "accept-cur",
        )) {
            val m = runCatching {
                cl.loadClass(clsName).declaredMethods.firstOrNull {
                    it.name == "acceptLocationChange" && it.parameterTypes.size == 1
                }
            }.getOrNull()
            if (m == null) { missing.add(tag); continue }
            runCatching {
                module.hook(m).setId("ven11.srvloc.$tag")
                    .intercept { chain -> onAcceptLocationChange(tag, chain) }
            }.onSuccess { n++ }
                .onFailure { missing.add("$tag(${it.javaClass.simpleName})") }
        }

        // ---- 主通道 3：getLastLocation(LastLocationRequest, CallerIdentity, int)
        runCatching {
            cl.loadClass("com.android.server.location.provider.LocationProviderManager")
                .declaredMethods.firstOrNull {
                    it.name == "getLastLocation" && it.parameterTypes.size == 3 &&
                        it.parameterTypes[1].simpleName == "CallerIdentity"
                }
        }.getOrNull()?.let { m ->
            runCatching {
                module.hook(m).setId("ven11.srvloc.getlast")
                    .intercept { chain -> onGetLastLocation(chain) }
            }.onSuccess { n++ }
                .onFailure { missing.add("getlast(${it.javaClass.simpleName})") }
        } ?: missing.add("getlast")

        // ---- GNSS 分发过滤 4：deliverToListeners(Function) 单重载
        // 只钩 Function 重载：位置级泄漏通道（卫星几何 onReportSvStatus、NMEA、原始测量、
        // 导航电文）全走 Function 重载（防泄漏报告核实）。ListenerOperation 重载经 dexdump
        // 核实只承载 onReportStatus（引擎开/关）与 onReportFirstFix（TTFF 毫秒），不含卫星
        // 几何/坐标，且其方法体自行遍历注册调 executeOperation（并不委托 Function 重载），
        // 无法按注册过滤且无位置泄漏面——不钩（避免为无泄漏通道加失效特例）。
        runCatching {
            cl.loadClass("com.android.server.location.listeners.ListenerMultiplexer")
        }.getOrNull()?.let { mux ->
            val fnMethod = mux.declaredMethods.firstOrNull {
                it.name == "deliverToListeners" && it.parameterTypes.size == 1 &&
                    Function::class.java.isAssignableFrom(it.parameterTypes[0])
            }
            if (fnMethod != null) {
                val voidRet = fnMethod.returnType == Void.TYPE
                runCatching {
                    module.hook(fnMethod).setId("ven11.srvloc.gnss-fn")
                        .intercept { chain -> onDeliverFunction(chain, voidRet) }
                }.onSuccess { n++ }.onFailure { missing.add("gnss-fn(${it.javaClass.simpleName})") }
            } else missing.add("gnss-fn")
        } ?: missing.add("gnss(mux-class)")

        // ---- 注入捕获 5：registerLocationRequest（应用注册时触发，thisObject=manager；室内也有）
        runCatching {
            cl.loadClass("com.android.server.location.provider.LocationProviderManager")
                .declaredMethods.filter { it.name == "registerLocationRequest" }
        }.getOrNull()?.let { ms ->
            var armed = 0
            for (m in ms) {
                runCatching {
                    module.hook(m).setId("ven11.srvloc.reg/${m.parameterTypes.size}")
                        .intercept(object : XposedInterface.Hooker {
                            override fun intercept(chain: XposedInterface.Chain): Any? {
                                runCatching { ServerLocationInjector.recordManager(ServerReflect.hookInstance(chain)) }
                                return chain.proceed()
                            }
                        })
                }.onSuccess { armed++ }.onFailure { missing.add("reg(${it.javaClass.simpleName})") }
            }
            if (armed > 0) n++ else missing.add("reg")
        } ?: missing.add("reg(class)")

        installSummary = "installed=$n/5 missing=${missing.joinToString("|").ifEmpty { "-" }}"
        ProbeLog.log("SRVLOC-INSTALL $installSummary")
        module.log(Log.INFO, Ven11Module.TAG, "SRVLOC $installSummary")

        // 预热配置轮询（后台执行器，不阻塞安装流程）
        runCatching { SnapshotStore.current() }
        startBootIdentityLoader()
        startStatLogger()
        return n
    }

    // ---------------------------------------------------------------- 主通道 1/2：acceptLocationChange

    private fun onAcceptLocationChange(tag: String, chain: XposedInterface.Chain): Any? {
        var proceeded = false
        try {
            val registration = ServerReflect.hookInstance(chain)
            ServerLocationInjector.noteDelivery(registration) // 真实交付记账（注入自身被 injecting 标志排除）
            ServerLocationInjector.recordManagerFromRegistration(registration) // 兜底捕获 manager
            val identity = ServerReflect.registrationIdentity(registration)
            val uid = ServerReflect.identityUid(identity)
            if (uid <= 0 || uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) {
                SrvStat.count("$tag:pass")
                proceeded = true
                return chain.proceed()
            }
            val pkg = ServerReflect.identityPkg(identity)
            return when (ServerLocationDecider.modeFor(uid, pkg)) {
                ServerLocationDecider.Mode.PASS -> {
                    SrvStat.count("$tag:pass")
                    proceeded = true
                    chain.proceed()
                }
                ServerLocationDecider.Mode.BLOCK -> {
                    SrvStat.count("$tag:block")
                    SrvStat.first(tag, uid, pkg, "block")
                    null // 不交付：严格模式下该应用不应拿到任何位置
                }
                ServerLocationDecider.Mode.SPOOF -> {
                    val original = chain.args.firstOrNull()
                    val rewritten = LocationResultRewriter.rewrite(pkg.orEmpty(), uid, original)
                    if (rewritten == null) {
                        // 无法整份伪装（决策翻转/构造失败）→ 透传原结果（约束 1）
                        SrvStat.count("$tag:pass")
                        proceeded = true
                        chain.proceed()
                    } else {
                        SrvStat.count("$tag:spoof")
                        SrvStat.first(tag, uid, pkg, "spoof")
                        proceeded = true
                        val delivered = chain.proceed(arrayOf(rewritten))
                        // 注入探针观测点（摇杆失效原因.md §5.5）：改写后的点被原方法体
                        // 丢弃（ColorOS 门控 / 限速过滤 / app-op 拒绝都会静默 return null）
                        if (delivered == null) SrvStat.count("$tag:drop")
                        delivered
                    }
                }
            }
        } catch (t: Throwable) {
            if (proceeded) throw t // 原方法自身异常：保持系统原语义
            SrvStat.errOnce(tag, t)
            return runCatching { chain.proceed() }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- 主通道 3：getLastLocation

    private fun onGetLastLocation(chain: XposedInterface.Chain): Any? {
        var proceeded = false
        try {
            val identity = chain.args.getOrNull(1)
            val uid = ServerReflect.identityUid(identity)
            if (uid <= 0 || uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) {
                SrvStat.count("getlast:pass")
                proceeded = true
                return chain.proceed()
            }
            val pkg = ServerReflect.identityPkg(identity)
            return when (ServerLocationDecider.modeFor(uid, pkg)) {
                ServerLocationDecider.Mode.PASS -> {
                    SrvStat.count("getlast:pass")
                    proceeded = true
                    chain.proceed()
                }
                ServerLocationDecider.Mode.BLOCK -> {
                    SrvStat.count("getlast:block")
                    SrvStat.first("getlast", uid, pkg, "block")
                    null // 严格模式：应用看到"无最近位置"
                }
                ServerLocationDecider.Mode.SPOOF -> {
                    val template = chain.proceed() as? Location
                    proceeded = true
                    val p = pkg ?: return template
                    // proceed 之后：伪装逻辑（我们自己的代码）异常不得抛进 system_server
                    // ——独立兜底回 template，而非落到 catch 的 `if (proceeded) throw t`
                    runCatching {
                        val d = LocationFactory.decide(p, uid, template?.provider ?: "gps", template)
                        if (d is Decision.Spoof) {
                            SrvStat.count("getlast:spoof")
                            SrvStat.first("getlast", uid, pkg, "spoof")
                            d.loc
                        } else {
                            SrvStat.count("getlast:pass")
                            template // 决策翻转：透传真实值
                        }
                    }.getOrElse {
                        SrvStat.errOnce("getlast", it)
                        template // 伪装失败：透传真实值（约束 1）
                    }
                }
            }
        } catch (t: Throwable) {
            if (proceeded) throw t
            SrvStat.errOnce("getlast", t)
            return runCatching { chain.proceed() }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- GNSS 过滤 4：Function 重载

    private fun onDeliverFunction(chain: XposedInterface.Chain, voidReturn: Boolean): Any? {
        var proceeded = false
        try {
            val owner = ServerReflect.hookInstance(chain)
            val fn = chain.args.firstOrNull() as? Function<Any?, Any?>
            if (!GnssDeliveryFilter.isGnssOwner(owner) || fn == null) {
                SrvStat.count("gnss-fn:pass")
                proceeded = true
                return chain.proceed()
            }
            SrvStat.count("gnss-fn:filter")
            val wrapped = GnssDeliveryFilter.wrapFunction(fn)
            proceeded = true
            return chain.proceed(arrayOf(wrapped))
        } catch (t: Throwable) {
            if (proceeded) throw t
            SrvStat.errOnce("gnss-fn", t)
            return if (voidReturn) null else runCatching { chain.proceed() }.getOrNull() ?: false
        }
    }

    // ---------------------------------------------------------------- 启动标识与统计

    /**
     * 后台读一次 BOOT_COUNT 注入 LocationFactory（system_server 无 Application，
     * 应用侧读取路径失效）。开机完成后才能读到，重试至多 2 分钟；取不到则路线
     * 模式按 UNKNOWN_BOOT 降级为摇杆/静态（与客户端行为一致，评审二）。
     */
    private fun startBootIdentityLoader() {
        Thread {
            repeat(120) {
                val ctx = UidResolver.systemContext()
                if (ctx != null) {
                    // system_server 读全局设置无需特殊权限；异常按"未取到"处理，下一秒重试
                    val boot = try {
                        android.provider.Settings.Global.getInt(
                            ctx.contentResolver,
                            android.provider.Settings.Global.BOOT_COUNT,
                        )
                    } catch (_: Throwable) {
                        LocationFactory.UNKNOWN_BOOT
                    }
                    if (boot >= 0) {
                        LocationFactory.setBootIdentity(boot)
                        ProbeLog.log("SRVLOC-BOOT boot=$boot")
                        return@Thread
                    }
                }
                try {
                    Thread.sleep(1_000)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            ProbeLog.log("SRVLOC-BOOT 未取到 BOOT_COUNT（路线模式降级为摇杆/静态）")
        }.apply {
            name = "ven11-srvloc-boot"
            isDaemon = true
        }.start()
    }

    private fun startStatLogger() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(STAT_PERIOD_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                runCatching { SrvStat.logStat(installSummary) }
            }
        }.apply {
            name = "ven11-srvloc-stat"
            isDaemon = true
        }.start()
    }
}

/**
 * 服务端钩统计与限频日志（方案B §2.6）：
 *  - SRVLOC-FIRST：每钩点×uid×动作首次一条；
 *  - SRVLOC-STAT：每 60s 汇总一次并清零（带安装结果）；
 *  - SRVLOC-ERR：每钩点×异常类型一条。
 */
internal object SrvStat {

    private val counters = ConcurrentHashMap<String, LongAdder>()
    private val firstSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val errSeen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun count(key: String) {
        counters.computeIfAbsent(key) { LongAdder() }.increment()
    }

    fun first(hook: String, uid: Int, pkg: String?, action: String) {
        if (!firstSeen.add("$hook/$uid/$action")) return
        ProbeLog.log("SRVLOC-FIRST $hook uid=$uid pkg=${pkg ?: "-"} action=$action")
    }

    fun errOnce(hook: String, t: Throwable) {
        val type = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        count("$hook:err")
        if (errSeen.add("$hook/$type")) {
            ProbeLog.log("SRVLOC-ERR $hook $type: ${t.message ?: t.javaClass.name}")
        }
    }

    /** 60s 汇总一行：SRVLOC-STAT <install> accept-reg{trig=..,spoof=..} ... */
    fun logStat(installSummary: String) {
        val byHook = LinkedHashMap<String, MutableMap<String, Long>>()
        for (e in counters) {
            val v = e.value.sumThenReset()
            if (v == 0L) continue
            val idx = e.key.indexOf(':')
            if (idx <= 0) continue
            byHook.getOrPut(e.key.substring(0, idx)) { LinkedHashMap() }[e.key.substring(idx + 1)] = v
        }
        val detail = byHook.entries.joinToString(" ") { (hook, kv) ->
            "$hook{" + kv.entries.joinToString(",") { (k, v) -> "$k=$v" } + "}"
        }.ifEmpty { "-" }
        ProbeLog.log("SRVLOC-STAT $installSummary $detail")
    }
}
