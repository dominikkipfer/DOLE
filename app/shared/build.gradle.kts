@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.about.libraries)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

val jdkVersion = libs.versions.java.get().toInt()
val coreDir = file("${project.rootDir}/core")
val uniffiGenDir = file("${layout.buildDirectory.get()}/generated/uniffi/kotlin")

kotlin {
    jvmToolchain(jdkVersion)

    android {
        namespace = "dole.app.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        androidResources.enable = true
    }

    jvm()

    if (HostManager.hostIsMac) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework {
                baseName = "shared"
                isStatic = true
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            kotlin.srcDir(layout.buildDirectory.dir("generated/source/constants/kotlin"))
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.animation)
                implementation(libs.compose.components.resources)
                implementation(libs.multiplatform.settings)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        androidMain {
            kotlin.srcDir(uniffiGenDir)
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation("${libs.jna.get()}@aar")
            }
        }

        jvmMain {
            kotlin.srcDir(uniffiGenDir)
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
            }
        }
    }
}

val buildJvmRust = tasks.register<Exec>("buildJvmRust") {
    dependsOn(rootProject.tasks.named("generateConstants"))
    workingDir = coreDir
    commandLine("cargo", "build", "--release")
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    dependsOn(buildJvmRust)
    workingDir = coreDir

    val libName = System.mapLibraryName("core")
    val libPath = "target/release/$libName"

    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen",
        "--",
        "generate", "--library", libPath, "--language", "kotlin", "--out-dir", uniffiGenDir.absolutePath, "--no-format"
    )
}

val buildAndroidRust = tasks.register<Exec>("buildAndroidRust") {
    dependsOn(generateUniffiBindings)
    workingDir = coreDir
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "-o", "${coreDir}/dist/android/jniLibs", "build", "--release")
}

val syncJvmRustBinaries = tasks.register<Copy>("syncJvmRustBinaries") {
    dependsOn(buildJvmRust)
    from("${coreDir}/target/release") {
        include("*.dll", "*.dylib", "*.so")
    }
    into(layout.projectDirectory.dir("src/jvmMain/resources"))
}

val syncAndroidRustBinaries = tasks.register<Copy>("syncAndroidRustBinaries") {
    dependsOn(buildAndroidRust)
    from("${coreDir}/dist/android/jniLibs")
    into(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

val syncRustBinaries = tasks.register("syncRustBinaries") {
    dependsOn(syncAndroidRustBinaries, syncJvmRustBinaries)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(generateUniffiBindings, syncRustBinaries, rootProject.tasks.named("generateConstants"))
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(syncAndroidRustBinaries)
}

tasks.matching { it.name == "jvmProcessResources" || it.name == "jvmTestProcessResources" }.configureEach {
    dependsOn(syncJvmRustBinaries)
}

aboutLibraries {
    library.duplicationMode = DuplicateMode.MERGE
    library.duplicationRule = DuplicateRule.SIMPLE
    export.outputFile = File("src/commonMain/composeResources/files/aboutlibraries.json")
}