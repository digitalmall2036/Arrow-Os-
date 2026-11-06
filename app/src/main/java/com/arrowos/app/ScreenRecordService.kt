package com.arrowos.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File

/**
 * Records the screen (and the teacher's voice) while they show Arrow Cloner how they work.
 * The video goes into the user's Arrow Files. Android shows its own "recording" icon the
 * whole time, and the notification has a Stop button.
 */
class ScreenRecordService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var outputPath: String? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val path = intent?.getStringExtra(EXTRA_PATH)
        val withAudio = intent?.getBooleanExtra(EXTRA_AUDIO, false) == true
        if (data == null || path == null || code != Activity.RESULT_OK) {
            report(false, path)
            stopSelf()
            return START_NOT_STICKY
        }
        outputPath = path
        try {
            // Android 10+ needs the service in the foreground before the projection starts.
            var types = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types)
            start(code, data, path, withAudio)
            started = true
            running = true
        } catch (e: Exception) {
            lastError = e.message
            cleanUp()
            report(false, path)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(code: Int, data: Intent, path: String, withAudio: Boolean) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(code, data) ?: throw IllegalStateException("Screen recording was not allowed")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            // The user stopped it from Android's own controls.
            override fun onStop() {
                main.post { finish() }
            }
        }, main)

        val metrics = resources.displayMetrics
        // 720 pixels wide is clear enough to read and light on a 2 GB phone.
        val scale = minOf(1f, 720f / metrics.widthPixels)
        val width = ((metrics.widthPixels * scale).toInt() / 16) * 16
        val height = ((metrics.heightPixels * scale).toInt() / 16) * 16

        File(path).parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
        recorder = rec
        if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
        rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        rec.setOutputFile(path)
        rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        rec.setVideoSize(width, height)
        rec.setVideoFrameRate(24)
        rec.setVideoEncodingBitRate(2_000_000)
        if (withAudio) {
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(96_000)
        }
        rec.prepare()
        display = mp.createVirtualDisplay(
            "ArrowCloner", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.surface, null, null,
        )
        rec.start()
    }

    private fun finish() {
        if (!started) {
            stopSelf()
            return
        }
        started = false
        var ok = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            ok = false // stopped too soon: nothing was recorded
        }
        cleanUp()
        report(ok, outputPath)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanUp() {
        try { recorder?.release() } catch (e: Exception) { }
        try { display?.release() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        recorder = null
        display = null
        projection = null
        running = false
    }

    private fun report(ok: Boolean, path: String?) {
        running = false
        val done = onFinished
        main.post { done?.invoke(ok, path) }
    }

    override fun onDestroy() {
        if (started) finish()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Arrow Cloner lessons", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Arrow Cloner is watching and learning")
            .setContentText("Do your work. Tap Stop when you finish.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_PATH = "path"
        const val EXTRA_AUDIO = "audio"
        const val ACTION_STOP = "com.arrowos.app.STOP_RECORDING"
        private const val CHANNEL = "cloner_lessons"
        private const val NOTIFICATION_ID = 7

        /** True while a lesson is being recorded. */
        @Volatile var running = false
        var lastError: String? = null

        /** Called on the main thread when recording ends: whether a video was saved, and where. */
        var onFinished: ((ok: Boolean, path: String?) -> Unit)? = null

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
