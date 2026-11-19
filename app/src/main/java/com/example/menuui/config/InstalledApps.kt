package com.example.menuui.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import java.util.concurrent.ConcurrentHashMap

/**
 * 已安装应用元信息（应用名 / 图标 / 是否系统应用），按包名缓存。
 * 依赖 QUERY_ALL_PACKAGES 可见性；查不到的包视为"未安装"（手动填写的包名可能如此）。
 * ⚠ 首次读取含 PackageManager IO，在后台线程调用。
 */
object InstalledApps {

    class Info(
        val installed: Boolean,
        val label: String,
        /** 预缩放图标（ICON_PX 见方）；未安装 / 加载失败为 null */
        val icon: Bitmap?,
        /** 系统预装（含已更新的预装应用）——非第三方 */
        val system: Boolean,
    )

    private const val ICON_PX = 96

    private val cache = ConcurrentHashMap<String, Info>()

    fun cached(pkg: String): Info? = cache[pkg]

    /** 未安装结果不缓存：手动输入的中间态包名、稍后才安装的应用都能重新查到 */
    fun load(context: Context, pkg: String): Info =
        cache[pkg] ?: query(context, pkg).also { if (it.installed) cache[pkg] = it }

    /** 作用域同步用：系统应用不进目标应用列表；查不到的包按第三方处理（宁可显示也不误删），跳过图标解码以降低同步耗时 */
    fun isThirdParty(context: Context, pkg: String): Boolean {
        cache[pkg]?.let { return !it.installed || !it.system }
        val ai = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return true
        }
        return (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
    }

    private fun query(context: Context, pkg: String): Info {
        val pm = context.packageManager
        val ai = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return Info(installed = false, label = pkg, icon = null, system = false)
        }
        val system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: pkg
        val icon = runCatching { rasterize(pm.getApplicationIcon(ai)) }.getOrNull()
        return Info(installed = true, label = label, icon = icon, system = system)
    }

    private fun rasterize(d: android.graphics.drawable.Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) {
            return Bitmap.createScaledBitmap(d.bitmap, ICON_PX, ICON_PX, true)
        }
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, ICON_PX, ICON_PX)
        d.draw(c)
        return bmp
    }
}
