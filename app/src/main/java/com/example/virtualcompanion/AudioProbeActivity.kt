package com.example.virtualcompanion

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Throwaway probe: verifies the mic actually delivers samples on this device and
 * reports which audio effects (AEC / NS / AGC) the platform will hand out.
 * Logs under tag AUDIO.
 */
class AudioProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        thread { runProbe() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.i("AUDIO", "permission granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        thread { runProbe() }
    }

    @SuppressLint("MissingPermission")
    private fun runProbe() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i("AUDIO", "RESULT=NO_PERMISSION")
                return
            }
            Log.i("AUDIO", "sr rates: 16000 supported=" + AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            Log.i("AUDIO", "AEC available=" + AcousticEchoCanceler.isAvailable())
            Log.i("AUDIO", "NS available=" + NoiseSuppressor.isAvailable())
            Log.i("AUDIO", "AGC available=" + AutomaticGainControl.isAvailable())

            val minBuf = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 4, 32000))
            Log.i("AUDIO", "AudioRecord state=" + rec.state + " (1=INITIALIZED)")

            val aec = AcousticEchoCanceler.create(rec.audioSessionId)
            Log.i("AUDIO", "AEC instance=" + (aec != null) + " enabled=" + (aec?.enabled))
            val ns = NoiseSuppressor.create(rec.audioSessionId)
            Log.i("AUDIO", "NS instance=" + (ns != null) + " enabled=" + (ns?.enabled))

            rec.startRecording()
            Log.i("AUDIO", "recording state=" + rec.recordingState)

            val buf = ShortArray(1600)
            var total = 0
            var peak = 0
            var nonzero = 0
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 3000) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    total += n
                    for (i in 0 until n) {
                        val v = kotlin.math.abs(buf[i].toInt())
                        if (v > peak) peak = v
                        if (v > 0) nonzero++
                    }
                }
            }
            rec.stop()
            rec.release()
            Log.i("AUDIO", "samples=$total peak=$peak nonzero=$nonzero")
            Log.i("AUDIO", "RESULT=" + if (total > 0 && peak > 0) "MIC_OK" else "MIC_SILENT")
        } catch (e: Exception) {
            Log.i("AUDIO", "RESULT=EXCEPTION " + e)
        }
    }
}
