package com.example.menuui.ui.map

import android.content.Context
import android.content.pm.PackageManager
import com.amap.api.maps.MapsInitializer

/**
 * 高德 SDK 合规与 Key 检查。SDK 8.1.0 起，创建地图前必须先告知"已展示隐私政策 +
 * 用户已同意"，否则地图白屏。同意状态由本类持久化；SDK 侧状态是进程级的，
 * 所以每次建图前都调一次 [applyToSdk]。
 */
object AmapPrivacy {

    private const val PREFS = "amap"
    private const val KEY_AGREED = "privacy_agreed"
    private const val META_API_KEY = "com.amap.api.v2.apikey"

    fun isAgreed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AGREED, false)

    fun agree(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AGREED, true).apply()
    }

    fun applyToSdk(context: Context) {
        val app = context.applicationContext
        MapsInitializer.updatePrivacyShow(app, true, true)
        MapsInitializer.updatePrivacyAgree(app, true)
        MapsInitializer.setProtocol(MapsInitializer.HTTPS) // targetSdk 28+ 默认禁明文
    }

    /** local.properties 未填 AMAP_KEY 时 manifest 里是空串 */
    fun hasApiKey(context: Context): Boolean = try {
        val info = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA,
        )
        !info.metaData?.getString(META_API_KEY).isNullOrBlank()
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
