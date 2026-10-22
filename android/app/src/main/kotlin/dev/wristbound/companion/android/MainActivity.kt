package dev.wristbound.companion.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.wristbound.companion.core.AlertScope
import dev.wristbound.companion.core.HermesClient
import dev.wristbound.companion.core.ReplyAlertMode
import dev.wristbound.companion.core.Submitter
import dev.wristbound.companion.core.ToolActivityMode
import dev.wristbound.companion.core.displayTitle
import dev.wristbound.companion.core.isTelegram
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Setup and status screen. Day-to-day use happens on the watch. */
class MainActivity : Activity() {
    private val scope = MainScope()
    private val hub get() = (application as WristboundApp).hub
    private lateinit var statusView: TextView
    private lateinit var sessionsView: TextView
    private lateinit var testView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = hub.settings
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 48, 40, 48) }

        root.addView(header("Hermes connection"))
        root.addView(note("Hermes API server URL, e.g. https://hermes.<tailnet>.ts.net (tailscale serve → 127.0.0.1:8642). The API_SERVER_KEY is required: Tailscale reachability is not authentication."))
        val url = field("Hermes API server URL", s.baseUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val token = field(if (s.apiToken.isNullOrBlank()) "API_SERVER_KEY" else "API key stored (leave blank to keep)", "",
            InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val adapter = field("Optional media adapter URL (Hermes audio)", s.mediaAdapterUrl, InputType.TYPE_TEXT_VARIATION_URI)
        val poll = field("Poll interval seconds (15–900)", s.pollSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        listOf(url, token, adapter, poll).forEach(root::addView)

        testView = note("")
        root.addView(button("Save and test connection") {
            s.baseUrl = url.text.toString()
            token.text.toString().takeIf { it.isNotBlank() }?.let { s.apiToken = it }
            s.mediaAdapterUrl = adapter.text.toString()
            poll.text.toString().toIntOrNull()?.let { s.pollSeconds = it }
            token.setText(""); token.hint = "API key stored (leave blank to keep)"
            hub.onSettingsChanged()
            testConnection()
        })
        root.addView(button("Forget API key") { s.apiToken = null; token.hint = "API_SERVER_KEY"; hub.onSettingsChanged() })
        root.addView(testView)

        root.addView(header("Background"))
        root.addView(check("Keep Hermes and the watch in sync in the background", s.serviceEnabled) { on ->
            s.serviceEnabled = on
            if (on) WristboundService.start(this) else WristboundService.stop(this)
        })
        root.addView(check("Low latency (keeps CPU awake; higher battery use)", s.lowLatency) { on ->
            s.lowLatency = on
            if (s.serviceEnabled) WristboundService.start(this)
        })
        root.addView(button("Allow background activity (battery optimisation)") { requestBatteryExemption() })

        root.addView(header("Sessions and alerts"))
        val p = s.alertPrefs
        root.addView(check("Include Telegram-origin sessions", p.includeTelegram) { on -> s.updateAlertPrefs { it.copy(includeTelegram = on) }; hub.requestPoll() })
        root.addView(check("Alert for sessions hidden by the filter (off: hidden means silent)", p.alertFilteredSessions) { on -> s.updateAlertPrefs { it.copy(alertFilteredSessions = on) } })
        root.addView(check("Show reply text in notifications", p.notificationPreviews) { on -> s.updateAlertPrefs { it.copy(notificationPreviews = on) } })
        root.addView(cycle("Final replies", ReplyAlertMode.entries, p.replies, { replyLabel(it) }) { v -> s.updateAlertPrefs { it.copy(replies = v) } })
        root.addView(cycle("Tool activity", ToolActivityMode.entries, p.toolActivity, { toolLabel(it) }) { v -> s.updateAlertPrefs { it.copy(toolActivity = v) } })
        root.addView(cycle("Alert scope", AlertScope.entries, p.scope, { Settings.scopeLabel(it) }) { v -> s.updateAlertPrefs { it.copy(scope = v) } })

        root.addView(header("Status"))
        statusView = note("")
        sessionsView = note("")
        root.addView(statusView)
        root.addView(button("Sync now") { hub.requestPoll() })
        root.addView(sessionsView)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        scope.launch {
            hub.status.collect { st ->
                val last = st.lastPollOkAt?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: "never"
                statusView.text = "${st.text} · last sync $last · service ${if (WristboundService.running) "running" else "stopped"}"
                sessionsView.text = st.sessions.take(15).joinToString("\n") { sess ->
                    val unread = if (hub.snapshot().isUnread(sess.id)) "● " else "   "
                    val tg = if (sess.isTelegram()) " [Telegram]" else ""
                    "$unread${sess.displayTitle()}$tg"
                }.ifBlank { "No sessions loaded" }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun testConnection() {
        testView.text = "Testing…"
        scope.launch {
            val api = hub.api() as? HermesClient
            if (api == null) { testView.text = "Enter URL and API key"; return@launch }
            testView.text = try {
                val health = runCatching { api.health() }.getOrDefault(false)
                val features = api.capabilities()
                val sessions = api.listSessions(50, 0).data
                val tg = sessions.count { it.isTelegram() }
                "Health ${if (health) "ok" else "?"} · auth ok · ${sessions.size} recent sessions ($tg Telegram) · " +
                    "durable idempotency: ${Submitter.durableIdempotency(features)}"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            hub.requestPoll()
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            testView.text = "Already exempt from battery optimisation"
            return
        }
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun replyLabel(m: ReplyAlertMode) = when (m) {
        ReplyAlertMode.NOTIFY -> "Show and vibrate"
        ReplyAlertMode.SHOW_SILENTLY -> "Show silently"
    }

    private fun toolLabel(m: ToolActivityMode) = when (m) {
        ToolActivityMode.HIDDEN -> "Hidden"
        ToolActivityMode.SHOW_SILENTLY -> "Show silently"
        ToolActivityMode.NOTIFY -> "Notify"
    }

    // ------------------------------------------------------------------------- tiny view kit

    private fun header(t: String) = TextView(this).apply { text = t; textSize = 20f; setPadding(0, 36, 0, 8) }
    private fun note(t: String) = TextView(this).apply { text = t; textSize = 14f; setPadding(0, 4, 0, 4) }
    private fun field(hintText: String, value: String, type: Int) = EditText(this).apply {
        hint = hintText; setText(value); isSingleLine = true
        inputType = if (type == InputType.TYPE_CLASS_NUMBER) type else InputType.TYPE_CLASS_TEXT or type
    }
    private fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() } }
    private fun check(t: String, value: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = t; isChecked = value; setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    private fun <T> cycle(label: String, values: List<T>, initial: T, name: (T) -> String, onChange: (T) -> Unit): View {
        var idx = values.indexOf(initial).coerceAtLeast(0)
        return Button(this).apply {
            text = "$label: ${name(values[idx])}"
            setOnClickListener {
                idx = (idx + 1) % values.size
                text = "$label: ${name(values[idx])}"
                onChange(values[idx])
            }
        }
    }
}
