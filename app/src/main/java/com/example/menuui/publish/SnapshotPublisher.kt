package com.example.menuui.publish

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 快照下发器（管理端 → hook 模块的配置写入端）。
 *
 * 通道与权限契约：
 *  - 应用进程从模块 provider（binder）读配置；provider 不可达时（模块进程被
 *    ColorOS 冻结等）用被钩应用私有目录里的进程内缓存兜底（模块侧实现）；
 *  - 文件通道 [SNAPSHOT_PATH]（/data/system/ven11/snapshot.json）只给
 *    system_server 用（本域私有目录）：本类把 JSON 写进应用缓存目录，再借助
 *    `su`（LSPosed 环境本身要求 root）以 root 身份 mkdir + cp + chmod 完成
 *    放置，并顺带清理旧 /data/local/tmp 方案的残留；
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

    /** 与 module 侧 SnapshotStore.SNAPSHOT_PATHS 保持一致（改动需两处同步） */
    const val SNAPSHOT_PATH = "/data/system/ven11/snapshot.json"
    const val SNAPSHOT_DIR = "/data/system/ven11"
    private const val LEGACY_DIR = "/data/local/tmp/ven11"

    const val MODULE_PACKAGE = "dev.ven11.module"
    private const val MODULE_DE_DIR = "/data/user_de/0/dev.ven11.module"
    private const val MODULE_PAYLOAD_PATH = "$MODULE_DE_DIR/files/config/payload.json"
    private const val VECTOR_CLI = "/data/adb/modules/zygisk_vector/cli"
    private val SCOPE_NON_APP = setOf("system", "android", "com.android.phone")

    // ---- 模块配置通道契约（审查八 #1）：独立 APK 不能共享代码，与
    // module 侧 ConfigContentProvider 的常量同步维护 ----
    private const val PROVIDER_PAYLOAD_URI = "content://dev.ven11.module.config/payload"
    private const val PROVIDER_VERSION_URI = "content://dev.ven11.module.config/version"
    private const val PROVIDER_SCOPE_URI = "content://dev.ven11.module.config/scope"
    private const val PROVIDER_COL_PAYLOAD = "payload"
    private const val WAKE_ACTION = "dev.ven11.module.action.WAKE"

    data class Result(val ok: Boolean, val via: String, val message: String)
    data class ScopeQueryResult(val packages: List<String>, val via: String)

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

    /**
     * 读取 Vector / LSPosed 作用域（应用包名，已滤掉 system / phone 等框架项）。⚠ 阻塞，后台线程调用。
     * 4 级通道：直查 provider → 应用广播唤醒重试 → root 广播唤醒重试 → vector-cli JSON 兜底。
     * 全部不可用返回 null（调用方保持现状，不当作"作用域为空"去删应用）。
     */
    fun queryScope(context: Context): List<String>? = queryScopeWithSource(context)?.packages

    fun queryScopeWithSource(context: Context): ScopeQueryResult? {
        queryScopeOnce(context)?.let {
            Log.i(TAG, "queryScope ok via=provider (direct) count=${it.size}")
            return ScopeQueryResult(it, "provider")
        }
        wakeModule(context)
        repeat(2) {
            SystemClock.sleep(400)
            queryScopeOnce(context)?.let {
                Log.i(TAG, "queryScope ok via=provider (wake) count=${it.size}")
                return ScopeQueryResult(it, "provider")
            }
        }
        wakeModuleViaSu(context)
        repeat(4) {
            SystemClock.sleep(500)
            queryScopeOnce(context)?.let {
                Log.i(TAG, "queryScope ok via=provider (su-wake) count=${it.size}")
                return ScopeQueryResult(it, "provider")
            }
        }
        queryScopeViaVectorCli()?.let {
            Log.i(TAG, "queryScope ok via=vector-cli count=${it.size}")
            return ScopeQueryResult(it, "vector-cli")
        }
        Log.w(TAG, "queryScope failed: provider and vector-cli both unavailable")
        return null
    }

    private fun queryScopeViaVectorCli(): List<String>? = try {
        val proc = ProcessBuilder(
            "su", "-c", "$VECTOR_CLI scope --json ls $MODULE_PACKAGE",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        if (code != 0) {
            Log.w(TAG, "vector-cli scope failed code=$code out=${output.take(200)}")
            null
        } else {
            val start = output.indexOf('{')
            if (start < 0) null
            else {
                val root = JSONObject(output.substring(start))
                if (!root.optBoolean("success", false)) null
                else {
                    val arr = root.optJSONArray("data") ?: return null
                    buildList {
                        for (i in 0 until arr.length()) {
                            val pkg = arr.optJSONObject(i)?.optString("APP_PACKAGE").orEmpty().trim()
                            if (pkg.isNotBlank() && pkg !in SCOPE_NON_APP) add(pkg)
                        }
                    }.distinct()
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "vector-cli scope exception: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    private fun queryScopeOnce(context: Context): List<String>? = try {
        context.contentResolver.query(Uri.parse(PROVIDER_SCOPE_URI), null, null, null, null)
            ?.use { c ->
                val idx = c.getColumnIndex("pkg")
                if (idx < 0) return@use null
                buildList { while (c.moveToNext()) c.getString(idx)?.let(::add) }
            }
    } catch (_: Throwable) {
        null
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

    /** root 广播 + content query 唤醒（绕过 ColorOS 后台自启/stopped 限制；绝不用 --include-stopped-packages） */
    private fun wakeModuleViaSu(@Suppress("UNUSED_PARAMETER") context: Context) {
        runCatching {
            val proc = ProcessBuilder(
                "su",
                "-c",
                "am broadcast -a $WAKE_ACTION -p $MODULE_PACKAGE; content query --uri $PROVIDER_VERSION_URI >/dev/null 2>&1",
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
     * binder 不受 SELinux 目录限制，system_server / phone / 应用进程读同一版本）；
     * 写侧是签名权限，管理端与模块同签名时可用，签名不符或模块未装时回落 su
     * 文件通道（仅 system_server 域可读；应用进程靠 provider + 进程内缓存）。
     * 两条通道失败都不静默，结果里带可读原因与 adb 兜底指引。
     * ⚠ 含 su 阻塞等待，后台线程调用。
     */
    fun publish(context: Context, json: String): Result {
        val values = ContentValues(1).apply { put(PROVIDER_COL_PAYLOAD, json) }
        val payloadUri = Uri.parse(PROVIDER_PAYLOAD_URI)
        var lastResult = runCatching { context.contentResolver.insert(payloadUri, values) }
        var inserted = lastResult.getOrNull()
        // 进程未运行（Unknown URL / null）→ 两级唤醒（应用广播 → su 广播）后重试；
        // SecurityException（签名不符）唤醒无意义，直接走兜底
        if (inserted == null && lastResult.exceptionOrNull() !is SecurityException) {
            wakeModule(context)
            repeat(2) {
                SystemClock.sleep(400)
                lastResult = runCatching { context.contentResolver.insert(payloadUri, values) }
                inserted = lastResult.getOrNull()
                if (inserted != null) return@repeat
            }
            if (inserted == null && lastResult.exceptionOrNull() !is SecurityException) {
                wakeModuleViaSu(context)
                repeat(3) {
                    SystemClock.sleep(500)
                    lastResult = runCatching { context.contentResolver.insert(payloadUri, values) }
                    inserted = lastResult.getOrNull()
                    if (inserted != null) return@repeat
                }
            }
        }
        if (inserted == null) {
            val why = lastResult.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
                ?: "insert 返回空"
            return fallbackToFile(context, json, "模块配置通道不可用（$why）")
        }
        // 双写文件通道（system_server 用）：provider 只在模块进程存活时可达——
        // 进程被 ColorOS 冻结/杀死后，system_server 回落文件通道；若 provider
        // 成功就跳过文件写入，两条通道内容会分叉。双写后读到同一份最新快照。
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
     */
    private fun maybeDualWriteFile(context: Context, json: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFileWriteAt < FILE_DUALWRITE_MIN_MS) return
        lastFileWriteAt = now
        runCatching { publishViaSu(context, json) }
    }

    private var lastFileWriteAt = 0L

    /** 文件通道兜底（su 同步写 system 通道与模块 DE payload，消除双通道脑裂） */
    private fun fallbackToFile(context: Context, json: String, why: String): Result {
        val r = publishViaSu(context, json)
        val note = "已同步写入 system 与模块 DE 通道"
        val prefix = if (r.ok) "$why；已回落文件通道" else why
        return r.copy(message = "$prefix（${r.message}）。$note")
    }

    /**
     * 写缓存 → su 拷贝到同目录临时文件 → chmod → mv 原子替换；任一步失败即返回失败
     * 结果（带可读原因）。同时在模块 DE 目录存在时原子同步 payload.json，杜绝双通道脑裂。
     */
    private fun publishViaSu(context: Context, json: String): Result {
        val cache = File(context.cacheDir, "ven11-snapshot.json.tmp").apply { writeText(json) }
        val backup = File(context.cacheDir, "ven11-snapshot.json")
        if (backup.exists()) backup.delete()
        if (!cache.renameTo(backup)) {
            return Result(false, "local", "缓存文件重命名失败（${cache.absolutePath}）")
        }
        val tmp = "$SNAPSHOT_PATH.tmp"
        val modTmp = "$MODULE_PAYLOAD_PATH.tmp"
        val modCfgDir = "$MODULE_DE_DIR/files/config"
        val cmd = "mkdir -p $SNAPSHOT_DIR && chown system:system $SNAPSHOT_DIR && chmod 700 $SNAPSHOT_DIR " +
            "&& cp '${backup.absolutePath}' '$tmp' && chown system:system '$tmp' " +
            "&& chmod 644 '$tmp' && mv -f '$tmp' '$SNAPSHOT_PATH'; " +
            "restorecon '$SNAPSHOT_PATH' 2>/dev/null; " +
            "if [ -d '$MODULE_DE_DIR' ]; then " +
            "ug=\$(stat -c '%u:%g' '$MODULE_DE_DIR'); " +
            "mkdir -p '$modCfgDir' && chown \"\$ug\" '$MODULE_DE_DIR/files' '$modCfgDir' " +
            "&& chmod 700 '$modCfgDir' " +
            "&& cp '${backup.absolutePath}' '$modTmp' && chown \"\$ug\" '$modTmp' " +
            "&& chmod 600 '$modTmp' && mv -f '$modTmp' '$MODULE_PAYLOAD_PATH'; " +
            "restorecon '$MODULE_DE_DIR/files' '$modCfgDir' '$MODULE_PAYLOAD_PATH' 2>/dev/null; " +
            "fi; " +
            "rm -f $LEGACY_DIR/snapshot.json $LEGACY_DIR/snapshot.json.tmp; rmdir $LEGACY_DIR 2>/dev/null; " +
            "test -f $SNAPSHOT_PATH"
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
