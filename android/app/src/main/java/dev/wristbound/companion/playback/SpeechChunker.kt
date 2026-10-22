package dev.wristbound.companion.playback

/**
 * Splits reply text into utterances so playback can pause and resume at a sentence boundary
 * (Android TextToSpeech cannot pause mid-utterance).
 */
object SpeechChunker {
    private const val MAX_CHUNK = 400
    private val SENTENCE_END = Regex("""(?<=[.!?…])\s+|\n+""")

    fun chunks(text: String): List<String> {
        val out = mutableListOf<String>()
        for (sentence in text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (sentence.length <= MAX_CHUNK) {
                out += sentence
                continue
            }
            // Very long run-on text: split on word boundaries.
            var rest = sentence
            while (rest.length > MAX_CHUNK) {
                val cut = rest.lastIndexOf(' ', MAX_CHUNK).takeIf { it > MAX_CHUNK / 2 } ?: MAX_CHUNK
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
        }
        return out
    }
}
