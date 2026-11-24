package app.chudmusic.youtube

import java.net.URI

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
            val text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            SPOTIFY_URI.matchEntire(text)?.let { m -> return Spotify(spotifyKind(m.groupValues[1])!!, m.groupValues[2]) }
            val uri = runCatching { URI(if (Regex("^[a-z]+://", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "https://$text") }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
            val host = uri.host?.lowercase() ?: return null
            val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            val query = queryOf(uri.rawQuery)

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
            val v = query["v"]?.takeIf { VIDEO.matches(it) }
            val list = query["list"]
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

        private fun spotifyKind(s: String) = SpotifyKind.entries.firstOrNull { it.path == s }

        private fun queryOf(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { p ->
            val k = p.substringBefore('=', "").ifEmpty { return@mapNotNull null }
            k to java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
        }.toMap()
    }
}
