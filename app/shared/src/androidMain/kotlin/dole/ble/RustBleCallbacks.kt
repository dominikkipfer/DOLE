package dole.ble

import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult

class AdvertisingSetCallback : AdvertisingSetCallback() {
    override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
        BleNative.onAdvertisingSetStarted(advertisingSet, txPower, status)
    }

    override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) {
        BleNative.onAdvertisingDataSet(status)
    }

    override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
        BleNative.onAdvertisingSetStopped()
    }
}

class ScanCallback : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        BleNative.onScanResult(result)
    }

    override fun onBatchScanResults(results: MutableList<ScanResult>) {
        results.forEach(BleNative::onScanResult)
    }

    override fun onScanFailed(errorCode: Int) {
        BleNative.onScanFailed(errorCode)
    }
}

object BleNative {
    external fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int)
    external fun onAdvertisingDataSet(status: Int)
    external fun onAdvertisingSetStopped()
    external fun onScanResult(result: ScanResult)
    external fun onScanFailed(errorCode: Int)
}