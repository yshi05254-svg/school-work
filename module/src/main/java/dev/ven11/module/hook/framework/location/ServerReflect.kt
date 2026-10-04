package dev.ven11.module.hook.framework.location

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端反射访问器集中缓存（方案B ⑤）：system_server 的类（CallerIdentity、
 * LocationResult、各 Registration）不在编译期 classpath，全部运行时反射。
 * Method/Field 定位一次后按类缓存，热路径只剩 invoke。
 *
 * 全部访问器解析失败返回 null / -1，调用方一律透传真实值——异常不进 system_server
 * （约束 1），也不让单点反射失败放大成整条链故障。
 */
object ServerReflect {

    // ---------------------------------------------------------------- 钩子 this

    /** libxposed Chain 取被钩方法实例的访问器（跨 API 小版本命名差异，反射兜底） */
    @Volatile private var thisAccessor: Method? = null

    /** 被钩方法的接收实例（如 acceptLocationChange 所属的 Registration）；取不到返回 null */
    fun hookInstance(chain: Any?): Any? {
        if (chain == null) return null
        var m = thisAccessor
        if (m == null) {
            m = chain.javaClass.methods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                    (it.name == "getThis" || it.name == "getThisObject")
            } ?: return null
            thisAccessor = m
        }
        return runCatching { m.invoke(chain) }.getOrNull()
    }

    // ---------------------------------------------------------------- 注册身份

    /**
     * registration.getIdentity() → 回落 mIdentity 字段；按声明类缓存访问器。
     * getIdentity()/mIdentity 都声明在抽象基类 Registration 上（Android 16 实测），
     * 运行时类是 LocationRegistration 等子类——必须走继承链：
     * getter 用 getMethod（public 可继承），字段沿父类链 getDeclaredField。
     */
    fun registrationIdentity(registration: Any?): Any? {
        if (registration == null) return null
        val c = registration.javaClass
        val getter = accessor(c, "identity-getter") {
            runCatching { it.getMethod("getIdentity") }.getOrNull()
        }
        if (getter != null) {
            runCatching { getter.isAccessible = true; return getter.invoke(registration) }
        }
        val field = accessor(c, "identity-field") { k ->
            var cls: Class<*>? = k
            while (cls != null) {
                val f = runCatching { cls.getDeclaredField("mIdentity") }.getOrNull()
                if (f != null) return@accessor f
                cls = cls.superclass
            }
            null
        } ?: return null
        return runCatching { field.isAccessible = true; field.get(registration) }.getOrNull()
    }

    // ---------------------------------------------------------------- CallerIdentity

    /** CallerIdentity.getUid()（AOSP 方法名）；按类缓存 */
    fun identityUid(identity: Any?): Int {
        if (identity == null) return -1
        val getter = accessor(identity.javaClass, "uid-getter") {
            it.declaredMethods.firstOrNull { m -> m.name == "getUid" && m.parameterTypes.isEmpty() }
        } ?: return -1
        return runCatching { getter.isAccessible = true; getter.invoke(identity) as? Int ?: -1 }
            .getOrDefault(-1)
    }

    /** CallerIdentity.getPackageName()（字段读取器，无 IPC）；按类缓存 */
    fun identityPkg(identity: Any?): String? {
        if (identity == null) return null
        val getter = accessor(identity.javaClass, "pkg-getter") {
            it.declaredMethods.firstOrNull { m -> m.name == "getPackageName" && m.parameterTypes.isEmpty() }
        } ?: return null
        return runCatching { getter.isAccessible = true; getter.invoke(identity) as? String }.getOrNull()
    }

    // ---------------------------------------------------------------- LocationResult

    private val lrClass: Class<*>? by lazy {
        runCatching { Class.forName("android.location.LocationResult") }.getOrNull()
    }

    /** LocationResult.asList() → 内部位置列表（API 31+ 公开方法，反射引用避免 minSdk lint） */
    fun locationResultList(locationResult: Any?): List<Any?>? {
        if (locationResult == null) return null
        val m = accessor(lrClass ?: return null, "lr-aslist") {
            it.declaredMethods.firstOrNull { m -> m.name == "asList" && m.parameterTypes.isEmpty() }
        } ?: return null
        return runCatching { m.isAccessible = true; m.invoke(locationResult) as? List<Any?> }.getOrNull()
    }

    /**
     * LocationResult.create(List)（隐藏静态工厂）→ 回落私有构造 (List)。
     * 两个类成员在本机 services/framework 都存在（方案B 报告核实），均失败返回 null。
     */
    fun createLocationResult(locations: List<Any?>): Any? {
        val c = lrClass ?: return null
        if (locations.isEmpty()) return null
        val factory = accessor(c, "lr-create") {
            it.declaredMethods.firstOrNull { m ->
                m.name == "create" && m.parameterTypes.size == 1 &&
                    java.util.List::class.java.isAssignableFrom(m.parameterTypes[0])
            }
        }
        if (factory != null) {
            val r = runCatching { factory.isAccessible = true; factory.invoke(null, locations) }.getOrNull()
            if (r != null) return r
        }
        val ctor = accessor(c, "lr-ctor") {
            it.declaredConstructors.firstOrNull { k ->
                k.parameterTypes.size == 1 &&
                    java.util.List::class.java.isAssignableFrom(k.parameterTypes[0])
            }
        } ?: return null
        return runCatching { ctor.isAccessible = true; ctor.newInstance(locations) }.getOrNull()
    }

    // ---------------------------------------------------------------- 注入（主动连续交付）

    /** LocationProviderManager.mName（"gps"/"fused"/"network"/"passive"）；按类缓存字段 */
    fun providerManagerName(manager: Any?): String? {
        if (manager == null) return null
        val field = accessor(manager.javaClass, "lpm-mName") { k ->
            var cls: Class<*>? = k
            while (cls != null) {
                val f = runCatching { cls.getDeclaredField("mName") }.getOrNull()
                if (f != null) return@accessor f
                cls = cls.superclass
            }
            null
        } ?: return null
        return runCatching { field.isAccessible = true; field.get(manager) as? String }.getOrNull()
    }

    /** Registration.getRequest().getIntervalMillis()（API 31+ 公开）；取不到回落 default */
    fun requestIntervalMillis(registration: Any?, default: Long): Long {
        if (registration == null) return default
        val getReq = accessor(registration.javaClass, "reg-getRequest") { k ->
            var cls: Class<*>? = k
            while (cls != null) {
                val m = cls.declaredMethods.firstOrNull { it.name == "getRequest" && it.parameterTypes.isEmpty() }
                if (m != null) return@accessor m
                cls = cls.superclass
            }
            null
        } ?: return default
        val req = runCatching { getReq.isAccessible = true; getReq.invoke(registration) }.getOrNull()
            ?: return default
        val getIv = accessor(req.javaClass, "lr-interval") {
            it.declaredMethods.firstOrNull { m -> m.name == "getIntervalMillis" && m.parameterTypes.isEmpty() }
        } ?: return default
        return runCatching { getIv.isAccessible = true; (getIv.invoke(req) as? Long) ?: default }
            .getOrDefault(default)
    }

    /** registration.acceptLocationChange(LocationResult) → ListenerOperation（经我们的钩再伪装一次，幂等） */
    fun acceptLocationChange(registration: Any?, locationResult: Any?): Any? {
        if (registration == null || locationResult == null) return null
        val m = accessor(registration.javaClass, "reg-accept") { k ->
            var cls: Class<*>? = k
            while (cls != null) {
                val mm = cls.declaredMethods.firstOrNull {
                    it.name == "acceptLocationChange" && it.parameterTypes.size == 1
                }
                if (mm != null) return@accessor mm
                cls = cls.superclass
            }
            null
        } ?: return null
        return runCatching { m.isAccessible = true; m.invoke(registration, locationResult) }.getOrNull()
    }

    /** manager.deliverToListeners(Function)：系统原生交付入口，内部持 mMultiplexerLock + ReentrancyGuard */
    fun deliverToListeners(manager: Any?, fn: java.util.function.Function<Any?, Any?>): Boolean {
        if (manager == null) return false
        val m = accessor(manager.javaClass, "lpm-deliver-fn") { k ->
            var cls: Class<*>? = k
            while (cls != null) {
                val mm = cls.declaredMethods.firstOrNull {
                    it.name == "deliverToListeners" && it.parameterTypes.size == 1 &&
                        java.util.function.Function::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (mm != null) return@accessor mm
                cls = cls.superclass
            }
            null
        } ?: return false
        return runCatching { m.isAccessible = true; m.invoke(manager, fn); true }.getOrDefault(false)
    }

    /** Registration 运行时类的 simpleName（用于区分连续注册 LocationRegistration） */
    fun registrationSimpleName(registration: Any?): String = registration?.javaClass?.simpleName ?: ""

    /** LocationRegistration 内部类的外部 LocationProviderManager（this$0 合成字段），沿父类链找 */
    fun enclosingManager(registration: Any?): Any? {
        if (registration == null) return null
        val field = accessor(registration.javaClass, "reg-this0") { k ->
            var cls: Class<*>? = k
            while (cls != null) {
                val f = cls.declaredFields.firstOrNull { it.name == "this\$0" }
                if (f != null) return@accessor f
                cls = cls.superclass
            }
            null
        } ?: return null
        return runCatching { field.isAccessible = true; field.get(registration) }.getOrNull()
    }

    // ---------------------------------------------------------------- 基建

    private val accessors = ConcurrentHashMap<String, Any>()

    /** "已查找且不存在"的哨兵：ConcurrentHashMap 不允许 null 值，用它表示负缓存 */
    private val ABSENT = Any()

    /**
     * 访问器按 (类, 用途) 缓存；解析失败缓存 [ABSENT] 哨兵（下次不再找）。
     * ConcurrentHashMap 不允许 null 值——不能直接存 null（会抛 NPE，让 create→ctor /
     * getIdentity→字段 等兜底不可达），故用哨兵代替负缓存。
     */
    private inline fun <reified T : AccessibleObject> accessor(
        c: Class<*>,
        purpose: String,
        find: (Class<*>) -> T?,
    ): T? {
        val key = "${c.name}#$purpose"
        accessors[key]?.let { return if (it === ABSENT) null else it as? T }
        val found = runCatching { find(c) }.getOrNull()?.apply { isAccessible = true }
        accessors[key] = found ?: ABSENT
        return found
    }
}
