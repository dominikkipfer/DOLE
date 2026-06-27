package dole.ble

import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult

class RustAdvertisingSetCallback : AdvertisingSetCallback() {
    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
        RustBleNative.onAdvertisingSetStarted(advertisingSet, txPower, status)
    }

    override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
        RustBleNative.onAdvertisingDataSet(status)
    }

    override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
        RustBleNative.onAdvertisingSetStopped()
    }
}

class RustScanCallback : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        RustBleNative.onScanResult(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach(RustBleNative::onScanResult)
    }

    override fun onScanFailed(errorCode: Int) {
        RustBleNative.onScanFailed(errorCode)
    }
}

object RustBleNative {
    external fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int)
    external fun onAdvertisingDataSet(status: Int)
    external fun onAdvertisingSetStopped()
    external fun onScanResult(result: ScanResult)
    external fun onScanFailed(errorCode: Int)
}