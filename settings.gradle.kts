@file:Suppress("UnstableApiUsage")

rootProject.name = "DOLE"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val isJetBrains = System.getProperty("idea.vendor.name") == "JetBrains"

include(":card")

include(":app:shared")
include(":app:desktopApp")
if (!isJetBrains) include(":app:androidApp")