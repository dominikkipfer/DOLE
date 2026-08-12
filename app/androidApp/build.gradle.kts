import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.plugin.compose)
}

val jdkVersion = libs.versions.java.get()

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jdkVersion))
    }
}

dependencies {
    implementation(libs.androidx.fragment.ktx)
    implementation(project(":app:shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.multiplatform.settings)
}

android {
    namespace = "dole.app"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jdkVersion.toInt())
        targetCompatibility = JavaVersion.toVersion(jdkVersion.toInt())
    }

    signingConfigs {
        create("ciRelease") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "dole.app"
        targetSdk = libs.versions.android.compile.sdk.get().toInt()
        minSdk = libs.versions.android.min.sdk.get().toInt()
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt")
            )
            if (!System.getenv("ANDROID_KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
    }
}
