import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    java
}

val gp: Configuration by configurations.creating

val toolSourceSet: SourceSet = sourceSets.create("tool") {
    java.srcDir("src/tool/java")
}

val sdkUrl = "https://github.com/martinpaljak/oracle_javacard_sdks/archive/3751d774dd.zip"
val antJavacardUrl = "https://github.com/martinpaljak/ant-javacard/releases/latest/download/ant-javacard.jar"
val gpUrl = "https://github.com/martinpaljak/GlobalPlatformPro/releases/latest/download/gp.jar"

val downloadDir: Provider<Directory> = layout.buildDirectory.dir("download")
val sdkZip: Provider<RegularFile> = downloadDir.map { it.file("sdk.zip") }
val antJar: Provider<RegularFile> = downloadDir.map { it.file("ant-javacard.jar") }
val gpJar: Provider<RegularFile> = downloadDir.map { it.file("gp.jar") }

val sdkRoot: Provider<Directory> = layout.buildDirectory.dir("javacard-sdk")
val sdkLib: Provider<RegularFile> = sdkRoot.map { it.dir("jc320v25.1_kit").dir("lib").file("api_classic-3.0.5.jar") }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

val downloadAntJar by tasks.registering {
    val dest = antJar
    val url = antJavacardUrl
    outputs.file(dest)
    doLast {
        val f = dest.get().asFile
        f.parentFile.mkdirs()
        URI.create(url).toURL().openStream().use { input ->
            Files.copy(input, f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val downloadGpJar by tasks.registering {
    val dest = gpJar
    val url = gpUrl
    outputs.file(dest)
    doLast {
        val f = dest.get().asFile
        f.parentFile.mkdirs()
        URI.create(url).toURL().openStream().use { input ->
            Files.copy(input, f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val downloadSdk by tasks.registering {
    val dest = sdkZip
    val url = sdkUrl
    outputs.file(dest)
    doLast {
        val f = dest.get().asFile
        f.parentFile.mkdirs()
        URI.create(url).toURL().openStream().use { input ->
            Files.copy(input, f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val extractSdk by tasks.registering(Copy::class) {
    dependsOn(downloadSdk)
    from(zipTree(sdkZip.map { it.asFile })) {
        eachFile {
            val seg = relativePath.segments
            if (seg.size > 1) relativePath = RelativePath(true, *seg.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    include("**/jc320v25.1_kit/**")
    into(sdkRoot)
}

dependencies {
    implementation(project(":common"))
    "toolImplementation"(project(":common"))

    gp(files(gpJar) {
        builtBy(downloadGpJar)
    })

    compileOnly(files(sdkLib) {
        builtBy(extractSdk)
    })
}

val constantsFile: File = project(":common").file("src/main/java/dole/Constants.java")
val constantsText = if (constantsFile.exists()) constantsFile.readText() else ""
val appletAid = """APPLET_AID_HEX\s*=\s*"([A-Fa-f0-9]+)"""".toRegex().find(constantsText)?.groupValues?.get(1)
val pkgAid = appletAid?.substring(0, 10)

val buildApplet by tasks.registering {
    group = "javacard"

    dependsOn("compileJava")
    dependsOn(downloadAntJar)
    dependsOn(downloadGpJar)

    val sourceDirs = files("src/main/java", project(":common").file("src/main/java"))
    inputs.files(sourceDirs)
    inputs.file(constantsFile)

    val capFile = layout.buildDirectory.file("card.cap")
    outputs.file(capFile)

    val localSdkDir = sdkRoot.map { it.dir("jc320v25.1_kit").asFile.absolutePath }
    val localAntJar = antJar.map { it.asFile.absolutePath }
    val localCapFile = capFile.map { it.asFile.absolutePath }
    val localClassesDir = layout.buildDirectory.dir("classes").map { it.asFile.absolutePath }

    val localPkgAid = pkgAid
    val localAppletAid = appletAid
    val localSources = "${project.projectDir}/src/main/java;${project(":common").projectDir}/src/main/java"

    doLast {
        val sdkPath = localSdkDir.get()
        val antJarPath = localAntJar.get()
        val capPath = localCapFile.get()
        val classesPath = localClassesDir.get()

        File(classesPath).mkdirs()

        this.ant.withGroovyBuilder {
            "taskdef"("name" to "javacard", "classname" to "pro.javacard.ant.JavaCard", "classpath" to antJarPath)

            "javacard"("jckit" to sdkPath) {
                "cap"(
                    "targetsdk" to "3.0.5",
                    "aid" to localPkgAid,
                    "version" to "0.1",
                    "output" to capPath,
                    "sources" to localSources,
                    "classes" to classesPath
                ) {
                    "applet"("class" to "card.Card", "aid" to localAppletAid)
                }
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(downloadAntJar)
    dependsOn(downloadGpJar)
}

mapOf("Minter" to true, "User" to false).forEach { (type, isMinter) ->

    val installTask = tasks.register<Exec>("install$type") {
        dependsOn(buildApplet)

        val capFileProvider = layout.buildDirectory.file("card.cap")
        val gpJarProvider = gpJar
        val params = if (isMinter) "C90101" else "C90100"

        executable = "java"

        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-jar", gpJarProvider.get().asFile.absolutePath,
                "-force",
                "-install", capFileProvider.get().asFile.absolutePath,
                "-params", params,
                "-default",
                "-verbose"
            )
        })
    }

    tasks.register<JavaExec>("setup$type") {
        group = "javacard"
        description = "Setup for $type"
        dependsOn(installTask)
        classpath = toolSourceSet.runtimeClasspath
        mainClass.set("provisioner.Provisioner")
        standardInput = System.`in`
    }
}