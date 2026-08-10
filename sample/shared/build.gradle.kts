import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * The published version, read from `gradle.properties` so this file cannot drift from the
 * artifacts it documents.
 *
 * The Pikto dependencies below are the real Maven coordinates, exactly as anyone else would write
 * them, so this module can be lifted out of the repo and built on its own. Inside the repo,
 * `settings.gradle.kts` substitutes the local projects for them, so CI compiles the sample against
 * source and any API change that breaks it fails the build immediately.
 */
val piktoVersion: String = providers.gradleProperty("VERSION_NAME").get()

kotlin {
    android {
        namespace = "pikto.sample.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    // One framework holding the whole Compose UI. `iosApp` links against it and calls
    // MainViewController(); there is no other Swift.
    val frameworkName = "ComposeApp"
    iosArm64 { binaries.framework { baseName = frameworkName; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = frameworkName; isStatic = true } }

    sourceSets {
        commonMain.dependencies {
            api("io.github.daniatitienei:pikto-core:$piktoVersion")
            implementation("io.github.daniatitienei:pikto-images:$piktoVersion")
            implementation("io.github.daniatitienei:pikto-video:$piktoVersion")

            api(libs.compose.runtime)
            api(libs.compose.ui)
            implementation(libs.compose.foundation)
            // Via the plugin accessor rather than the catalog: material3 is versioned on its own
            // line and does not follow the compose-multiplatform version.
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
