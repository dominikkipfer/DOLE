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
    outputs.file(antJar)
    doLast {
        val f = antJar.get().asFile
        mkdir(f.parentFile)
        ant.withGroovyBuilder { "get"("src" to antJavacardUrl, "dest" to f) }
    }
}

val downloadGpJar by tasks.registering {
    outputs.file(gpJar)
    doLast {
        val f = gpJar.get().asFile
        mkdir(f.parentFile)
        ant.withGroovyBuilder { "get"("src" to gpUrl, "dest" to f, "verbose" to "true") }
    }
}

val downloadSdk by tasks.registering {
    outputs.file(sdkZip)
    doLast {
        val f = sdkZip.get().asFile
        mkdir(f.parentFile)
        ant.withGroovyBuilder { "get"("src" to sdkUrl, "dest" to f) }
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

    doLast {
        val sdkPath = sdkRoot.get().dir("jc320v25.1_kit").asFile.absolutePath
        val antJarPath = antJar.get().asFile.absolutePath

        ant.withGroovyBuilder {
            "taskdef"("name" to "javacard", "classname" to "pro.javacard.ant.JavaCard", "classpath" to antJarPath)

            mkdir(layout.buildDirectory.dir("classes"))

            "javacard"("jckit" to sdkPath) {
                "cap"("targetsdk" to "3.0.5", "aid" to pkgAid, "version" to "0.1",
                    "output" to capFile.get().asFile.absolutePath,
                    "sources" to "${project.projectDir}/src/main/java;${project(":common").projectDir}/src/main/java",
                    "classes" to layout.buildDirectory.dir("classes").get().asFile.absolutePath) {
                    "applet"("class" to "card.Card", "aid" to appletAid)
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

        val capPath = layout.buildDirectory.file("card.cap").get().asFile.absolutePath
        val gpPath = gpJar.get().asFile.absolutePath
        val params = if (isMinter) "C90101" else "C90100"

        commandLine("java", "-jar", gpPath,
            "-force",
            "-install", capPath,
            "-params", params,
            "-default",
            "-verbose")
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