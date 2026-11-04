package dev.jarvis.universal.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.JarvisState
import dev.jarvis.universal.asr.Transcript
import dev.jarvis.universal.asr.TranscriptionRouter
import dev.jarvis.universal.audio.AckTone
import dev.jarvis.universal.audio.CommandCapture
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.DeviceControlService
import dev.jarvis.universal.net.CoreClient
import dev.jarvis.universal.net.CoreUnauthorized
import dev.jarvis.universal.net.CoreUnavailable
import dev.jarvis.universal.notify.NotificationBuffer
import dev.jarvis.universal.notify.NotificationRelay
import dev.jarvis.universal.tts.JarvisTts
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import dev.jarvis.universal.util.SecretStore
import dev.jarvis.universal.wake.PorcupineWakeEngine
import dev.jarvis.universal.wake.VoskWakeEngine
import dev.jarvis.universal.wake.WakeConfig
import dev.jarvis.universal.wake.WakeEngine
import dev.jarvis.universal.wake.WakeEngineFactory
import dev.jarvis.universal.wake.WakeHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The always-available voice system.
 *
 * Runs as a microphone foreground service so Android keeps it alive and the owner can always see
 * whether the microphone is active. The holographic interface is a separate Activity and can be
 * closed without touching this service.
 *
 * Pipeline for every activation:
 *   wake phrase -> acknowledgement tone -> command capture -> transcription -> core plan
 *   -> permission + confirmation gate -> device action -> verification -> spoken response.
 *
 * If the core is down, the service says so and keeps listening; it never pretends a command ran.
 */
class WakeWordService : Service(), WakeEngine.Callbacks, NotificationRelay.Sink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pipelineMutex = Mutex()
    private val capture = CommandCapture()
    private val ack = AckTone()
    private val sessionId: UUID = UUID.randomUUID()

    private lateinit var prefs: Prefs
    private lateinit var secrets: SecretStore
    private lateinit var core: CoreClient
    private lateinit var tts: JarvisTts
    private lateinit var control: DeviceControlService
    private lateinit var transcriber: TranscriptionRouter

    private var engine: WakeEngine? = null
    private var heartbeatJob: Job? = null
    private var lastAnnounceAt = 0L
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        val app = application as JarvisApp
        prefs = app.prefs
        secrets = app.secrets
        core = app.core
        control = app.control

        JarvisNotifications.ensureChannels(this)
        tts = JarvisTts(this, prefs).also { it.init() }
        transcriber = TranscriptionRouter(this, core, prefs)

        JarvisState.setMuted(prefs.micMuted)
        JarvisState.setWakeEnabled(false)
        NotificationRelay.sink = this
        running = true

        // Foreground immediately: Android requires this within seconds of startForegroundService.
        startForegroundSafely()

        core.startEventChannel(
            scope = scope,
            onEvent = { event -> handleCoreEvent(event) },
            onConnection = { connected, detail ->
                if (connected) {
                    JarvisState.setCoreOnline(true, detail)
                } else {
                    JarvisState.setCoreOnline(false, detail)
                }
                updateNotification()
            }
        )

        initialHealthCheck()
        startHeartbeat()
        Logx.d(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logx.d(TAG, "stop requested")
                prefs.wakeServiceEnabled = false
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_MUTE -> applyMute(true)
            ACTION_UNMUTE -> applyMute(false)

            ACTION_TEXT_COMMAND -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty().trim()
                if (text.isNotEmpty()) {
                    prefs.wakeServiceEnabled = true
                    if (engine == null && !prefs.micMuted) beginListening()
                    scope.launch { processTyped(text) }
                }
            }

            else -> {
                prefs.wakeServiceEnabled = true
                beginListening()
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The interface was closed; the voice service must keep running.
        Logx.d(TAG, "task removed, service stays alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Logx.d(TAG, "service destroyed")
        running = false
        NotificationRelay.sink = null
        heartbeatJob?.cancel()
        engine?.stop()
        engine = null
        tts.shutdown()
        core.stopEventChannel()
        scope.cancel()
        JarvisState.setWakeEnabled(false)
        JarvisState.setPhase(JarvisState.Phase.IDLE)
        JarvisState.setMuted(prefs.micMuted)
        super.onDestroy()
    }

    // ------------------------------------------------------------ lifecycle bits

    private fun startForegroundSafely() {
        val notification = JarvisNotifications.listening(
            context = this,
            muted = prefs.micMuted,
            wakeEnabled = prefs.wakeServiceEnabled,
            coreOnline = JarvisState.coreOnline.value,
            detail = null
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    JarvisNotifications.LISTENING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(JarvisNotifications.LISTENING_NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        } catch (t: Throwable) {
            Logx.e(TAG, "startForeground failed", t)
            JarvisState.error("Android refused to start the listening service.")
            stopSelf()
        }
    }

    private fun beginListening() {
        if (!hasMicrophonePermission()) {
            prefs.wakeServiceEnabled = false
            JarvisState.error(getString(dev.jarvis.universal.R.string.error_mic_permission))
            JarvisNotifications.alert(
                this,
                "Microphone permission needed",
                "JARVIS cannot listen without microphone access. Open setup to grant it."
            )
            updateNotification()
            stopSelf()
            return
        }
        startEngine()
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun applyMute(muted: Boolean) {
        prefs.micMuted = muted
        JarvisState.setMuted(muted)
        if (muted) {
            engine?.stop()
            engine = null
            JarvisState.setWakeEnabled(false)
            JarvisState.setPhase(JarvisState.Phase.IDLE)
            JarvisState.setAmplitude(0f)
            Logx.d(TAG, "microphone muted")
        } else {
            startEngine()
        }
        updateNotification()
    }

    // ------------------------------------------------------------ engine control

    private fun startEngine() {
        if (!prefs.wakeServiceEnabled) {
            JarvisState.setWakeEnabled(false)
            return
        }
        if (prefs.micMuted) {
            JarvisState.setWakeEnabled(false)
            JarvisState.setPhase(JarvisState.Phase.IDLE)
            return
        }
        if (!hasMicrophonePermission()) {
            JarvisState.error(getString(dev.jarvis.universal.R.string.error_mic_permission))
            return
        }
        if (engine?.isRunning == true) return

        engine?.stop()
        val config = WakeConfig(mode = prefs.wakeMode, sensitivity = prefs.sensitivity)
        val next: WakeEngine = when (prefs.engineId) {
            Prefs.ENGINE_PORCUPINE -> PorcupineWakeEngine(
                context = this,
                accessKey = secrets.get(PORCUPINE_KEY).orEmpty(),
                initialConfig = config,
                callbacks = this
            )

            else -> VoskWakeEngine(
                modelDir = WakeEngineFactory.voskModelDir(filesDir),
                initialConfig = config,
                callbacks = this
            )
        }
        engine = next
        JarvisState.setWakeEnabled(true)
        JarvisState.setPhase(JarvisState.Phase.LISTENING)
        JarvisState.setDetail("")
        next.start()
        updateNotification()
    }

    private suspend fun pauseForInteraction() {
        engine?.stop()
        engine = null
        JarvisState.setWakeEnabled(false)
        JarvisState.setAmplitude(0f)
        // Give Android a moment to release the microphone before we re-open it.
        delay(160)
    }

    private fun resumeAfterInteraction() {
        if (!running) return
        if (!prefs.wakeServiceEnabled || prefs.micMuted) return
        JarvisState.setPhase(JarvisState.Phase.LISTENING)
        startEngine()
    }

    // ------------------------------------------------------------ wake engine callbacks

    override fun onWake(hit: WakeHit) {
        scope.launch { processWake(hit) }
    }

    override fun onError(code: String, message: String, cause: Throwable?) {
        Logx.e(TAG, "engine error [$code] $message", cause)
        JarvisState.error(message)
        if (code == "model_missing" || code == "model_load_failed") {
            prefs.wakeServiceEnabled = false
            JarvisNotifications.alert(
                this,
                "Wake word needs its model",
                "Install the offline wake model in Setup step 4, then enable listening again."
            )
        } else if (code == "mic_permission" || code == "mic_unavailable") {
            JarvisNotifications.alert(this, "Microphone problem", message)
        }
        updateNotification()
    }

    override fun onLevel(level: Float) {
        if (JarvisState.phase.value == JarvisState.Phase.LISTENING) {
            JarvisState.setAmplitude((level * 3.5f).coerceIn(0f, 0.5f))
        }
    }

    // ------------------------------------------------------------ the pipeline

    private suspend fun processWake(hit: WakeHit) {
        if (!pipelineMutex.tryLock()) {
            Logx.d(TAG, "wake ignored: pipeline busy")
            return
        }
        try {
            pauseForInteraction()
            ack.play()
            JarvisState.setPhase(JarvisState.Phase.CAPTURING)
            JarvisState.setTask("Listening\u2026")
            JarvisState.setDetail("wake=${hit.phrase} conf=${"%.2f".format(hit.confidence)}")
            updateNotification()

            val transcript = captureTranscript()
            if (transcript is Transcript.Failed) {
                speak("I did not catch that. Try again with \u201cHey JARVIS\u201d.")
                return
            }
            val command = (transcript as Transcript.Ok).text.trim()
            if (command.isEmpty()) {
                speak("I did not catch that.")
                return
            }
            Logx.d(TAG, "command via ${transcript.source}: ${command.take(80)}")
            prefs.lastTask = command
            JarvisState.setTask(command)

            // Setup's wake-word test stops here: the owner sees and hears confirmation that the
            // phrase was detected without touching any device feature.
            if (prefs.wakeTestPending) {
                prefs.wakeTestPending = false
                speak("Wake word confirmed. I heard you say: $command.")
                return
            }

            runCommand(command)
        } catch (t: Throwable) {
            Logx.e(TAG, "pipeline failure", t)
            JarvisState.error("Something went wrong while handling that command.")
            speak("Something went wrong while handling that command.")
        } finally {
            JarvisState.setPhase(JarvisState.Phase.IDLE)
            pipelineMutex.unlock()
            resumeAfterInteraction()
            updateNotification()
        }
    }

    /**
     * Typed commands from the holographic interface share the exact voice pipeline, so typed and
     * spoken requests get the same validation, confirmation gate and audit trail.
     */
    private suspend fun processTyped(text: String) {
        if (!pipelineMutex.tryLock()) return
        try {
            pauseForInteraction()
            JarvisState.setPhase(JarvisState.Phase.PROCESSING)
            JarvisState.setTask(text)
            prefs.lastTask = text
            runCommand(text)
        } catch (t: Throwable) {
            Logx.e(TAG, "typed command failure", t)
            JarvisState.error("Something went wrong while handling that command.")
        } finally {
            JarvisState.setPhase(JarvisState.Phase.IDLE)
            pipelineMutex.unlock()
            resumeAfterInteraction()
            updateNotification()
        }
    }

    /** Plan -> authorize -> execute -> verify -> speak. Shared by voice and typed input. */
    private suspend fun runCommand(command: String) {
        JarvisState.setPhase(JarvisState.Phase.PROCESSING)
        val plan = try {
            core.command(command, sessionId.toString(), deviceContext())
        } catch (e: CoreUnavailable) {
            coreDown(e.message ?: "unreachable")
            return
        } catch (e: CoreUnauthorized) {
            JarvisNotifications.alert(
                this,
                "Pairing needed",
                "My intelligence core rejected the device token. Pair again in setup."
            )
            speak("My core rejected my pairing token. Please pair me again in setup.")
            return
        }

        JarvisState.setCoreOnline(true, "connected")

        if (plan.actions.isEmpty()) {
            speak(plan.speech.ifBlank { "I have nothing to add." })
            return
        }

        val results = JSONArray()
        var lastSpoken: String? = null
        for (action in plan.actions) {
            var result = control.execute(
                actionId = action.id,
                args = Args.fromJson(action.args),
                confirmed = false,
                origin = "voice"
            )

            if (result.needsConfirmation) {
                speak(result.spoken)
                val approved = awaitConfirmation()
                result = if (approved) {
                    control.execute(
                        actionId = action.id,
                        args = Args.fromJson(action.args),
                        confirmed = true,
                        origin = "voice-confirmed"
                    )
                } else {
                    ActionResult(
                        ok = false,
                        spoken = "Cancelled. I did not do it.",
                        error = "cancelled_by_owner"
                    )
                }
            }

            results.put(JSONObject().put("action_id", action.id).put("result", result.toJson()))
            lastSpoken = result.spoken
            JarvisState.setDetail(result.error ?: result.spoken.take(120))
        }

        val finalSpeech = if (plan.planId.isNotBlank()) {
            try {
                val outcome = core.execute(plan.planId, results)
                (outcome.optString("speech").ifBlank { null } ?: plan.speech)
            } catch (e: CoreUnavailable) {
                Logx.w(TAG, "final composition failed: ${e.message}")
                lastSpoken
            } catch (e: CoreUnauthorized) {
                lastSpoken
            }
        } else {
            lastSpoken ?: plan.speech
        }
        speak(finalSpeech ?: "Done.")
    }

    private suspend fun captureTranscript(): Transcript = withContext(Dispatchers.Default) {
        if (prefs.transcriptionMode == Prefs.TRANSCRIPTION_ANDROID) {
            transcriber.fromPlatform()
        } else {
            val captured = capture.capture(
                maxMs = prefs.listeningTimeoutMs,
                onLevel = { level -> JarvisState.setAmplitude((level * 4f).coerceIn(0f, 1f)) }
            )
            JarvisState.setAmplitude(0f)
            if (captured.isEmpty) {
                Transcript.Failed("no speech detected")
            } else {
                transcriber.fromCapturedAudio(captured.pcm)
            }
        }
    }

    private suspend fun awaitConfirmation(): Boolean {
        val captured = capture.capture(maxMs = 4000, silenceMs = 700)
        if (captured.isEmpty) return false
        val text = when (val t = transcriber.fromCapturedAudio(captured.pcm)) {
            is Transcript.Ok -> t.text.lowercase()
            is Transcript.Failed -> return false
        }
        val normalized = text.trim()
        Logx.d(TAG, "confirmation answer: ${normalized.take(40)}")
        return normalized.startsWith("yes") ||
            normalized.startsWith("yeah") ||
            normalized.startsWith("yep") ||
            normalized.startsWith("confirm") ||
            normalized.startsWith("do it") ||
            normalized.startsWith("go ahead") ||
            normalized == "okay" ||
            normalized == "ok"
    }

    private suspend fun speak(text: String) {
        if (text.isBlank()) return
        JarvisState.setPhase(JarvisState.Phase.SPEAKING)
        JarvisState.setDetail(text.take(180))
        val ok = tts.speakAndWait(text)
        if (!ok) Logx.w(TAG, "speech output did not complete")
        JarvisState.setPhase(JarvisState.Phase.IDLE)
        JarvisState.setAmplitude(0f)
    }

    private fun coreDown(detail: String) {
        JarvisState.setCoreOnline(false, detail)
        JarvisNotifications.alert(
            this,
            "Intelligence core unreachable",
            "Start the core in Termux: cd ~/jarvis-core && ./start.sh . I will reconnect automatically."
        )
        scope.launch {
            speak("My intelligence core is not running. Start it in Termux and I will reconnect.")
            resumeAfterInteraction()
        }
    }

    // ------------------------------------------------------------ core events

    private fun handleCoreEvent(event: JSONObject) {
        when (event.optString("type")) {
            "core_status" -> {
                JarvisState.setCoreOnline(true, event.optString("detail", "connected"))
                updateNotification()
            }

            "speak" -> {
                val text = event.optString("text")
                if (text.isNotBlank()) {
                    scope.launch { speakPushed(text) }
                }
            }

            else -> Unit
        }
    }

    private suspend fun speakPushed(text: String) {
        if (!pipelineMutex.tryLock()) return
        try {
            pauseForInteraction()
            speak(text)
        } finally {
            pipelineMutex.unlock()
            resumeAfterInteraction()
        }
    }

    @Synchronized
    override fun onImportantNotification(entry: NotificationBuffer.Entry) {
        if (!prefs.announceNotifications) return
        if (prefs.micMuted || !prefs.wakeServiceEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastAnnounceAt < 60_000) return
        lastAnnounceAt = now
        scope.launch {
            if (!pipelineMutex.tryLock()) return@launch
            try {
                pauseForInteraction()
                speak("${entry.appLabel}: ${entry.title.ifBlank { entry.text }}")
            } finally {
                pipelineMutex.unlock()
                resumeAfterInteraction()
            }
        }
    }

    // ------------------------------------------------------------ health

    private fun initialHealthCheck() {
        scope.launch {
            if (!core.hasPairedToken()) {
                JarvisState.setCoreOnline(false, "not paired")
                updateNotification()
                return@launch
            }
            val health = core.health()
            JarvisState.setCoreOnline(
                health.reachable,
                if (health.reachable) "core ${health.version}" else health.detail.ifBlank { health.status }
            )
            updateNotification()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(if (prefs.batterySaver) 180_000L else 60_000L)
                if (!core.hasPairedToken()) {
                    JarvisState.setCoreOnline(false, "not paired")
                    continue
                }
                val health = core.health()
                JarvisState.setCoreOnline(
                    health.reachable,
                    if (health.reachable) "core ${health.version}" else health.detail.ifBlank { health.status }
                )
                if (engine?.isRunning != true && prefs.wakeServiceEnabled && !prefs.micMuted &&
                    JarvisState.phase.value == JarvisState.Phase.IDLE
                ) {
                    Logx.w(TAG, "engine was not running; restarting")
                    startEngine()
                }
            }
        }
    }

    private fun deviceContext(): JSONObject = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("sdk", Build.VERSION.SDK_INT)
        .put("wake_mode", prefs.wakeMode.name.lowercase())
        .put("muted", prefs.micMuted)
        .put("actions", JSONArray(control.registry.ids()))
        .put("permissions", JSONObject(control.permissionReport() as Map<*, *>))

    private fun updateNotification() {
        if (!foregroundStarted) return
        val notification = JarvisNotifications.listening(
            context = this,
            muted = prefs.micMuted,
            wakeEnabled = prefs.wakeServiceEnabled,
            coreOnline = JarvisState.coreOnline.value,
            detail = when (JarvisState.phase.value) {
                JarvisState.Phase.CAPTURING -> "Listening to you"
                JarvisState.Phase.PROCESSING -> "Processing your command"
                JarvisState.Phase.SPEAKING -> "Speaking"
                JarvisState.Phase.ERROR -> JarvisState.lastError.value
                else -> null
            }
        )
        try {
            getSystemService(android.app.NotificationManager::class.java)
                ?.notify(JarvisNotifications.LISTENING_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Logx.w(TAG, "notification update failed: ${t.javaClass.simpleName}")
        }
    }

    private fun shutdown() {
        engine?.stop()
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    companion object {
        private const val TAG = "WakeService"
        const val ACTION_START = "dev.jarvis.universal.action.SERVICE_START"
        const val ACTION_STOP = "dev.jarvis.universal.action.SERVICE_STOP"
        const val ACTION_MUTE = "dev.jarvis.universal.action.SERVICE_MUTE"
        const val ACTION_UNMUTE = "dev.jarvis.universal.action.SERVICE_UNMUTE"
        const val ACTION_TEXT_COMMAND = "dev.jarvis.universal.action.TEXT_COMMAND"
        const val EXTRA_TEXT = "extra_text"
        const val PORCUPINE_KEY = "porcupine_access_key"

        @Volatile
        var running: Boolean = false
            private set

        /**
         * Starts the listening service from a foreground context (activity, notification tap).
         * Returns false when Android refuses a background start; callers must tell the owner.
         */
        fun start(context: Context): Boolean {
            val intent = Intent(context, WakeWordService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                Logx.w(TAG, "foreground service start refused: ${t.javaClass.simpleName}")
                false
            }
        }

        fun sendAction(context: Context, action: String): Boolean = try {
            context.startService(Intent(context, WakeWordService::class.java).setAction(action))
            true
        } catch (t: Throwable) {
            false
        }
    }
}
