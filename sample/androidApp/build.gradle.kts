plugins {
    alias(libs.plugins.androidApplication)
}

/**
 * A shell: a manifest, a theme and one activity. Everything on screen comes from
 * `:sample:shared`, which is where the Pikto calls live.
 *
 * It is a separate module because since AGP 9 the `com.android.application` plugin cannot be
 * applied to a module that also applies Kotlin Multiplatform.
 */
android {
    namespace = "pikto.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "pikto.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(projects.sample.shared)
    // For installPikto(), which is the one Android-only call an app has to make.
    implementation("io.github.daniatitienei:pikto-core:${providers.gradleProperty("VERSION_NAME").get()}")
    implementation(libs.androidx.activity.compose)
}
