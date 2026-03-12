package dole.ditto

import com.ditto.kotlin.Ditto
import com.ditto.kotlin.DittoConfig
import com.ditto.kotlin.DittoAuthenticationProvider
import dole.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

expect fun createDittoPlatform(config: DittoConfig): Ditto
expect fun getCustomPersistenceDir(): String?

private var dittoInstance: Ditto? = null

fun setupDitto(appId: String, token: String): Ditto {
    if (dittoInstance != null) return dittoInstance!!

    val config = DittoConfig(
        databaseId = appId,
        connect = DittoConfig.Connect.Server(url = Constants.DITTO_AUTH_URL),
        persistenceDirectory = getCustomPersistenceDir()
    )

    val ditto = createDittoPlatform(config)

    ditto.updateTransportConfig { transportConfig ->
        transportConfig.peerToPeer.lan.enabled = true
        transportConfig.peerToPeer.bluetoothLe.enabled = true
        transportConfig.peerToPeer.wifiAware.enabled = true
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            ditto.auth?.expirationHandler = { dittoInst, _ ->
                dittoInst.auth?.login(token, DittoAuthenticationProvider.development())
            }

            ditto.auth?.login(token, DittoAuthenticationProvider.development())
            ditto.sync.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    dittoInstance = ditto
    return ditto
}