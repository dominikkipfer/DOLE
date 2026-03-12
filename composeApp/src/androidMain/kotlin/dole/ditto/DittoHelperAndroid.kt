package dole.ditto

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoFactory

actual fun createDittoPlatform(config: DittoConfig): Ditto {
    return DittoFactory.create(config = config)
}

actual fun getCustomPersistenceDir(): String? {
    return null
}