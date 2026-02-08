plugins {
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
    implementation(project(":common"))
    implementation(libs.json)
    implementation(libs.gson)
    implementation(libs.slf4j.simple)

    implementation(libs.runtime)
    implementation(libs.foundation)
    implementation(libs.material.icons.extended)
    implementation(libs.material3)
    implementation(libs.ui)
    implementation(libs.components.resources)
    implementation(libs.lifecycle.viewmodel.compose)
}