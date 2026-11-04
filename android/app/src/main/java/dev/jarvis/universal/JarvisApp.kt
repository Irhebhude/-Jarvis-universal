package dev.jarvis.universal

import android.app.Application
import dev.jarvis.universal.control.DeviceControlService
import dev.jarvis.universal.net.CoreClient
import dev.jarvis.universal.service.HealthWorker
import dev.jarvis.universal.service.JarvisNotifications
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import dev.jarvis.universal.util.SecretStore

/**
 * Process-wide singletons. Kept intentionally small: settings, encrypted secret storage, the
 * bridge client, and the action registry that owns every permitted device operation.
 */
class JarvisApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var core: CoreClient
        private set
    lateinit var control: DeviceControlService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        secrets = SecretStore(this)
        core = CoreClient(prefs, secrets)
        control = DeviceControlService(this, prefs, core)
        JarvisNotifications.ensureChannels(this)
        HealthWorker.schedule(this)

        JarvisState.setMuted(prefs.micMuted)
        JarvisState.setWakeEnabled(false)
        Logx.d("App", "JARVIS started; ${control.registry.size()} device actions registered")
    }

    companion object {
        @Volatile
        lateinit var instance: JarvisApp
            private set
    }
}
