package com.example.menuui.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 品牌色：换主题色只改这里。语义约定（状态条 / 模块状态卡使用）：
 *  - primary 蓝：品牌与主要操作；
 *  - secondary 绿：正常 / 已同步；
 *  - tertiary 琥珀：需要注意（有未发布改动、签名不符、模块未响应）；
 *  - error 红：失败 / 未安装。
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF185FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6F8),
    onPrimaryContainer = Color(0xFF042C53),
    secondary = Color(0xFF0F6E56),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F0E5),
    onSecondaryContainer = Color(0xFF05392B),
    tertiary = Color(0xFF8A5300),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2BF),
    onTertiaryContainer = Color(0xFF2C1700),
    surface = Color(0xFFFBFBFD),
    surfaceContainerLow = Color(0xFFF2F4F7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF85B7EB),
    onPrimary = Color(0xFF042C53),
    primaryContainer = Color(0xFF0C447C),
    onPrimaryContainer = Color(0xFFD6E6F8),
    secondary = Color(0xFF5DCAA5),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF0B4F3E),
    onSecondaryContainer = Color(0xFFD3F0E5),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFE2BF),
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // true = Android 12+ 跟随壁纸取色
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
