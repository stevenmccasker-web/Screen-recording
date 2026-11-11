package app.screenrec

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.screenrec.RecorderState.Status
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that owns the whole recording: it waits out the countdown,
 * captures the screen with MediaProjection, encodes it with MediaRecorder and saves
 * the finished video into Movies/ScreenRecorder.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "app.screenrec.action.START"
        const val ACTION_STOP = "app.screenrec.action.STOP"
        const val ACTION_PAUSE = "app.screenrec.action.PAUSE"
        const val ACTION_RESUME = "app.screenrec.action.RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"

        private const val CH_RECORDING = "recording"
        private const val CH_SAVED = "saved"
        private const val ID_RECORDING = 1
        private const val ID_SAVED = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var display: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var countdownLeft = 0
    private var withMic = false
    private var tearingDown = false

    /** Called when the system stops the projection (e.g. the user taps the status bar chip). */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopRecording() }
        }
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            if (RecorderState.status != Status.STARTING) return
            if (countdownLeft > 0) {
                notifyUpdate()
                countdownLeft--
                main.postDelayed(this, 1000)
            } else {
                beginCapture()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            else -> if (RecorderState.status == Status.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (RecorderState.status != Status.IDLE) stopRecording()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- start

    private fun startRecording(intent: Intent) {
        if (RecorderState.status != Status.IDLE) return

        val prefs = Prefs(this)
        withMic = prefs.mic &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        countdownLeft = prefs.countdown
        RecorderState.starting()

        // Must go foreground straight away, before touching MediaProjection.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (withMic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, ID_RECORDING, buildNotification(), type)
        } catch (e: Exception) {
            fail("Couldn't start the recording service")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null || resultCode != Activity.RESULT_OK) {
            fail("Screen capture permission is missing")
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            fail("Couldn't start screen capture")
            return
        }
        // Android 14+ requires a callback to be registered before creating the display.
        proj.registerCallback(projectionCallback, main)
        projection = proj

        main.post(countdownTick)
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun beginCapture() {
        val proj = projection
        if (proj == null) {
            fail("Screen capture isn't available")
            return
        }
        val prefs = Prefs(this)
        val (w, h) = captureSize(prefs.resolution)
        val fps = prefs.fps
        val bitrate = (w.toDouble() * h * fps * prefs.bitsPerPixel())
            .toLong()
            .coerceIn(1_000_000L, 60_000_000L)
            .toInt()

        try {
            val uri = createVideoEntry()
            if (uri == null) {
                fail("Couldn't create the video file")
                return
            }
            outputUri = uri
            val descriptor = contentResolver.openFileDescriptor(uri, "w")
            if (descriptor == null) {
                fail("Couldn't open the video file")
                return
            }
            pfd = descriptor

            val rec = newRecorder()
            recorder = rec
            if (withMic) rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (withMic) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(128_000)
                rec.setAudioSamplingRate(44_100)
            }
            rec.setVideoSize(w, h)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(bitrate)
            rec.setOutputFile(descriptor.fileDescriptor)
            rec.prepare()

            display = proj.createVirtualDisplay(
                "ScreenRecorder",
                w,
                h,
                resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface,
                null,
                null,
            )
            rec.start()

            RecorderState.recording()
            notifyUpdate()
        } catch (e: Exception) {
            fail("Couldn't start recording: ${e.message}")
        }
    }

    /** Works out the video size, scaled down so the shorter side matches the chosen resolution. */
    private fun captureSize(targetShortSide: Int): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val bounds = wm.maximumWindowMetrics.bounds
        var w = bounds.width()
        var h = bounds.height()
        if (targetShortSide > 0) {
            val short = minOf(w, h)
            if (short > targetShortSide) {
                val scale = targetShortSide.toFloat() / short
                w = (w * scale).toInt()
                h = (h * scale).toInt()
            }
        }
        // Video encoders want even dimensions.
        return Pair((w / 2) * 2, (h / 2) * 2)
    }

    private fun createVideoEntry(): Uri? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Screen_$stamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Recordings.FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        )
    }

    // ---------------------------------------------------------- pause / stop

    private fun pauseRecording() {
        if (RecorderState.status != Status.RECORDING) return
        try {
            recorder?.pause()
        } catch (e: IllegalStateException) {
            return
        }
        RecorderState.paused()
        notifyUpdate()
    }

    private fun resumeRecording() {
        if (RecorderState.status != Status.PAUSED) return
        try {
            recorder?.resume()
        } catch (e: IllegalStateException) {
            return
        }
        RecorderState.resumed()
        notifyUpdate()
    }

    private fun fail(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        stopRecording()
    }

    private fun stopRecording() {
        if (tearingDown) return
        if (RecorderState.status == Status.IDLE) {
            stopSelf()
            return
        }
        tearingDown = true
        main.removeCallbacks(countdownTick)

        val wasRecording = RecorderState.status == Status.RECORDING ||
            RecorderState.status == Status.PAUSED
        var saved = false
        recorder?.let { rec ->
            try {
                rec.stop()
                saved = true
            } catch (e: RuntimeException) {
                // Thrown when nothing usable was recorded (e.g. stopped within a second).
                saved = false
            }
            try {
                rec.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        recorder = null

        display?.release()
        display = null

        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null

        try {
            pfd?.close()
        } catch (e: IOException) {
            // ignore
        }
        pfd = null

        val uri = outputUri
        outputUri = null
        if (uri != null) {
            if (saved) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, done, null, null)
                showSavedNotification(uri)
            } else {
                contentResolver.delete(uri, null, null)
                if (wasRecording) {
                    Toast.makeText(applicationContext, "Recording was too short to save", Toast.LENGTH_SHORT).show()
                }
            }
        }

        RecorderState.idle()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        tearingDown = false
    }

    // --------------------------------------------------------- notifications

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RECORDING, "Screen recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the screen is being recorded"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SAVED, "Saved recordings", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown when a recording has been saved"
            }
        )
    }

    private fun servicePending(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CH_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when (RecorderState.status) {
            Status.STARTING -> builder
                .setContentTitle("Get ready")
                .setContentText("Recording starts in ${countdownLeft}s")
                .addAction(0, "Cancel", servicePending(ACTION_STOP))

            Status.PAUSED -> builder
                .setContentTitle("Recording paused")
                .setContentText("Tap to open")
                .addAction(0, "Resume", servicePending(ACTION_RESUME))
                .addAction(0, "Stop", servicePending(ACTION_STOP))

            else -> builder
                .setContentTitle("Recording screen")
                .setContentText("Tap to open")
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis() - RecorderState.elapsedMs())
                .addAction(0, "Pause", servicePending(ACTION_PAUSE))
                .addAction(0, "Stop", servicePending(ACTION_STOP))
        }
        return builder.build()
    }

    private fun notifyUpdate() {
        getSystemService(NotificationManager::class.java).notify(ID_RECORDING, buildNotification())
    }

    private fun showSavedNotification(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pending = PendingIntent.getActivity(
            this,
            10,
            view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CH_SAVED)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle("Recording saved")
            .setContentText("Tap to watch it. It's in Movies/ScreenRecorder.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_SAVED, notification)
    }
}
