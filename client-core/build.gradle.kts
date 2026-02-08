plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    api(project(":common"))

    api(libs.runtime)
    api(libs.foundation)
    api(libs.material3)
    api(libs.material.icons.extended)
    api(libs.ui)
    api(libs.components.resources)
    api(libs.ui.tooling.preview)
    api(libs.lifecycle.viewmodel.compose)

    api(libs.slf4j.simple)
    api(libs.json)
    implementation(libs.gson)
}

compose.resources {
    publicResClass = true
    packageOfResClass = "dole.resources"
}