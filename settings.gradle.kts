pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

    }
}

rootProject.name = "CarLink"

// The dedicated USB CarPlay APK.
include(":app")

// Generates the app's baseline and startup profiles and benchmarks them (Macrobenchmark,
// docs/LOW_END_PERFORMANCE.md "Round 2"). Runs on a CarLink emulator; never part of the APK.
include(":baselineprofile")

// The connection backend as a reusable library (detection, sessions, engine adapters, settings).
include(":carlink-core")

// CarPlay engine, imported from DiPlay (git subtree at vendor/diplay).
include(":carplay-shared")
project(":carplay-shared").projectDir = file("vendor/diplay/shared")
include(":carplay-common")
project(":carplay-common").projectDir = file("vendor/diplay/common")

// Isolated wireless compatibility experiment; no dependency from the wired APK.
include(":wireless-lab")
