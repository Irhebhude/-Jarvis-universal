package dev.jarvis.universal.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.JarvisState
import dev.jarvis.universal.util.Logx
import java.util.concurrent.TimeUnit

/**
 * Low-frequency health check (15 minute minimum, the shortest Android allows for periodic work).
 *
 * It never restarts the microphone service by itself — that would violate Android's background
 * foreground-service rules — it tells the owner what happened and offers a tap-to-resume action.
 */
class HealthWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApp ?: return Result.success()
        if (!app.prefs.wakeServiceEnabled) return Result.success()

        if (!WakeWordService.running) {
            JarvisNotifications.resumePrompt(applicationContext)
            Logx.w(TAG, "listening service not running; resume prompt posted")
            return Result.success()
        }

        if (!app.core.hasPairedToken()) {
            JarvisState.setCoreOnline(false, "not paired")
            return Result.success()
        }

        val wasOnline = JarvisState.coreOnline.value
        val health = app.core.health()
        JarvisState.setCoreOnline(
            health.reachable,
            if (health.reachable) "core ${health.version}" else health.detail.ifBlank { health.status }
        )

        val now = System.currentTimeMillis()
        if (!health.reachable && wasOnline != false && now - lastAlertAt > ALERT_INTERVAL_MS) {
            lastAlertAt = now
            JarvisNotifications.alert(
                applicationContext,
                "JARVIS core is unreachable",
                "The voice service is running but the Termux intelligence core is not. " +
                    "Stop and start the core with ./start.sh in ~/jarvis-core; JARVIS reconnects automatically."
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "HealthWorker"
        private const val WORK_NAME = "jarvis-health"
        private const val ALERT_INTERVAL_MS = 30 * 60 * 1000L

        @Volatile
        private var lastAlertAt = 0L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Logx.d(TAG, "health worker scheduled")
        }
    }
}
