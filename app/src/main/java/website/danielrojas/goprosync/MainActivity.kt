package website.danielrojas.goprosync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import website.danielrojas.goprosync.services.SyncService


class MainActivity : AppCompatActivity() {

    var running: Boolean = false
    lateinit var intentService: Intent

    val logs = ArrayDeque<String>()
    var counter = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            1
        )
        val cameraLbl: TextView = findViewById(R.id.txtCamera)
        val micLbl: TextView = findViewById(R.id.txtMic)
        val logLbl: TextView = findViewById(R.id.txtLog)
        intentService = Intent(this, SyncService::class.java)
        SyncService.AppRepository.camera.observe(this, Observer{ camera-> cameraLbl.text = camera})
        SyncService.AppRepository.mic.observe(this, Observer{ mic-> micLbl.text = mic})
        SyncService.AppRepository.log.observe(this, Observer{ log->
            Log.d("MainActivityLog", log)
            logs.addLast("[${counter}]: ${log}\n")
            counter++
            if(logs.size > 5) {
                logs.removeFirstOrNull()
            }
            var logMSG = ""
            for (i in logs.size-1 downTo  0){
                logMSG += logs[i]
            }
            logLbl.text = logMSG
        })
    }

    fun onStartServiceClick(view: View) {
        val button = view as Button

        Log.d("MainActivity","OnStart Clicked")
        if (!running){
            running = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d("MainActivity","startForegroundService ")
                startForegroundService(intentService)
            } else {
                Log.d("MainActivity","start Service ")
                startService(intentService)
            }
            button.text = "Stop Service"
        }else {
            running = false
            button.text = "Start Service"
            stopService(intentService)
        }
    }


}