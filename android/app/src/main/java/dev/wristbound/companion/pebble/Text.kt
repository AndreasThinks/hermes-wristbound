package dev.wristbound.companion.pebble

/** Text shaping for the watch: plain, compact and inside a UTF-8 byte budget. */
object Text {
    private const val ELLIPSIS = "…"

    private val AUDIO_TAG = Regex("""MEDIA:\S+\.(?:mp3|ogg|opus|wav|m4a|aac|flac)\b""", RegexOption.IGNORE_CASE)
    private val MEDIA_TAG = Regex("""MEDIA:\S+""")
    private val VOICE_MARKER = Regex("""\[\[audio_as_voice]]""")
    private val PLACEHOLDER = Regex("""\[(?:image|media|audio)]""")
    private val IMAGE_MD = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val LINK_MD = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val AUTOLINK = Regex("""<(https?://[^>\s]+)>""")
    private val BARE_URL = Regex("""(?<![\w/])https?://(?:www\.)?([^/\s)>\]]+)(/[^\s)>\]]*)?""")
    private val FENCE = Regex("""^\s*(```|~~~)""")
    private val ESCAPE = Regex("""\\([\\`*_{}\[\]()#+\-.!>|~])""")
    private val STRONG = Regex("""(\*\*|__|~~|`)""")
    private val STAR_EM = Regex("""(?<![\w*])\*(?=\S)([^*\n]+?)(?<=\S)\*(?![\w*])""")
    private val UNDER_EM = Regex("""(?<![\w_])_(?=\S)([^_\n]+?)(?<=\S)_(?![\w_])""")
    private val HEADING = Regex("""^\s{0,3}#{1,6}\s+(.*?)(?:\s+#+)?\s*$""")
    private val QUOTE = Regex("""^\s*(?:>\s?)+""")
    private val RULE = Regex("""^\s{0,3}([-*_])(?:\s*\1){2,}\s*$""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(?:\|\s*:?-{2,}:?\s*)*\|?\s*$""")
    private val TASK = Regex("""^(\s*)[-*+]\s+\[([ xX])]\s+""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+""")
    private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+""")
    private val BREAK_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val INLINE_TAG = Regex("""</?(?:b|i|em|strong|u|s|del|ins|code|pre|kbd|mark|sup|sub|span|p|div|small)(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
    private val BLANK_LINES = Regex("""\n{3,}""")
    private const val BULLET_MARK = "\u2022 " // "• ": in every Pebble system font
    private const val CELL_JOIN = " \u00B7 " // " · "
    // Stands in for an escaped markdown character until the markup around it is gone.
    private const val ESCAPED = '\uE000'

    /**
     * Turn markdown into plain text the watch can lay out: markup and server media tags the
     * watch cannot render are dropped, while the structure stays readable in Gothic. Lists keep
     * a bullet ("•") or their number, table rows become cells joined by " · ", links keep their
     * text and bare URLs shrink to their host. Code blocks keep their lines as written. Images
     * become "[image]" so a reply that is only an image is not shown as empty.
     */
    fun forWatch(raw: String): String {
        val text = raw
            .replace("\r\n", "\n")
            .replace(IMAGE_MD, "[image]")
            .replace(VOICE_MARKER, "")
            .replace(AUDIO_TAG, "[audio]")
            .replace(MEDIA_TAG, "[media]")
            .replace(BREAK_TAG, "\n")
        val out = StringBuilder()
        var inCode = false
        for (line in text.split('\n')) {
            if (FENCE.containsMatchIn(line)) {
                inCode = !inCode
                continue
            }
            val shaped = if (inCode) line.trimEnd() else unescaped(line, ::block)
            if (shaped != null) out.append(shaped).append('\n')
        }
        return out.toString().replace(BLANK_LINES, "\n\n").trim()
    }

    /** Shape [line] with its backslash-escaped characters kept out of the markup's way. */
    private fun unescaped(line: String, shape: (String) -> String?): String? {
        val escaped = ArrayDeque<Char>()
        val shaped = shape(line.replace(ESCAPE) { escaped.addLast(it.groupValues[1][0]); ESCAPED.toString() })
            ?: return null
        return buildString { shaped.forEach { append(if (it == ESCAPED) escaped.removeFirstOrNull() ?: it else it) } }
    }

    /** One line outside a code block; null drops it (a table's separator row). */
    private fun block(line: String): String? {
        if (TABLE_SEPARATOR.matches(line) && line.contains('|')) return null
        if (RULE.matches(line)) return ""
        HEADING.matchEntire(line)?.let { return inline(it.groupValues[1]) }
        var body = line.replace(QUOTE, "")
        val trimmed = body.trim()
        if (trimmed.startsWith('|') && trimmed.endsWith('|') && trimmed.length > 1) {
            return trimmed.removePrefix("|").removeSuffix("|").split('|')
                .map { inline(it.trim()) }.filter { it.isNotEmpty() }.joinToString(CELL_JOIN)
        }
        var lead = ""
        TASK.find(body)?.let {
            lead = indent(it.groupValues[1]) + if (it.groupValues[2] == " ") "[ ] " else "[x] "
            body = body.substring(it.range.last + 1)
        } ?: BULLET.find(body)?.let {
            lead = indent(it.groupValues[1]) + BULLET_MARK
            body = body.substring(it.range.last + 1)
        } ?: NUMBERED.find(body)?.let {
            lead = indent(it.groupValues[1]) + it.groupValues[2] + ". "
            body = body.substring(it.range.last + 1)
        }
        return lead + inline(body).trimEnd()
    }

    /** Nested list items: two spaces a level, however the source indented them. */
    private fun indent(spaces: String): String = "  ".repeat(spaces.replace("\t", "    ").length / 2)

    private fun inline(s: String): String = s
        .replace(LINK_MD, "$1")
        .replace(AUTOLINK, "$1")
        .replace(BARE_URL) { m -> m.groupValues[1] + if (m.groupValues[2].length > 1) "/\u2026" else "" }
        .replace(INLINE_TAG, "")
        .replace(STRONG, "")
        .replace(STAR_EM, "$1")
        .replace(UNDER_EM, "$1")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    /** Watch text made fit to be read aloud: placeholders for media are dropped. */
    fun forSpeech(watchText: String): String = watchText.replace(PLACEHOLDER, "").replace(BLANK_LINES, "\n\n").trim()

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
