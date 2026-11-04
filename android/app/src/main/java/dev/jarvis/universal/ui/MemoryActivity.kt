package dev.jarvis.universal.ui

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.jarvis.universal.JarvisApp
import dev.jarvis.universal.R
import dev.jarvis.universal.util.Logx
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Owner-facing memory control: view, correct by deleting, export, and switch memory off.
 * Everything here talks to the local core over the authenticated loopback bridge.
 */
class MemoryActivity : AppCompatActivity() {

    private lateinit var app: JarvisApp
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        app = application as JarvisApp
        list = findViewById(R.id.memory_list)
        status = findViewById(R.id.memory_status)

        findViewById<Button>(R.id.btn_export).setOnClickListener { export() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener { confirmClear() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        list.removeAllViews()
        status.text = "Loading memory\u2026"
        lifecycleScope.launch {
            try {
                val statusJson = app.core.memory("/memory/status")
                val enabled = statusJson.optBoolean("enabled", true)
                status.text = "Memory ${if (enabled) "on" else "off"} \u00b7 " +
                    "${statusJson.optInt("count", 0)} items \u00b7 " +
                    "encrypted=${statusJson.optBoolean("encryption", false)}"

                addToggle(enabled)

                val data = app.core.memory("/memory/list?limit=100")
                val memories = data.optJSONArray("memories")
                if (memories == null || memories.length() == 0) {
                    list.addView(TextView(this@MemoryActivity).apply {
                        text = "No memories stored. JARVIS only remembers what you approve."
                        setTextColor(Color.parseColor("#7593A8"))
                        textSize = 12f
                        setPadding(0, dp(12), 0, 0)
                    })
                    return@launch
                }
                for (i in 0 until memories.length()) {
                    val item = memories.optJSONObject(i) ?: continue
                    list.addView(memoryRow(item))
                }
            } catch (t: Throwable) {
                status.text = "Memory unavailable: ${t.message}"
                Logx.w("Memory", "load failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun addToggle(enabled: Boolean) {
        list.addView(Button(this).apply {
            text = if (enabled) "Turn memory off" else "Turn memory on"
            textSize = 12f
            setOnClickListener {
                lifecycleScope.launch {
                    try {
                        app.core.memory(
                            "/memory/enabled",
                            "POST",
                            JSONObject().put("enabled", !enabled)
                        )
                        load()
                    } catch (t: Throwable) {
                        Toast.makeText(this@MemoryActivity, "Failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun memoryRow(item: JSONObject): LinearLayout {
        val id = item.optInt("id", -1)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val content = TextView(this).apply {
            text = "[${item.optString("kind", "note")}] ${item.optString("content", "")}"
            setTextColor(Color.parseColor("#D7F6FF"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val delete = Button(this).apply {
            text = "Delete"
            textSize = 10f
            setOnClickListener { confirmDelete(id, item.optString("content", "")) }
        }
        row.addView(content)
        row.addView(delete)
        return row
    }

    private fun confirmDelete(id: Int, content: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete memory")
            .setMessage(content.take(160))
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.core.memory("/memory/$id", "DELETE")
                        load()
                    } catch (t: Throwable) {
                        Toast.makeText(this@MemoryActivity, "Delete failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all memory?")
            .setMessage("This deletes every stored memory and the conversation history on the core. It cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    try {
                        app.core.memory("/memory/clear", "POST", JSONObject())
                        Toast.makeText(this@MemoryActivity, "Memory cleared.", Toast.LENGTH_LONG).show()
                        load()
                    } catch (t: Throwable) {
                        Toast.makeText(this@MemoryActivity, "Clear failed: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun export() {
        lifecycleScope.launch {
            try {
                val data = app.core.memory("/memory/export")
                val text = data.toString(2)
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS memory export", text))
                Toast.makeText(
                    this@MemoryActivity,
                    "Memory export copied to the clipboard (${text.length} characters).",
                    Toast.LENGTH_LONG
                ).show()
            } catch (t: Throwable) {
                Toast.makeText(this@MemoryActivity, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
