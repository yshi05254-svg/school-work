plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.ven11.module"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ven11.module"
        minSdk = 27          // 代码路径按 28+（P）设计，27 留给降级验证
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // 反射字段名不可混淆
        }
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
    // libxposed 新 API，仅编译期（宿主 LSPosed 运行时提供）；
    // Maven Central 上可用版本 101.0.0+（评审时写过的 "100" 坐标不存在）
    compileOnly("io.github.libxposed:api:102.0.0")
}
