import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.language.jvm.tasks.ProcessResources

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

sourceSets {
    main {
        val coreRoot = project(":client-core").projectDir

        java.srcDirs("src/main/java", coreRoot.resolve("src/main/java"))
        kotlin.srcDirs("src/main/kotlin", coreRoot.resolve("src/main/kotlin"))
        resources.srcDirs("src/main/resources", coreRoot.resolve("src/main/res"))
    }
}

dependencies {
    implementation(project(":common"))
    implementation(libs.runtime)
    implementation(libs.foundation)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.ui)
    implementation(libs.components.resources)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.slf4j.simple)
    implementation(libs.json)
    implementation(libs.gson)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.ditto.java)
    implementation(libs.ditto.binaries)

    testImplementation(libs.junit.jupiter)
}

compose.desktop {
    application {
        mainClass = "dole.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "DittoWallet"
            packageVersion = "1.0.0"
        }
    }
}

tasks.withType<ProcessResources>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}