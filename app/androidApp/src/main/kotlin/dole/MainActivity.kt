package dole

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import com.russhwolf.settings.SharedPreferencesSettings
import dole.card.AndroidSmartCard
import dole.data.AccountPreferences
import dole.data.AccountRegistry
import dole.data.CardSyncState
import dole.utils.AndroidSecureStorage
import dole.utils.ScreenCaptureProtection
import dole.viewmodel.WalletApp
import dole.viewmodel.WalletViewModel

class MainActivity : FragmentActivity(), NfcAdapter.ReaderCallback {

    private var multicastLock: WifiManager.MulticastLock? = null
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var storagePath: String
    private lateinit var viewModel: WalletViewModel
    private val smartCard = AndroidSmartCard(null)

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!::viewModel.isInitialized) return

            when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    Log.i("DOLE", "Bluetooth off; stopping BLE transport")
                    viewModel.developer.onBluetoothAvailabilityChanged(false)
                }
                BluetoothAdapter.STATE_ON -> {
                    Log.i("DOLE", "Bluetooth back on; restarting BLE transport")
                    viewModel.developer.onBluetoothAvailabilityChanged(true)
                }
            }
        }
    }

    private external fun initNdkContext(context: Context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            System.loadLibrary("core")
        } catch (e: Throwable) {
            Log.e("DOLE", "Failed to load libcore.so", e)
        }

        try {
            initNdkContext(this.applicationContext)
            Log.i("DOLE", "ndk-context initialized")
        } catch (e: Throwable) {
            Log.e("DOLE", "ndk-context init failed", e)
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        storagePath = applicationContext.filesDir.absolutePath
        val prefs = getSharedPreferences("dole_settings", MODE_PRIVATE)
        val settings = SharedPreferencesSettings(prefs)
        val secureStorage = AndroidSecureStorage(this)
        val accountPreferences = AccountPreferences(settings)
        val cardSyncState = CardSyncState(settings)
        val accounts = AccountRegistry(settings, secureStorage, accountPreferences, cardSyncState)
        viewModel = WalletViewModel(accounts, accountPreferences, cardSyncState, settings, smartCard, storagePath)

        ScreenCaptureProtection.bind(this)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        setContent {
            WalletApp(viewModel)
        }

        registerReceiver(bluetoothStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        if (checkPermissions()) startNetworkServices()
    }

    override fun onStart() {
        super.onStart()
        if (::viewModel.isInitialized) viewModel.onAppForeground()
    }

    override fun onStop() {
        if (::viewModel.isInitialized) viewModel.onAppBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        nfcAdapter?.enableReaderMode(this, this, flags, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)

        smartCard.tag = null
        try { smartCard.disconnect() } catch (_: Exception) {}
    }

    override fun onTagDiscovered(tag: Tag?) {
        Log.i("NFC", "Card scanned")
        smartCard.tag = tag
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: IllegalArgumentException) { }
        if (::viewModel.isInitialized) viewModel.stopNetworkServices()
        releaseMulticastLock()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != NETWORK_PERMISSION_REQUEST) return

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }

        if (granted) {
            Log.i("DOLE", "Network permissions granted; starting sync and BLE broadcast")
            startNetworkServices()
        } else {
            Log.w("DOLE", "Network permissions denied; sync engine not started")
        }
    }

    private fun startNetworkServices() {
        acquireMulticastLock()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        viewModel.developer.onBluetoothAvailabilityChanged(adapter?.isEnabled == true)
        viewModel.onNetworkPermissionsGranted()
        Log.i("DOLE", "Network services start requested")
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("dole-p2p-lock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i("DOLE", "MulticastLock acquired")
        } catch (t: Throwable) {
            Log.e("DOLE", "MulticastLock acquire failed", t)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            Log.i("DOLE", "MulticastLock released")
        } catch (t: Throwable) {
            Log.e("DOLE", "MulticastLock release failed", t)
        } finally {
            multicastLock = null
        }
    }

    private fun checkPermissions(): Boolean {
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
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
            Log.i("DOLE", "Requesting network permissions: ${missing.joinToString()}")
            requestPermissions(missing.toTypedArray(), NETWORK_PERMISSION_REQUEST)
            return false
        }

        return true
    }

    companion object {
        private const val NETWORK_PERMISSION_REQUEST = 1001
    }
}
