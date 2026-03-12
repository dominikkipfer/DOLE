package dole.ditto

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoFactory
import java.io.File

actual fun createDittoPlatform(config: DittoConfig): Ditto {
    return DittoFactory.create(config = config)
}

actual fun getCustomPersistenceDir(): String? {
    val userHome = System.getProperty("user.home")
    val dittoDir = File(userHome, ".dole/ditto")
    if (!dittoDir.exists()) dittoDir.mkdirs()
    return dittoDir.absolutePath
}