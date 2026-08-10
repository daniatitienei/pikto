rootProject.name = "pikto"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":pikto-core")
include(":pikto-images")
include(":pikto-video")
include(":sample:shared")
include(":sample:androidApp")

/**
 * The sample depends on the published Maven coordinates so its build file reads exactly like a
 * consumer's and can be copied verbatim. Inside this build, point those coordinates at the local
 * projects instead: CI then compiles the sample against source, so an API change that breaks it
 * breaks the build rather than shipping and being noticed by whoever clones the sample next.
 *
 * Run with `-Ppikto.sample.useArtifacts=true` to resolve the real artifacts from Maven Central
 * instead, which is the smoke test that a release is actually consumable.
 */
if (providers.gradleProperty("pikto.sample.useArtifacts").orNull != "true") {
    gradle.beforeProject {
        if (!path.startsWith(":sample")) return@beforeProject
        configurations.all {
            resolutionStrategy.dependencySubstitution {
                substitute(module("io.github.daniatitienei:pikto-core"))
                    .using(project(":pikto-core"))
                substitute(module("io.github.daniatitienei:pikto-images"))
                    .using(project(":pikto-images"))
                substitute(module("io.github.daniatitienei:pikto-video"))
                    .using(project(":pikto-video"))
            }
        }
    }
}
