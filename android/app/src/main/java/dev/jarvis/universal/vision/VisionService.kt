package dev.jarvis.universal.vision

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.jarvis.universal.R
import dev.jarvis.universal.service.JarvisNotifications
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Camera foreground service.
 *
 * Android rules this design obeys:
 *  - a declared `camera` foreground service type plus FOREGROUND_SERVICE_CAMERA on Android 14+;
 *  - the service must be started while an activity is visible, so a voice command issued with the
 *    app closed is refused honestly instead of pretending the camera turned on;
 *  - an ongoing notification names the active lens and carries an immediate stop control;
 *  - the session is unbound on stop, error and task removal, so no frame outlives the request.
 */
class VisionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: CameraSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        JarvisNotifications.ensureChannels(this)
        ensureCameraChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        val lens = Lens.parse(intent?.getStringExtra(EXTRA_LENS)) ?: Lens.REAR

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            VisionRepository.onError("camera_permission_required")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground(lens)) {
            VisionRepository.onError("camera_foreground_blocked")
            stopSelf()
            return START_NOT_STICKY
        }

        openLens(lens)
        return START_NOT_STICKY
    }

    private fun promoteToForeground(lens: Lens): Boolean = try {
        val notification = buildNotification(lens, "Analysing on this device")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        true
    } catch (t: Throwable) {
        // Android 12+ refuses a foreground start from the background. Say so rather than lie.
        Logx.w(TAG, "camera foreground start refused: ${t.javaClass.simpleName}")
        running = false
        false
    }

    private fun openLens(lens: Lens) {
        scope.launch {
            session?.close()
            val prefs = Prefs(applicationContext)
            val cadence = VisionCadence(prefs.cameraFps, prefs.cameraTextEvery)
            val newSession = CameraSession(applicationContext) { error ->
                Logx.w(TAG, "camera error: $error")
                VisionRepository.onError(error)
                updateNotification(lens, "Camera stopped: $error")
                running = false
                stopSelf()
            }
            session = newSession
            val opened = newSession.open(lens, cadence) { snapshot ->
                VisionRepository.onSnapshot(snapshot)
            }
            if (!opened) {
                running = false
                stopSelf()
                return@launch
            }
            VisionRepository.frameCapture = { newSession.captureJpeg() }
            val remote = Prefs(applicationContext).cameraRemoteVision
            updateNotification(
                lens,
                if (remote) "Remote reasoning enabled for asked questions"
                else "Analysing on this device only"
            )
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        running = false
        VisionRepository.onSessionStopped()
        scope.cancel()
        super.onDestroy()
    }

    private fun shutdown() {
        session?.close()
        session = null
        running = false
        VisionRepository.onSessionStopped()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun ensureCameraChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = android.app.NotificationChannel(
            CHANNEL_CAMERA,
            "Camera vision",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while JARVIS is using a camera."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(lens: Lens, detail: String): android.app.Notification {
        val open = android.app.PendingIntent.getActivity(
            this,
            30,
            Intent(this, dev.jarvis.universal.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val stop = android.app.PendingIntent.getService(
            this,
            31,
            Intent(this, VisionService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_CAMERA)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS \u00b7 ${lens.spoken.uppercase()} camera active")
            .setContentText(detail)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop camera", stop)
            .build()
    }

    private fun updateNotification(lens: Lens, detail: String) {
        if (!running) return
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification(lens, detail))
        }
    }

    companion object {
        private const val TAG = "VisionService"
        const val ACTION_START = "dev.jarvis.universal.action.CAMERA_START"
        const val ACTION_STOP = "dev.jarvis.universal.action.CAMERA_STOP"
        const val EXTRA_LENS = "dev.jarvis.universal.extra.CAMERA_LENS"
        const val CHANNEL_CAMERA = "jarvis_camera"
        const val NOTIFICATION_ID = 4104

        @Volatile
        var running: Boolean = false
            private set

        /** Returns null when the request was accepted, or a machine-readable reason otherwise. */
        fun start(context: Context, lens: Lens): String? {
            val intent = Intent(context, VisionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_LENS, lens.wire)
            return try {
                ContextCompat.startForegroundService(context, intent)
                null
            } catch (t: Throwable) {
                Logx.w(TAG, "camera start refused: ${t.javaClass.simpleName}")
                VisionRepository.onError("camera_background_blocked")
                "camera_background_blocked"
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VisionService::class.java)) }
            VisionRepository.onSessionStopped()
            running = false
        }
    }
}
