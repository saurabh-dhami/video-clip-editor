plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.maven.publish)
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "video-clip-editor-compose", version.toString())

    pom {
        name.set("Video Clip Editor Compose")
        description.set("Shared Compose Multiplatform clip-editor screen with Android Media3 preview integration.")
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
