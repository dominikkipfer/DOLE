plugins {
    alias(libs.plugins.about.libraries) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.kotlin.plugin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("generateConstants") {
    group = "dole"

    val confFile = rootProject.file("constants.conf")
    val javaFile = rootProject.file("card/build/generated/source/constants/java/dole/Constants.java")
    val ktFile = rootProject.file("app/shared/build/generated/source/constants/kotlin/dole/Constants.kt")
    val rustFile = rootProject.file("core/src/constants.rs")
    val hubRustFile = rootProject.file("hub/src/constants.rs")

    inputs.file(confFile)
    outputs.files(javaFile, ktFile, rustFile, hubRustFile)

    doLast {
        var javaCode = "package dole;\n\npublic final class Constants {\n    private Constants() {}\n"
        var ktCode = "package dole\n\nobject Constants {\n"
        var rustCode = "#![allow(dead_code)]\n\n"

        val constantsMap = mutableMapOf<String, Int>()
        val rawLines = confFile.readLines().map { it.substringBefore("#").trim() }
        val mergedLines = mutableListOf<String>()

        var currentLine = ""
        var inArray = false

        for (line in rawLines) {
            if (line.isEmpty()) continue
            if (!inArray && line.startsWith("[") && line.endsWith("]")) continue

            if (inArray) {
                currentLine += " $line"
                if (line.contains("]")) {
                    mergedLines.add(currentLine)
                    currentLine = ""
                    inArray = false
                }
            } else if (line.contains("=")) {
                val rightSide = line.substringAfter("=").trim()
                if (rightSide.startsWith("[") && !rightSide.contains("]")) {
                    currentLine = line
                    inArray = true
                } else {
                    mergedLines.add(line)
                }
            }
        }

        for (line in mergedLines) {
            val eqIdx = line.indexOf("=")
            if (eqIdx == -1) continue

            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim()

            if (value.startsWith("\"")) {
                javaCode += "    public static final String $key = $value;\n"
                ktCode += "    const val $key = $value\n"
                rustCode += "pub const $key: &str = $value;\n"
            } else if (value.startsWith("[")) {
                val inner = value.removeSurrounding("[", "]").trim()
                val tokens = inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val javaArr = tokens.joinToString(", ") { "(byte)$it" }
                val ktArr = tokens.joinToString(", ") { "$it.toByte()" }
                val numArr = tokens.joinToString(", ")

                javaCode += "    public static final byte[] $key = { $javaArr };\n"
                ktCode += "    val $key = byteArrayOf($ktArr)\n"
                rustCode += "#[rustfmt::skip]\npub const $key: [u8; ${tokens.size}] = [$numArr];\n"
            } else {
                var sum = 0
                value.split("+").forEach { part ->
                    val token = part.trim()
                    if (token.startsWith("0x")) {
                        sum += token.substring(2).toInt(16)
                    } else if (token.toIntOrNull() != null) {
                        sum += token.toInt()
                    } else if (constantsMap.containsKey(token)) {
                        sum += constantsMap[token]!!
                    }
                }

                constantsMap[key] = sum
                val evalVal = if (value.contains("0x")) "0x" + sum.toString(16).uppercase() else sum.toString()

                val isInt = key == "CLA_PROPRIETARY" || sum > 0xFFFF
                val isShort = !isInt &&
                    (key.startsWith("SW_") || key == "CARD_RAM_BUFFER_SIZE" || key == "CARD_MAX_PEERS" || sum > 0xFF)

                val javaType = if (isShort) "short" else if (isInt) "int" else "byte"
                val ktType = if (isShort) "Short" else if (isInt) "Int" else "Byte"
                val rsType = if (isShort) "u16" else if (isInt) "u32" else "u8"

                javaCode += "    public static final $javaType $key = ($javaType) $evalVal;\n"
                ktCode += "    const val $key: $ktType = $evalVal.to$ktType()\n"
                rustCode += "pub const $key: $rsType = $evalVal;\n"
            }
        }

        javaCode += "}\n"
        ktCode += "}\n"

        javaFile.apply { parentFile.mkdirs(); writeText(javaCode) }
        ktFile.apply { parentFile.mkdirs(); writeText(ktCode) }
        rustFile.apply { parentFile.mkdirs(); writeText(rustCode) }
        hubRustFile.apply { parentFile.mkdirs(); writeText(rustCode) }
    }
}
