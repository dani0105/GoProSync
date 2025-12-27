package website.danielrojas.goprosync.recorders

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresPermission
import org.vosk.Recognizer
import website.danielrojas.goprosync.services.SyncService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean


class MicRecorder(val context:Context,private val recognizer: Recognizer, val startCallback: ()->Int, val stopCallback: () -> Int){
    private var isRunning = false
    private val isRecording = AtomicBoolean(false)
    private lateinit var audioRecord: AudioRecord
    private val inputSampleRate = 48000
    private val bufferSize = AudioRecord.getMinBufferSize(
        inputSampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val recognitionQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    val recordingQueue: BlockingQueue<ByteArray> = LinkedBlockingQueue()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun init() {
        isRunning = true

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        SyncService.AppRepository.mic.postValue( audioRecord.routedDevice.productName.toString())
        audioRecord.startRecording()
        startListeningThread()
        startRecognitionThread()
    }

    fun startRecording(context: Context) {
        isRecording.set(true)
        var pcmByteCount = 0
        Thread {
            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val pcmFile = File(musicDir, "audio_${System.currentTimeMillis()}.wav")
            Log.d("MicRecord","writing wav file")
            val output = pcmFile.outputStream()
            output.write(ByteArray(44))
            while (isRecording.get()) {
                val buffer = recordingQueue.poll()
                if (buffer !=null) {
                    val read =buffer.size
                    if (read > 0) {
                        pcmByteCount += buffer.size
                        output.write(buffer)
                    }
                }
            }
            Log.d("MicRecord","Saving wav File")
            output.channel.position(0)
            writeWavHeader(output, pcmByteCount)
            output.close()

        }.start()
    }

    private fun startRecognitionThread() {
        Thread {

            while (isRunning) {
                val smallBuffer = recognitionQueue.poll()
                if(smallBuffer == null){
                    continue
                }
                val read = smallBuffer.size
                if (read > 0) {

                    // Feed Vosk
                    val recognized = recognizer.acceptWaveForm(smallBuffer,read)
                    if (recognized) {
                        val text = recognizer.result
                        Log.d("VOSK", recognizer.result)
                        if (text.contains("start", ignoreCase = true)) {
                            Log.d("VOSK", "Start command")
                            startCallback()
                            continue
                        }
                        if (text.contains("stop", ignoreCase = true)) {
                            Log.d("VOSK", "stop command")
                            stopCallback()
                            continue
                        }
                    }
                }
            }
        }.start()
    }

    private fun startListeningThread(){
        Thread {
            val buffer = ByteArray(bufferSize)
            while (isRunning) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val copy = buffer.copyOf(read)
                    if(isRecording.get()){
                        recordingQueue.put(copy)  // shared for other threads
                    }
                    val pcm16k = downsample48kTo16k(copy)
                    recognitionQueue.put(pcm16k)
                }
            }
        }.start()
    }

    fun stopRecording(){
        isRecording.set(false)
    }

    fun kill() {
        isRunning = false
        isRecording.set(false)
        audioRecord.stop()
        audioRecord.release()
    }

    private fun downsample48kTo16k(input: ByteArray): ByteArray {
        // 16-bit samples = 2 bytes per sample, little endian
        val samples = input.size / 2
        val outSamples = samples / 3
        val output = ByteArray(outSamples * 2)

        var inIndex = 0
        var outIndex = 0

        while (outIndex < outSamples) {
            // copy 1 sample = 2 bytes
            output[outIndex * 2] = input[inIndex * 2]
            output[outIndex * 2 + 1] = input[inIndex * 2 + 1]

            inIndex += 3       // skip 3 input samples → 48 → 16
            outIndex++
        }
        return output
    }
    fun writeWavHeader(out: FileOutputStream, pcmDataLength: Int) {
        val sampleRate = inputSampleRate
        val channels = 1
        val bitsPerSample = 16

        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        writeInt(header, 4, totalDataLen)
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeInt(header, 16, 16)
        writeShort(header, 20, 1)
        writeShort(header, 22, channels.toShort())
        writeInt(header, 24, sampleRate)
        writeInt(header, 28, byteRate)
        writeShort(header, 32, (channels * bitsPerSample / 8).toShort())
        writeShort(header, 34, bitsPerSample.toShort())
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeInt(header, 40, pcmDataLength)

        out.write(header, 0, 44)
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xff).toByte()
        data[offset + 1] = ((value shr 8) and 0xff).toByte()
        data[offset + 2] = ((value shr 16) and 0xff).toByte()
        data[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(data: ByteArray, offset: Int, value: Short) {
        data[offset] = (value.toInt() and 0xff).toByte()
        data[offset + 1] = ((value.toInt() shr 8) and 0xff).toByte()
    }
}