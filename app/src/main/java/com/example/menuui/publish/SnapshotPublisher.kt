package com.example.menuui.publish

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 快照下发器（管理端 → hook 模块的配置写入端）。
 *
 * 通道与权限契约（评审三轮 #1）：
 *  - 模块侧从 [SNAPSHOT_PATH]（/data/local/tmp/ven11/snapshot.json）轮询读取；
 *  - 普通应用进程无权直写 /data/local/tmp（shell 属主，目录 0771）：本类把 JSON
 *    写进应用缓存目录，再借助 `su`（LSPosed 环境本身要求 root）以 root 身份
 *    mkdir + cp + chmod 644 完成放置；文件全局可读、父目录 others 位有 x，
 *    目标应用进程因此可读（模块侧 File.canRead() 校验依赖这一权限组合）；
 *  - 若 su 不可用（未 root / 无管理授权），直接写该路径会失败——此时返回失败
 *    结果并给出提示，绝不静默：链路断了要让用户知道，而不是以为配置已生效；
 *  - 调用流程：管理端 ConfigBus → SnapshotBuilder → [publish] →
 *    目标进程 SnapshotStore 1s 内轮询到新 configVersion。
 *
 * ⚠ 外部必需组件说明：若部署环境没有 root（例如模块经 magisk systemless 挂载但
 * 管理端为普通 app），需由仓库之外的受控通道（adb shell 推送、受权限保护的
 * ContentProvider、或 Binder 服务）完成同路径写入；本类是 root 环境下的内置实现。
 */

/** 模块通道探测结果（首页状态卡）：顶层声明，ConfigBus / UI 直接引用 */
sealed interface Probe {
    /** 模块已安装且 provider 可达；sameSignature=false 时写入将回落 su 通道 */
    data class Ok(val version: Long, val sameSignature: Boolean) : Probe
    object NotInstalled : Probe
    data class SignatureMismatch(val version: Long) : Probe
    data class Fault(val cause: String) : Probe
}

object SnapshotPublisher {

    const val TAG = "VEN11-MGR"

    /** provider 成功后补写文件通道的最小间隔（摇杆输入发布 250ms 节流，su 落盘有 fork 开销） */
    private const val FILE_DUALWRITE_MIN_MS = 2_000L

    /** 与 module 侧 SnapshotStore.SNAPSHOT_PATH 保持一致（改动需两处同步） */
    const val SNAPSHOT_PATH = "/data/local/tmp/ven11/snapshot.json"
    const val SNAPSHOT_DIR = "/data/local/tmp/ven11"

    const val MODULE_PACKAGE = "dev.ven11.module"

    // ---- 模块配置通道契约（审查八 #1）：独立 APK 不能共享代码，与
    // module 侧 ConfigContentProvider 的常量同步维护 ----
    private const val PROVIDER_PAYLOAD_URI = "content://dev.ven11.module.config/payload"
    private const val PROVIDER_VERSION_URI = "content://dev.ven11.module.config/version"
    private const val PROVIDER_COL_PAYLOAD = "payload"
    private const val WAKE_ACTION = "dev.ven11.module.action.WAKE"

    data class Result(val ok: Boolean, val via: String, val message: String)

    /**
     * 探测模块配置通道：安装状态 → provider 存活（version 查询）→ 签名比对
     * （决定 provider 直写还是回落 su）。
     * provider 查不到时先两级唤醒（应用广播 → su 广播）再重试——ColorOS 限制
     * 后台自启时 binder 调用拉不起模块进程，显式广播可以。
     */
    fun probeModule(context: Context): Probe {
        val pm = context.packageManager
        val moduleInfo = try {
            pm.getPackageInfo(MODULE_PACKAGE, 0)
        } catch (_: Throwable) {
            return Probe.NotInstalled
        }
        var version = queryVersionOnce(context)
        if (version == null) {
            wakeModule(context)
            SystemClock.sleep(500)
            version = queryVersionOnce(context)
        }
        if (version == null) {
            wakeModuleViaSu(context)
            SystemClock.sleep(500)
            version = queryVersionOnce(context)
        }
        if (version == null) {
            return Probe.Fault(
                "进程唤醒失败（广播被系统拦截且 su 不可用）。" +
                    "发布会自动走 su 文件通道，功能不受影响",
            )
        }
        val same = signatureMatches(pm, context.packageName, MODULE_PACKAGE)
        return if (same) Probe.Ok(version, true) else Probe.SignatureMismatch(version)
    }

    /** version 轻查询：provider 不可达（Unknown URL / 异常）返回 null */
    private fun queryVersionOnce(context: Context): Long? = try {
        context.contentResolver.query(Uri.parse(PROVIDER_VERSION_URI), null, null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex("version")
                    if (idx >= 0) c.getLong(idx) else -1L
                } else -1L
            }
    } catch (_: Throwable) {
        null
    }

    /** 应用级显式广播唤醒（FLAG_INCLUDE_STOPPED_PACKAGES 覆盖 force-stop 后的停止态） */
    fun wakeModule(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(WAKE_ACTION)
                    .setPackage(MODULE_PACKAGE)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES),
            )
        }
    }

    /** root 广播唤醒（绕过 ColorOS 后台自启限制；su 不可用时静默跳过） */
    private fun wakeModuleViaSu(context: Context) {
        runCatching {
            val proc = ProcessBuilder(
                "su", "-c", "am broadcast -a $WAKE_ACTION -p $MODULE_PACKAGE",
            ).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
        }
    }

    private fun signatureMatches(pm: PackageManager, self: String, module: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = pm.getPackageInfo(self, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
            val m = pm.getPackageInfo(module, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
            s.isNotEmpty() && s == m
        } else {
            @Suppress("DEPRECATION")
            val s = pm.getPackageInfo(self, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            @Suppress("DEPRECATION")
            val m = pm.getPackageInfo(module, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            s.isNotEmpty() && s.any { a -> m.any { it == a } }
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 发布入口（审查八 #1）：优先写模块 APK 的配置通道（ConfigContentProvider，
     * binder 不受 /data/local/tmp 的 SELinux 限制，system_server / phone / 普通应用
     * 读同一版本）；写侧是签名权限，管理端与模块同签名时可用，签名不符或模块未装
     * 时回落旧的 su 文件通道（仅应用进程可靠可读，框架侧可能被 SELinux 挡）。
     * 两条通道失败都不静默，结果里带可读原因与 adb 兜底指引。
     * ⚠ 含 su 阻塞等待，后台线程调用。
     */
    fun publish(context: Context, json: String): Result {
        val values = ContentValues(1).apply { put(PROVIDER_COL_PAYLOAD, json) }
        val payloadUri = Uri.parse(PROVIDER_PAYLOAD_URI)
        val first = runCatching { context.contentResolver.insert(payloadUri, values) }
        var inserted = first.getOrNull()
        // 进程未运行（Unknown URL / null）→ 广播唤醒后短暂重试（进程冷启动约
        // 0.5-1s，ColorOS 冻结回收频繁，给足窗口）；SecurityException（签名不符）
        // 唤醒无意义，直接走兜底
        if (inserted == null && first.exceptionOrNull() !is SecurityException) {
            wakeModule(context)
            repeat(3) {
                SystemClock.sleep(500)
                inserted = runCatching { context.contentResolver.insert(payloadUri, values) }.getOrNull()
                if (inserted != null) return@repeat
            }
        }
        if (inserted == null) {
            val why = first.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "insert 返回空"
            return fallbackToFile(context, json, "模块配置通道不可用（$why）")
        }
        // 双写文件通道（脑裂修复）：provider 只在模块进程存活时可达——进程被
        // ColorOS 冻结/杀死后，被钩进程回落文件通道；若 provider 成功就跳过
        // 文件写入，两条通道内容会分叉（实测：provider=新配置、文件=旧配置，
        // 应用永远拿到旧位置）。双写后任意通道读到同一份最新快照。
        maybeDualWriteFile(context, json)
        val outcome = Result(true, "provider", "已写入模块配置通道（已双写文件通道，≤1s 热生效）")
        Log.i(TAG, "publish ok=${outcome.ok} via=${outcome.via}: ${outcome.message}")
        return outcome
    }

    /**
     * provider 成功后的文件通道补写：尽力而为（失败不影响 provider 结果），
     * 节流 [FILE_DUALWRITE_MIN_MS]——摇杆输入发布 250ms 一次，su 每次落盘
     * 有 fork 开销，文件通道 2s 内的最新快照足够（模块侧 1s 轮询 + 摇杆
     * 心跳 3s 续期，过期阈值 6s，2s 粒度不产生断档）。
     * 节流窗口内的发布不丢弃：只保留最新一份，窗口到期补写（尾沿）——
     * 否则窗口内的最后一次发布（如摇杆关闭）永远到不了文件通道，读文件的
     * 进程要等摇杆段过期（静止态 30s）才回落。su 次数不因此增加。
     */
    private fun maybeDualWriteFile(context: Context, json: String) {
        val appCtx = context.applicationContext
        synchronized(fileLock) {
            val wait = lastFileWriteAt + FILE_DUALWRITE_MIN_MS - SystemClock.elapsedRealtime()
            if (wait > 0L || flushScheduled) {
                pendingFileJson = json
                if (!flushScheduled) {
                    flushScheduled = true
                    fileScheduler.schedule(Runnable { flushPendingFile(appCtx) }, wait, TimeUnit.MILLISECONDS)
                }
                return
            }
            lastFileWriteAt = SystemClock.elapsedRealtime()
        }
        runCatching { publishViaSu(context, json) }
    }

    private fun flushPendingFile(context: Context) {
        val json = synchronized(fileLock) {
            flushScheduled = false
            lastFileWriteAt = SystemClock.elapsedRealtime()
            pendingFileJson.also { pendingFileJson = null }
        } ?: return
        runCatching { publishViaSu(context, json) }
    }

    private val fileLock = Any()
    private var lastFileWriteAt = 0L
    private var pendingFileJson: String? = null
    private var flushScheduled = false

    /** 尾沿补写调度线程（daemon，只承载节流到期后的一次 su 落盘） */
    private val fileScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ven11-file-dualwrite").apply { isDaemon = true }
    }

    /** su 落盘串行化：发布线程与尾沿补写线程共用同一组缓存文件 */
    private val suLock = Any()

    /** 文件通道兜底（su 落盘双路径：/data/local/tmp + /data/system，应用域与 system_server 域都可读） */
    private fun fallbackToFile(context: Context, json: String, why: String): Result {
        // 本次直写即最新：丢弃待补写的旧载荷（模块侧另有 configVersion 防回滚兜底）
        synchronized(fileLock) {
            pendingFileJson = null
            lastFileWriteAt = SystemClock.elapsedRealtime()
        }
        val r = publishViaSu(context, json)
        val note = "已双路径落盘（应用域 + system_server 域）"
        val prefix = if (r.ok) "$why；已回落文件通道" else why
        return r.copy(message = "$prefix（${r.message}）。$note")
    }

    /**
     * 写缓存 → su 拷贝到同目录临时文件 → chmod → mv 原子替换；任一步失败即返回失败
     * 结果（带可读原因）。此前直接 cp 覆盖目标文件：目标进程可能读到写了一半的 JSON，
     * 解析失败回落 EMPTY，语言/时区跟着闪一次（审查五）；tmp + mv 是同目录 rename，
     * 对读方原子。轮询侧只在解析成功后更新文件戳，失败会重试（审查八 #2）。
     *
     * 双路径落盘（与 scripts/publish-snapshot.sh 一致）：/data/local/tmp 供普通应用域，
     * /data/system/ven11 供 system_server 域（目录须 system 属主 700，文件 644 +
     * restorecon）。system 段尽力而为（`；`分隔），退出码以 local 段文件存在为准。
     */
    private fun publishViaSu(context: Context, json: String): Result = synchronized(suLock) {
        publishViaSuLocked(context, json)
    }

    private fun publishViaSuLocked(context: Context, json: String): Result {
        val cache = File(context.cacheDir, "ven11-snapshot.json.tmp").apply { writeText(json) }
        val backup = File(context.cacheDir, "ven11-snapshot.json")
        if (backup.exists()) backup.delete()
        if (!cache.renameTo(backup)) {
            return Result(false, "local", "缓存文件重命名失败（${cache.absolutePath}）")
        }
        val tmp = "$SNAPSHOT_PATH.tmp"
        val sysDir = "/data/system/ven11"
        val sysPath = "$sysDir/snapshot.json"
        val sysTmp = "$sysPath.tmp"
        val cmd = "mkdir -p $SNAPSHOT_DIR && cp '${backup.absolutePath}' '$tmp' " +
            "&& chmod 644 '$tmp' && mv -f '$tmp' '$SNAPSHOT_PATH' && chmod 771 $SNAPSHOT_DIR; " +
            "mkdir -p $sysDir && chown system:system $sysDir && chmod 700 $sysDir " +
            "&& cp '${backup.absolutePath}' '$sysTmp' && chown system:system '$sysTmp' " +
            "&& chmod 644 '$sysTmp' && mv -f '$sysTmp' '$sysPath'; " +
            "restorecon '$sysPath' 2>/dev/null; test -f $SNAPSHOT_PATH"
        return try {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().use { it.readText().trim() }
            val code = proc.waitFor()
            if (code == 0) {
                Result(true, "su", "已写入 $SNAPSHOT_PATH（0644）")
            } else {
                Result(
                    false, "su",
                    "su 退出码 $code：$output。设备未 root 或未授予管理端 root 时，" +
                        "请改用 adb shell 推送快照到 $SNAPSHOT_PATH（chmod 644）"
                )
            }
        } catch (t: Throwable) {
            Result(
                false, "su",
                "su 不可用（${t.javaClass.simpleName}: ${t.message}）。" +
                    "请改用 adb shell 推送快照到 $SNAPSHOT_PATH（chmod 644）"
            )
        }
    }
}
