@file:OptIn(ExperimentalKotlinGradlePluginApi::class)

import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

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

kotlin {
    jvmToolchain(jdkVersion)

    android {
        namespace = "dole.app.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        androidResources.enable = true
    }

    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }

        it.compilations.getByName("main").cinterops.create("core_bindings") {
            defFile(project.file("src/nativeInterop/cinterop/core_bindings.def"))
            compilerOpts("-framework", "CoreBindings", "-F${project.rootDir}/core/dist/apple/")
        }

        it.binaries.all {
            linkerOpts("-framework", "CoreBindings", "-F${project.rootDir}/core/dist/apple/")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain{
            kotlin.srcDir("${project.rootDir}/core/dist/common/kotlin")

            dependencies {
                implementation(project(":common"))

                implementation(libs.runtime)
                implementation(libs.foundation)
                implementation(libs.material.icons.extended)
                implementation(libs.material3)
                implementation(libs.ui)
                implementation(libs.components.resources)

                implementation(libs.multiplatform.settings)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }

        iosMain.dependencies {

        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

val coreDir = file("${project.rootDir}/core")

val buildAndroidRust = tasks.register<Exec>("buildAndroidRust") {
    workingDir = coreDir
    commandLine("boltffi", "pack", "android")
}

val buildAppleRust = tasks.register<Exec>("buildAppleRust") {
    workingDir = coreDir
    commandLine("boltffi", "pack", "apple")
}

val buildJvmRust = tasks.register<Exec>("buildJvmRust") {
    workingDir = coreDir
    commandLine("boltffi", "pack", "jvm")
}

val buildRustBindings = tasks.register("buildRustBindings") {
    dependsOn(buildAndroidRust, buildAppleRust, buildJvmRust)

    inputs.dir(file("${coreDir}/src"))
    inputs.file(file("${coreDir}/Cargo.toml"))
    outputs.dir(file("${coreDir}/dist"))

    doLast {
        println("Rust Bindings wurden erfolgreich für alle Plattformen generiert!")
    }
}

val syncRustBinaries = tasks.register<Copy>("syncRustBinaries") {
    dependsOn(buildRustBindings)

    from("${project.rootDir}/core/dist/android/jniLibs") {
        into("src/androidMain/jniLibs")
    }

    from("${project.rootDir}/core/dist/jvm/lib") {
        into("src/jvmMain/resources/native")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(syncRustBinaries)
}

aboutLibraries {
    library.duplicationMode = DuplicateMode.MERGE
    library.duplicationRule = DuplicateRule.SIMPLE
    export.outputFile = File("src/commonMain/composeResources/files/aboutlibraries.json")
}