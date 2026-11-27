package app.chudmusic.youtube

/**
 * A music link pasted in the YouTube Music search box or shared to the app: YouTube / YouTube
 * Music or Spotify. Only the link's kind and id are read here; nothing is fetched. Same rules as
 * the PC app's `parseMusicLink` (src/shared/links.ts).
 */
sealed interface MusicLink {
    val id: String

    data class YtVideo(override val id: String) : MusicLink
    /** [videoId]: the video the playlist was shared from, played when the list itself cannot be read. */
    data class YtPlaylist(override val id: String, val videoId: String? = null) : MusicLink
    data class YtAlbum(override val id: String) : MusicLink
    data class YtArtist(override val id: String) : MusicLink
    data class Spotify(val kind: SpotifyKind, override val id: String) : MusicLink

    enum class SpotifyKind(val path: String) { TRACK("track"), ALBUM("album"), PLAYLIST("playlist"), ARTIST("artist") }

    companion object {
        private val YT_HOSTS = Regex("""^(?:www\.|m\.|music\.)?youtube(?:-nocookie)?\.com$""", RegexOption.IGNORE_CASE)
        private val VIDEO = Regex("""^[\w-]{11}$""")
        private val SPOTIFY_ID = Regex("""^[A-Za-z0-9]{22}$""")
        private val SPOTIFY_URI = Regex("""^spotify:(track|album|playlist|artist):([A-Za-z0-9]{22})$""")
        private val URL_IN_TEXT = Regex("""(?:https?://)?(?:[\w-]+\.)*(?:youtube(?:-nocookie)?\.com|youtu\.be|spotify\.com)/\S+|spotify:\w+:[A-Za-z0-9]{22}""", RegexOption.IGNORE_CASE)

        /** The link this text is (a URL or a spotify: URI), or null when it is not one. */
        fun parse(input: String): MusicLink? {
            val text = input.trim { it.isWhitespace() || it == '﻿' }
            if (text.isEmpty() || text.any { it.isWhitespace() || it == '﻿' }) return null
            SPOTIFY_URI.matchEntire(text)?.let { m -> return Spotify(spotifyKind(m.groupValues[1])!!, m.groupValues[2]) }
            val url = WebUrl.parse(if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "https://$text") ?: return null
            val host = url.host
            val parts = url.segments

            if (host == "open.spotify.com" || host == "play.spotify.com") {
                // /track/ID, /intl-fr/album/ID, /embed/playlist/ID
                val i = parts.indexOfFirst { spotifyKind(it) != null }
                val id = parts.getOrNull(i + 1)
                return if (i >= 0 && id != null && SPOTIFY_ID.matches(id)) Spotify(spotifyKind(parts[i])!!, id) else null
            }
            if (host == "youtu.be") {
                val id = parts.firstOrNull()
                return if (id != null && VIDEO.matches(id)) YtVideo(id) else null
            }
            if (!YT_HOSTS.matches(host)) return null
            // A parameter given twice with two values names two things: not a link. Neither app
            // guesses which one was meant (the PC app reads links the same way).
            val vs = url.query.filter { it.first == "v" }.map { it.second }.toSet()
            val lists = url.query.filter { it.first == "list" }.map { it.second }.toSet()
            if (vs.size > 1 || lists.size > 1) return null
            val v = vs.firstOrNull()?.takeIf { VIDEO.matches(it) }
            val list = lists.firstOrNull()
            // A "radio" list (RD…) is generated around the video: the video is what was shared.
            if (list != null && !list.startsWith("RD") && Regex("""^[\w-]{10,}$""").matches(list)) return YtPlaylist(list, v)
            if (v != null) return YtVideo(v)
            val head = parts.getOrNull(0)
            val second = parts.getOrNull(1)
            if (head in setOf("shorts", "embed", "live", "v") && second != null && VIDEO.matches(second)) return YtVideo(second)
            if (head == "browse" && second != null && second.startsWith("MPREb_")) return YtAlbum(second)
            if (head == "channel" && second != null && Regex("""^UC[\w-]{22}$""").matches(second)) return YtArtist(second)
            return null
        }

        /** The first music link inside a longer text ("Listen to … on Spotify: https://…"), or null. */
        fun find(text: String): MusicLink? =
            parse(text) ?: URL_IN_TEXT.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', '!', '?')) }

        /**
         * The YouTube video of an M3U line: a video link, or the video of a watch link inside a
         * playlist; null otherwise (same as the PC app's m3uVideoId).
         */
        fun m3uVideoId(line: String): String? = when (val l = parse(line)) {
            is YtVideo -> l.id
            is YtPlaylist -> l.videoId
            else -> null
        }

        private fun spotifyKind(s: String) = SpotifyKind.entries.firstOrNull { it.path == s }
    }
}

/**
 * The parts of a web address that a music link needs, read the way a browser reads them (the PC
 * app uses the browser's URL and URLSearchParams). java.net.URI refuses characters that browsers
 * accept (`|`, `{`, a stray `%`), which made the same link work on the PC and not here.
 */
internal class WebUrl(val host: String, val segments: List<String>, val query: List<Pair<String, String>>) {
    companion object {
        private const val FORBIDDEN_IN_HOST = " #%/:<>?@[\\]^|"

        fun parse(text: String): WebUrl? {
            val scheme = text.substringBefore("://", "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            // For http(s), a browser takes a backslash for a slash.
            val rest = text.substringAfter("://").replace('\\', '/')
            val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
            val hostPort = authority.substringAfterLast('@')
            // An IPv6 address is kept as it is: it is never a music service anyway.
            val host = if (hostPort.startsWith("[")) hostPort.lowercase() else {
                if (!portOk(hostPort.substringAfterLast(':', ""))) return null
                percentDecode(hostPort.substringBeforeLast(':'), plus = false).lowercase()
                    .takeIf { h -> h.isNotEmpty() && h.none { it in FORBIDDEN_IN_HOST } } ?: return null
            }
            val afterAuthority = rest.substring(authority.length).substringBefore('#')
            val path = afterAuthority.substringBefore('?')
            val query = if (afterAuthority.contains('?')) afterAuthority.substringAfter('?') else ""
            val segments = ArrayList<String>()
            for (seg in path.split('/')) when (seg.lowercase()) {
                "", ".", "%2e" -> {}
                "..", ".%2e", "%2e.", "%2e%2e" -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += seg
            }
            return WebUrl(host, segments, formPairs(query))
        }

        /**
         * A browser's port: ASCII digits only (not `٣` nor `１`, which Char.isDigit accepts), at most
         * 65535, leading zeros allowed; empty means the default port.
         */
        fun portOk(port: String): Boolean =
            port.all { it in '0'..'9' } && port.trimStart('0').let { it.length <= 5 && (it.toIntOrNull() ?: 0) <= 65535 }

        /** URLSearchParams: `&`-separated, `+` is a space, names and values percent-decoded. */
        fun formPairs(query: String): List<Pair<String, String>> = query.split('&').filter { it.isNotEmpty() }.map { p ->
            val k = p.substringBefore('=')
            val v = if (p.contains('=')) p.substringAfter('=') else ""
            percentDecode(k, plus = true) to percentDecode(v, plus = true)
        }

        /** Like a browser: a `%` not followed by two hex digits stays as it is; bad UTF-8 becomes U+FFFD. */
        fun percentDecode(s: String, plus: Boolean): String {
            val bytes = s.toByteArray(Charsets.UTF_8)
            val out = java.io.ByteArrayOutputStream(bytes.size)
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i]
                val hi = if (i + 2 <= bytes.lastIndex) Character.digit(bytes[i + 1].toInt(), 16) else -1
                val lo = if (hi >= 0) Character.digit(bytes[i + 2].toInt(), 16) else -1
                when {
                    b == '%'.code.toByte() && hi >= 0 && lo >= 0 -> { out.write(hi * 16 + lo); i += 3 }
                    plus && b == '+'.code.toByte() -> { out.write(' '.code); i++ }
                    else -> { out.write(b.toInt()); i++ }
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
