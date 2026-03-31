package dole

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dole.card.AndroidSmartCard
import dole.ditto.setupDitto
import dole.gui.WalletApp
import dole.gui.WalletViewModel
import dole.ledger.Ledger
import dole.ledger.LedgerService
import dole.wallet.AndroidWalletStorage
import dole.wallet.SettingsService

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: WalletViewModel
    private var nfcAdapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissions()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        try {
            val storage = AndroidWalletStorage(applicationContext)
            val settingsService = SettingsService(storage)
            val ledger = Ledger()
            settingsService.attachLedger(ledger)

            val ditto = setupDitto(Constants.DITTO_APP_ID, Constants.DITTO_PLAYGROUND_TOKEN)
            val ledgerService = LedgerService(ditto)

            viewModel = WalletViewModel(settingsService, ledger, ledgerService)

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            if (tag != null) viewModel.setCardImplementation(AndroidSmartCard(tag))

        } catch (e: Exception) { e.printStackTrace() }

        setContent { if (::viewModel.isInitialized) WalletApp(viewModel) }
    }

    private fun checkPermissions() {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.BLUETOOTH_SCAN)
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missing.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }
        }
        else {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }

        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 0)
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {

            @Suppress("DEPRECATION")
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

            if (tag != null && ::viewModel.isInitialized) {
                try {
                    val card = AndroidSmartCard(tag)
                    viewModel.setCardImplementation(card)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::viewModel.isInitialized) viewModel.logout()
    }
}