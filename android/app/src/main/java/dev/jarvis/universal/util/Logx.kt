package dev.jarvis.universal.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tagged logger that also keeps a bounded in-memory ring buffer so the setup screen can show
 * recent diagnostics without writing logs to disk. Never log tokens, transcripts of private
 * conversations at length, or contact details: [redact] is used for anything sensitive.
 */
object Logx {
    private const val TAG = "Jarvis"
    private const val CAPACITY = 200

    private val buffer = ArrayDeque<String>(CAPACITY)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun d(area: String, message: String) = record("D", area, message)

    fun w(area: String, message: String) = record("W", area, message)

    fun e(area: String, message: String, t: Throwable? = null) {
        record("E", area, if (t == null) message else "$message (${t.javaClass.simpleName}: ${t.message})")
    }

    private fun record(level: String, area: String, message: String) {
        val line = "${fmt.format(Date())} $level/$area $message"
        when (level) {
            "E" -> Log.e(TAG, "$area: $message")
            "W" -> Log.w(TAG, "$area: $message")
            else -> Log.d(TAG, "$area: $message")
        }
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    fun recent(limit: Int = 60): List<String> = synchronized(buffer) {
        buffer.toList().takeLast(limit).reversed()
    }

    /** Masks a token/code so it can be shown as evidence that it exists without revealing it. */
    fun redact(secret: String?): String {
        if (secret.isNullOrEmpty()) return "<unset>"
        return if (secret.length <= 6) "***" else "${secret.take(3)}…${secret.takeLast(2)} (${secret.length} chars)"
    }
}
