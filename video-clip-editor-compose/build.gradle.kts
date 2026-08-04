plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
}

android {
    namespace = "com.oneononearena.videoclip.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    add("commonMainImplementation", project(":video-clip-editor-core"))
}
