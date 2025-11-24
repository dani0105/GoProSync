package website.danielrojas.goprosync

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.le.ScanFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelUuid
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.*
import java.util.UUID


class MicrophoneService: Service() {
    private var audioRecord: AudioRecord? = null
    private var listeningThread: Thread? = null
    private lateinit var model: Model
    private var ble:Bluetooth? = null
    lateinit var goproAddress: String
    private lateinit var recognizer: Recognizer
    val recordQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    var recordingThread: Thread? = null
    private var isRecording = false

    override fun onCreate() {
        super.onCreate()
        Log.d("MicrophoneService", "OnCreate")
        Log.d("MicrophoneService", "Init Vosk")
        initModel()
        startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        ble = Bluetooth.getInstance(applicationContext)


        Log.d("MicrophoneService", "Start Listening")
        startMicListening()
    }

    private fun initModel() {
        StorageService.unpack(
            this, "model-en-us", "model",
            { model: Model? ->
                this.model = model!!
                Log.d("MicrophoneService", "Model loaded")


                recognizer= Recognizer(
                    model, 16000f//, grammar
                )
                Log.d("MicrophoneService", "recognized created")
            },
            { exception: IOException? -> null })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // service will restart if killed
    }

    override fun onDestroy() {
        audioRecord?.stop()
        audioRecord?.release()
        listeningThread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enableBluetoothMic() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    private fun disableBluetoothMic() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    @SuppressLint("MissingPermission")
    private fun startMicListening() {
        enableBluetoothMic()
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord?.startRecording()

        listeningThread = Thread {
            val buffer = ByteArray(bufferSize)
            Log.d("MicrophoneService", "On Thread")
            while (!Thread.interrupted()) {
                val result = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (result > 0) {
                    // TODO: send buffer to voice recognizer (Vosk / Porcupine / etc.)

                    if (::recognizer.isInitialized){
                        if(recognizer.acceptWaveForm(buffer, result)){
                            val text = recognizer.result
                            Log.d("VOSK", "Result: $text")

                            if (text.contains("start", ignoreCase = true)) {
                                Log.d("VOSK", "Start command")
                                GlobalScope.async { startGoProRecording() }
                            }

                            if (text.contains("stop", ignoreCase = true)) {
                                Log.d("VOSK", "stop command")
                                GlobalScope.async { stopGoProRecording() }
                            }
                        }

                        if (isRecording) {
                            recordQueue.offer(buffer.copyOf(result)) // don't reuse parent buffer
                        }
                    }else {
                        Log.d("VOSK", "recognizer not initialized")
                    }

                }
            }
            disableBluetoothMic()
        }
        Log.d("MicrophoneService", "Start Listening thread")
        listeningThread?.start()
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    private val scanFilters = listOf<ScanFilter>(
        ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(GOPRO_UUID)).build()
    )

    private val receivedData: Channel<UByteArray> = Channel()
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun naiveNotificationHandler(characteristic: UUID, data: UByteArray) {
        Log.d("naiveNotificationHandler","Received response on $characteristic: ${data.toHexString()}")
        if ((characteristic == GoProUUID.CQ_COMMAND_RSP.uuid)) {
            Log.d("naiveNotificationHandler","Command status received")
            CoroutineScope(Dispatchers.IO).launch { receivedData.send(data) }
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
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
    private suspend fun startGoProRecording() {
        if (ble==null) {
            return
        }

        ble!!.startScan(scanFilters).onSuccess { scanResults ->
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
            ble!!.stopScan(scanResults)
        }.onFailure { throw it }

        Log.d("startGoProRecording","Connecting to $goproAddress")
        ble!!.connect(goproAddress).onFailure { throw it }

        // Store connected device for other tutorials to use
        //DataStore.connectedGoPro = goproAddress

        /**
         * Perform initial BLE setup
         */
        // Discover all characteristics
        Log.d("startGoProRecording","Discovering characteristics")
        ble!!.discoverCharacteristics(goproAddress).onFailure { throw it }
        // Read a known encrypted characteristic to trigger pairing
        Log.d("startGoProRecording","Pairing")
        ble!!.readCharacteristic(goproAddress, GoProUUID.WIFI_AP_PASSWORD.uuid, 30000)
            .onFailure { throw it }
        Log.d("startGoProRecording","Enabling notifications")
        // Now that we're paired, for each characteristic that is notifiable...
        ble!!.servicesOf(goproAddress).fold(onFailure = { throw it }, onSuccess = { services ->
            services.forEach { service ->
                service.characteristics.forEach { char ->
                    if (char.isNotifiable()) {
                        // Enable notifications for this characteristic
                        ble!!.enableNotification(goproAddress, char.uuid).onFailure { throw it }
                    }
                }
            }
        })
        Log.d("startGoProRecording","Bluetooth is ready for communication!")
        delay(2000)
        ble!!.registerListener(goproAddress, bleListeners)
        val setShutterOnCmd = ubyteArrayOf(0x03U, 0x01U, 0x01U, 0x01U)
        ble!!.writeCharacteristic(goproAddress, GoProUUID.CQ_COMMAND.uuid, setShutterOnCmd)
        // Wait to receive the notification response, then check its status
        val result = checkStatus(receivedData.receive())
        if (result) {
            startRecording()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun stopGoProRecording() {
        if (ble==null) {
            return
        }
        //                        paquete de 3   comando   parametros valor
        val setShutterOffCmd = ubyteArrayOf(0x03U, 0x01U, 0x01U, 0x00U)
        ble!!.writeCharacteristic(goproAddress, GoProUUID.CQ_COMMAND.uuid, setShutterOffCmd)
        // Wait to receive the notification response, then check its status
        checkStatus(receivedData.receive())
        stopRecording()
        delay(3000)
        val setShutdownCmd = ubyteArrayOf(0x01U, 0x05U,)
        ble!!.writeCharacteristic(goproAddress, GoProUUID.CQ_COMMAND.uuid, setShutdownCmd)
        checkStatus(receivedData.receive())
        delay(1000)

        ble!!.unregisterListener(bleListeners)
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val wavFile = File(downloads, "audio_${System.currentTimeMillis()}.wav")
        var pcmByteCount = 0
        val audio = Audio()
        recordingThread = Thread {
            FileOutputStream(wavFile).use { output ->

                // Reserve 44 bytes for WAV header
                output.write(ByteArray(44))

                while (isRecording || recordQueue.isNotEmpty()) {
                    val data = recordQueue.poll()
                    if (data != null) {
                        pcmByteCount += data.size
                        output.write(data)
                    }
                }

                // After recording ends → rewrite WAV header with real sizes
                output.channel.position(0)
                audio.writeWavHeader(output, pcmByteCount)
            }

            Log.d("Recorder", "WAV saved: ${wavFile.absolutePath}")
        }
            recordingThread?.start()
    }


    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        Log.d("startRecording", "Waiting to finish recording thread")
        recordingThread?.join()
        recordingThread = null
    }

    private fun createNotification(): Notification {
        Log.d("MicrophoneService", "Request Permission")
        val channelId = "voice_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Voice Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Listening for voice commands")
            .setContentText("Microphone active")
            .build()
    }

}