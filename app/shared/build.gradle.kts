import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.about.libraries)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val jdkVersion = libs.versions.java.get().toInt()
val coreDir = file("${project.rootDir}/core")
val generatedConstantsKotlinDir = layout.buildDirectory.dir("generated/source/constants/kotlin")
val uniffiGenDir = layout.buildDirectory.dir("generated/uniffi/kotlin")
val uniffiSwiftGenDir = layout.buildDirectory.dir("generated/uniffi/swift")
val generateConstantsTask = rootProject.tasks.named("generateConstants")

fun Exec.cargoCommand(vararg cargoArgs: String) {
    if (HostManager.hostIsMac) {
        val cargoHome = System.getenv("CARGO_HOME")
            ?: "${System.getProperty("user.home")}/.cargo"

        val cargoExecutable = file("$cargoHome/bin/cargo")

        executable = cargoExecutable.absolutePath
        args(*cargoArgs)
        environment("PATH", "${cargoExecutable.parentFile.absolutePath}:${System.getenv("PATH").orEmpty()}")

        doFirst {
            if (!cargoExecutable.exists()) {
                throw GradleException("cargo not found at ${cargoExecutable.absolutePath}")
            }
        }
    } else {
        commandLine("cargo", *cargoArgs)
    }
}

val buildHostRust = tasks.register<Exec>("buildHostRust") {
    dependsOn(generateConstantsTask)
    workingDir = coreDir
    cargoCommand("build", "--release", "--features", "bench-workload")
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    dependsOn(buildHostRust)
    workingDir = coreDir

    val libName = System.mapLibraryName("core")
    val libPath = "target/release/$libName"

    cargoCommand(
        "run", "--bin", "uniffi-bindgen",
        "--",
        "generate", "--library", libPath, "--language", "kotlin", "--out-dir",
        uniffiGenDir.get().asFile.absolutePath, "--no-format"
    )

    doLast {
        val generatedFile = uniffiGenDir.get().asFile.resolve("dole/core/core.kt")
        if (!generatedFile.exists()) {
            return@doLast
        }

        val source = generatedFile.readText()
        val unsuppressed = "public fun uniffiEnsureInitialized() {\n"
        val suppressed = "@Suppress(\"UNUSED_EXPRESSION\")\n$unsuppressed"
        if (!source.contains(suppressed)) {
            generatedFile.writeText(source.replace(unsuppressed, suppressed))
        }
    }
}

kotlin {
    jvmToolchain(jdkVersion)

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "dole.app.shared"
        compileSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        androidResources.enable = true
    }

    jvm()

    val xcf = XCFramework()
    if (HostManager.hostIsMac) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach {
            it.binaries.framework {
                baseName = "shared"
                isStatic = true
                xcf.add(this)
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            kotlin.srcDir(files(generatedConstantsKotlinDir).builtBy(generateConstantsTask))
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.animation)
                implementation(libs.calf.ui)
                implementation(libs.navigationevent.compose)
                implementation(libs.compose.components.resources)
                implementation(libs.multiplatform.settings)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ditto.kotlin)
            }
        }

        androidMain {
            kotlin.srcDir(files(uniffiGenDir).builtBy(generateUniffiBindings))
            dependencies {
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.biometric)
                implementation(libs.androidx.security.crypto)
                implementation("${libs.jna.get()}@aar")
            }
        }

        jvmMain {
            kotlin.srcDir(files(uniffiGenDir).builtBy(generateUniffiBindings))
            dependencies {
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.ditto.binaries)
            }
        }
    }
}

val syncJvmRustBinaries = tasks.register<Copy>("syncJvmRustBinaries") {
    dependsOn(buildHostRust)
    from("${coreDir}/target/release") {
        include("*.dll", "*.dylib", "*.so")
    }
    into(layout.projectDirectory.dir("src/jvmMain/resources"))
}

val buildAndroidRust = tasks.register<Exec>("buildAndroidRust") {
    dependsOn(generateConstantsTask)
    workingDir = coreDir
    cargoCommand("ndk", "-t", "arm64-v8a", "-o", "${coreDir}/dist/android/jniLibs", "build", "--release", "--features", "bench-workload")
}

val syncAndroidRustBinaries = tasks.register<Copy>("syncAndroidRustBinaries") {
    dependsOn(buildAndroidRust)
    from("${coreDir}/dist/android/jniLibs")
    into(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

val buildIosRust = tasks.register<Exec>("buildIosRust") {
    dependsOn(generateConstantsTask)
    workingDir = coreDir
    environment("IPHONEOS_DEPLOYMENT_TARGET", "26.0")
    cargoCommand("build", "--target", "aarch64-apple-ios", "--release", "--features", "bench-workload")
}

val buildIosSimulatorRust = tasks.register<Exec>("buildIosSimulatorRust") {
    dependsOn(generateConstantsTask)
    workingDir = coreDir
    environment("IPHONEOS_DEPLOYMENT_TARGET", "26.0")
    cargoCommand("build", "--target", "aarch64-apple-ios-sim", "--release", "--features", "bench-workload")
}

val generateUniffiSwiftBindings = tasks.register<Exec>("generateUniffiSwiftBindings") {
    dependsOn(buildIosRust)
    workingDir = coreDir
    cargoCommand(
        "run", "--bin", "uniffi-bindgen",
        "--",
        "generate", "--library", "target/aarch64-apple-ios/release/libcore.a", "--language", "swift", "--out-dir",
        uniffiSwiftGenDir.get().asFile.absolutePath, "--no-format"
    )
}

val syncRustBinaries = tasks.register("syncRustBinaries") {
    dependsOn(syncAndroidRustBinaries, syncJvmRustBinaries)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(syncAndroidRustBinaries)
}

tasks.matching { it.name == "jvmProcessResources" || it.name == "jvmTestProcessResources" || it.name == "runJvm" }.configureEach {
    dependsOn(syncJvmRustBinaries)
}

if (HostManager.hostIsMac) {
    tasks.matching {
        it.name.contains("Ios", ignoreCase = true) && (it.name.contains("XCFramework", ignoreCase = true) || it.name.startsWith("link"))
    }.configureEach {
        dependsOn(generateUniffiSwiftBindings, buildIosSimulatorRust)
    }
}

aboutLibraries {
    library.duplicationMode = DuplicateMode.MERGE
    library.duplicationRule = DuplicateRule.SIMPLE
    export.outputFile = File("src/commonMain/composeResources/files/aboutlibraries.json")
}
