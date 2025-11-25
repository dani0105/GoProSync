package website.danielrojas.goprosync.recorders

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import website.danielrojas.goprosync.network.BleEventListener
import website.danielrojas.goprosync.network.Bluetooth
import website.danielrojas.goprosync.utils.GOPRO_UUID
import website.danielrojas.goprosync.utils.GoProUUID
import website.danielrojas.goprosync.utils.isNotifiable
import website.danielrojas.goprosync.utils.toHexString
import java.util.UUID
import kotlin.collections.listOf

@OptIn(ExperimentalUnsignedTypes::class)
class GoProRecorder {

    private var ble:Bluetooth
    lateinit var goproAddress: String
    private val receivedData: Channel<UByteArray>
    private val scanFilters: List<ScanFilter>

    constructor(context: Context){
        this.ble = Bluetooth.Companion.getInstance(context)
        this.scanFilters = listOf<ScanFilter>(
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(GOPRO_UUID)).build()
        )
        this.receivedData = Channel()
    }

    private fun naiveNotificationHandler(characteristic: UUID, data: UByteArray) {
        Log.d("naiveNotificationHandler","Received response on $characteristic: ${data.toHexString()}")
        if ((characteristic == GoProUUID.CQ_COMMAND_RSP.uuid)) {
            Log.d("naiveNotificationHandler","Command status received")
            CoroutineScope(Dispatchers.IO).launch { receivedData.send(data) }
        }
    }

    private val bleListeners by lazy {
        BleEventListener().apply {
            onNotification = ::naiveNotificationHandler
        }
    }

    private fun checkStatus(data: UByteArray) :Boolean {
        if (data[2].toUInt() == 0U) {
            Log.i("checkStatus","Command sent successfully ${data.toHexString()}")
            return true
        }
        else {
            Log.e("CheckStatus","Command Failed ${data.toHexString()}")
            return false
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    suspend fun detect(){
        ble.startScan(scanFilters).onSuccess { scanResults ->
            Log.i("startGoProRecording","Scanning for GoPro's")
            val deviceChannel: Channel<BluetoothDevice> = Channel()
            // Collect scan results
            CoroutineScope(Dispatchers.IO).launch {
                scanResults.collect { scanResult ->
                    Log.i("startGoProRecording","Found GoPro: ${scanResult.device.name}")
                    // We will take the first discovered gopro
                    deviceChannel.send(scanResult.device)
                }
            }
            // Wait to receive the scan result
            goproAddress = deviceChannel.receive().address
            // We're done with scanning now
            ble.stopScan(scanResults)
        }.onFailure { throw it }
    }


    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(){
        Log.d("startGoProRecording","Connecting to $goproAddress")
        ble.connect(goproAddress).onFailure { throw it }

        /**
         * Perform initial BLE setup
         */
        // Discover all characteristics
        Log.d("startGoProRecording","Discovering characteristics")
        ble.discoverCharacteristics(goproAddress).onFailure { throw it }
        // Read a known encrypted characteristic to trigger pairing
        Log.d("startGoProRecording","Pairing")
        ble.readCharacteristic(goproAddress, GoProUUID.WIFI_AP_PASSWORD.uuid, 30000)
            .onFailure { throw it }
        Log.d("startGoProRecording","Enabling notifications")
        // Now that we're paired, for each characteristic that is notifiable...
        ble.servicesOf(goproAddress).fold(onFailure = { throw it }, onSuccess = { services ->
            services.forEach { service ->
                service.characteristics.forEach { char ->
                    if (char.isNotifiable()) {
                        // Enable notifications for this characteristic
                        ble.enableNotification(goproAddress, char.uuid).onFailure { throw it }
                    }
                }
            }
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun startRecording():Boolean{
        ble.registerListener(goproAddress, bleListeners)
        val setShutterOnCmd = ubyteArrayOf(0x03U, 0x01U, 0x01U, 0x01U)
        ble.writeCharacteristic(goproAddress, GoProUUID.CQ_COMMAND.uuid, setShutterOnCmd)
        // Wait to receive the notification response, then check its status
        return checkStatus(receivedData.receive())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun stopRecording():Boolean{
        val setShutterOffCmd = ubyteArrayOf(0x03U, 0x01U, 0x01U, 0x00U)
        ble.writeCharacteristic(goproAddress, GoProUUID.CQ_COMMAND.uuid, setShutterOffCmd)
        // Wait to receive the notification response, then check its status
        return checkStatus(receivedData.receive())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun disconnect(){
        //                        paquete de 3   comando   parametros valor
        val setShutdownCmd = ubyteArrayOf(0x01U, 0x05U)
        ble.writeCharacteristic(goproAddress, GoProUUID.CQ_COMMAND.uuid, setShutdownCmd)
        checkStatus(receivedData.receive())
        ble.unregisterListener(bleListeners)
    }

}