package dev.jarvis.universal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.R
import dev.jarvis.universal.control.AndroidPermissionState
import dev.jarvis.universal.model.ModelInstaller
import dev.jarvis.universal.service.WakeWordService
import dev.jarvis.universal.tts.JarvisTts
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.wake.WakeEngineFactory
import dev.jarvis.universal.wake.WakeMode
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Guided setup. Permissions are requested one feature at a time, never all at installation, and
 * each row reports the real platform state rather than what JARVIS wishes were true.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var app: JarvisApp
    private lateinit var container: LinearLayout
    private lateinit var installer: ModelInstaller
    private lateinit var permissionState: AndroidPermissionState
    private lateinit var tts: JarvisTts

    private var pendingPermission: String? = null
    private var progressRow: TextView? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val permission = pendingPermission
            pendingPermission = null
            if (permission != null && !granted) {
                Toast.makeText(this, "Permission denied by the owner.", Toast.LENGTH_SHORT).show()
            }
            render()
        }

    private val zipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importModel(uri)
        }

    private val folderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (t: Throwable) {
                    Logx.w(TAG, "persistable URI not granted: ${t.javaClass.simpleName}")
                }
                app.prefs.safTreeUri = uri.toString()
                Toast.makeText(this, "Notes folder granted.", Toast.LENGTH_SHORT).show()
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        app = application as JarvisApp
        container = findViewById(R.id.steps_container)
        installer = ModelInstaller(this)
        permissionState = AndroidPermissionState(this)
        tts = JarvisTts(this, app.prefs).also { it.init() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        tts.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- rendering

    private data class Status(val text: String, val ok: Boolean, val warn: Boolean = false)

    private fun render() {
        container.removeAllViews()
        addHeader()

        step(
            "1. Device compatibility",
            "Android 8+ and an ABI supported by the offline wake engine (${Build.SUPPORTED_ABIS.joinToString(", ")}).",
            status = {
                val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                Status("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) \u00b7 ${Build.MODEL}", ok)
            }
        )

        permissionStep(
            title = "2. Microphone",
            description = "Required for the wake word. JARVIS processes wake audio on-device only.",
            permission = Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissionStep(
                title = "3. Notifications",
                description = "Android requires a visible notification while the microphone service runs.",
                permission = Manifest.permission.POST_NOTIFICATIONS
            )
        }

        step(
            "4. Offline wake model",
            "Vosk model used to detect both wake phrases without any network. About 40 MB.",
            status = {
                val installed = WakeEngineFactory.voskModelInstalled(filesDir)
                Status(
                    if (installed) "Installed (${installer.installedSizeMb()} MB)" else "Not installed",
                    installed
                )
            },
            actionLabel = "Manage model"
        ) { showModelDialog() }

        step(
            "5. Wake phrases and sensitivity",
            "Both phrases are supported. \u201cJARVIS\u201d alone is matched more strictly than \u201cHey JARVIS\u201d.",
            status = {
                Status(
                    "${app.prefs.wakeMode.label} \u00b7 sensitivity ${app.prefs.sensitivity}/100 " +
                        "(${app.prefs.engineId})",
                    true,
                    warn = app.prefs.wakeMode == WakeMode.DUAL
                )
            },
            actionLabel = "Configure"
        ) { showWakeDialog() }

        step(
            "6. Female voice",
            "Choose which installed voice JARVIS uses. If no female voice is available, a pitched fallback is reported honestly.",
            status = {
                val fallback = tts.usingFallbackPitch
                Status(tts.voiceLabel, !fallback, warn = fallback)
            },
            actionLabel = "Choose voice"
        ) { showVoiceDialog() }

        step(
            "7. Intelligence core (Termux)",
            "The Python core does the reasoning. Start it with ./start.sh inside ~/jarvis-core.",
            status = { coreStatus },
            actionLabel = "Check core"
        ) { checkCore() }

        step(
            "8. Pair with core",
            "Enter the pairing code printed by ./pair.sh in Termux. The device token is stored in the Android Keystore.",
            status = {
                val paired = app.core.hasPairedToken()
                Status(
                    if (paired) "Paired with ${app.core.baseUrl}" else "Not paired",
                    paired
                )
            },
            actionLabel = "Pair"
        ) { showPairDialog() }

        permissionStep(
            title = "9. Contacts (needed for \u201ccall my brother\u201d)",
            description = "Contact names are resolved on-device; only the resolved number is acted on.",
            permission = Manifest.permission.READ_CONTACTS
        )

        permissionStep(
            title = "10. Phone and SIM",
            description = "Lets JARVIS place calls through Android Telecom and show which SIM will be used.",
            permission = Manifest.permission.CALL_PHONE,
            extraPermission = Manifest.permission.READ_PHONE_STATE
        )

        permissionStep(
            title = "11. SMS",
            description = "Only needed if you want JARVIS to send messages directly. Drafting never needs it.",
            permission = Manifest.permission.SEND_SMS
        )

        step(
            "12. Notification access (optional)",
            "Required for notification summaries. Excluded apps are never read, and nothing is stored on disk.",
            status = {
                val ok = permissionState.notificationAccessEnabled()
                Status(if (ok) "Granted" else "Not granted", ok)
            },
            actionLabel = "Open settings"
        ) { openSystemScreen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }

        step(
            "13. Display over other apps",
            "Android 10+ blocks background app launching. This grant is what lets JARVIS open apps while its interface is closed.",
            status = {
                val ok = Settings.canDrawOverlays(this)
                Status(if (ok) "Granted" else "Not granted \u2014 app launching will refuse to run", ok)
            },
            actionLabel = "Open settings"
        ) {
            openSystemScreen(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName")
        }

        step(
            "14. Accessibility navigation (optional)",
            "Only global back/home/recents actions. Screen content is never read. Not needed for voice control.",
            status = {
                val ok = permissionState.accessibilityEnabled()
                Status(if (ok) "Enabled" else "Disabled (optional)", ok)
            },
            actionLabel = "Open settings"
        ) { openSystemScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS) }

        step(
            "15. Battery optimisation",
            "Allow JARVIS to run without battery restrictions so the listening service is not killed.",
            status = {
                val ok = permissionState.batteryUnrestricted()
                Status(if (ok) "Unrestricted" else "Still optimised", ok)
            },
            actionLabel = "Allow"
        ) { requestBatteryExemption() }

        step(
            "16. Notes folder (optional)",
            "Storage Access Framework folder JARVIS may create notes in. Files are never deleted or overwritten.",
            status = {
                val uri = app.prefs.safTreeUri
                Status(if (uri.isNullOrBlank()) "No folder granted" else "Granted", !uri.isNullOrBlank())
            },
            actionLabel = "Choose folder"
        ) { folderLauncher.launch(null) }

        step(
            "17. Wake word test",
            "Starts the listening service and asks you to say \u201cHey JARVIS\u201d. The first detection is spoken back with the transcript.",
            status = {
                Status(
                    if (WakeWordService.running) "Service running \u00b7 ${app.prefs.wakeMode.label}" else "Service stopped",
                    WakeWordService.running
                )
            },
            actionLabel = "Run test"
        ) { startWakeTest() }

        step(
            "18. Voice command test",
            "Sends a typed command through the same pipeline that voice uses (no microphone needed).",
            status = {
                val detail = dev.jarvis.universal.JarvisState.detail.value
                Status(if (detail.isBlank()) "No command run yet" else detail.take(70), detail.isNotBlank())
            },
            actionLabel = "Run test"
        ) { sendTestCommand() }

        step(
            "19. Background operation",
            "Close this app and say the wake phrase. Android may still stop the service on aggressive battery managers; check the listening notification.",
            status = {
                Status(
                    when {
                        !WakeWordService.running -> "Service not running"
                        app.prefs.micMuted -> "Service running, microphone muted"
                        else -> "Service running and unmuted"
                    },
                    WakeWordService.running && !app.prefs.micMuted
                )
            }
        )

        step(
            "20. Diagnostics",
            "Permissions, core reachability and the last log lines.",
            status = {
                val perms = app.control.permissionReport()
                val granted = perms.count { it.value }
                Status("$granted/${perms.size} capabilities granted", true)
            },
            actionLabel = "Show"
        ) { showDiagnostics() }

        addTermuxInstructions()
    }

    private var coreStatus: Status = Status("Not checked", false, warn = true)

    private fun addHeader() {
        val title = TextView(this).apply {
            text = "JARVIS SETUP"
            setTextColor(Color.parseColor("#3FE0FF"))
            textSize = 20f
            letterSpacing = 0.28f
        }
        val subtitle = TextView(this).apply {
            text = "Permissions are requested only when you enable the matching feature. " +
                "Camera access is never requested."
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(18))
        }
        container.addView(title)
        container.addView(subtitle)
    }

    private fun step(
        title: String,
        description: String,
        status: () -> Status,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.parseColor("#0A1322"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        card.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#D7F6FF"))
            textSize = 14f
        })
        card.addView(TextView(this).apply {
            text = description
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(6))
        })

        val current = status()
        val statusView = TextView(this).apply {
            text = current.text
            textSize = 12f
            setTextColor(
                Color.parseColor(
                    when {
                        current.warn -> "#FFC46B"
                        current.ok -> "#6BFFD0"
                        else -> "#FF6B8A"
                    }
                )
            )
        }
        card.addView(statusView)

        if (actionLabel != null && onAction != null) {
            card.addView(Button(this).apply {
                text = actionLabel
                textSize = 12f
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
                setOnClickListener { onAction() }
            })
        }

        container.addView(card)
    }

    private fun permissionStep(
        title: String,
        description: String,
        permission: String,
        extraPermission: String? = null
    ) {
        step(
            title = title,
            description = description,
            status = {
                val granted = permissionState.isGranted(permission) &&
                    (extraPermission == null || permissionState.isGranted(extraPermission))
                Status(if (granted) "Granted" else "Not granted", granted)
            },
            actionLabel = "Grant",
            onAction = {
                val missing = when {
                    !permissionState.isGranted(permission) -> permission
                    extraPermission != null && !permissionState.isGranted(extraPermission) -> extraPermission
                    else -> null
                }
                if (missing == null) {
                    Toast.makeText(this, "Already granted.", Toast.LENGTH_SHORT).show()
                    render()
                } else {
                    pendingPermission = missing
                    permissionLauncher.launch(missing)
                }
            }
        )
    }

    // ---------------------------------------------------------------- actions

    private fun showModelDialog() {
        val installed = installer.isInstalled()
        val items = mutableListOf("Download official model (~40 MB)")
        items.add("Import a downloaded .zip")
        if (installed) items.add("Remove installed model")

        AlertDialog.Builder(this)
            .setTitle("Wake model")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> confirmDownload()
                    1 -> zipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    2 -> {
                        installer.uninstall()
                        render()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmDownload() {
        val progress = ProgressBar(this).apply {
            max = 100
            visibility = ProgressBar.GONE
        }
        val label = TextView(this).apply {
            text = "Downloads from alphacephei.com and installs into app storage."
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 12f
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(label)
            addView(progress)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Download wake model?")
            .setView(layout)
            .setPositiveButton("Download", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                progress.visibility = ProgressBar.VISIBLE
                lifecycleScope.launch {
                    val result = installer.downloadAndInstall { p ->
                        runOnUiThread {
                            progress.progress = p.percent
                            label.text = "${p.phase}: ${p.percent}%"
                        }
                    }
                    result.onSuccess {
                        Toast.makeText(this@SetupActivity, "Wake model installed.", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        label.text = "Failed: ${it.message}"
                        Toast.makeText(this@SetupActivity, "Install failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                    dialog.dismiss()
                    render()
                }
            }
        }
        dialog.show()
    }

    private fun importModel(uri: Uri) {
        val label = TextView(this).apply {
            text = "Extracting\u2026"
            setTextColor(Color.parseColor("#7593A8"))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Importing model")
            .setView(label)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            val result = installer.importFromUri(uri) { p -> runOnUiThread { label.text = "${p.phase} ${p.percent}%" } }
            dialog.dismiss()
            result.onSuccess { Toast.makeText(this@SetupActivity, "Model imported.", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this@SetupActivity, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
            render()
        }
    }

    private fun showWakeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val group = RadioGroup(this)
        val heyOnly = RadioButton(this).apply { text = WakeMode.HEY_ONLY.label; id = 1 }
        val dual = RadioButton(this).apply { text = WakeMode.DUAL.label; id = 2 }
        group.addView(heyOnly)
        group.addView(dual)
        if (app.prefs.wakeMode == WakeMode.DUAL) dual.isChecked = true else heyOnly.isChecked = true
        layout.addView(group)

        val sensitivityLabel = TextView(this).apply {
            text = "Sensitivity: ${app.prefs.sensitivity}/100 (higher = more activations, more false positives)"
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }
        val seek = SeekBar(this).apply {
            max = 100
            progress = app.prefs.sensitivity
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                sensitivityLabel.text =
                    "Sensitivity: $value/100 (higher = more activations, more false positives)"
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        layout.addView(sensitivityLabel)
        layout.addView(seek)

        val engineLabel = TextView(this).apply {
            text = WakeEngineFactory.describeVosk(filesDir).detail + "\n" +
                WakeEngineFactory.describePorcupine(null).detail
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        }
        layout.addView(engineLabel)

        AlertDialog.Builder(this)
            .setTitle("Wake phrases")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                app.prefs.wakeMode = if (dual.isChecked) WakeMode.DUAL else WakeMode.HEY_ONLY
                app.prefs.sensitivity = seek.progress
                if (WakeWordService.running) {
                    WakeWordService.sendAction(this, WakeWordService.ACTION_STOP)
                    WakeWordService.start(this)
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVoiceDialog() {
        val voices = tts.availableVoices()
        if (voices.isEmpty()) {
            Toast.makeText(this, "No voices reported yet. Try again in a moment.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = voices.map { voice ->
            (if (voice.femaleGuess) "\u2640 " else "\u2642 ") + voice.name + "  (" + voice.locale + ")"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Female voice")
            .setItems(labels) { _, which ->
                val chosen = voices[which]
                tts.selectVoice(chosen.name)
                lifecycleScope.launch {
                    tts.speakAndWait("Hello. I am JARVIS. This is my voice.")
                }
                render()
            }
            .setNeutralButton("Test current") { _, _ ->
                lifecycleScope.launch { tts.speakAndWait("Hello. I am JARVIS.") }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkCore() {
        lifecycleScope.launch {
            coreStatus = Status("Checking\u2026", true, warn = true)
            render()
            if (!app.core.hasPairedToken()) {
                coreStatus = Status("Not paired \u2014 complete step 8 first", false, warn = true)
                render()
                return@launch
            }
            try {
                val health = app.core.health()
                if (!health.reachable) {
                    coreStatus = Status("Unreachable: ${health.detail}", false)
                    render()
                    return@launch
                }
                val status = app.core.status()
                val provider = status.optString("provider", "unknown")
                val model = status.optString("model", "unknown")
                coreStatus = Status(
                    "Online \u00b7 provider=$provider \u00b7 model=$model \u00b7 core ${health.version}",
                    true
                )
            } catch (t: Throwable) {
                coreStatus = Status("Error: ${t.message}", false)
            }
            render()
        }
    }

    private fun showPairDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
        }
        val portInput = EditText(this).apply {
            hint = "Core port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(app.prefs.corePort.toString())
        }
        val codeInput = EditText(this).apply {
            hint = "Pairing code from ./pair.sh"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        layout.addView(TextView(this).apply {
            text = "Run ./pair.sh in Termux to print a one-time code. It expires in 5 minutes."
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 12f
        })
        layout.addView(portInput)
        layout.addView(codeInput)

        AlertDialog.Builder(this)
            .setTitle("Pair device")
            .setView(layout)
            .setPositiveButton("Pair") { _, _ ->
                val port = portInput.text.toString().trim().toIntOrNull() ?: app.prefs.corePort
                val code = codeInput.text.toString().trim()
                if (code.isBlank()) {
                    Toast.makeText(this, "Enter the pairing code.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    try {
                        app.core.pair(port, code, "${Build.MANUFACTURER} ${Build.MODEL}")
                        Toast.makeText(this@SetupActivity, "Paired successfully.", Toast.LENGTH_LONG).show()
                        checkCore()
                    } catch (t: Throwable) {
                        Toast.makeText(
                            this@SetupActivity,
                            "Pairing failed: ${t.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSystemScreen(action: String, data: String? = null) {
        val intent = if (data != null) Intent(action, Uri.parse(data)) else Intent(action)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "This device does not expose that settings screen.", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestBatteryExemption() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            openSystemScreen(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    private fun startWakeTest() {
        if (!WakeEngineFactory.voskModelInstalled(filesDir) && app.prefs.engineId == "vosk") {
            Toast.makeText(this, "Install the wake model first (step 4).", Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Grant the microphone permission first (step 2).", Toast.LENGTH_LONG).show()
            return
        }
        app.prefs.wakeTestPending = true
        app.prefs.wakeServiceEnabled = true
        if (WakeWordService.start(this)) {
            Toast.makeText(
                this,
                "Listening. Say \u201cHey JARVIS\u201d, then anything \u2014 I will repeat it back.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Android refused to start the service.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun sendTestCommand() {
        val input = EditText(this).apply {
            setText("what is my battery level")
            setTextColor(Color.parseColor("#D7F6FF"))
        }
        AlertDialog.Builder(this)
            .setTitle("Typed command test")
            .setView(input)
            .setPositiveButton("Run") { _, _ ->
                val intent = Intent(this, WakeWordService::class.java)
                    .setAction(WakeWordService.ACTION_TEXT_COMMAND)
                    .putExtra(WakeWordService.EXTRA_TEXT, input.text.toString())
                try {
                    startService(intent)
                    Toast.makeText(
                        this,
                        "Sent. JARVIS speaks the answer once the core responds.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (t: Throwable) {
                    Toast.makeText(this, "Start listening first.", Toast.LENGTH_LONG).show()
                }
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDiagnostics() {
        val report = buildString {
            appendLine("Permissions")
            app.control.permissionReport().forEach { (key, value) ->
                appendLine("  ${if (value) "\u2713" else "\u2717"} $key")
            }
            appendLine()
            appendLine("Service running: ${WakeWordService.running}")
            appendLine("Muted: ${app.prefs.micMuted}")
            appendLine("Core: ${app.core.baseUrl} \u00b7 paired=${app.core.hasPairedToken()}")
            appendLine("Wake model: ${installer.installedSizeMb()} MB")
            appendLine("Registered actions: ${app.control.registry.size()}")
            appendLine()
            appendLine("Recent log")
            Logx.recent(25).forEach { appendLine("  $it") }
        }
        val text = TextView(this).apply {
            text = report
            textSize = 10f
            setTextColor(Color.parseColor("#D7F6FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("Diagnostics")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun addTermuxInstructions() {
        val body = """
            Termux setup (run on the device)
            1. Install Termux and Termux:Boot from F-Droid.
            2. In Termux:
                 pkg update && pkg install python git
                 git clone <your repo> ~/jarvis-universal
                 cd ~/jarvis-universal && ./scripts/termux-install.sh
            3. Start the core:
                 ~/jarvis-core/start.sh
            4. Pair this app:
                 ~/jarvis-universal/scripts/pair.sh
            5. Diagnostics any time:
                 ~/jarvis-universal/scripts/termux-diagnostics.sh
        """.trimIndent()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.parseColor("#0A1322"))
        }
        card.addView(TextView(this).apply {
            text = "Termux intelligence core"
            setTextColor(Color.parseColor("#3FE0FF"))
            textSize = 14f
        })
        card.addView(TextView(this).apply {
            text = body
            setTextColor(Color.parseColor("#7593A8"))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        })
        card.addView(Button(this).apply {
            text = "Copy commands"
            textSize = 12f
            setOnClickListener {
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS Termux", body))
                Toast.makeText(this@SetupActivity, "Copied.", Toast.LENGTH_SHORT).show()
            }
        })
        container.addView(card)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "Setup"
    }
}
