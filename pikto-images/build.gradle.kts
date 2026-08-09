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
        namespace = "pikto.images"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        withHostTest { }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.piktoCore)
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    // Only in CI: a local publishToMavenLocal must not be blocked by the absence of keys.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    pom {
        name = "Pikto Images"
        description = "Compose Multiplatform photo gallery images: cached thumbnail and " +
            "full-size decoding of the device photo library into ImageBitmaps, with composables " +
            "for showing and prefetching them on Android and iOS."
    }
}
