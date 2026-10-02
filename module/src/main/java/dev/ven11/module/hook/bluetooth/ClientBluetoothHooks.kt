package dev.ven11.module.hook.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import android.util.Log
import dev.ven11.module.ipc.PolicyResolver
import dev.ven11.module.model.VirtualEnvironment
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.Collections

/**
 * 客户端蓝牙钩（目标应用进程）。语义与实现严格对齐（取代旧文档中互相矛盾的说法）：
 *
 * 1. getBondedDevices —— 由 policy.strictMode 决定：
 *    - 严格（strictMode=true，显式配置）：只返回环境中 bondState=BONDED 的设备；
 *      环境为空返回空集（与基站域"严格=空列表"一致）。
 *    - 兼容（strictMode 缺省 false，与定位域默认对齐）：环境设备 ∪ 真实已配对设备
 *      （同址保留真实对象，属性由设备属性钩改写）；环境无蓝牙数据时整体透传真实值。
 *    环境设备用 BluetoothAdapter.getRemoteDevice(address) 构造——公开 API，地址合法
 *    即返回对象，不要求设备真实存在——并配套 BluetoothDevice 属性钩补齐
 *    name/bondState/type/alias（否则真实蓝牙栈对陌生地址返回 null/BOND_NONE/UNKNOWN，
 *    与"已配对"自相矛盾）。
 *
 * 2. 本机适配器身份 —— 模型独立字段 btAdapterAddress/btAdapterName，不再拿第一台
 *    远程设备充当本机（避免本机与某台配对设备同址同名）。getAddress 保留 Android 6.0+
 *    隐私占位语义：真实结果为 02:00:00:00:00:00 时不替换。所有身份钩先 proceed()，
 *    S+ 上无 BLUETOOTH_CONNECT 的 SecurityException 按真实权限行为传播。
 *
 * 3. policy.bluetoothEnabled（可空，null=不模拟状态）同步 isEnabled/getState；
 *    有效状态为关时 getBondedDevices 返回空集、getName 返回 null，
 *    不出现"蓝牙关闭却有配对设备"的组合。
 *
 * 4. **远程设备地址一律透传**（VEN 0.3.0-rc2 崩溃教训）：不钩 BluetoothDevice.getAddress；
 *    设备属性钩只在地址命中环境设备时改写属性，不改地址本身。
 *
 * 依赖的模型/策略字段（model/Snapshot.kt、ipc/PolicyResolver.kt 侧需同步提供）：
 *   - environment.btDevices[{address,name,bondState,rssi}]（rssi 留待扫描类出口使用）
 *   - environment.btAdapterAddress / btAdapterName（新增，可空）
 *   - policy.bluetoothEnabled（已有）/ policy.strictMode（缺省 false=兼容，与定位域一致）
 */
class ClientBluetoothHooks(private val module: XposedModule) {

    /** 快照中一台蓝牙设备的归一化视图（address 已大写；rssi 留待扫描出口使用）。 */
    private class EnvDevice(val address: String, val name: String?, val bondState: Int, val rssi: Int?)

    /** 从某个 environment 实例派生的蓝牙域数据，随实例缓存（快照更新会解析出新实例）。 */
    private class BtEnv(
        val byAddr: Map<String, EnvDevice>,
        val bondedAddrs: List<String>,
        val adapterAddress: String?,
        val adapterName: String?,
    )

    /** 单次 intercept 的已解析上下文。enabled=null 表示未配置状态策略（跟随真实）。 */
    private class Ctx(val bt: BtEnv, val enabled: Boolean?, val strict: Boolean) {
        val off: Boolean get() = enabled == false
    }

    private companion object {
        const val TAG = "VEN11"
        const val LOG_THROTTLE_MS = 5_000L
        const val MAC_PLACEHOLDER = "02:00:00:00:00:00"
        val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        val EMPTY_BONDED: Set<BluetoothDevice> = Collections.unmodifiableSet(HashSet())

        /** 快照暂无设备 type 字段；真实栈对 getRemoteDevice 构造的对象返回 UNKNOWN，与已配对矛盾。 */
        val DEVICE_TYPE_FALLBACK = BluetoothDevice.DEVICE_TYPE_DUAL
    }

    fun install(cl: ClassLoader, pkg: String): Int {
        val uid = android.os.Process.myUid()
        var n = 0
        n += installAdapterHooks(cl, pkg, uid)
        n += installDeviceHooks(cl, pkg, uid)
        n += installLeScanHooks(cl, pkg, uid)
        module.log(Log.INFO, TAG, "client bluetooth hooks installed=$n pkg=$pkg")
        dev.ven11.module.ProbeLog.log("BT-HOOKS n=$n pkg=$pkg")
        return n
    }

    /**
     * BLE 扫描（覆盖域扩展 4a）：信标定位的唯一入口。注册（startScan 各公开
     * ScanCallback 重载）照常 proceed，经 CallbackHooks 在回调具体类上改写交付：
 *  - 严格模式（strictMode=true 显式配置）：抑制 onScanResult / onBatchScanResults
 *    交付（无 BLE 环境数据 → 空视图，与 bonded 严格语义一致）；onScanFailed 透传；
 *  - 兼容模式（缺省）：透传真实扫描（真实设备 ∪ 环境设备的合成留待 model 增补）。
     * PendingIntent 变体不做：投递经系统 PendingIntent 通道，客户端拦不到
     * （覆盖计划 4a 备注，留系统侧）。
     */
    private fun installLeScanHooks(cl: ClassLoader, pkg: String, uid: Int): Int {
        val cls = runCatching { cl.loadClass("android.bluetooth.le.BluetoothLeScanner") }.getOrNull()
            ?: return 0
        val cbBase = runCatching { cl.loadClass("android.bluetooth.le.ScanCallback") }.getOrNull()
            ?: return 0
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.name != "startScan") continue
            if (m.parameterTypes.none { cbBase.isAssignableFrom(it) }) continue
            runCatching {
                module.hook(m).setId("ven11.bt.le.start/${m.parameterTypes.size}")
                    .intercept(object : XposedInterface.Hooker {
                        override fun intercept(chain: XposedInterface.Chain): Any? {
                            val registered = chain.proceed()
                            val cb = chain.args.firstOrNull { cbBase.isInstance(it) }
                                ?: return registered
                            runCatching {
                                dev.ven11.module.hook.util.CallbackHooks.install(
                                    module, "BT-LE", cb, null,
                                    methodNames = setOf("onScanResult", "onBatchScanResults"),
                                    baseFallback = cbBase,
                                ) { chain, name ->
                                    val ctx = resolveCtx(pkg, uid) ?: return@install chain.proceed()
                                    if (ctx.strict) {
                                        // 严格模式：单条抑制（返回 null）；批量交空表
                                        if (name == "onBatchScanResults") {
                                            // API 102 的 chain.args 不可变：复制数组后改写
                                            val newArgs = chain.args.toTypedArray()
                                            newArgs[0] = ArrayList<Any?>()
                                            chain.proceed(newArgs)
                                        } else {
                                            null
                                        }
                                    } else {
                                        chain.proceed()
                                    }
                                }
                            }.onFailure { dev.ven11.module.ProbeLog.log("BT-LE-FAIL $it") }
                            return registered
                        }
                    })
            }.onSuccess { n++ }.onFailure {
                module.log(Log.WARN, TAG, "bt le hook failed: $it")
            }
        }
        return n
    }

    private fun installAdapterHooks(cl: ClassLoader, pkg: String, uid: Int): Int {
        val cls = runCatching { cl.loadClass("android.bluetooth.BluetoothAdapter") }.getOrNull() ?: return 0
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.parameterTypes.isNotEmpty()) continue
            val target = when (m.name) {
                "getAddress" -> "address"
                "getName" -> "name"
                "getBondedDevices" -> "bonded"
                "isEnabled" -> "enabled" // 状态模拟（policy.bluetoothEnabled，可缺省）
                "getState" -> "state"
                else -> null
            } ?: continue
            runCatching {
                module.hook(m).setId("ven11.bt.$target").intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        onAdapterMethod(chain, target, pkg, uid)
                })
            }.onSuccess { n++ }.onFailure {
                module.log(Log.WARN, TAG, "bt adapter hook failed target=$target: $it")
            }
        }
        return n
    }

    private fun installDeviceHooks(cl: ClassLoader, pkg: String, uid: Int): Int {
        val cls = runCatching { cl.loadClass("android.bluetooth.BluetoothDevice") }.getOrNull() ?: return 0
        var n = 0
        for (m in cls.declaredMethods) {
            if (m.parameterTypes.isNotEmpty()) continue
            val target = when (m.name) {
                "getName" -> "d-name"
                "getAlias" -> "d-alias" // hidden API，枚举不到时自然跳过
                "getBondState" -> "d-bond"
                "getType" -> "d-type"
                else -> null
            } ?: continue
            runCatching {
                module.hook(m).setId("ven11.bt.$target").intercept(object : XposedInterface.Hooker {
                    override fun intercept(chain: XposedInterface.Chain): Any? =
                        onDeviceMethod(chain, target, pkg, uid)
                })
            }.onSuccess { n++ }.onFailure {
                module.log(Log.WARN, TAG, "bt device hook failed target=$target: $it")
            }
        }
        return n
    }

    private fun onAdapterMethod(chain: XposedInterface.Chain, target: String, pkg: String, uid: Int): Any? {
        // 审查 #9：快照/策略解析整体 runCatching，任何异常都退回透传；其后所有路径
        // chain.proceed() 至多调用一次，proceed 抛出的 SecurityException 等按真实权限
        // 行为继续传播（#2/#4），不存在"吞异常后二次 proceed"的路径（#6）。
        val ctx = resolveCtx(pkg, uid) ?: return chain.proceed()
        return when (target) {
            "enabled" -> ctx.enabled ?: chain.proceed()
            "state" -> when (ctx.enabled) {
                null -> chain.proceed()
                true -> BluetoothAdapter.STATE_ON
                false -> BluetoothAdapter.STATE_OFF
            }
            "address" -> {
                val real = chain.proceed() as? String
                val spoof = ctx.bt.adapterAddress ?: return real
                // 无 LOCAL_MAC_ADDRESS 的普通 App 只能看到占位地址：保持占位不替换（#4）
                if (real == MAC_PLACEHOLDER) return real
                if (real == null && ctx.off) return null
                hit("identity", "adapter address spoofed")
                spoof
            }
            "name" -> {
                val real = chain.proceed() as? String
                val spoof = ctx.bt.adapterName ?: return real
                if (ctx.off) return null // 关闭态 getName 真实语义为 null
                hit("identity", "adapter name spoofed")
                spoof
            }
            else -> bondedDevices(chain, ctx)
        }
    }

    private fun bondedDevices(chain: XposedInterface.Chain, ctx: Ctx): Any? {
        @Suppress("UNCHECKED_CAST") // 运行时就是框架返回的 Set<BluetoothDevice>
        val real = chain.proceed() as? Set<BluetoothDevice>
            ?: return null // 真实 null（RemoteException 路径）原样透传，不二次 proceed（#6）
        if (ctx.off) return EMPTY_BONDED // 模拟关闭：空集；权限异常已在 proceed 阶段传播（#8）
        val adapter = chain.thisObject as? BluetoothAdapter ?: return wrapSet(real)
        // 显式关闭之外的有效状态：isEnabled（未配置状态策略时，该调用经本钩透传回真实值，
        // 避免"蓝牙关闭却有配对设备"的矛盾，#8）
        val effOn = ctx.enabled ?: runCatching { adapter.isEnabled }.getOrDefault(true)
        if (!effOn) return EMPTY_BONDED
        if (ctx.bt.byAddr.isEmpty()) {
            // 空环境语义由策略统一：严格=空集（同基站域），兼容=透传真实（#1/#10）
            return if (ctx.strict) EMPTY_BONDED else wrapSet(real)
        }
        val out = LinkedHashSet<BluetoothDevice>()
        if (!ctx.strict) out.addAll(real)
        val seen = HashSet<String>(out.size + ctx.bt.bondedAddrs.size)
        for (d in out) runCatching { seen.add(d.address.uppercase()) } // 框架本就返回大写，防御性归一（#5）
        for (addr in ctx.bt.bondedAddrs) {
            if (addr in seen) continue // 同址已有真实对象：保留真实对象，属性由设备属性钩改写
            // 公开 API：地址合法即返回对象，不要求设备真实存在（#2）
            runCatching { adapter.getRemoteDevice(addr) }
                .getOrNull()?.let { out.add(it); seen.add(addr) }
        }
        hit("bonded", "bonded real=${real.size} out=${out.size} strict=${ctx.strict}")
        return Collections.unmodifiableSet(out) // 对齐 AOSP unmodifiableSet 语义（#7）
    }

    private fun onDeviceMethod(chain: XposedInterface.Chain, target: String, pkg: String, uid: Int): Any? {
        val ctx = resolveCtx(pkg, uid) ?: return chain.proceed()
        val dev = chain.thisObject as? BluetoothDevice ?: return chain.proceed()
        // 仅当该设备地址命中环境定义时改写属性；远程地址本身一律透传（rc2 教训，#2）
        val spec = runCatching { dev.address.uppercase() }.getOrNull()
            ?.let { ctx.bt.byAddr[it] } ?: return chain.proceed()
        // 先 proceed 再替换：S+ 上 getName/getAlias 需要 BLUETOOTH_CONNECT，
        // 无权限时按真实行为抛 SecurityException（#2）
        return when (target) {
            "d-name", "d-alias" -> { chain.proceed(); spec.name }
            "d-bond" -> { chain.proceed(); spec.bondState }
            "d-type" -> { chain.proceed(); DEVICE_TYPE_FALLBACK }
            else -> chain.proceed()
        }
    }

    /** 解析失败/域未启用/无环境 → null（调用方透传）。整体 runCatching（#9）。 */
    private fun resolveCtx(pkg: String, uid: Int): Ctx? = runCatching {
        val eff = PolicyResolver.resolve(pkg, uid)
        if (!eff.domainEnabled(PolicyResolver.Domain.BLUETOOTH)) return@runCatching null
        val env = eff.environment ?: return@runCatching null
        // 策略字段统一在 Policy.policy（评审一.4：原误用复数 policies，编译不过）：
        // bluetoothEnabled: Boolean?（null=不模拟状态）、strictMode: Boolean?（缺省 false=兼容，
        // 与定位域对齐——环境未配置蓝牙数据时透传真实设备/扫描，避免默认配置下
        // 已配对设备消失、BLE 扫描被全屏蔽；显式 strictMode=true 才走隔离语义）
        Ctx(
            bt = btEnv(env),
            enabled = eff.policy?.bluetoothEnabled,
            strict = eff.policy?.strictMode == true,
        )
    }.getOrNull()

    @Volatile private var cacheSrc: VirtualEnvironment? = null
    @Volatile private var cacheEnv: BtEnv? = null

    /** 派生数据随 environment 实例缓存，避免每次 getBondedDevices 轮询都重建地址表。 */
    private fun btEnv(env: VirtualEnvironment): BtEnv {
        cacheEnv?.let { if (cacheSrc === env) return it }
        return synchronized(this) {
            cacheEnv?.takeIf { cacheSrc === env }?.let { return it }
            val built = buildBtEnv(env)
            cacheSrc = env
            cacheEnv = built
            built
        }
    }

    private fun buildBtEnv(env: VirtualEnvironment): BtEnv {
        // 地址归一化（#5）：BluetoothDevice.getAddress() 返回大写，快照里的小写地址
        // 不归一会全部匹配失败；同时过滤非法格式（非法地址传给 getRemoteDevice 会抛
        // IllegalArgumentException）。隐藏 API checkBluetoothAddress 用等价正则替代。
        val byAddr = LinkedHashMap<String, EnvDevice>()
        val bonded = ArrayList<String>()
        for (d in env.btDevices.orEmpty()) {
            val mac = normMac(d.address) ?: continue
            if (mac in byAddr) continue // 重复地址保留首条
            val bs = d.bondState ?: BluetoothDevice.BOND_NONE
            byAddr[mac] = EnvDevice(mac, d.name, bs, d.rssi)
            if (bs == BluetoothDevice.BOND_BONDED) bonded.add(mac)
        }
        return BtEnv(
            byAddr = Collections.unmodifiableMap(byAddr),
            bondedAddrs = Collections.unmodifiableList(bonded),
            adapterAddress = normMac(env.btAdapterAddress),
            adapterName = env.btAdapterName?.takeIf { it.isNotBlank() },
        )
    }

    private fun normMac(raw: String?): String? =
        raw?.trim()?.uppercase()?.takeIf { MAC_RE.matches(it) }

    private fun wrapSet(src: Collection<BluetoothDevice>): Set<BluetoothDevice> =
        Collections.unmodifiableSet(LinkedHashSet(src))

    private val lastHitAt = HashMap<String, Long>()

    /** ProbeLog 限流：getBondedDevices/身份钩会被高频轮询，避免刷屏。 */
    private fun hit(key: String, msg: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lastHitAt) {
            val last = lastHitAt[key] ?: 0L
            if (now - last < LOG_THROTTLE_MS) return
            lastHitAt[key] = now
        }
        dev.ven11.module.ProbeLog.log("BT-SPOOF $msg")
    }
}