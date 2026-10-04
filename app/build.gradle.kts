import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // Kotlin 2.0 起 Compose 编译器随 Kotlin 版本走
}

// 本机配置（local.properties 已 gitignore）：百度地图 AK 等不进仓库的值从这里读
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.menuui"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.menuui"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 百度地图 Android AK：编译前在 local.properties 写一行 BAIDU_MAP_KEY=你的AK
        // （lbsyun.baidu.com 控制台创建 Android 应用，填包名 com.example.menuui
        // + 签名 SHA1，debug/release 各一）。留空也能编译，地图选点页会提示"未配置 Key"。
        manifestPlaceholders["BAIDU_MAP_KEY"] = localProps.getProperty("BAIDU_MAP_KEY", "")

        // 只打包真机常用的两种 ABI（百度 SDK 另带 x86 的 so，模拟器用不到）
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    // 百度地图（地图选点）；SDK 坐标系设为 GCJ-02，进出都经 geo.CoordTransform 转换
    implementation("com.baidu.lbsyun:BaiduMapSDK_Map:8.2.0")

    testImplementation("junit:junit:4.13.2")
}
