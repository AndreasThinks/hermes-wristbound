package dev.wristbound.companion.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.wristbound.companion.pebble.Protocol
import java.io.File

/**
 * Plays one exact reply's Hermes audio, chosen by message id ([playFiles]). Text replies are never
 * read aloud. Each request names its output. On headphones,
 * playback never silently falls back to the loudspeaker: without them it reports
 * [Protocol.Playback.NEEDS_SPEAKER], and a headphone disconnect pauses rather than continuing
 * out loud. On the speaker, it plays from the phone's own speaker even while headphones are
 * connected.
 */
class PlaybackController(
    private val context: Context,
    private val onState: (messageId: String?, state: Protocol.Playback, detail: String) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)

    private var messageId: String? = null
    private var player: MediaPlayer? = null
    private var playerLabel = ""
    private var playing = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false
    private var onSpeaker = false
    private val remaining = java.util.ArrayDeque<File>()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing && !onSpeaker) {
                pause("Headphones disconnected")
            }
        }
    }

    val currentMessageId: String? get() = messageId
    val isPlaying: Boolean get() = playing
    val isPaused: Boolean get() = !playing && messageId != null && player != null

    fun hasPrivateOutput(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUTS }

    /** Calls [listener] with whether headphones are connected: once now, then on every change. */
    fun watchHeadphones(listener: (Boolean) -> Unit) {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            private var last: Boolean? = null

            private fun check() {
                val now = hasPrivateOutput()
                if (now != last) {
                    last = now
                    listener(now)
                }
            }

            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = check()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = check()
        }, Handler(Looper.getMainLooper()))
    }

    /** Play ordered parts of the same reply; no synthesis and no implicit speaker fallback. */
    @Synchronized
    fun playFiles(id: String, files: List<File>, speaker: Boolean, label: String) {
        if (files.isEmpty()) return
        if (!speaker && !hasPrivateOutput()) {
            onState(id, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        stopInternal(report = false)
        messageId = id
        onSpeaker = speaker
        playerLabel = label
        remaining.addAll(files)
        prepareNext(id)
    }

    private fun prepareNext(id: String) {
        val file = remaining.pollFirst() ?: return
        val mp = MediaPlayer()
        try {
            // Keeps the CPU awake for the clip with the screen off; released when playback stops.
            mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { if (player === mp) done(id) }
            mp.setOnErrorListener { _, _, _ -> if (player === mp) failed(id); true }
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            failed(id)
            return
        }
        player = mp
        startPlayer()
    }

    @Synchronized
    fun pause(reason: String = "Paused") {
        if (!playing) return
        playing = false
        player?.let { runCatching { it.pause() } }
        releaseFocus()
        onState(messageId, Protocol.Playback.PAUSED, reason)
    }

    @Synchronized
    fun resume(speaker: Boolean = false) {
        val id = messageId ?: return
        if (playing || player == null) return
        if (!speaker && !hasPrivateOutput()) {
            onState(id, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        onSpeaker = speaker
        startPlayer()
    }

    private fun builtInSpeaker(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    private fun startPlayer() {
        val mp = player ?: return
        val id = messageId ?: return
        val output = if (onSpeaker) builtInSpeaker() else
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in PRIVATE_OUTPUTS }
        if (output == null || !mp.setPreferredDevice(output)) {
            onState(id, if (onSpeaker) Protocol.Playback.ERROR else Protocol.Playback.NEEDS_SPEAKER,
                if (onSpeaker) "Speaker unavailable" else "Connect headphones or choose speaker")
            return
        }
        if (!requestFocus()) {
            onState(id, Protocol.Playback.ERROR, "Audio busy")
            return
        }
        registerNoisy()
        playing = true
        mp.start()
        onState(id, Protocol.Playback.PLAYING, playerLabel)
    }

    @Synchronized
    private fun done(id: String) {
        if (id != messageId) return
        playing = false
        releasePlayer()
        releaseFocus()
        if (remaining.isNotEmpty()) {
            prepareNext(id)
            return
        }
        messageId = null
        onState(id, Protocol.Playback.DONE, "Finished")
    }

    @Synchronized
    private fun failed(id: String) {
        if (id != messageId) return
        playing = false
        releasePlayer()
        releaseFocus()
        messageId = null
        remaining.clear()
        onState(id, Protocol.Playback.ERROR, "Playback failed")
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    @Synchronized
    fun stop() = stopInternal(report = true)

    fun shutdown() = stopInternal(report = false)

    private fun stopInternal(report: Boolean) {
        val id = messageId
        remaining.clear()
        playing = false
        releasePlayer()
        releaseFocus()
        messageId = null
        if (report && id != null) onState(id, Protocol.Playback.IDLE, "Stopped")
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    pause("Interrupted")
                }
            }
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun releaseFocus() {
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private companion object {
        val PRIVATE_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
