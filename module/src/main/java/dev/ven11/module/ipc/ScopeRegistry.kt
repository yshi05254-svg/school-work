package dev.ven11.module.ipc

import android.content.Context
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dev.ven11.module.ProbeLog
import dev.ven11.module.Ven11Module
import dev.ven11.module.hook.framework.UidResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * LSPosed 作用域登记表：哪些应用"在作用域里"（模块已注入其进程）。
 *
 * 作用域只有 LSPosed 知道，libxposed API 不向 hook 进程暴露列表；但模块被注入
 * 进某个应用进程这件事本身就是"该应用在作用域内"的证明——注入时由应用进程
 * 向 system_server 登记，pkg=null 的作用域默认策略（[PolicyResolver]）只对
 * 登记过的应用生效。管理端因此不必把作用域里已勾选的应用再添加一遍。
 *
 * 登记通道：LocationManager.sendExtraCommand（任何应用都能调、binder 身份由内核
 * 保证、不受包可见性限制——微信等读不到模块 provider 的应用也能登记）。
 * system_server 内由 ScopeCommandHook 钩 LocationManagerService.sendExtraCommand，
 * 命令名命中本模块常量时在权限检查之前截获，交给 [onServerCommand]，不走原方法。
 * 本类不引用 libxposed（模块 provider 进程里没有它）。
 *
 * 三种角色：
 *  - SERVER（system_server）：登记表本体，uid → pid 集合，只存内存；判定时要求
 *    至少一个登记 pid 仍存活且属于该 uid——应用被移出作用域后重启（新进程不再
 *    登记），旧 pid 消失即失效，无需重启手机；
 *  - CLIENT（作用域内应用进程）：只判断自己（uid == 本进程 uid），启动时登记；
 *  - REMOTE（phone 进程 / 模块 provider 进程）：向 system_server 查询在作用域内
 *    的 uid 列表，按 [REMOTE_TTL_MS] 刷新。
 */
object ScopeRegistry {

    const val CMD_REGISTER = "dev.ven11.module.SCOPE_REGISTER"
    const val CMD_QUERY = "dev.ven11.module.SCOPE_QUERY"
    const val KEY_UIDS = "dev.ven11.module.SCOPE_UIDS"

    private const val REMOTE_TTL_MS = 1_000L
    private const val ALIVE_TTL_MS = 1_000L

    /** 客户端登记重试：首次在装钩时同步尝试，失败后后台退避重试 */
    private val REGISTER_RETRY_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000)

    private enum class Role { REMOTE, CLIENT, SERVER }

    @Volatile private var role = Role.REMOTE

    // ---------------------------------------------------------------- 查询（各角色共用）

    /** uid 是否在作用域内（模块已注入它的进程）。热路径：只查内存 */
    fun inScope(uid: Int): Boolean = when (role) {
        Role.CLIENT -> uid == Process.myUid()
        Role.SERVER -> serverInScope(uid)
        Role.REMOTE -> uid in remoteUids
    }

    // ---------------------------------------------------------------- CLIENT

    /** 作用域内应用进程装钩时调用：登记本进程（同步尝试一次，失败转后台重试） */
    fun registerSelf(pkg: String) {
        role = Role.CLIENT
        if (sendRegister()) return
        Thread({
            for (delay in REGISTER_RETRY_MS) {
                SystemClock.sleep(delay)
                if (sendRegister()) return@Thread
            }
            ProbeLog.log("SCOPE-REGISTER-FAIL pkg=$pkg（system_server 钩未就绪？）")
        }, "ven11-scope-reg").apply { isDaemon = true }.start()
    }

    private fun sendRegister(): Boolean {
        return runCatching {
            val lm = locationManager(UidResolver.anyContext()) ?: return false
            val extras = Bundle()
            lm.sendExtraCommand(LocationManager.GPS_PROVIDER, CMD_REGISTER, extras)
            // 钩子回写确认位：system_server 没装钩时原方法不会写它（或直接抛权限异常）
            extras.getBoolean(CMD_REGISTER, false)
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- REMOTE

    @Volatile private var remoteUids: Set<Int> = emptySet()
    @Volatile private var remoteAt = 0L

    /** REMOTE 角色按 TTL 刷新（SnapshotStore 后台轮询 / provider 鉴权调用）；其它角色 no-op */
    fun refresh(ctx: Context? = null) {
        if (role != Role.REMOTE) return
        val now = SystemClock.elapsedRealtime()
        if (now - remoteAt < REMOTE_TTL_MS) return
        remoteAt = now
        runCatching {
            val lm = locationManager(ctx ?: UidResolver.anyContext()) ?: return
            val extras = Bundle()
            lm.sendExtraCommand(LocationManager.GPS_PROVIDER, CMD_QUERY, extras)
            val uids = extras.getIntArray(KEY_UIDS) ?: return
            remoteUids = uids.toHashSet()
        }
    }

    // ---------------------------------------------------------------- SERVER

    private val registered = ConcurrentHashMap<Int, MutableSet<Int>>()

    private class Alive(val at: Long, val alive: Boolean)

    private val aliveCache = ConcurrentHashMap<Int, Alive>()

    /** system_server 装钩时调用：本进程持有登记表 */
    fun becomeServer() {
        role = Role.SERVER
    }

    /** system_server：sendExtraCommand 截获到本模块命令（binder 线程，调用方身份即应用） */
    fun onServerCommand(cmd: String, extras: Bundle?) {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        when (cmd) {
            CMD_REGISTER -> {
                // 只登记应用 uid：系统进程不参与作用域默认策略
                if (uid % Ven11Module.PER_USER_RANGE < Process.FIRST_APPLICATION_UID) return
                val pids = registered.getOrPut(uid) { ConcurrentHashMap.newKeySet<Int>() }
                if (pids.add(pid)) {
                    aliveCache.remove(uid)
                    PolicyResolver.invalidate()
                    ProbeLog.log("SCOPE-REGISTER uid=$uid pid=$pid pkg=${UidResolver.pkgOfUid(uid)}")
                }
                extras?.putBoolean(CMD_REGISTER, true)
            }
            CMD_QUERY -> {
                // 只回答系统进程（phone）与模块自身（provider 鉴权），不向普通应用暴露作用域
                if (uid % Ven11Module.PER_USER_RANGE >= Process.FIRST_APPLICATION_UID &&
                    UidResolver.pkgOfUid(uid) != Ven11Module.MODULE_PKG
                ) return
                extras?.putIntArray(KEY_UIDS, registered.keys.filter { serverInScope(it) }.toIntArray())
            }
        }
    }

    private fun serverInScope(uid: Int): Boolean {
        val pids = registered[uid] ?: return false
        val now = SystemClock.elapsedRealtime()
        aliveCache[uid]?.let { if (now - it.at < ALIVE_TTL_MS) return it.alive }
        // 死掉的 pid 剔除；pid 被复用给别的 uid 也视为死亡（/proc/<pid> 属主即进程 uid）。
        // 空集合留在表里（量级 = 作用域应用数），避免与并发登记竞争丢 pid
        pids.removeAll { pid -> !pidAlive(pid, uid) }
        val alive = pids.isNotEmpty()
        aliveCache[uid] = Alive(now, alive)
        return alive
    }

    /** ENOENT = 进程已退出；其它错误（如 SELinux 拒绝 stat）无法判定，按存活处理 */
    private fun pidAlive(pid: Int, uid: Int): Boolean = try {
        Os.stat("/proc/$pid").st_uid == uid
    } catch (e: ErrnoException) {
        e.errno != OsConstants.ENOENT
    } catch (_: Throwable) {
        true
    }

    // ----------------------------------------------------------------

    private fun locationManager(ctx: Context?): LocationManager? =
        ctx?.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
}
