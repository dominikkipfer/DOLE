plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.metro)
}

kotlin {
    dependencies {
        implementation(projects.app.shared)
        implementation(compose.desktop.currentOs)
        implementation(libs.components.resources)
        implementation(libs.kotlinx.coroutines.swing)
    }
}

compose.desktop {
    application {
        mainClass = "org.jetbrains.kotlinconf.MainKt"
    }
}

compose.resources {
    packageOfResClass = "org.jetbrains.kotlinconf.generated.resources.desktop"
}
