import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    android {
        namespace = "com.oneononearena.videoclip"
        compileSdk = 36
        minSdk = 23
        androidResources {
            enable = true
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }
    val xcf = XCFramework("VideoClipEditorCore")

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "VideoClipEditorCore"
            isStatic = true
            export(libs.kotlinx.coroutines.core)
            xcf.add(this)
        }
    }

    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.media3.transformer)
    }
    sourceSets.named("androidDeviceTest") {
        dependencies {
            implementation(libs.media3.transformer)
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
    }
}
