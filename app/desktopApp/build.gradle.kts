import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets["main"].resources.srcDir("icons")

    dependencies {
        implementation(project(":app:shared"))
        implementation(compose.desktop.currentOs)
        implementation(libs.compose.components.resources)
        implementation(libs.kotlinx.coroutines.swing)
        implementation(libs.multiplatform.settings)
    }
}

compose.desktop {
    application {
        mainClass = "dole.MainKt"

        jvmArgs("-Djna.nosys=true")

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "DOLE"
            packageVersion = "0.0.1"
            description = "DOLE Wallet"
            vendor = "Dominik Kipfer"

            modules("java.desktop", "java.instrument", "java.prefs", "java.smartcardio", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/dole.ico"))
                menuGroup = "DOLE"
                shortcut = true
                upgradeUuid = "8F4C2A16-3D9E-4B1A-9C77-2E5A1B0D6F34"
            }

            macOS {
                iconFile.set(project.file("icons/dole.icns"))
                bundleID = "dole.app"
            }

            linux {
                iconFile.set(project.file("icons/dole.png"))
            }
        }
    }
}

compose.resources {
    packageOfResClass = "dole.generated.resources.desktop"
}
