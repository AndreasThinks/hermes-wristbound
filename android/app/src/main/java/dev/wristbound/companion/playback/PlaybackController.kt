package dev.wristbound.companion.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dev.wristbound.companion.pebble.Protocol
import java.io.File

/**
 * Plays one exact reply, chosen by message id, on the phone's current audio route: either an
 * audio file from Hermes ([playFile]) or the phone's TextToSpeech engine ([play]). Playback
 * never silently falls back to the loudspeaker: without headphones it reports
 * [Protocol.Playback.NEEDS_SPEAKER] unless the request explicitly allowed the speaker, and a
 * headphone disconnect pauses rather than continuing out loud.
 */
class PlaybackController(
    private val context: Context,
    private val onState: (messageId: String?, state: Protocol.Playback, detail: String) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingStart: (() -> Unit)? = null

    private var messageId: String? = null
    private var player: MediaPlayer? = null
    private var playerLabel = ""
    private var chunks: List<String> = emptyList()
    private var position = 0
    private var playing = false
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && playing) {
                pause("Headphones disconnected")
            }
        }
    }

    val currentMessageId: String? get() = messageId
    val isPlaying: Boolean get() = playing
    val isPaused: Boolean get() = !playing && messageId != null && (player != null || position < chunks.size)

    fun hasPrivateOutput(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in PRIVATE_OUTPUTS }

    /** Start [text] for [id] from the beginning. */
    @Synchronized
    fun play(id: String, text: String, allowSpeaker: Boolean) {
        if (!allowSpeaker && !hasPrivateOutput()) {
            onState(id, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        stopInternal(report = false)
        messageId = id
        chunks = SpeechChunker.chunks(text)
        position = 0
        if (chunks.isEmpty()) {
            onState(id, Protocol.Playback.ERROR, "Nothing to read")
            return
        }
        startWhenReady()
    }

    /** Play the audio file [file] for [id] from the start; [label] is what the watch shows. */
    @Synchronized
    fun playFile(id: String, file: File, allowSpeaker: Boolean, label: String) {
        if (!allowSpeaker && !hasPrivateOutput()) {
            onState(id, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        stopInternal(report = false)
        messageId = id
        playerLabel = label
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { done(id) }
            mp.setOnErrorListener { _, _, _ -> failed(id); true }
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            messageId = null
            onState(id, Protocol.Playback.ERROR, "Cannot play this audio")
            return
        }
        player = mp
        startPlayer()
    }

    @Synchronized
    fun pause(reason: String = "Paused") {
        if (!playing) return
        playing = false
        player?.let { runCatching { it.pause() } } ?: tts?.stop()
        releaseFocus()
        onState(messageId, Protocol.Playback.PAUSED, reason)
    }

    @Synchronized
    fun resume(allowSpeaker: Boolean = false) {
        val id = messageId ?: return
        if (playing || (player == null && position >= chunks.size)) return
        if (!allowSpeaker && !hasPrivateOutput()) {
            onState(id, Protocol.Playback.NEEDS_SPEAKER, "No headphones")
            return
        }
        if (player != null) startPlayer() else startWhenReady()
    }

    private fun startPlayer() {
        val mp = player ?: return
        val id = messageId ?: return
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
        onState(id, Protocol.Playback.ERROR, "Playback failed")
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    @Synchronized
    fun stop() = stopInternal(report = true)

    fun shutdown() {
        stopInternal(report = false)
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun stopInternal(report: Boolean) {
        val id = messageId
        playing = false
        tts?.stop()
        releasePlayer()
        releaseFocus()
        messageId = null
        chunks = emptyList()
        position = 0
        if (report && id != null) onState(id, Protocol.Playback.IDLE, "Stopped")
    }

    private fun startWhenReady() {
        val engine = tts
        if (engine != null && ttsReady) {
            speakFromPosition()
            return
        }
        pendingStart = { speakFromPosition() }
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (!ttsReady) {
                        onState(messageId, Protocol.Playback.ERROR, "Phone voice unavailable")
                        pendingStart = null
                        return@synchronized
                    }
                    tts?.setAudioAttributes(attributes)
                    tts?.setOnUtteranceProgressListener(progress)
                    pendingStart?.invoke()
                    pendingStart = null
                }
            }
        }
    }

    private fun speakFromPosition() {
        val engine = tts ?: return
        val id = messageId ?: return
        if (!requestFocus()) {
            onState(id, Protocol.Playback.ERROR, "Audio busy")
            return
        }
        registerNoisy()
        playing = true
        val params = Bundle()
        for (i in position until chunks.size) {
            engine.speak(chunks[i], if (i == position) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "$id#$i")
        }
        onState(id, Protocol.Playback.PLAYING, "Playing phone voice")
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}

        override fun onDone(utteranceId: String) = synchronized(this@PlaybackController) {
            val (id, index) = parse(utteranceId) ?: return@synchronized
            if (id != messageId || !playing) return@synchronized
            position = index + 1
            if (position >= chunks.size) {
                playing = false
                releaseFocus()
                onState(id, Protocol.Playback.DONE, "Finished")
                messageId = null
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = onError(utteranceId, TextToSpeech.ERROR)

        override fun onError(utteranceId: String, errorCode: Int) = synchronized(this@PlaybackController) {
            val (id, _) = parse(utteranceId) ?: return@synchronized
            if (id != messageId) return@synchronized
            playing = false
            releaseFocus()
            onState(id, Protocol.Playback.ERROR, "Playback failed")
        }

        private fun parse(utteranceId: String): Pair<String, Int>? {
            val hash = utteranceId.lastIndexOf('#')
            if (hash < 0) return null
            return utteranceId.substring(0, hash) to (utteranceId.substring(hash + 1).toIntOrNull() ?: return null)
        }
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
