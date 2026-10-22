package dev.wristbound.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import dev.wristbound.companion.hermes.Credentials
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.LoopbackReceiver
import dev.wristbound.companion.hermes.SignInException
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
        column.addView(note("Wristbound talks to the Hermes dashboard, like the desktop app and Hermes-Relay, so Telegram and desktop sessions can be read and continued. Use the dashboard address on your tailnet, e.g. https://hermes.tailnet-name.ts.net:10443 (tailscale serve in front of port 9119)."))
        val url = EditText(this).apply {
            hint = "Hermes dashboard URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(s.baseUrl)
        }
        column.addView(url)
        status = note(connectionSummary())
        column.addView(status)

        column.addView(note("Signed-in dashboards: sign in with your dashboard username and password, or in the browser for other providers."))
        val user = EditText(this).apply {
            hint = "Username"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(user)
        column.addView(pass)
        column.addView(Button(this).apply {
            text = "Sign in"
            setOnClickListener {
                s.baseUrl = url.text.toString()
                val u = user.text.toString()
                val p = pass.text.toString()
                pass.text.clear()
                signInWithPassword(u, p)
            }
        })
        column.addView(Button(this).apply {
            text = "Sign in with browser"
            setOnClickListener {
                s.baseUrl = url.text.toString()
                signInWithBrowser()
            }
        })

        column.addView(note("Loopback dashboards (no sign-in page): paste the session token the desktop app sets as HERMES_DASHBOARD_SESSION_TOKEN."))
        val token = EditText(this).apply {
            hint = "Session token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        column.addView(token)
        column.addView(Button(this).apply {
            text = "Use session token"
            setOnClickListener {
                s.baseUrl = url.text.toString()
                val t = token.text.toString()
                token.text.clear()
                val auth = controller.client()?.auth
                when {
                    auth == null -> status.text = "Invalid URL."
                    t.isBlank() -> status.text = "Enter the token."
                    else -> {
                        auth.useSessionToken(t)
                        testConnection()
                    }
                }
            }
        })
        column.addView(Button(this).apply {
            text = "Test connection"
            setOnClickListener {
                s.baseUrl = url.text.toString()
                testConnection()
            }
        })
        column.addView(Button(this).apply {
            text = "Sign out"
            setOnClickListener {
                controller.client()?.auth?.signOut()
                status.text = connectionSummary()
            }
        })

        column.addView(heading("Watch"))
        column.addView(switch("Show Telegram sessions", s.includeTelegram) { s.includeTelegram = it })
        column.addView(switch("Show tool activity on watch", s.toolActivity != ToolActivity.HIDDEN) {
            s.toolActivity = if (it) ToolActivity.SILENT else ToolActivity.HIDDEN
        })
        column.addView(Button(this).apply {
            text = "Open Wristbound on watch"
            setOnClickListener { lifecycleScope.launch { controller.openWatchApp() } }
        })

        column.addView(heading("Playback"))
        column.addView(switch("Use Hermes' voice", s.hermesVoice) { s.hermesVoice = it })
        column.addView(note("Replies with audio Hermes generated play that audio. Other replies are spoken in the voice set under tts: in Hermes, or by the phone when this is off or Hermes cannot speak."))

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

    private fun connectionSummary(): String = when (controller.settings.load()) {
        is Credentials.Native -> "Signed in."
        is Credentials.SessionToken -> "Using a session token."
        null -> if (controller.settings.baseUrl.isBlank()) "Not configured." else "Not signed in."
    }

    private fun signInWithPassword(username: String, password: String) {
        val auth = controller.client()?.auth ?: run { status.text = "Invalid URL."; return }
        if (username.isBlank() || password.isBlank()) {
            status.text = "Enter a username and password."
            return
        }
        status.text = "Signing in..."
        lifecycleScope.launch {
            try {
                val dashboard = auth.status()
                if (!dashboard.auth_required) {
                    status.text = "This dashboard has no sign-in page. Use its session token instead."
                    return@launch
                }
                val provider = auth.providers().firstOrNull { it.supports_password }
                if (provider == null) {
                    status.text = "This dashboard has no password sign-in. Use Sign in with browser."
                    return@launch
                }
                auth.signInWithPassword(provider.name, username, password)
                testConnection()
            } catch (e: Exception) {
                status.text = describe(e)
            }
        }
    }

    private fun signInWithBrowser() {
        val auth = controller.client()?.auth ?: run { status.text = "Invalid URL."; return }
        status.text = "Finish signing in in the browser..."
        lifecycleScope.launch {
            try {
                val dashboard = auth.status()
                if (!dashboard.auth_required) {
                    status.text = "This dashboard has no sign-in page. Use its session token instead."
                    return@launch
                }
                val receiver = LoopbackReceiver()
                val pending = auth.beginBrowserSignIn(receiver.redirectUri)
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.url)))
                val result = receiver.await()
                auth.completeBrowserSignIn(pending, result.code, result.state)
                testConnection()
            } catch (e: Exception) {
                status.text = describe(e)
            }
        }
    }

    private fun testConnection() {
        val s = controller.settings
        if (!s.isConfigured) {
            status.text = if (s.baseUrl.isBlank()) "Enter the dashboard URL." else "Sign in first."
            return
        }
        status.text = "Testing..."
        lifecycleScope.launch {
            status.text = try {
                val client = controller.client() ?: throw IllegalArgumentException()
                val sessions = client.listSessions(20)
                // Prove the gateway socket too: that is what sends replies.
                client.openGateway().close()
                controller.pushSessions()
                val telegram = sessions.count { it.isTelegram }
                "Connected. ${sessions.size} recent sessions, $telegram from Telegram."
            } catch (e: Exception) {
                describe(e)
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is SignInException -> e.message.orEmpty()
        is HermesHttpException -> if (e.isAuth) "Hermes rejected the sign-in. Sign in again." else "Hermes error: ${e.message}"
        is IllegalArgumentException -> "Invalid URL."
        else -> "Cannot reach Hermes: ${e.message}. Is Tailscale connected?"
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
