package website.danielrojas.goprosync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import website.danielrojas.goprosync.services.SyncService


class MainActivity : AppCompatActivity() {

    var running: Boolean = false
    lateinit var intentService: Intent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            1
        )
        intentService = Intent(this, SyncService::class.java)
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