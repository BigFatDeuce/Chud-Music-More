package app.chudmusic.youtube

import app.chudmusic.model.Track
import kotlin.math.abs

/** A song known only by its title, artists and duration (a Spotify track, a local file). */
data class WantedTrack(val title: String, val artists: List<String>, val durationMs: Long? = null)

/**
 * Picks the YouTube Music result that is the wanted song, or none. A port of the PC app's scorer
 * (src/main/youtube.ts: norm, similarity, score), so both apps find the same tracks.
 */
object Matcher {
    /** Below this, a candidate is not the song. */
    const val THRESHOLD = 0.6

    private val FEAT_BRACKET = Regex("""[(\[](?:feat|ft|with|prod)\.?\s[^)\]]*[)\]]""")
    private val FEAT_TAIL = Regex("""\s+(?:feat|ft)\.?\s.*$""")
    private val VERSION_TAIL = Regex("""\s-\s.*\b(?:remaster(?:ed)?|version|mono|stereo|edit|live|mix)\b.*$""")
    private val NOISE_BRACKET = Regex("""[(\[][^)\]]*\b(?:remaster(?:ed)?|official|video|audio|lyrics?|clip|hd|4k)\b[^)\]]*[)\]]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val MARKS = Regex("""\p{M}+""")

    /** Words of fan uploads and altered versions: "Letra", "lyrics", "cover", "sped up"… */
    private val ALTERED = Regex("""\b(?:letra|lyrics?|paroles|traduc\w*|subtitulad\w*|legendad\w*|cover|karaoke|instrumental|nightcore|sped up|slowed|reverb|8d|bass boosted|remix|live|acoustic|acustic\w*)\b""")

    /** Lower case, no accents, no punctuation, without "(feat. …)", "- Remastered 2011"… */
    fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase()
        .replace(FEAT_BRACKET, " ")
        .replace(FEAT_TAIL, " ")
        .replace(VERSION_TAIL, " ")
        .replace(NOISE_BRACKET, " ")
        .replace(NON_WORD, " ")
        .trim()

    /** Share of words in common (Dice coefficient on words). */
    fun similarity(a: String, b: String): Double {
        val x = norm(a).split(' ').filter { it.isNotEmpty() }
        val y = norm(b).split(' ').filter { it.isNotEmpty() }
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val pool = y.toMutableList()
        var common = 0
        for (w in x) if (pool.remove(w)) common++
        return 2.0 * common / (x.size + y.size)
    }

    fun score(c: Track, want: WantedTrack): Double {
        val title = similarity(c.title, want.title)
        val who = norm(c.artist ?: "")
        val names = want.artists.map(::norm).filter { it.isNotEmpty() }
        // The artist is the uploader (best), or only named in the video title (weaker: anyone can).
        val byArtist = names.any { who.contains(it) }
        val named = !byArtist && names.any { norm(c.title).contains(it) }
        if (title < 0.5 || (!byArtist && !named && title < 0.9)) return 0.0
        var s = title * 0.6 + if (byArtist) 0.3 else if (named) 0.15 else 0.0
        // "Song (Letra)" by someone else is not the song, unless that is what was asked for.
        val altered = ALTERED.find(c.title.lowercase())?.value
        if (altered != null && !want.title.lowercase().contains(altered)) s -= 0.3
        val wantSec = want.durationMs?.let { it / 1000 }
        val gotSec = c.durationMs?.let { it / 1000 }
        if (wantSec != null && gotSec != null && wantSec > 0 && gotSec > 0) {
            val d = abs(wantSec - gotSec)
            s += when { d <= 3 -> 0.1; d <= 10 -> 0.05; d > 30 -> -0.2; else -> 0.0 }
        }
        return s
    }

    /** The best of [candidates] (the first 8 count, as YouTube ranks them), or null when none is close enough. */
    fun best(candidates: List<Track>, want: WantedTrack): Track? =
        candidates.take(8).map { it to score(it, want) }.maxByOrNull { it.second }?.takeIf { it.second >= THRESHOLD }?.first

    /** "A, B feat. C" → [A, B, C]. */
    fun splitArtists(s: String?): List<String> =
        s.orEmpty().split(Regex("""\s*(?:,|;|&|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
}
