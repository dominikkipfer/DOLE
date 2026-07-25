import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.file.RelativePath
import org.gradle.process.CommandLineArgumentProvider

plugins {
    java
}

val gp = configurations.create("gp")

val toolSourceSet: SourceSet = sourceSets.create("tool") {
    java.srcDir("src/tool/java")

    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val sdkUrl = "https://codeload.github.com/martinpaljak/oracle_javacard_sdks/zip/refs/heads/master"
val antJavacardUrl = "https://github.com/martinpaljak/ant-javacard/releases/latest/download/ant-javacard.jar"
val gpUrl = "https://github.com/martinpaljak/GlobalPlatformPro/releases/latest/download/gp.jar"

val downloadDir: Provider<Directory> = layout.buildDirectory.dir("download")
val sdkZip: Provider<RegularFile> = downloadDir.map { it.file("sdk.zip") }
val antJar: Provider<RegularFile> = downloadDir.map { it.file("ant-javacard.jar") }
val gpJar: Provider<RegularFile> = downloadDir.map { it.file("gp.jar") }

val javaCardKitVersion = libs.versions.javaCardKit.get()
val javaCardApiVersion = libs.versions.javaCardApi.get()
val sdkArchiveDirName = "jc320v${javaCardKitVersion}_kit"
val sdkRoot: Provider<Directory> = layout.buildDirectory.dir("javacard-sdk")
val sdkKit: Provider<Directory> = sdkRoot.map { it.dir("kit") }
val sdkLib: Provider<RegularFile> = sdkKit.map { it.dir("lib").file("api_classic-$javaCardApiVersion.jar") }
val jdkVersion = libs.versions.java.get().toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/source/constants/java"))
    }
}

val downloadAntJar = tasks.register("downloadAntJar") {
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

val downloadGpJar = tasks.register("downloadGpJar") {
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

val downloadSdk = tasks.register("downloadSdk") {
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

val extractSdk = tasks.register<Copy>("extractSdk") {
    dependsOn(downloadSdk)

    from(zipTree(sdkZip.map { it.asFile })) {
        include("**/$sdkArchiveDirName/**")

        eachFile {
            val seg = relativePath.segments
            val kitIndex = seg.indexOf(sdkArchiveDirName)

            if (kitIndex >= 0) {
                val afterKit = seg.drop(kitIndex + 1)

                if (afterKit.isNotEmpty()) {
                    relativePath = RelativePath(!isDirectory, "kit", *afterKit.toTypedArray())
                } else {
                    exclude()
                }
            } else {
                exclude()
            }
        }
        
        includeEmptyDirs = false
    }

    into(sdkRoot)
}

dependencies {
    add(gp.name, files(gpJar) {
        builtBy(downloadGpJar)
    })

    compileOnly(files(sdkLib) {
        builtBy(extractSdk)
    })
}

val confFile: File = rootProject.file("constants.conf")
val tomlText = if (confFile.exists()) confFile.readText() else ""
val appletAid = """APPLET_AID_HEX\s*=\s*"([A-Fa-f0-9]+)"""".toRegex().find(tomlText)?.groupValues?.get(1)
val pkgAid = appletAid?.substring(0, 10)

val ndefModuleAid = pkgAid?.plus("02")
val ndefInstanceAid = "D2760000850101"

val buildApplet = tasks.register("buildApplet") {
    group = "javacard"

    dependsOn("compileJava")
    dependsOn(extractSdk)
    dependsOn(downloadAntJar)
    dependsOn(downloadGpJar)
    dependsOn(rootProject.tasks.named("generateConstants"))

    val sourceDirs = files("src/main/java", layout.buildDirectory.dir("generated/source/constants/java"))
    inputs.files(sourceDirs)
    inputs.file(confFile)

    val capFile = layout.buildDirectory.file("card.cap")
    outputs.file(capFile)

    val localSdkDir = sdkKit.map { it.asFile.absolutePath }
    val localAntJar = antJar.map { it.asFile.absolutePath }
    val localCapFile = capFile.map { it.asFile.absolutePath }
    val localClassesDir = layout.buildDirectory.dir("classes").map { it.asFile.absolutePath }

    val localPkgAid = pkgAid
    val localAppletAid = appletAid
    val localNdefModuleAid = ndefModuleAid
    val localSources = "${project.projectDir}/src/main/java;${layout.buildDirectory.get().asFile.absolutePath}/generated/source/constants/java"

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
                    "targetsdk" to javaCardApiVersion,
                    "aid" to localPkgAid,
                    "version" to "0.1",
                    "output" to capPath,
                    "sources" to localSources,
                    "classes" to classesPath,
                    "ints" to "true"
                ) {
                    "applet"("class" to "card.Card", "aid" to localAppletAid)
                    "applet"("class" to "card.Ndef", "aid" to localNdefModuleAid)
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
        val walletAid = appletAid

        executable = "java"

        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-jar", gpJarProvider.get().asFile.absolutePath,
                "-force",
                "-install", capFileProvider.get().asFile.absolutePath,
                "-applet", walletAid,
                "-params", params,
                "-default",
                "-verbose"
            )
        })
    }

    val installNdefTask = tasks.register<Exec>("installNdef$type") {
        dependsOn(installTask)

        val gpJarProvider = gpJar
        val localPkgAid = pkgAid
        val localNdefModuleAid = ndefModuleAid
        val localNdefInstanceAid = ndefInstanceAid

        executable = "java"

        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-jar", gpJarProvider.get().asFile.absolutePath,
                "-package", localPkgAid,
                "-applet", localNdefModuleAid,
                "-create", localNdefInstanceAid,
                "-verbose"
            )
        })
    }

    tasks.register<JavaExec>("setup$type") {
        group = "javacard"
        description = "Setup for $type"
        dependsOn(installNdefTask)
        classpath = toolSourceSet.runtimeClasspath
        mainClass.set("provisioner.Provisioner")
        standardInput = System.`in`
    }
}

tasks.named("compileJava") {
    dependsOn(extractSdk)
    dependsOn(rootProject.tasks.named("generateConstants"))
}

tasks.named("compileToolJava") {
    dependsOn(rootProject.tasks.named("generateConstants"))
}