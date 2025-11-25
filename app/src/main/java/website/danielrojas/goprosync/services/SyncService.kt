package website.danielrojas.goprosync.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import website.danielrojas.goprosync.recorders.GoProRecorder
import website.danielrojas.goprosync.recorders.MicRecorder
import java.io.IOException

class SyncService: Service() {

    private lateinit var micRecorder: MicRecorder
    private lateinit var goproRecorder: GoProRecorder
    private var start:Boolean = false

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        Log.d("MicrophoneService", "Init Vosk")
        StorageService.unpack(
            this, "model-en-us", "model",
            { model: Model? ->
                val model = model!!
                Log.d("MicrophoneService", "Model loaded")

                val recognizer= Recognizer(
                    model, 16000f//, grammar
                )
                Log.d("MicrophoneService", "recognized created")
                //enableBluetoothMic()
                goproRecorder = GoProRecorder(applicationContext)
                enableBluetoothMic()
                micRecorder = MicRecorder(recognizer, { startCallback() }, { stopCallback() })
                micRecorder.init()
                GlobalScope.async { goproRecorder.detect() }

            },
            { exception: IOException? -> null })
        startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    @SuppressLint("MissingPermission")
    fun startCallback():Int {
        if (start){
            return 0
        }
        start = true
        GlobalScope.async {
            Log.d("MicrophoneService", "Connecting to camera")
            goproRecorder.connect()
            delay(1000)
            Log.d("MicrophoneService", "Start Recording camera")
            goproRecorder.startRecording()
            Log.d("MicrophoneService", "Start Recording mic")
            micRecorder.startRecording(applicationContext)
        }
        return 1
    }

    @SuppressLint("MissingPermission")
    fun stopCallback():Int {
        if (!start){
            return 0
        }
        start = false
        GlobalScope.async {
            Log.d("MicrophoneService", "stop recording camera")
            goproRecorder.stopRecording()
            Log.d("MicrophoneService", "stop recording mic")
            micRecorder.stopRecording()
            delay(5000)
            Log.d("MicrophoneService", "shutdown camera")
            goproRecorder.disconnect()

        }
        return 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // service will restart if killed
    }

    override fun onDestroy() {
        disableBluetoothMic()
        micRecorder.kill()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enableBluetoothMic() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
    }

    private fun disableBluetoothMic() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
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