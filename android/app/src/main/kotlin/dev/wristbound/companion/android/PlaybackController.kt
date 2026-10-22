package dev.wristbound.companion.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import dev.wristbound.companion.core.MediaRef
import dev.wristbound.companion.core.MessageClassifier
import dev.wristbound.companion.core.MessageKind
import dev.wristbound.companion.core.TurnMedia
import dev.wristbound.companion.core.WP
import dev.wristbound.companion.core.WatchEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Plays exactly one selected reply.
 *
 * Source order: Hermes-generated audio of that reply's turn (only via the optional Wristbound
 * media adapter, because the Hermes API server has no media endpoint) → on-phone speech
 * synthesis of the exact reply text. The watch is told which source is playing.
 *
 * Routing: by default playback requires a private output (wired/Bluetooth/USB/hearing aid).
 * Speaker playback only happens on an explicit speaker request. Headphone disconnect pauses
 * (ExoPlayer "becoming noisy") and never falls through to the speaker.
 */
@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context, private val hub: Hub) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null

    private var current: Current? = null

    private data class Current(
        val sessionId: String,
        val msgId: Long,
        val speaker: Boolean,
        val source: Int,
        val triedFallback: Boolean = false,
    )

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            val cur = current ?: return
            if (!cur.speaker && removed.any { isPrivate(it) } && !privateRouteAvailable()) {
                player?.pause()
                report(cur, WP.PS_PAUSED, "Headphones disconnected")
            }
        }
    }

    suspend fun play(sessionId: String, msgId: Long, speaker: Boolean) = withContext(Dispatchers.Main) {
        val messages = hub.messagesFor(sessionId, refresh = false).takeIf { list -> list.any { it.id == msgId } }
            ?: hub.messagesFor(sessionId, refresh = true)
        val msg = messages.firstOrNull { it.id == msgId }
        if (msg == null || MessageClassifier.kind(msg) != MessageKind.ASSISTANT_FINAL) {
            hub.watch.send(WatchEncoder.playbackState(msgId, WP.PS_ERROR, 0, "Reply not found"))
            return@withContext
        }
        if (!speaker && !privateRouteAvailable()) {
            hub.watch.send(WatchEncoder.playbackState(msgId, WP.PS_NEED_HEADPHONES, 0, "Connect headphones or choose Speaker"))
            return@withContext
        }
        val media = TurnMedia.audioFor(messages, msgId)
        val adapter = hub.settings.mediaAdapterUrl
        val text = MessageClassifier.displayText(msg)
        val cur: Current
        val items: List<MediaItem>
        if (media.isNotEmpty() && adapter.isNotBlank()) {
            cur = Current(sessionId, msgId, speaker, WP.SRC_HERMES_AUDIO)
            items = media.map { MediaItem.fromUri(adapterUri(adapter, sessionId, it)) }
        } else {
            cur = Current(sessionId, msgId, speaker, WP.SRC_PHONE_TTS)
            report(cur, WP.PS_PREPARING, "Preparing speech")
            val files = synthesize(text, "${safe(sessionId)}_$msgId")
            if (files.isEmpty()) {
                report(cur, WP.PS_ERROR, "Speech unavailable")
                return@withContext
            }
            items = files.map { MediaItem.fromUri(Uri.fromFile(it)) }
        }
        start(cur, items)
        hub.markRead(sessionId, msgId)
    }

    suspend fun control(action: Int) = withContext(Dispatchers.Main) {
        val p = player ?: return@withContext
        val cur = current ?: return@withContext
        when (action) {
            WP.PA_PAUSE -> p.pause()
            WP.PA_RESUME -> {
                if (!cur.speaker && !privateRouteAvailable()) {
                    report(cur, WP.PS_NEED_HEADPHONES, "Connect headphones or choose Speaker")
                } else {
                    p.play()
                }
            }
            WP.PA_REPLAY -> {
                if (!cur.speaker && !privateRouteAvailable()) {
                    report(cur, WP.PS_NEED_HEADPHONES, "Connect headphones or choose Speaker")
                } else {
                    p.seekTo(0, 0); p.play()
                }
            }
            WP.PA_STOP -> {
                p.stop(); report(cur, WP.PS_IDLE, "Stopped"); WristboundService.setPlaying(context, false)
            }
        }
    }

    private suspend fun start(cur: Current, items: List<MediaItem>) {
        val p = ensurePlayer()
        p.stop()
        current = cur
        p.setPreferredAudioDevice(if (cur.speaker) builtInSpeaker() else null)
        p.setMediaItems(items)
        p.prepare()
        p.play()
        WristboundService.setPlaying(context, true)
        report(cur, WP.PS_PREPARING, if (cur.source == WP.SRC_HERMES_AUDIO) "Hermes audio" else "Phone speech")
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(hub.settings.apiToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(context, http),
            ))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val cur = current ?: return
                if (isPlaying) {
                    report(cur, WP.PS_PLAYING, if (cur.source == WP.SRC_HERMES_AUDIO) "Hermes audio" else "Phone speech")
                } else if (p.playbackState == Player.STATE_READY) {
                    val why = if (!cur.speaker && !privateRouteAvailable()) "Headphones disconnected" else "Paused"
                    report(cur, WP.PS_PAUSED, why)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                val cur = current ?: return
                if (state == Player.STATE_ENDED) {
                    report(cur, WP.PS_ENDED, "Finished")
                    WristboundService.setPlaying(context, false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = current ?: return
                Log.w(TAG, "playback error", error)
                if (cur.source == WP.SRC_HERMES_AUDIO && !cur.triedFallback) {
                    // Hermes prunes cache/audio after ~24h; speak the exact text instead.
                    main.post { fallbackToSpeech(cur) }
                } else {
                    report(cur, WP.PS_ERROR, "Playback failed")
                    WristboundService.setPlaying(context, false)
                }
            }
        })
        player = p
        session = runCatching { MediaSession.Builder(context, p).setId("wristbound").build() }.getOrNull()
        audio.registerAudioDeviceCallback(deviceCallback, main)
        return p
    }

    private fun fallbackToSpeech(cur: Current) {
        scope.launch {
            val msg = hub.cached(cur.sessionId).firstOrNull { it.id == cur.msgId } ?: return@launch
            val files = synthesize(MessageClassifier.displayText(msg), "${safe(cur.sessionId)}_${cur.msgId}")
            if (files.isEmpty()) {
                report(cur, WP.PS_ERROR, "Audio and speech unavailable"); return@launch
            }
            start(cur.copy(source = WP.SRC_PHONE_TTS, triedFallback = true), files.map { MediaItem.fromUri(Uri.fromFile(it)) })
        }
    }

    private fun report(cur: Current, state: Int, text: String) {
        hub.watch.send(WatchEncoder.playbackState(cur.msgId, state, cur.source, text))
        hub.notifier.updateServiceStatus(if (state == WP.PS_PLAYING) "Playing reply" else hub.status.value.text)
    }

    // ------------------------------------------------------------------------------- routing

    fun privateRouteAvailable(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isPrivate(it) }

    private fun isPrivate(d: AudioDeviceInfo): Boolean = d.type in PRIVATE_TYPES

    private fun builtInSpeaker(): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    // ---------------------------------------------------------------------------- synthesis

    private suspend fun ensureTts(): Boolean {
        ttsReady?.let { return withTimeoutOrNull(5000) { it.await() } ?: false }
        val ready = CompletableDeferred<Boolean>()
        ttsReady = ready
        tts = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        return withTimeoutOrNull(5000) { ready.await() } ?: false
    }

    /** Synthesizes [text] into one or more WAV files (engine input length is limited). */
    private suspend fun synthesize(text: String, baseName: String): List<File> {
        if (text.isBlank() || !ensureTts()) return emptyList()
        val engine = tts ?: return emptyList()
        val dir = File(context.cacheDir, "tts").apply { mkdirs() }
        pruneTtsCache(dir)
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val parts = splitForSpeech(text, max)
        val out = mutableListOf<File>()
        for ((i, part) in parts.withIndex()) {
            val f = File(dir, "${baseName}_$i.wav")
            if (f.exists() && f.length() > 0) { out += f; continue }
            val ok = suspendCancellableCoroutine { cont ->
                val id = "${baseName}_$i"
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                })
                if (engine.synthesizeToFile(part, null, f, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
            }
            if (!ok) break
            out += f
        }
        return out
    }

    private fun pruneTtsCache(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_TTS_FILES).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "Playback"
        private const val MAX_TTS_FILES = 24
        private val PRIVATE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )

        fun adapterUri(adapterBase: String, sessionId: String, ref: MediaRef): Uri {
            val base = adapterBase.trim().trimEnd('/')
            val sid = URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")
            return Uri.parse("$base/wristbound/v1/media/$sid/${ref.toolMessageId}")
        }

        fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60)

        /** Splits on paragraph/sentence boundaries into pieces no longer than [max] chars. */
        fun splitForSpeech(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = mutableListOf<String>()
            var rest = text
            while (rest.length > max) {
                val window = rest.substring(0, max)
                val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf(' '))
                    .firstOrNull { it > max / 2 } ?: max
                out += rest.substring(0, cut + if (cut < max) 1 else 0).trim()
                rest = rest.substring(cut + if (cut < max) 1 else 0)
            }
            if (rest.isNotBlank()) out += rest.trim()
            return out
        }
    }
}
