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
        androidResources {
            enable = true
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
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
        implementation(libs.compose.ui.test)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.media3.exoplayer)
        implementation(libs.media3.ui.compose)
    }
    sourceSets.named("androidDeviceTest") {
        dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.ui.compose)
            implementation(libs.compose.ui.test)
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
