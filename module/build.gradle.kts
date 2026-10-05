import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 密钥与签名凭据统一在 secret/keys.properties(整个 secret/ 已 gitignore)
val secretProps = Properties().apply {
    val f = rootProject.file("secret/keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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

    // 统一签名(与管理端同证书):签名权限 dev.ven11.module.permission.CONFIG
    // 才会授予同签名的管理端,provider 直写通道依赖它
    signingConfigs {
        create("gogogo") {
            storeFile = rootProject.file(secretProps.getProperty("GOOGO_STORE_FILE", "secret/GoGoGo.jks"))
            storePassword = secretProps.getProperty("GOOGO_STORE_PASSWORD", "")
            keyAlias = secretProps.getProperty("GOOGO_KEY_ALIAS", "")
            keyPassword = secretProps.getProperty("GOOGO_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gogogo")
        }
        release {
            isMinifyEnabled = false // 反射字段名不可混淆
            signingConfig = signingConfigs.getByName("gogogo")
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
