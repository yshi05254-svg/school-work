package dev.ven11.module.publish

import android.os.DeadObjectException
import android.os.SystemClock
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * LSPosed 作用域来源（模块进程内）：框架在模块进程启动后经 XposedProvider 投递
 * 服务 binder，此处缓存它并按需读取作用域列表。
 *
 * 作用域没有变更回调（OnScopeEventListener 只回报 requestScope 结果），因此每次
 * 查询都直接调 getScope()——管理端只在回到前台时查一次，开销可忽略。
 */
object ScopeSource {

    /** 框架虚拟包 / 框架侧进程：不是"目标应用"，不参与管理端同步 */
    private val NON_APP = setOf("system", "android", "com.android.phone")

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val bindLock = Object()

    @Volatile private var service: XposedService? = null
    @Volatile private var registered = false

    /** provider onCreate 调用；重复调用无副作用 */
    fun init() {
        if (registered) return
        registered = true
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                synchronized(bindLock) {
                    service = s
                    bindLock.notifyAll()
                }
            }

            override fun onServiceDied(s: XposedService) {
                synchronized(bindLock) {
                    if (service === s) service = null
                }
            }
        })
    }

    /** 作用域内的普通应用包名；服务尚未投递 / 调用失败返回 null（与"作用域为空"区分） */
    fun appScope(waitMs: Long = 1_500L): List<String>? {
        var s = service
        if (s == null && waitMs > 0L) {
            val deadline = SystemClock.elapsedRealtime() + waitMs
            synchronized(bindLock) {
                while (service == null) {
                    val rem = deadline - SystemClock.elapsedRealtime()
                    if (rem <= 0L) break
                    try {
                        bindLock.wait(rem)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                s = service
            }
        }
        val active = s ?: return null
        return try {
            active.scope
                ?.filter { it.isNotBlank() && it !in NON_APP }
                ?.distinct()
        } catch (t: Throwable) {
            if (t is DeadObjectException || t.cause is DeadObjectException) {
                synchronized(bindLock) {
                    if (service === active) service = null
                }
            }
            null
        }
    }
}
