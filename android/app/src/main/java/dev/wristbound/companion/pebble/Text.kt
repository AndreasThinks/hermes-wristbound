package dev.wristbound.companion.pebble

/** Text shaping for the watch: plain, compact and inside a UTF-8 byte budget. */
object Text {
    private const val ELLIPSIS = "…"

    private val MEDIA_TAG = Regex("""MEDIA:\S+""")
    private val IMAGE_MD = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK_MD = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val FENCE = Regex("""```[^\n]*\n?""")
    private val EMPHASIS = Regex("""(\*\*|__|`)""")
    private val HEADING = Regex("""(?m)^#{1,6}\s+""")
    private val BLANK_LINES = Regex("""\n{3,}""")

    /**
     * Strip markdown noise and server media tags that the watch cannot render. Images become
     * "[image]" so a reply that is only an image is not shown as empty.
     */
    fun forWatch(raw: String): String = raw
        .replace(IMAGE_MD, "[image]")
        .replace(MEDIA_TAG, "[media]")
        .replace(LINK_MD, "$1")
        .replace(FENCE, "")
        .replace(EMPHASIS, "")
        .replace(HEADING, "")
        .replace("\r\n", "\n")
        .replace(BLANK_LINES, "\n\n")
        .trim()

    /**
     * Fit [value] into [budget] UTF-8 bytes, leaving room for the C string terminator, without
     * splitting a code point. Returns the text and whether it was truncated.
     */
    fun fit(value: String, budget: Int): Pair<String, Boolean> {
        val max = budget - 1
        if (max <= 0) return "" to value.isNotEmpty()
        if (utf8Len(value) <= max) return value to false
        val ellipsisBytes = utf8Len(ELLIPSIS)
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val cp = value.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = utf8Len(value.substring(i, i + chars))
            if (used + bytes + ellipsisBytes > max) break
            sb.append(value, i, i + chars)
            used += bytes
            i += chars
        }
        return sb.toString().trimEnd() + ELLIPSIS to true
    }

    fun utf8Len(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}
