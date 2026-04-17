import java.util.StringTokenizer

tasks.register("generateConstants") {
    group = "dole"

    val tomlFile = project.rootProject.file("constants.toml")
    val javaFile = project.rootProject.file("card/build/generated/source/constants/java/dole/Constants.java")
    val ktFile = project.rootProject.file("app/shared/build/generated/source/constants/kotlin/dole/Constants.kt")
    val rustFile = project.rootProject.file("core/src/constants.rs")

    inputs.file(tomlFile)
    outputs.files(javaFile, ktFile, rustFile)

    doLast {
        var javaCode = "package dole;\n\npublic final class Constants {\n    private Constants() {}\n"
        var ktCode = "package dole\n\nobject Constants {\n"
        var rustCode = "#![allow(dead_code)]\n\n"

        tomlFile.forEachLine { rawLine ->
            var line = rawLine
            val hashIdx = line.indexOf("#")
            if (hashIdx != -1) line = line.substring(0, hashIdx)
            line = line.trim()

            val eqIdx = line.indexOf("=")
            if (eqIdx != -1) {
                val k = line.substring(0, eqIdx).trim()
                val v = line.substring(eqIdx + 1).trim()

                if (v.startsWith("\"")) {
                    javaCode += "    public static final String $k = $v;\n"
                    ktCode += "    const val $k = $v\n"
                    rustCode += "pub const $k: &str = $v;\n"
                } else if (v.startsWith("[")) {
                    var inner = v
                    if (inner.startsWith("[")) inner = inner.substring(1)
                    if (inner.endsWith("]")) inner = inner.substring(0, inner.length - 1)

                    var javaArr = ""
                    var numArr = ""
                    var count = 0

                    val tokenizer = StringTokenizer(inner, ",")
                    while (tokenizer.hasMoreTokens()) {
                        val token = tokenizer.nextToken().trim()
                        if (token.isNotEmpty()) {
                            if (count > 0) {
                                javaArr += ", "
                                numArr += ", "
                            }
                            javaArr += "(byte)$token"
                            numArr += token
                            count++
                        }
                    }

                    javaCode += "    public static final byte[] $k = { $javaArr };\n"
                    ktCode += "    val $k = byteArrayOf($numArr)\n"
                    rustCode += "pub const $k: [u8; $count] = [$numArr];\n"
                } else {
                    val isShort = k.startsWith("SW_") || k == "CARD_RAM_BUFFER_SIZE" || k == "CARD_MAX_PEERS"
                    val isInt = k == "CLA_PROPRIETARY"

                    val javaType = if (isShort) "short" else if (isInt) "int" else "byte"
                    val ktType = if (isShort) "Short" else if (isInt) "Int" else "Byte"
                    val rsType = if (isShort) "u16" else if (isInt) "u32" else "u8"

                    javaCode += "    public static final $javaType $k = ($javaType) $v;\n"
                    ktCode += "    const val $k: $ktType = $v.to$ktType()\n"
                    rustCode += "pub const $k: $rsType = $v;\n"
                }
            }
        }

        javaCode += "}\n"
        ktCode += "}\n"

        javaFile.parentFile.mkdirs()
        javaFile.writeText(javaCode)

        ktFile.parentFile.mkdirs()
        ktFile.writeText(ktCode)

        rustFile.parentFile.mkdirs()
        rustFile.writeText(rustCode)
    }
}