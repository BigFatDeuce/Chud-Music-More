package app.chudmusic.update

/**
 * Release notes as written on GitHub, made readable in the app.
 *
 * The GitHub page is in English. A translation can ride along, invisible on GitHub, in an HTML
 * comment that names its language:
 *
 *     What's new…
 *     <!-- lang:fr
 *     Nouveautés…
 *     -->
 *
 * [select] gives the block of the interface language when there is one, else the visible text.
 */
object ReleaseNotes {
    private val block = Regex("""<!--\s*lang:([A-Za-z-]+)\s*\n(.*?)-->""", RegexOption.DOT_MATCHES_ALL)
    private val comment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val bold = Regex("""\*\*(.+?)\*\*""")

    fun select(body: String, language: String): String {
        val lang = language.lowercase().substringBefore('-')
        val own = block.findAll(body).firstOrNull { it.groupValues[1].lowercase().substringBefore('-') == lang }
        return (own?.groupValues?.get(2) ?: comment.replace(body, "")).replace("\r", "").trim()
    }

    /** One piece of a line: plain or bold text. */
    data class Span(val text: String, val bold: Boolean)

    /**
     * The little Markdown release notes use, as lines of spans: `**bold**`, `# titles` (bold),
     * `- item` / `* item` (a bullet), `` `code` `` and `[text](url)` (kept as their text).
     */
    fun lines(markdown: String): List<List<Span>> = markdown.lines().map { raw ->
        var line = raw.trimEnd()
        val title = line.trimStart().startsWith("#")
        if (title) line = line.trimStart().trimStart('#').trim()
        val bullet = Regex("""^\s*[-*]\s+""").find(line)
        if (bullet != null) line = "•  " + line.substring(bullet.range.last + 1)
        line = line.replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1").replace("`", "")
        if (title) return@map listOf(Span(line.replace("**", ""), true))
        val out = mutableListOf<Span>()
        var at = 0
        for (m in bold.findAll(line)) {
            if (m.range.first > at) out += Span(line.substring(at, m.range.first), false)
            out += Span(m.groupValues[1], true)
            at = m.range.last + 1
        }
        if (at < line.length) out += Span(line.substring(at), false)
        out
    }
}
