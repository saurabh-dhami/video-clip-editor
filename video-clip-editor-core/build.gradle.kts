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
}
