plugins {
    `java-library`
}

val jdkVersion = libs.versions.javaCard.get().toInt()

java {
    sourceCompatibility = JavaVersion.toVersion(jdkVersion)
    targetCompatibility = JavaVersion.toVersion(jdkVersion)
}