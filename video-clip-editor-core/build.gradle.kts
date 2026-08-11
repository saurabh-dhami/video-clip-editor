import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "video-clip-editor-core", version.toString())

    pom {
        name.set("Video Clip Editor Core")
        description.set("Kotlin Multiplatform contracts and Android engine for local MP4 video clipping.")
        inceptionYear.set("2026")
        url.set("https://github.com/saurabh-dhami/video-clip-editor")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("saurabh-dhami")
                name.set("Saurabh Dhami")
                url.set("https://github.com/saurabh-dhami")
            }
        }
        scm {
            url.set("https://github.com/saurabh-dhami/video-clip-editor")
            connection.set("scm:git:git://github.com/saurabh-dhami/video-clip-editor.git")
            developerConnection.set("scm:git:ssh://git@github.com/saurabh-dhami/video-clip-editor.git")
        }
    }
}
