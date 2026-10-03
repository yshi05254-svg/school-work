package dev.ven11.module.publish

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File
import org.json.JSONObject

/**
 * 快照载荷的跨进程通道（审查八 #1）：管理端 insert 写入，被钩进程（普通应用 /
 * system_server / phone）query 读取。binder 通道不受 /data/local/tmp 的 SELinux
 * 目录限制，三类进程读到同一版本；读侧不设权限（被钩的目标应用要读），写侧由
 * manifest 的签名权限保护（android:writePermission），签名不符的第三方无法注入。
 *
 * 载荷整体单文件存放于 device-protected storage：directBoot 期间 system_server
 * 早读可命中，重启后无需打开管理端。版本取载荷文件的 mtime——读侧先做版本轻查询，
 * 版本未变不搬运载荷（审查八 #1：载荷整体存放，避免版本与正文分别更新造成混读；
 * 同毫秒两次发布的极小概率由 mtime+内容双检兜底，见 SnapshotStore）。
 *
 * 写入即校验：payload 必须是合法 JSON（org.json），非法载荷在写入口就拒绝，
 * 不会让被钩进程读到坏数据后回落 EMPTY 还让管理端以为成功。
 */
class ConfigContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val f = payloadFile()
        val ver = f?.lastModified() ?: 0L
        val cols = projection ?: ALL_COLUMNS
        return when (uri.lastPathSegment) {
            PATH_VERSION -> MatrixCursor(cols).apply {
                addRow(cols.map { c -> when (c) { COL_VERSION -> ver; else -> null } })
            }
            PATH_PAYLOAD -> MatrixCursor(cols).apply {
                val text = f?.takeIf { it.isFile }?.runCatching { readText() }?.getOrNull()
                addRow(cols.map { c -> when (c) {
                    COL_VERSION -> ver
                    COL_PAYLOAD -> text
                    else -> null
                } })
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.lastPathSegment != PATH_PAYLOAD) {
            throw IllegalArgumentException("insert only supported on /$PATH_PAYLOAD")
        }
        val json = values?.getAsString(COL_PAYLOAD)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("payload required")
        runCatching { JSONObject(json) }.onFailure {
            throw IllegalArgumentException("payload is not valid JSON", it)
        }
        writeAtomic(json)
        // 推送变更（功耗）：注册了 ContentObserver 的被钩进程即时拉取，
        // 无需高频 binder 轮询 version；通知失败不影响写入结果
        runCatching { context?.contentResolver?.notifyChange(payloadUri(), null) }
        return uri
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String = TYPE

    // ---------------------------------------------------------------- 存储

    /** device-protected 优先（directBoot 可读）；不可用时退内部存储（早读拿不到，可接受） */
    private fun payloadFile(): File? {
        val ctx = context ?: return null
        val dir = try {
            ctx.createDeviceProtectedStorageContext().filesDir
        } catch (_: Throwable) {
            ctx.filesDir
        }
        return File(dir, "config/payload.json")
    }

    /** tmp + rename 原子替换，读侧永远看到完整载荷 */
    private fun writeAtomic(json: String) {
        val f = payloadFile() ?: throw IllegalStateException("config storage unavailable")
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json)
        if (f.exists() && !f.delete()) {
            tmp.delete()
            throw IllegalStateException("config payload replace failed")
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IllegalStateException("config payload rename failed")
        }
    }

    companion object {
        const val PATH_VERSION = "version"
        const val PATH_PAYLOAD = "payload"
        const val COL_VERSION = "version"
        const val COL_PAYLOAD = "payload"
        const val AUTHORITY = "dev.ven11.module.config"
        const val TYPE = "vnd.android.cursor.item/vnd.dev.ven11.module.config"
        val ALL_COLUMNS = arrayOf(COL_VERSION, COL_PAYLOAD)

        /** 消费方用：content://dev.ven11.module.config/payload */
        fun payloadUri(): Uri = Uri.parse("content://$AUTHORITY/$PATH_PAYLOAD")
    }
}
