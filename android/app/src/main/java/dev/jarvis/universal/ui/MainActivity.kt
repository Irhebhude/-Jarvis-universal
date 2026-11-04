package dev.jarvis.universal.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.JarvisState
import dev.jarvis.universal.R
import dev.jarvis.universal.service.ServiceControlReceiver
import dev.jarvis.universal.service.WakeWordService
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.wake.WakeEngineFactory
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hosts the female holographic command centre in a WebView.
 *
 * Important separation: closing this Activity does not touch [WakeWordService]. Voice operation
 * continues in the background, exactly as the requirements demand. The avatar is a view of state,
 * never a dependency of it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var app: JarvisApp

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        app = application as JarvisApp
        webView = findViewById(R.id.hologram)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = false
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pushFullState()
            }
        }
        webView.addJavascriptInterface(HologramBridge(), "JarvisNative")
        webView.loadUrl("file:///android_asset/hologram/index.html")
        observeState()
    }

    override fun onResume() {
        super.onResume()
        webView.post { pushFullState() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    JarvisState.phase.collect { phase ->
                        webView.post { evaluate("window.jarvis.setState('${phase.name.lowercase()}')") }
                    }
                }
                launch {
                    JarvisState.amplitude.collect { amplitude ->
                        webView.post { evaluate("window.jarvis.setAmplitude(${amplitude})") }
                    }
                }
                launch {
                    JarvisState.task.collect { task ->
                        webView.post { evaluate("window.jarvis.setTask(${JSONObject.quote(task)})") }
                    }
                }
                launch {
                    JarvisState.detail.collect { detail ->
                        webView.post { evaluate("window.jarvis.setDetail(${JSONObject.quote(detail)})") }
                    }
                }
                launch {
                    JarvisState.coreOnline.collect { online ->
                        webView.post {
                            evaluate("window.jarvis.setCore(${online ?: JSONObject.NULL})")
                        }
                    }
                }
                launch {
                    JarvisState.micMuted.collect { muted ->
                        webView.post { evaluate("window.jarvis.setMuted($muted)") }
                    }
                }
                launch {
                    JarvisState.wakeEnabled.collect { enabled ->
                        webView.post { evaluate("window.jarvis.setWake($enabled)") }
                    }
                }
                launch {
                    JarvisState.lastError.collect { error ->
                        webView.post { evaluate("window.jarvis.setError(${JSONObject.quote(error)})") }
                    }
                }
            }
        }
    }

    private fun pushFullState() {
        evaluate("window.jarvis.setStatus(${JSONObject.quote(statusJson())})")
        evaluate("window.jarvis.setMuted(${JarvisState.micMuted.value})")
        evaluate("window.jarvis.setWake(${JarvisState.wakeEnabled.value})")
        evaluate("window.jarvis.setTask(${JSONObject.quote(JarvisState.task.value)})")
        evaluate("window.jarvis.setDetail(${JSONObject.quote(JarvisState.detail.value)})")
    }

    private fun evaluate(script: String) {
        try {
            webView.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            Logx.w("MainActivity", "webview eval failed: ${t.javaClass.simpleName}")
        }
    }

    private fun statusJson(): String {
        val permissions = app.control.permissionReport()
        val json = JSONObject()
            .put("phase", JarvisState.phase.value.name.lowercase())
            .put("muted", JarvisState.micMuted.value)
            .put("wake_enabled", JarvisState.wakeEnabled.value)
            .put("service_running", WakeWordService.running)
            .put("core_online", JarvisState.coreOnline.value ?: JSONObject.NULL)
            .put("core_detail", JarvisState.coreDetail.value)
            .put("paired", app.core.hasPairedToken())
            .put("core_url", app.core.baseUrl)
            .put("voice", appVoiceLabel())
            .put("wake_mode", app.prefs.wakeMode.name.lowercase())
            .put("model_installed", WakeEngineFactory.voskModelInstalled(filesDir))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.RELEASE)
            .put("actions", app.control.registry.size())
        val perms = JSONObject()
        permissions.forEach { (key, value) -> perms.put(key, value) }
        json.put("permissions", perms)
        return json.toString()
    }

    @Volatile
    private var cachedVoiceLabel: String = ""

    private fun appVoiceLabel(): String {
        if (cachedVoiceLabel.isBlank()) {
            // The service owns the speaking engine; this is a read-only best-effort label.
            cachedVoiceLabel = "system female voice"
        }
        return cachedVoiceLabel
    }

    /** Exposed to the holographic interface as `JarvisNative`. */
    inner class HologramBridge {

        @JavascriptInterface
        fun status(): String = statusJson()

        @JavascriptInterface
        fun toggleMute(): Boolean {
            val muted = !app.prefs.micMuted
            app.prefs.micMuted = muted
            JarvisState.setMuted(muted)
            val action = if (muted) ServiceControlReceiver.ACTION_MUTE else ServiceControlReceiver.ACTION_UNMUTE
            sendBroadcast(Intent(action).setPackage(packageName))
            return muted
        }

        @JavascriptInterface
        fun setListening(enabled: Boolean): Boolean {
            app.prefs.wakeServiceEnabled = enabled
            if (enabled) {
                val started = WakeWordService.start(this@MainActivity)
                if (!started) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Android refused to start the microphone service from here.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                return started
            }
            sendBroadcast(Intent(ServiceControlReceiver.ACTION_STOP).setPackage(packageName))
            return true
        }

        @JavascriptInterface
        fun openSetup() = runOnUiThread {
            startActivity(Intent(this@MainActivity, SetupActivity::class.java))
        }

        @JavascriptInterface
        fun openMemory() = runOnUiThread {
            startActivity(Intent(this@MainActivity, MemoryActivity::class.java))
        }

        @JavascriptInterface
        fun sendText(text: String) {
            if (text.isBlank()) return
            val intent = Intent(this@MainActivity, WakeWordService::class.java)
                .setAction(WakeWordService.ACTION_TEXT_COMMAND)
                .putExtra(WakeWordService.EXTRA_TEXT, text.trim())
            try {
                startService(intent)
            } catch (t: Throwable) {
                Logx.w("MainActivity", "typed command dispatch failed: ${t.javaClass.simpleName}")
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "Start listening first, then send a typed command.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        @JavascriptInterface
        fun refresh() = runOnUiThread { pushFullState() }
    }
}
