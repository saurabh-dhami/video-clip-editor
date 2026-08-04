plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    android {
        namespace = "com.oneononearena.videoclip"
        compileSdk = 36
        minSdk = 23
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
}
