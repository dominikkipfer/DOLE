tasks.register("generateConstants") {
    group = "dole"

    val confFile = project.rootProject.file("constants.conf")
    val javaFile = project.rootProject.file("card/build/generated/source/constants/java/dole/Constants.java")
    val ktFile = project.rootProject.file("app/shared/build/generated/source/constants/kotlin/dole/Constants.kt")
    val rustFile = project.rootProject.file("core/src/constants.rs")

    inputs.file(confFile)
    outputs.files(javaFile, ktFile, rustFile)

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
                currentLine += " " + line
                if (line.contains("]")) {
                    mergedLines.add(currentLine)
                    currentLine = ""
                    inArray = false
                }
            } else {
                if (line.contains("=")) {
                    val rightSide = line.substringAfter("=").trim()
                    if (rightSide.startsWith("[") && !rightSide.contains("]")) {
                        currentLine = line
                        inArray = true
                    } else {
                        mergedLines.add(line)
                    }
                }
            }
        }

        for (line in mergedLines) {
            val eqIdx = line.indexOf("=")
            if (eqIdx == -1) continue

            val k = line.substring(0, eqIdx).trim()
            val v = line.substring(eqIdx + 1).trim()

            if (v.startsWith("\"")) {
                javaCode += "    public static final String $k = $v;\n"
                ktCode += "    const val $k = $v\n"
                rustCode += "pub const $k: &str = $v;\n"
            } else if (v.startsWith("[")) {
                val inner = v.removeSurrounding("[", "]").trim()
                val tokens = inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val javaArr = tokens.joinToString(", ") { "(byte)$it" }
                val ktArr = tokens.joinToString(", ") { "$it.toByte()" }
                val numArr = tokens.joinToString(", ")

                javaCode += "    public static final byte[] $k = { $javaArr };\n"
                ktCode += "    val $k = byteArrayOf($ktArr)\n"
                rustCode += "pub const $k: [u8; ${tokens.size}] = [$numArr];\n"
            } else {
                var sum = 0
                v.split("+").forEach { t ->
                    val token = t.trim()
                    if (token.startsWith("0x")) {
                        sum += token.substring(2).toInt(16)
                    } else if (token.toIntOrNull() != null) {
                        sum += token.toInt()
                    } else if (constantsMap.containsKey(token)) {
                        sum += constantsMap[token]!!
                    }
                }

                constantsMap[k] = sum
                val evalVal = if (v.contains("0x")) "0x" + sum.toString(16).uppercase() else sum.toString()

                val isShort = k.startsWith("SW_") || k == "CARD_RAM_BUFFER_SIZE" || k == "CARD_MAX_PEERS"
                val isInt = k == "CLA_PROPRIETARY"

                val javaType = if (isShort) "short" else if (isInt) "int" else "byte"
                val ktType = if (isShort) "Short" else if (isInt) "Int" else "Byte"
                val rsType = if (isShort) "u16" else if (isInt) "u32" else "u8"

                javaCode += "    public static final $javaType $k = ($javaType) $evalVal;\n"
                ktCode += "    const val $k: $ktType = $evalVal.to$ktType()\n"
                rustCode += "pub const $k: $rsType = $evalVal;\n"
            }
        }

        javaCode += "}\n"
        ktCode += "}\n"

        javaFile.apply { parentFile.mkdirs(); writeText(javaCode) }
        ktFile.apply { parentFile.mkdirs(); writeText(ktCode) }
        rustFile.apply { parentFile.mkdirs(); writeText(rustCode) }
    }
}