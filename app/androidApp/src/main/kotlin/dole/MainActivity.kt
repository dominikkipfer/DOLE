package dole

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dole.ui.screens.WalletApp
import dole.ui.screens.WalletViewModel

class MainActivity : ComponentActivity() {

    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        acquireMulticastLock()

        val storagePath = applicationContext.filesDir.absolutePath
        val viewModel = WalletViewModel(storagePath)

        setContent {
            WalletApp(viewModel)
        }
    }

    override fun onDestroy() {
        releaseMulticastLock()
        super.onDestroy()
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("dole-p2p-lock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i("DOLE_P2P", "MulticastLock acquired")
        } catch (t: Throwable) {
            Log.e("DOLE_P2P", "Failed to acquire MulticastLock", t)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            Log.i("DOLE_P2P", "MulticastLock released")
        } catch (t: Throwable) {
            Log.e("DOLE_P2P", "Failed to release MulticastLock", t)
        } finally {
            multicastLock = null
        }
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 0)
        }
    }
}