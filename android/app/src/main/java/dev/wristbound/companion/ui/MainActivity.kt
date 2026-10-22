package dev.wristbound.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.wristbound.companion.core.NotifyScope
import dev.wristbound.companion.core.ToolActivity
import dev.wristbound.companion.core.Wristbound
import dev.wristbound.companion.hermes.HermesClient
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.sync.SyncService
import kotlinx.coroutines.launch

/** Setup screen: Hermes connection, notification preferences and background sync. */
class MainActivity : AppCompatActivity() {
    private val controller by lazy { Wristbound.get(this) }
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = controller.settings
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(heading("Hermes connection"))
        column.addView(note("Use your Hermes API server address on your tailnet, e.g. https://hermes.tailnet-name.ts.net:8642. The key is API_SERVER_KEY and is stored encrypted on this phone only."))
        val url = EditText(this).apply {
            hint = "Hermes URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(s.baseUrl)
        }
        val key = EditText(this).apply {
            hint = if (s.apiKey.isNotEmpty()) "API key saved (enter to replace)" else "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(url)
        column.addView(key)
        status = note(if (s.isConfigured) "Saved." else "Not configured.")
        column.addView(Button(this).apply {
            text = "Save and test"
            setOnClickListener {
                s.baseUrl = url.text.toString()
                if (key.text.isNotBlank()) s.apiKey = key.text.toString()
                key.text.clear()
                testConnection()
            }
        })
        column.addView(status)

        column.addView(heading("Watch"))
        column.addView(switch("Show Telegram sessions", s.includeTelegram) { s.includeTelegram = it })
        column.addView(switch("Show tool activity on watch", s.toolActivity != ToolActivity.HIDDEN) {
            s.toolActivity = if (it) ToolActivity.SILENT else ToolActivity.HIDDEN
        })
        column.addView(Button(this).apply {
            text = "Open Wristbound on watch"
            setOnClickListener { lifecycleScope.launch { controller.openWatchApp() } }
        })

        column.addView(heading("Notifications"))
        column.addView(switch("Notify final replies", s.notifyReplies) { s.notifyReplies = it })
        column.addView(switch("Also notify for Telegram sessions", s.notifyTelegram) { s.notifyTelegram = it })
        column.addView(note("Off by default so Telegram and Wristbound do not both alert for the same reply. Hidden Telegram sessions never alert unless this is on."))
        column.addView(switch("Notify when Hermes uses a tool", s.toolActivity == ToolActivity.NOTIFY) {
            s.toolActivity = if (it) ToolActivity.NOTIFY else ToolActivity.SILENT
        })
        column.addView(switch("Hide reply text in notifications", s.privatePreviews) { s.privatePreviews = it })
        column.addView(spinner("Alert for", listOf("All eligible sessions", "Only the open session"),
            if (s.notifyScope == NotifyScope.ALL_ELIGIBLE) 0 else 1) {
            s.notifyScope = if (it == 0) NotifyScope.ALL_ELIGIBLE else NotifyScope.OPEN_SESSION
        })

        column.addView(heading("Background"))
        column.addView(note("Keeps Wristbound running while the phone is locked so replies arrive with the watchapp closed. Shows a silent ongoing notification."))
        column.addView(switch("Watch for replies in background", s.backgroundSync) {
            s.backgroundSync = it
            if (it) SyncService.start(this) else SyncService.stop(this)
        })
        val pollChoices = listOf(30, 60, 120, 300)
        column.addView(spinner("Check every", pollChoices.map { "$it seconds" }, pollChoices.indexOf(s.pollSeconds).coerceAtLeast(1)) {
            s.pollSeconds = pollChoices[it]
        })

        column.addView(heading("Privacy"))
        column.addView(Button(this).apply {
            text = "Clear cached conversations"
            setOnClickListener {
                controller.state.clearCache()
                status.text = "Cache cleared."
            }
        })

        setContentView(ScrollView(this).apply { addView(column) })
        requestNotificationPermission()
        if (s.backgroundSync) SyncService.start(this)
    }

    private fun testConnection() {
        val s = controller.settings
        if (!s.isConfigured) {
            status.text = "Enter a URL and key."
            return
        }
        status.text = "Testing..."
        lifecycleScope.launch {
            status.text = try {
                val client = HermesClient(s.baseUrl, s.apiKey)
                val sessions = client.listSessions(5, null)
                controller.pushSessions()
                "Connected. ${sessions.size} recent sessions visible."
            } catch (e: HermesHttpException) {
                if (e.isAuth) "Hermes rejected the key." else "Hermes error: ${e.message}"
            } catch (e: IllegalArgumentException) {
                "Invalid URL."
            } catch (e: Exception) {
                "Cannot reach Hermes: ${e.message}. Is Tailscale connected?"
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, 4)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        text = label
        isChecked = checked
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun spinner(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply { text = label })
        row.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(position)
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        return row
    }
}
