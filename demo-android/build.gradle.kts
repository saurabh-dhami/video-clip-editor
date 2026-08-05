plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

android {
    namespace = "com.oneononearena.videoclip.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oneononearena.videoclip.demo"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation(project(":video-clip-editor-core"))
    implementation(project(":video-clip-editor-compose"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
