package com.example.menuui.ui.map

import android.content.Context
import android.content.pm.PackageManager
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

/**
 * 百度地图 SDK 合规、初始化与 Key 检查。SDK 7.5 起必须先 setAgreePrivacy(true)
 * 再 initialize，否则初始化抛异常、地图无法创建。同意状态由本类持久化；
 * SDK 侧状态是进程级的，所以每次建图前都调一次 [ensureInitialized]。
 */
object BaiduMapSdk {

    private const val PREFS = "baidu_map"
    private const val KEY_AGREED = "privacy_agreed"
    private const val META_API_KEY = "com.baidu.lbsapi.API_KEY"

    fun isAgreed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AGREED, false)

    fun agree(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AGREED, true).apply()
    }

    /** 仅在用户同意后调用；重复调用无副作用 */
    fun ensureInitialized(context: Context) {
        val app = context.applicationContext
        SDKInitializer.setAgreePrivacy(app, true)
        if (!SDKInitializer.isInitialized()) SDKInitializer.initialize(app)
        // 地图的输入输出统一用 GCJ-02（默认是百度自有的 BD09LL），与 geo.CoordTransform 对接
        SDKInitializer.setCoordType(CoordType.GCJ02)
        SDKInitializer.setHttpsEnable(true) // targetSdk 28+ 默认禁明文
    }

    /** local.properties 未填 BAIDU_MAP_KEY 时 manifest 里是空串 */
    fun hasApiKey(context: Context): Boolean = try {
        val info = context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.GET_META_DATA,
        )
        !info.metaData?.getString(META_API_KEY).isNullOrBlank()
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
