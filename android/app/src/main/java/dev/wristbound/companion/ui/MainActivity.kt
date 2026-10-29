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
import android.text.format.DateUtils
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
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
import dev.wristbound.companion.core.ToolActivity
import dev.wristbound.companion.core.Wristbound
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
import javax.net.ssl.SSLException

/**
 * Setup screen: Hermes account and connection, watch, playback, notifications and background
 * checking. Signed out, it shows the sign-in options; signed in, an account card instead, so a
 * working sign-in is never next to a form inviting another one.
 */
class MainActivity : AppCompatActivity() {
    private val controller by lazy { Wristbound.get(this) }
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    private lateinit var signedOutGroup: LinearLayout
    private lateinit var signedInGroup: LinearLayout
    private lateinit var accountHeading: TextView
    private lateinit var accountDetail: TextView
    private lateinit var accountConnection: TextView
    private lateinit var watchStatus: TextView
    private lateinit var monitoring: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var notificationButton: Button
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var url: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // The whole screen is black glass, so the system bars keep light icons in any theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val s = controller.settings
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
        }

        column.addView(masthead())
        column.addView(heading("Hermes"))
        signedInGroup = accountCard()
        column.addView(signedInGroup)
        signedOutGroup = signInForm()
        column.addView(signedOutGroup)
        status = note("")
        column.addView(status)

        column.addView(heading("Watch"))
        watchStatus = note("")
        column.addView(watchStatus)
        column.addView(spinner("Watchapp opens on", QUICK_LAUNCH_CHOICES, s.quickLaunch ?: Protocol.QuickLaunch.HOME) {
            // The first callback only reports the initial selection: keep the watch's own choice until one is made here.
            if (s.quickLaunch != null || it != Protocol.QuickLaunch.HOME) {
                s.quickLaunch = it
                controller.onWatchSettingsChanged()
            }
        })
        column.addView(switch("Show tool activity on watch", s.toolActivity != ToolActivity.HIDDEN) {
            s.toolActivity = if (it) ToolActivity.SILENT else ToolActivity.HIDDEN
        })
        column.addView(button("Open Wristbound on watch", primary = false) { lifecycleScope.launch { controller.openWatchApp() } })

        column.addView(heading("Sessions on the watch"))
        column.addView(switch("Show messaging chats", s.showMessaging) {
            s.showMessaging = it
            controller.onWatchSettingsChanged()
        })
        column.addView(note("Telegram, Discord, Slack, Signal, WhatsApp, email and other messaging platforms."))
        column.addView(switch("Show scheduled & automation sessions", s.showAutomation) {
            s.showAutomation = it
            controller.onWatchSettingsChanged()
        })
        column.addView(note("Cron jobs, Kanban workers, webhooks and subagents. Pinned sessions always come first."))
        column.addView(switch("Allow replies to Telegram sessions", s.allowTelegramReplies) {
            s.allowTelegramReplies = it
            controller.onWatchSettingsChanged()
        })
        column.addView(note("Off keeps Telegram sessions read-only on the watch: you can still read and play replies, and answer in Telegram."))

        column.addView(heading("Notifications"))
        notificationStatus = note("")
        column.addView(notificationStatus)
        notificationButton = button("Allow notifications") { fixNotifications() }
        column.addView(notificationButton)
        val kinds = listOf(
            switch("Audio replies", s.alertAudio) { s.alertAudio = it },
            note("Alert whenever Hermes sends a voice reply, from any session."),
            switch("Replies to my watch messages", s.alertWatchTurns) { s.alertWatchTurns = it },
            switch("Replies in chats", s.alertChats) { s.alertChats = it },
            note("Chats on the desktop, terminal or web UI."),
            switch("Text replies in Telegram & messaging", s.alertMessagingText) { s.alertMessagingText = it },
            note("Off by default: those apps already notify your phone."),
            switch("Background & scheduled tasks", s.alertBackground) { s.alertBackground = it },
            note("Cron jobs and automation sessions, and Hermes following up on its own background work."),
        )
        column.addView(switch("Notifications", s.notifyReplies) { on ->
            s.notifyReplies = on
            kinds.forEach { it.isEnabled = on }
        })
        kinds.forEach { it.isEnabled = s.notifyReplies; column.addView(it) }
        column.addView(note("Alerts show on the open watchapp, or as native Pebble notifications forwarded from Android. The Hermes replies · silent on phone channel keeps a shade record without phone sound or vibration by default. Enable Wristbound forwarding in the Pebble app; watch vibration depends on its settings. Nothing plays until you press Play."))
        column.addView(switch("Notify when Hermes uses a tool", s.toolActivity == ToolActivity.NOTIFY) {
            s.toolActivity = if (it) ToolActivity.NOTIFY else ToolActivity.SILENT
        })
        column.addView(switch("Hide reply text in notifications", s.privatePreviews) { s.privatePreviews = it })
        column.addView(note("To test the path to the watch, press the button below and lock the phone: a test alert is posted about 10 s later. If it does not reach the watch, check that Wristbound is enabled in the Pebble app's notification settings, and whether it is set to skip notifications while the phone is in use."))
        column.addView(button("Send a test alert", primary = false) { sendTestAlert() })

        column.addView(heading("Checking for replies"))
        monitoring = note("")
        column.addView(monitoring)
        column.addView(note("Replies that arrive through Telegram, Ringbearer or the desktop are checked for every ${Wristbound.FOREGROUND_POLL_SECONDS} s while the watchapp is open. Background checking keeps going while the watchapp is closed and the phone is locked, with a silent ongoing notification. The first check after signing in only notes what is already there, so old conversations never alert."))
        column.addView(switch("Keep checking in the background", s.backgroundSync) {
            s.backgroundSync = it
            controller.updateMonitoring()
            render()
        })
        val pollChoices = listOf(30, 60, 120, 300)
        column.addView(spinner("Background check every", pollChoices.map { "$it seconds" }, pollChoices.indexOf(s.pollSeconds).coerceAtLeast(1)) {
            s.pollSeconds = pollChoices[it]
            render()
        })
        batteryStatus = note("")
        column.addView(batteryStatus)
        batteryButton = button("Allow background use", primary = false) { requestBatteryExemption() }
        column.addView(batteryButton)

        column.addView(heading("Privacy"))
        column.addView(button("Clear cached conversations", primary = false) {
            controller.state.clearCache()
            status.text = "Cache cleared."
        })

        setContentView(screen(column))
        requestNotificationPermission()
        controller.updateMonitoring()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.backend.collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (controller.settings.isConfigured) checkConnection(quiet = true)
    }

    // -- layout ---------------------------------------------------------------------------------

    /**
     * A toolbar that stays put above a scrolling form. Edge-to-edge is on (and enforced from
     * Android 15), so the system bars are kept clear with their real insets: the toolbar takes
     * the status bar and cutout, the form takes the navigation bar or the keyboard.
     */
    private fun screen(content: View): View {
        val toolbar = Toolbar(ContextThemeWrapper(this, androidx.appcompat.R.style.ThemeOverlay_AppCompat_Dark_ActionBar)).apply {
            // "wristbound" and a green block cursor, as the watch shows Hermes working.
            title = SpannableString("wristbound \u2588").apply {
                setSpan(ForegroundColorSpan(color(R.color.phosphor_signal)), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            setTitleTextAppearance(this@MainActivity, R.style.TextAppearance_Wristbound_Title)
            setBackgroundColor(color(R.color.phosphor_glass))
        }
        val scroll = ScrollView(this).apply {
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

    private fun accountCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad / 2, pad, pad / 2)
        background = GradientDrawable().apply {
            setStroke(dp(1), color(R.color.phosphor_signal))
        }
        accountHeading = TextView(this@MainActivity).apply {
            textSize = 17f
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.phosphor_signal))
        }
        accountDetail = note("")
        accountConnection = note("")
        addView(accountHeading)
        addView(accountDetail)
        addView(accountConnection)
        addView(button("Test connection") { checkConnection(quiet = false) })
        addView(button("Sign out", primary = false) { confirmSignOut() })
    }

    private fun signInForm(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(note("Wristbound talks to the Hermes dashboard, like the desktop app and Hermes-Relay, so Telegram and desktop sessions can be read and continued. Use the dashboard address on your tailnet, e.g. https://hermes.tailnet-name.ts.net:10443 (tailscale serve in front of port 9119)."))
        url = EditText(this@MainActivity).apply {
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
                }
            })
        }
        addView(url)

        addView(subheading("Sign in"))
        addView(note("Dashboards with a sign-in page: use your dashboard username and password, or sign in in the browser for other providers."))
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
        addView(button("Sign in with browser", primary = false) { signInWithBrowser() })

        addView(subheading("Advanced: session token"))
        addView(note("Only for a dashboard on a loopback bind with no sign-in page: paste the token the desktop app sets as HERMES_DASHBOARD_SESSION_TOKEN."))
        val token = EditText(this@MainActivity).apply {
            id = R.id.setup_token
            hint = "Session token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        addView(token)
        addView(button("Use session token", primary = false) {
            val t = token.text.toString()
            token.text.clear()
            useSessionToken(t)
        })
    }

    // -- state ----------------------------------------------------------------------------------

    /** Draw everything from saved state and the last backend result; safe to call any time. */
    private fun render() {
        if (!::status.isInitialized) return
        val s = controller.settings
        val view = Account.view(s.credentials, s.baseUrl, s.signInExpired, controller.backend.value)
        signedInGroup.visibility = if (view.signedIn) View.VISIBLE else View.GONE
        signedOutGroup.visibility = if (view.signedIn) View.GONE else View.VISIBLE
        accountHeading.text = view.heading
        accountDetail.text = view.detail
        accountConnection.text = "Hermes: ${view.connection}"
        if (!view.signedIn && s.signInExpired && status.text.isBlank()) status.text = view.connection
        watchStatus.text = if (controller.watchOpen) "\u25CF watchapp open" else "\u25CB watchapp closed"
        watchStatus.setTextColor(color(if (controller.watchOpen) R.color.phosphor_signal else R.color.phosphor_meta))
        val last = s.lastCheckAt
        monitoring.text = controller.monitoringSummary() +
            if (last > 0) " Last reached Hermes ${DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}." else ""
        val exempt = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatus.text = if (exempt) "Battery: unrestricted, so checks keep their pace while the phone sleeps."
        else "Battery: optimised. Android may delay checks for many minutes while the phone sleeps, or stop them."
        batteryButton.visibility = if (exempt) View.GONE else View.VISIBLE
        val canNotify = Notifier(this).canPost()
        notificationStatus.text = if (canNotify) "Notifications: allowed"
        else "Notifications: blocked. Replies cannot reach the watch while the watchapp is closed."
        notificationButton.visibility = if (canNotify) View.GONE else View.VISIBLE
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
        status.text = ""
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
                status.text = "Signed out."
                render()
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

    // -- widgets --------------------------------------------------------------------------------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    /** The Hermes girl beside the name, as on the watch's launch screen. */
    private fun masthead(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
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

    /** Green and filled for the main action, outlined for the rest; square, like the watch. */
    private fun button(label: String, primary: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = label
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        stateListAnimator = null
        val face = GradientDrawable().apply {
            if (primary) setColor(color(R.color.phosphor_signal))
            else setStroke(dp(1), color(R.color.phosphor_meta))
        }
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.phosphor_voice)), face, null)
        setTextColor(color(if (primary) R.color.phosphor_glass else R.color.phosphor_text))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
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

    private companion object {
        const val TAG = "WristboundSetup"
        const val TEST_DELAY_MS = 10_000L
        /** In [Protocol.QuickLaunch] order. */
        val QUICK_LAUNCH_CHOICES = listOf("Home", "Dictate a new session", "Recent audio", "Play latest audio", "Last session")
    }
}
