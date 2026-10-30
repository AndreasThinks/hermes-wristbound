package dev.wristbound.companion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.format.DateFormat
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.wristbound.companion.R
import dev.wristbound.companion.core.Account
import dev.wristbound.companion.core.AlertKind
import dev.wristbound.companion.core.AlertPreset
import dev.wristbound.companion.core.AlertRecord
import dev.wristbound.companion.core.AlertStyle
import dev.wristbound.companion.core.Health
import dev.wristbound.companion.core.HealthFix
import dev.wristbound.companion.core.LinkState
import dev.wristbound.companion.core.MessagingChats
import dev.wristbound.companion.core.ReplySpeed
import dev.wristbound.companion.core.ToolActivity
import dev.wristbound.companion.core.Wristbound
import dev.wristbound.companion.core.label
import dev.wristbound.companion.hermes.Credentials
import dev.wristbound.companion.hermes.DashboardAuth
import dev.wristbound.companion.hermes.DashboardClient
import dev.wristbound.companion.hermes.Endpoint
import dev.wristbound.companion.hermes.HermesHttpException
import dev.wristbound.companion.hermes.HermesProtocolException
import dev.wristbound.companion.hermes.LoopbackReceiver
import dev.wristbound.companion.hermes.SignInException
import dev.wristbound.companion.notify.Notifier
import dev.wristbound.companion.pebble.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Date
import javax.net.ssl.SSLException

/**
 * The companion's only screen. Signed out, it is the sign-in: the dashboard address first, then
 * only the sign-in that dashboard offers. Signed in, it opens on status: whether replies can reach
 * the wrist (Hermes → phone → Pebble) with one fix for the first broken link, what buzzes, and
 * recent alerts with the reason each one did or did not buzz. Everything else is one level down,
 * on pages drawn into the same scrolling column.
 */
class MainActivity : AppCompatActivity() {
    private val controller by lazy { Wristbound.get(this) }
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    private enum class Page(val title: String) {
        HOME(""), ALERTS("What buzzes"), SPEED("Reply speed"), WATCH("On the watch"), ADVANCED("Advanced"), ACCOUNT("Account")
    }

    private var page = Page.HOME
    /** Whether the page on screen was drawn signed in, so sign-in or sign-out redraws it. */
    private var drawnSignedIn: Boolean? = null
    private var showAllAlerts = false

    private lateinit var toolbar: Toolbar
    private lateinit var scroll: ScrollView
    private lateinit var column: LinearLayout
    /** Progress and errors from the last action; moved onto whichever page is showing. */
    private lateinit var status: TextView

    // The sign-in form, kept while signed out so typing survives redraws.
    private lateinit var url: EditText
    private lateinit var methods: LinearLayout
    private lateinit var passwordGroup: LinearLayout
    private lateinit var browserGroup: LinearLayout
    private lateinit var tokenGroup: LinearLayout
    private lateinit var otherWays: TextView

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = show(Page.HOME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The whole screen is black glass, so the system bars keep light icons in any theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        status = note("")
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
        }
        setContentView(screen(column))
        onBackPressedDispatcher.addCallback(this, back)
        page = savedInstanceState?.getString(KEY_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME
        show(page)
        requestNotificationPermission()
        controller.updateMonitoring()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { controller.backend.collect { render() } }
                launch { controller.alertLog.collect { render() } }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PAGE, page.name)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (controller.settings.isConfigured) checkConnection(quiet = true)
    }

    // -- pages ----------------------------------------------------------------------------------

    private fun show(next: Page) {
        page = if (signedIn()) next else Page.HOME
        back.isEnabled = page != Page.HOME
        toolbar.title = if (page == Page.HOME) wordmark() else "‹ ${page.title}"
        status.text = ""
        draw()
        scroll.scrollTo(0, 0)
    }

    /** Draw the current page from scratch. */
    private fun draw() {
        val signedIn = signedIn()
        drawnSignedIn = signedIn
        column.removeAllViews()
        (status.parent as? ViewGroup)?.removeView(status)
        if (!signedIn) {
            drawSignIn()
            return
        }
        when (page) {
            Page.HOME -> drawHome()
            Page.ALERTS -> drawAlerts()
            Page.SPEED -> drawSpeed()
            Page.WATCH -> drawWatch()
            Page.ADVANCED -> drawAdvanced()
            Page.ACCOUNT -> drawAccount()
        }
    }

    /**
     * Bring the screen up to date with saved state and the last backend result; safe to call any
     * time. The sign-in form is never redrawn under the user's typing.
     */
    private fun render() {
        if (!::column.isInitialized) return
        val signedIn = signedIn()
        if (signedIn != drawnSignedIn) {
            show(Page.HOME)
            return
        }
        if (!signedIn) {
            val s = controller.settings
            if (s.signInExpired && status.text.isBlank()) status.text = Account.view(null, s.baseUrl, true, controller.backend.value).connection
            return
        }
        if (page == Page.HOME || page == Page.ACCOUNT || page == Page.SPEED) {
            val y = scroll.scrollY
            draw()
            scroll.post { scroll.scrollTo(0, y) }
        }
    }

    private fun drawHome() {
        val s = controller.settings
        val health = health()
        column.addView(statusCard(health))
        column.addView(status)
        column.addView(button("Send a test alert", primary = false) { sendTestAlert() })

        column.addView(heading("Your wrist buzzes for"))
        val policy = s.alertPolicy
        val preset = AlertPreset.of(policy)
        column.addView(segmented(AlertPreset.entries.map { it.label }, preset.ordinal) { i ->
            val chosen = AlertPreset.entries[i]
            if (chosen == AlertPreset.CUSTOM) show(Page.ALERTS)
            else {
                s.alertPolicy = chosen.policy(s.alertPolicy) ?: s.alertPolicy
                draw()
            }
        })
        column.addView(note(preset.summary).apply { setPadding(0, dp(6), 0, dp(2)) })
        AlertKind.entries.forEach { kind ->
            val on = policy.enabled && kind.isOn(policy)
            column.addView(TextView(this).apply {
                text = "${if (on) "■" else "□"}  ${kind.label}"
                textSize = 13f
                setTextColor(color(if (on) R.color.phosphor_text else R.color.phosphor_meta))
                alpha = if (on) 1f else 0.6f
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        column.addView(row("Customise", "Choose each kind, alert style and privacy") { show(Page.ALERTS) })

        column.addView(heading("Recent alerts"))
        val log = controller.alertLog.value
        if (log.isEmpty()) {
            column.addView(note("Nothing yet. Each reply Hermes sends shows here, with whether it buzzed and why."))
        } else {
            val shown = if (showAllAlerts) log else log.take(RECENT_ALERTS)
            shown.forEach { column.addView(alertRow(it)) }
            if (log.size > RECENT_ALERTS) column.addView(textButton(if (showAllAlerts) "Show fewer" else "Show all ${log.size}") {
                showAllAlerts = !showAllAlerts
                draw()
            })
        }

        column.addView(heading("How fast"))
        val speed = s.replySpeed
        column.addView(row(speedTitle(), if (speed == ReplySpeed.WATCH_ONLY) "Background checking is off" else speed.note) { show(Page.SPEED) })

        column.addView(heading("On the watch"))
        val opens = s.quickLaunch?.let { QUICK_LAUNCH_CHOICES[it] } ?: "the watch's own choice"
        val listed = buildList {
            add("Chats")
            if (s.messagingChats.listed) add("messaging (${s.messagingChats.label.lowercase()})")
            if (s.showAutomation) add("automation")
        }.joinToString(", ")
        column.addView(row("Opens on $opens", listed) { show(Page.WATCH) })

        column.addView(heading("More"))
        column.addView(row("Advanced", "Tool activity, alert style, privacy, cache") { show(Page.ADVANCED) })
        val account = Account.view(s.credentials, s.baseUrl, s.signInExpired, controller.backend.value)
        val where = Endpoint.display(s.baseUrl)
        val who = account.detail.lines().firstOrNull { it.isNotBlank() && it != where } ?: account.heading
        column.addView(row(where, "$who · ${account.connection}") { show(Page.ACCOUNT) })
    }

    /** Every alert switch, with what it covers; picking any of them by hand makes the preset Custom. */
    private fun drawAlerts() {
        val s = controller.settings
        column.addView(note("A voice reply always follows the Voice switch, from any session. A text reply follows the switch for where it came from."))
        column.addView(switch("Alerts", s.notifyReplies) {
            s.notifyReplies = it
            draw()
        })
        AlertKind.entries.forEach { kind ->
            column.addView(switch(kind.label, kind.isOn(s.alertPolicy)) { s.alertPolicy = kind.set(s.alertPolicy, it) }.apply { isEnabled = s.notifyReplies })
            column.addView(note(kind.note).apply { alpha = if (s.notifyReplies) 1f else 0.5f })
        }
        column.addView(heading("Alert style"))
        drawAlertStyle()
        column.addView(heading("Privacy"))
        column.addView(switch("Hide reply text", s.privatePreviews) { s.privatePreviews = it })
        column.addView(note("Alerts say a reply arrived without showing it, on the lock screen and on the Pebble's copy."))
    }

    private fun drawSpeed() {
        val s = controller.settings
        column.addView(note("Replies to what you ask from the watch stream in live. This is for everything else: the desktop, Telegram, and Hermes's own follow-ups."))
        val current = s.replySpeed
        ReplySpeed.entries.forEach { speed ->
            column.addView(option(speed.title, speed.note, speed == current) {
                s.replySpeed = speed
                controller.updateMonitoring()
                draw()
            })
        }
        if (current != ReplySpeed.WATCH_ONLY && !batteryExempt()) {
            column.addView(problemCard("Android may slow this down",
                "Battery is optimised, so checks can be held back for many minutes while the phone sleeps.",
                HealthFix.BATTERY.label) { requestBatteryExemption() })
        }
        column.addView(note("While the watchapp is open it checks every ${Wristbound.FOREGROUND_POLL_SECONDS} s whatever you pick here. Background checking shows a silent ongoing notification, which Android requires.")
            .apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun drawWatch() {
        val s = controller.settings
        column.addView(button("Open Wristbound on the watch", primary = false) { lifecycleScope.launch { controller.openWatchApp() } })

        column.addView(heading("Opens on"))
        val chosen = s.quickLaunch
        if (chosen == null) column.addView(note("Not chosen here yet, so the watch keeps its own choice."))
        QUICK_LAUNCH_CHOICES.forEachIndexed { i, label ->
            column.addView(option(label, null, chosen == i) {
                s.quickLaunch = i
                controller.onWatchSettingsChanged()
                draw()
            })
        }

        column.addView(heading("Messaging chats"))
        column.addView(note("Telegram, Discord, Slack, Signal, WhatsApp, email and other messaging platforms."))
        val messaging = s.messagingChats
        MessagingChats.entries.forEach { choice ->
            column.addView(option(choice.label, choice.note, choice == messaging) {
                s.messagingChats = choice
                controller.onWatchSettingsChanged()
                draw()
            })
        }

        column.addView(heading("Other sessions"))
        column.addView(switch("Show scheduled and automation", s.showAutomation) {
            s.showAutomation = it
            controller.onWatchSettingsChanged()
        })
        column.addView(note("Cron jobs, Kanban workers and webhooks. Pinned sessions always come first."))

        column.addView(heading("While Hermes works"))
        val tool = s.toolActivity
        column.addView(segmented(ToolActivity.entries.map { it.label() }, tool.ordinal) {
            s.toolActivity = ToolActivity.entries[it]
            draw()
        })
        column.addView(note(when (tool) {
            ToolActivity.HIDDEN -> "The watch shows only questions and answers."
            ToolActivity.SILENT -> "The watch shows \"Using web_search\" while Hermes works, and tool steps in the conversation."
            ToolActivity.NOTIFY -> "As On watch, and with the watchapp closed, a phone notification for each tool Hermes uses."
        }).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun drawAdvanced() {
        val s = controller.settings
        column.addView(heading("Alert style"))
        drawAlertStyle()
        column.addView(heading("Privacy"))
        column.addView(switch("Hide reply text", s.privatePreviews) { s.privatePreviews = it })
        column.addView(note("Alerts say a reply arrived without showing it, on the lock screen and on the Pebble's copy."))
        column.addView(button("Clear cached conversations", primary = false) { confirmClearCache() })
        column.addView(status)
        column.addView(heading("Troubleshooting"))
        column.addView(note("If the test alert never reaches the watch, check that Wristbound is allowed in the Pebble app's notification settings, and whether it skips notifications while the phone is in use. Wristbound's \"Hermes replies\" channel is silent on the phone by default, so only the watch buzzes."))
    }

    private fun drawAccount() {
        val s = controller.settings
        val view = Account.view(s.credentials, s.baseUrl, s.signInExpired, controller.backend.value)
        column.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply { setStroke(dp(1), color(R.color.phosphor_signal)) }
            addView(TextView(this@MainActivity).apply {
                text = view.heading
                textSize = 17f
                typeface = Typeface.MONOSPACE
                setTextColor(color(R.color.phosphor_signal))
            })
            addView(note(view.detail))
            addView(note("Hermes: ${view.connection}"))
        }.also { it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) } })
        column.addView(status)
        column.addView(button("Test connection", primary = false) { checkConnection(quiet = false) })
        column.addView(button("Sign out", primary = false) { confirmSignOut() })
    }

    /** The two ways an alert can look on the watch, with a drawing of each. */
    private fun drawAlertStyle() {
        val s = controller.settings
        val current = s.alertStyle
        column.addView(pebblePreview(current))
        AlertStyle.entries.forEach { style ->
            column.addView(option(style.label, style.note, style == current) {
                s.alertStyle = style
                draw()
            })
        }
    }

    // -- sign-in --------------------------------------------------------------------------------

    /**
     * First run, or signed out: the dashboard address, then only the sign-in that dashboard
     * offers. "Other ways to sign in" shows them all, for a dashboard that cannot be probed.
     */
    private fun drawSignIn() {
        if (!::url.isInitialized) buildSignIn()
        column.addView(masthead())
        column.addView(heading("Connect Hermes"))
        column.addView(note("The address of your Hermes dashboard, on your tailnet: for example https://hermes.tailnet-name.ts.net:10443 (tailscale serve in front of port 9119)."))
        column.addView(url)
        column.addView(button("Continue") { probe() })
        column.addView(status)
        column.addView(methods)
        column.addView(otherWays)
    }

    private fun buildSignIn() {
        url = EditText(this).apply {
            id = R.id.setup_url
            hint = "Hermes dashboard URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(controller.settings.baseUrl)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    error = (Endpoint.validate(s?.toString().orEmpty()) as? Endpoint.Check.Invalid)
                        ?.takeIf { s?.isNotBlank() == true }?.reason
                    // A different address may sign in differently: ask again.
                    if (::methods.isInitialized) showMethods(password = false, browser = false, token = false)
                }
            })
        }

        passwordGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(subheading("Sign in"))
            addView(note("Your dashboard username and password."))
            val user = EditText(this@MainActivity).apply {
                id = R.id.setup_username
                hint = "Username"
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val pass = EditText(this@MainActivity).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
            }
            addView(user)
            addView(pass)
            addView(button("Sign in") {
                val p = pass.text.toString()
                pass.text.clear()
                signInWithPassword(user.text.toString(), p)
            })
        }
        browserGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(note("Or sign in with your dashboard's provider in the browser.").apply { setPadding(0, dp(12), 0, 0) })
            addView(button("Sign in with browser", primary = false) { signInWithBrowser() })
        }
        tokenGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(subheading("Session token"))
            addView(note("For a dashboard on a loopback bind with no sign-in page: paste the token the desktop app sets as HERMES_DASHBOARD_SESSION_TOKEN."))
            val token = EditText(this@MainActivity).apply {
                id = R.id.setup_token
                hint = "Session token"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
            }
            addView(token)
            addView(button("Use session token") {
                val t = token.text.toString()
                token.text.clear()
                useSessionToken(t)
            })
        }
        methods = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(passwordGroup)
            addView(browserGroup)
            addView(tokenGroup)
        }
        otherWays = textButton("Other ways to sign in") { showMethods(password = true, browser = true, token = true) }
        showMethods(password = false, browser = false, token = false)
    }

    private fun showMethods(password: Boolean, browser: Boolean, token: Boolean) {
        passwordGroup.visibility = if (password) View.VISIBLE else View.GONE
        browserGroup.visibility = if (browser) View.VISIBLE else View.GONE
        tokenGroup.visibility = if (token) View.VISIBLE else View.GONE
        otherWays.visibility = if (password && browser && token) View.GONE else View.VISIBLE
    }

    /** Ask the dashboard how it signs in, and show only that. */
    private fun probe() {
        val endpoint = draftEndpoint() ?: return
        val auth = DashboardAuth(endpoint, controller.settings.credentialsFor(endpoint))
        status.text = "Checking the dashboard..."
        lifecycleScope.launch {
            try {
                if (!auth.status().auth_required) {
                    showMethods(password = false, browser = false, token = true)
                } else {
                    val password = auth.providers().any { it.supports_password }
                    showMethods(password = password, browser = true, token = false)
                }
                status.text = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("probe", e)
                status.text = describe(e, endpoint)
            }
        }
    }

    // -- state ----------------------------------------------------------------------------------

    private fun signedIn() = Account.view(controller.settings.credentials, controller.settings.baseUrl,
        controller.settings.signInExpired, controller.backend.value).signedIn

    private fun batteryExempt() = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun health(): Health {
        val s = controller.settings
        return Health.of(
            signedIn = signedIn(),
            backend = controller.backend.value,
            canNotify = Notifier(this).canPost(),
            batteryExempt = batteryExempt(),
            speed = s.replySpeed,
            intervalSeconds = controller.pollIntervalSeconds(),
            watchOpen = controller.watchOpen,
            lastCheckAt = s.lastCheckAt,
            now = System.currentTimeMillis(),
        )
    }

    private fun speedTitle(): String {
        val s = controller.settings
        val speed = s.replySpeed
        return if (speed == ReplySpeed.WATCH_ONLY) speed.label else "${speed.label} · within ${ReplySpeed.within(s.pollSeconds)}"
    }

    private fun fix(fix: HealthFix) = when (fix) {
        HealthFix.SIGN_IN, HealthFix.SIGN_IN_AGAIN -> show(Page.ACCOUNT)
        HealthFix.RETRY -> {
            checkConnection(quiet = false)
            controller.scope.launch { controller.pollOnce() }
        }
        HealthFix.NOTIFICATIONS -> fixNotifications()
        HealthFix.BATTERY -> requestBatteryExemption()
    }

    /** The address in the form, if it is valid; otherwise says why and returns null. */
    private fun draftEndpoint(): String? = when (val check = Endpoint.validate(url.text.toString())) {
        is Endpoint.Check.Valid -> check.url
        is Endpoint.Check.Invalid -> {
            url.error = check.reason
            status.text = check.reason
            null
        }
    }

    /**
     * Sign in to [endpoint] with credentials bound to it. The active dashboard only changes once
     * [block] has succeeded, so a failed sign-in to a mistyped address leaves everything as it was.
     */
    private fun signIn(stage: String, progress: String, block: suspend (DashboardAuth) -> Unit) {
        val endpoint = draftEndpoint() ?: return
        val auth = DashboardAuth(endpoint, controller.settings.credentialsFor(endpoint))
        status.text = progress
        lifecycleScope.launch {
            try {
                block(auth)
                activate(endpoint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(stage, e)
                status.text = describe(e, endpoint)
            }
        }
    }

    private fun activate(endpoint: String) {
        controller.settings.baseUrl = endpoint
        controller.onCredentialsChanged()
        render()
        checkConnection(quiet = false)
    }

    private fun signInWithPassword(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            status.text = "Enter a username and password."
            return
        }
        signIn("password", "Signing in...") { auth ->
            requireSignInPage(auth)
            val provider = auth.providers().firstOrNull { it.supports_password }
                ?: throw SignInException("This dashboard has no password sign-in. Use Sign in with browser.")
            auth.signInWithPassword(provider.name, username, password)
        }
    }

    private fun signInWithBrowser() = signIn("browser", "Finish signing in in the browser...") { auth ->
        requireSignInPage(auth)
        val receiver = LoopbackReceiver()
        val pending = auth.beginBrowserSignIn(receiver.redirectUri)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.url)))
        val result = receiver.await()
        auth.completeBrowserSignIn(pending, result.code, result.state)
    }

    private suspend fun requireSignInPage(auth: DashboardAuth) {
        if (!auth.status().auth_required) {
            throw SignInException("This dashboard has no sign-in page. Use its session token instead.")
        }
    }

    /** Check the token against the dashboard before saving it, so a bad one changes nothing. */
    private fun useSessionToken(token: String) {
        if (token.isBlank()) {
            status.text = "Enter the token."
            return
        }
        signIn("session token", "Checking the token...") { auth ->
            val probe = object : dev.wristbound.companion.hermes.CredentialStore {
                override fun load() = Credentials.SessionToken(token.trim())
                override fun save(credentials: Credentials?) {}
            }
            DashboardClient(DashboardAuth(auth.base.toString(), probe)).listSessions(1)
            auth.useSessionToken(token)
        }
    }

    private fun checkConnection(quiet: Boolean) {
        if (!quiet) status.text = "Testing..."
        lifecycleScope.launch {
            try {
                val (total, telegram) = controller.checkConnection()
                controller.pushSessions()
                if (!quiet) status.text = "Connected. $total recent sessions, $telegram from Telegram."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure("connection test", e)
                if (!quiet) status.text = describe(e, controller.settings.baseUrl)
            }
            render()
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out of Hermes?")
            .setMessage("The watch stops showing sessions and replies until you sign in again.")
            .setPositiveButton("Sign out") { _, _ ->
                val s = controller.settings
                s.credentialsFor(s.baseUrl).save(null)
                s.signInExpired = false
                controller.onCredentialsChanged()
                render()
                status.text = "Signed out."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("Clear cached conversations?")
            .setMessage("The watch fetches sessions again from Hermes, and the recent alerts list starts empty.")
            .setPositiveButton("Clear") { _, _ ->
                controller.clearCache()
                status.text = "Cache cleared."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Record where and how a sign-in or check failed, without any secret or server text. */
    private fun logFailure(stage: String, e: Exception) {
        val http = (e as? HermesHttpException)?.let { " HTTP ${it.status}" }.orEmpty()
        Log.w(TAG, "$stage failed: ${e.javaClass.simpleName}$http")
    }

    private fun describe(e: Exception, endpoint: String): String {
        val host = runCatching { Uri.parse(endpoint).host }.getOrNull() ?: "the dashboard"
        return when (e) {
            is SignInException -> e.message.orEmpty()
            is HermesHttpException -> when {
                e.isAuth -> "Hermes rejected the sign-in. Check the username, password or token."
                e.status in 300..399 -> "Hermes redirected the request. Use the dashboard's final address (usually https://)."
                e.status == 404 -> "No Hermes dashboard at that address. Check the port (usually 9119, or your tailscale serve port)."
                else -> "Hermes error: ${e.message}"
            }
            is HermesProtocolException -> "That address answered, but not like a Hermes dashboard. Check the port (usually 9119, or your tailscale serve port)."
            is UnknownHostException -> "Cannot find $host. Is Tailscale connected?"
            is SSLException -> "Secure connection to $host failed. Check whether the address should be https:// or http://."
            is ConnectException, is SocketTimeoutException -> "Cannot reach $host. Is Tailscale connected and the dashboard running?"
            is IOException -> "Cannot reach Hermes: ${e.message}"
            else -> "Unexpected problem (${e.javaClass.simpleName})."
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    /**
     * Ask to be left out of battery optimisation, so Doze does not hold back checks and turns.
     * Play restricts this request, but the companion is installed directly, so it does not apply.
     */
    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val ask = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(ask) }
            .recoverCatching { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /** A test alert 10 s from now, so there is time to lock the phone before it is posted. */
    private fun sendTestAlert() {
        if (!Notifier(this).canPost()) {
            status.text = "Notifications are blocked, so the test alert cannot be sent."
            return
        }
        status.text = "Lock the phone now: a test alert follows in about 10 seconds."
        val app = applicationContext
        val lock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wristbound:test")
            ?.apply { acquire(TEST_DELAY_MS + 5_000L) }
        controller.scope.launch {
            try {
                delay(TEST_DELAY_MS)
                Notifier(app).testAlert()
            } finally {
                if (lock?.isHeld == true) lock.release()
            }
        }
    }

    /** Ask again, or open the app's notification settings once Android stops asking. */
    private fun fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestNotificationPermission()
            return
        }
        startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName))
    }

    // -- layout ---------------------------------------------------------------------------------

    /**
     * A toolbar that stays put above a scrolling column. Edge-to-edge is on (and enforced from
     * Android 15), so the system bars are kept clear with their real insets: the toolbar takes
     * the status bar and cutout, the column takes the navigation bar or the keyboard. On a page
     * below Home, tapping the title goes back.
     */
    private fun screen(content: View): View {
        toolbar = Toolbar(ContextThemeWrapper(this, androidx.appcompat.R.style.ThemeOverlay_AppCompat_Dark_ActionBar)).apply {
            title = wordmark()
            setTitleTextAppearance(this@MainActivity, R.style.TextAppearance_Wristbound_Title)
            setBackgroundColor(color(R.color.phosphor_glass))
            setOnClickListener { if (page != Page.HOME) show(Page.HOME) }
        }
        scroll = ScrollView(this).apply {
            id = R.id.setup_scroll
            clipToPadding = false
            isFillViewport = true
            addView(content)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setSupportActionBar(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            toolbar.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            scroll.updatePadding(left = bars.left, right = bars.right, bottom = maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    /** "wristbound" and a green block cursor, as the watch shows Hermes working. */
    private fun wordmark() = SpannableString("wristbound █").apply {
        setSpan(ForegroundColorSpan(color(R.color.phosphor_signal)), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // -- widgets --------------------------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun fullWidth(topMargin: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }

    /** The Hermes girl beside the name, as on the watch's launch screen. */
    private fun masthead(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, pad, 0, 0)
        addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.hermes_girl)
            contentDescription = "Hermes"
            adjustViewBounds = true
        }, LinearLayout.LayoutParams(dp(72), dp(84)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Hermes on your wrist"
                textSize = 17f
                typeface = Typeface.MONOSPACE
                setTextColor(color(R.color.phosphor_text))
            })
            addView(TextView(this@MainActivity).apply {
                text = "Replies, audio and quick answers, relayed to your Pebble."
                textSize = 13f
                setTextColor(color(R.color.phosphor_note))
            })
        })
    }

    /**
     * Hermes → phone → Pebble, each link green, amber or grey, with what it means in a sentence
     * and the one fix for the first broken link.
     */
    private fun statusCard(health: Health): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = fullWidth(dp(12))
        background = GradientDrawable().apply {
            setColor(if (health.ok) color(R.color.phosphor_panel) else (color(R.color.phosphor_warn) and 0x00FFFFFF) or 0x1F000000)
            setStroke(dp(1), color(if (health.ok) R.color.phosphor_signal else R.color.phosphor_warn))
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(chainNode("H", "Hermes", health.hermes))
            addView(chainLink(health.hermes == LinkState.PROBLEM || health.phone == LinkState.PROBLEM))
            addView(chainNode("P", "Phone", health.phone))
            addView(chainLink(health.phone == LinkState.PROBLEM))
            addView(chainNode("W", "Pebble", health.watch))
        }, fullWidth())
        addView(TextView(this@MainActivity).apply {
            text = health.headline
            textSize = 15f
            setTextColor(color(R.color.phosphor_text))
            setPadding(0, dp(10), 0, 0)
        })
        addView(note(health.detail))
        health.fix?.let { f -> addView(button(f.label, warn = true) { fix(f) }) }
    }

    private fun chainNode(letter: String, label: String, state: LinkState): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val tint = color(when (state) {
            LinkState.OK -> R.color.phosphor_signal
            LinkState.PROBLEM -> R.color.phosphor_warn
            LinkState.UNKNOWN -> R.color.phosphor_meta
        })
        addView(TextView(this@MainActivity).apply {
            text = letter
            gravity = Gravity.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(tint)
            background = GradientDrawable().apply {
                setColor((tint and 0x00FFFFFF) or 0x22000000)
                setStroke(dp(2), tint)
            }
            contentDescription = "$label: ${state.name.lowercase()}"
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.phosphor_meta))
        })
    }

    private fun chainLink(broken: Boolean): View = View(this).apply {
        setBackgroundColor(color(if (broken) R.color.phosphor_warn else R.color.phosphor_signal))
        alpha = if (broken) 0.6f else 1f
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(2)).apply {
            leftMargin = dp(6); rightMargin = dp(6); bottomMargin = dp(16)
        }
    }

    /** One alert decision: when, which session, a preview, and why it buzzed or did not. */
    private fun alertRow(r: AlertRecord): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(this@MainActivity).apply {
            text = DateFormat.getTimeFormat(this@MainActivity).format(Date(r.at))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.phosphor_meta))
        }, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = r.title
                textSize = 14f
                setTextColor(color(R.color.phosphor_text))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (r.preview.isNotBlank()) addView(note(r.preview).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val tint = color(when {
                r.outcome == AlertRecord.Outcome.FAILED -> R.color.phosphor_warn
                r.outcome == AlertRecord.Outcome.SILENT -> R.color.phosphor_meta
                r.audio -> R.color.phosphor_voice
                else -> R.color.phosphor_signal
            })
            addView(TextView(this@MainActivity).apply {
                text = r.label
                textSize = 10f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(tint)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = GradientDrawable().apply { setStroke(dp(1), tint) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** A drawing of what an alert looks like on the watch in [style]; example content only. */
    private fun pebblePreview(style: AlertStyle): View {
        val screen = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(1), color(R.color.phosphor_rule)) }
        }
        fun line(text: String, size: Float, colorId: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color(colorId))
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        when (style) {
            AlertStyle.NOTIFICATION -> screen.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(line("Wristbound · Morning brief", 10f, R.color.phosphor_meta, bold = true))
                addView(line("Audio reply available", 13f, R.color.phosphor_text, bold = true))
                addView(line("Three meetings today; the 10:00 moved to 10:30. Rain after 4.", 11f, R.color.phosphor_note))
            })
            AlertStyle.BANNER -> {
                screen.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    addView(line("HOME", 10f, R.color.phosphor_meta, bold = true, mono = true))
                    addView(line("▸ New session", 11f, R.color.phosphor_text))
                    addView(line("▸ Continue", 11f, R.color.phosphor_text))
                })
                screen.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = GradientDrawable().apply { setColor(color(R.color.phosphor_panel)) }
                    addView(View(this@MainActivity).apply { setBackgroundColor(color(R.color.phosphor_voice)) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).apply { bottomMargin = dp(4) })
                    addView(line("AUDIO 1:12 · SELECT: PLAY", 9f, R.color.phosphor_voice, bold = true, mono = true))
                    addView(line("Morning brief", 12f, R.color.phosphor_text, bold = true))
                    addView(line("Three meetings today; the 10:00 moved…", 10f, R.color.phosphor_note))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
        }
        val bezel = FrameLayout(this).apply {
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply { setColor(0xFF1B1B22.toInt()); cornerRadius = dp(20).toFloat() }
            addView(screen, FrameLayout.LayoutParams(dp(150), dp(150)))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
            addView(bezel)
            addView(TextView(this@MainActivity).apply {
                text = if (style == AlertStyle.BANNER) "· 50 · 100 · 50 · 100 · 250 ·" else "· Pebble's own vibration ·"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(color(R.color.phosphor_voice))
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun problemCard(title: String, detail: String, action: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        layoutParams = fullWidth(dp(12))
        background = GradientDrawable().apply {
            setColor((color(R.color.phosphor_warn) and 0x00FFFFFF) or 0x1F000000)
            setStroke(dp(1), color(R.color.phosphor_warn))
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 15f
            setTextColor(color(R.color.phosphor_text))
        })
        addView(note(detail))
        addView(button(action, warn = true, onClick = onClick))
    }

    /** "// SECTION": the watch's section headers, in the same grey. */
    private fun heading(text: String) = TextView(this).apply {
        this.text = "// ${text.uppercase()}"
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.06f
        setTextColor(color(R.color.phosphor_meta))
        setPadding(0, dp(24), 0, dp(6))
    }

    private fun subheading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.MONOSPACE
        setTextColor(color(R.color.phosphor_text))
        setPadding(0, dp(12), 0, 2)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(R.color.phosphor_note))
    }

    /** Green and filled for the main action, amber for a fix, outlined for the rest; square, like the watch. */
    private fun button(label: String, primary: Boolean = true, warn: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        stateListAnimator = null
        val face = GradientDrawable().apply {
            when {
                warn -> setColor(color(R.color.phosphor_warn))
                primary -> setColor(color(R.color.phosphor_signal))
                else -> setStroke(dp(1), color(R.color.phosphor_meta))
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.phosphor_voice)), face, null)
        setTextColor(color(if (primary || warn) R.color.phosphor_glass else R.color.phosphor_text))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    /** A quiet text link, for actions that should not compete with the page. */
    private fun textButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(color(R.color.phosphor_signal))
        setPadding(0, dp(12), 0, dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = SwitchCompat(this).apply {
        text = label
        isChecked = checked
        textSize = 15f
        setTextColor(color(R.color.phosphor_text))
        layoutParams = fullWidth(dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    /** A row that opens a page: title, one line of what is set now, and a chevron. */
    private fun row(title: String, detail: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.phosphor_rule)), null, GradientDrawable().apply { setColor(Color.WHITE) })
        setOnClickListener { onClick() }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 15f
                setTextColor(color(R.color.phosphor_text))
            })
            if (detail.isNotBlank()) addView(note(detail))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "›"
            textSize = 20f
            setTextColor(color(R.color.phosphor_meta))
            setPadding(dp(8), 0, 0, 0)
        })
    }

    /** One choice among several: a ring, a title and what picking it means. */
    private fun option(title: String, detail: String?, selected: Boolean, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = fullWidth(dp(8))
        isClickable = true
        isFocusable = true
        contentDescription = if (selected) "$title, selected" else title
        val face = GradientDrawable().apply {
            setColor(color(R.color.phosphor_panel))
            setStroke(dp(1), color(if (selected) R.color.phosphor_signal else R.color.phosphor_rule))
        }
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.phosphor_rule)), face, null)
        setOnClickListener { onClick() }
        addView(TextView(this@MainActivity).apply {
            text = if (selected) "◉" else "○"
            textSize = 16f
            setTextColor(color(if (selected) R.color.phosphor_signal else R.color.phosphor_meta))
            setPadding(0, 0, dp(10), 0)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                setTextColor(color(R.color.phosphor_text))
            })
            if (detail != null) addView(note(detail).apply { textSize = 12f })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Side-by-side choices in one outlined bar; the chosen one is filled green. */
    private fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setStroke(dp(1), color(R.color.phosphor_rule)) }
        labels.forEachIndexed { i, label ->
            addView(TextView(this@MainActivity).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                maxLines = 1
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(2), dp(10), dp(2), dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = if (i == selected) "$label, selected" else label
                if (i == selected) {
                    setBackgroundColor(color(R.color.phosphor_signal))
                    setTextColor(color(R.color.phosphor_glass))
                } else {
                    setTextColor(color(R.color.phosphor_note))
                }
                setOnClickListener { onSelect(i) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private companion object {
        const val TAG = "WristboundSetup"
        const val KEY_PAGE = "page"
        const val TEST_DELAY_MS = 10_000L
        const val RECENT_ALERTS = 5
        /** In [Protocol.QuickLaunch] order. */
        val QUICK_LAUNCH_CHOICES = listOf("Home", "Dictate a new session", "Recent audio", "Play latest audio", "Last session")
    }
}
