// 加到 app/build.gradle.kts 的 dependencies { } 中（版本号请用最新稳定版）
// 同时确保 buildFeatures { compose = true } 并已应用 Compose 编译器插件

implementation(platform("androidx.compose:compose-bom:<最新版本>"))
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.material:material-icons-core")
implementation("androidx.compose.ui:ui-tooling-preview")
debugImplementation("androidx.compose.ui:ui-tooling")
implementation("androidx.activity:activity-compose:<最新版本>")
implementation("androidx.navigation:navigation-compose:<最新版本>")
