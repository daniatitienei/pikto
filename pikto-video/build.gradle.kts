import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "pikto.video"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.piktoCore)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            // LocalLifecycleOwner, so playback pauses when the app leaves the foreground.
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        androidMain.dependencies {
            api(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.ui)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    // Only in CI: a local publishToMavenLocal must not be blocked by the absence of keys.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name = "Pikto Video"
        description = "Compose Multiplatform video player for clips in the device photo " +
            "library, with next-clip preloading, backed by ExoPlayer on Android and AVPlayer " +
            "on iOS."
    }
}
