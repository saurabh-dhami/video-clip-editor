plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "com.oneononearena.videoclip.compose"
        compileSdk = 36
        minSdk = 23
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets.commonMain.dependencies {
        implementation(project(":video-clip-editor-core"))
        implementation(libs.kotlinx.coroutines.core)
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    }
}
