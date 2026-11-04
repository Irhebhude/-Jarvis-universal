package dev.jarvis.universal.notify

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.jarvis.universal.util.Logx
import dev.jarvis.universal.util.Prefs
import java.util.ArrayDeque

/**
 * Bounded, in-memory notification digest. Nothing is written to disk and nothing is uploaded;
 * entries disappear when the process dies. Excluded packages are dropped at the source, so their
 * content is never even parsed.
 */
object NotificationBuffer {

    data class Entry(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val isOngoing: Boolean,
        val isClearable: Boolean
    )

    private const val CAPACITY = 60
    private const val MAX_FIELD = 180

    private val entries = ArrayDeque<Entry>(CAPACITY)

    fun record(entry: Entry) {
        synchronized(this) {
            entries.removeAll { it.packageName == entry.packageName && it.title == entry.title }
            while (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    fun recent(excluded: Set<String>, limit: Int = 12): List<Entry> = synchronized(this) {
        entries.filter { it.packageName !in excluded }.takeLast(limit).reversed()
    }

    fun clear() = synchronized(this) { entries.clear() }

    /** Drops buffered entries for packages the owner has just excluded. */
    fun purge(packages: Set<String>) = synchronized(this) {
        if (packages.isNotEmpty()) entries.removeAll { it.packageName in packages }
    }

    fun size(): Int = synchronized(this) { entries.size }

    fun trim(value: CharSequence?): String {
        val s = value?.toString()?.replace('\n', ' ')?.trim().orEmpty()
        return if (s.length > MAX_FIELD) s.take(MAX_FIELD) else s
    }
}

/** Receives notification content only when the owner has granted notification access. */
class JarvisNotificationListener : NotificationListenerService() {

    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        if (notification.packageName == packageName) return
        if (notification.packageName in prefs.notificationExclusions) return

        val extras = notification.notification?.extras ?: return
        val content = notification.notification?.let { summarize(it) }
        val entry = NotificationBuffer.Entry(
            packageName = notification.packageName,
            appLabel = appLabel(notification.packageName),
            title = NotificationBuffer.trim(extras.getCharSequence(Notification.EXTRA_TITLE)),
            text = NotificationBuffer.trim(
                extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: content
            ),
            postedAt = notification.postTime,
            isOngoing = notification.isOngoing,
            isClearable = notification.isClearable
        )
        if (entry.title.isBlank() && entry.text.isBlank()) return
        NotificationBuffer.record(entry)
        NotificationRelay.emit(entry)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onListenerDisconnected() {
        Logx.w("NotifListener", "listener disconnected by system")
        super.onListenerDisconnected()
    }

    private fun summarize(notification: Notification): String = try {
        notification.tickerText?.toString().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }
}

/** Bridges the listener to the running voice service without a system broadcast. */
object NotificationRelay {
    interface Sink {
        fun onImportantNotification(entry: NotificationBuffer.Entry)
    }

    @Volatile
    var sink: Sink? = null

    fun emit(entry: NotificationBuffer.Entry) {
        sink?.onImportantNotification(entry)
    }
}
