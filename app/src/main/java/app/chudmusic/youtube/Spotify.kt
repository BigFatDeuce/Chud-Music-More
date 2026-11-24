package app.chudmusic.youtube

import app.chudmusic.net.Http
import app.chudmusic.net.HttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * A Spotify link only names songs. Their titles and artists are read from Spotify's public embed
 * page (the one any website can show: no account, no key), as the PC app does; each song is then
 * looked up on YouTube Music. Nothing is ever played from Spotify.
 */
object Spotify {
    /** What an embed page says about a track, album, playlist or artist. */
    data class Entity(val title: String, val subtitle: String?, val tracks: List<WantedTrack>)

    /** Why a Spotify page could not be used; the UI words it. */
    class Failure(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause)
    enum class Reason { OFFLINE, NOT_FOUND, REFUSED, UNREADABLE }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val json = Json { ignoreUnknownKeys = true }

    fun entity(kind: MusicLink.SpotifyKind, id: String): Entity {
        val html = try {
            Http.getText("https://open.spotify.com/embed/${kind.path}/$id", mapOf("User-Agent" to UA, "Accept-Language" to "en"))
        } catch (e: HttpException) {
            throw Failure(if (e.code == 404) Reason.NOT_FOUND else Reason.REFUSED, e)
        } catch (e: IOException) {
            throw Failure(Reason.OFFLINE, e)
        }
        return parse(html, kind)
    }

    /** The entity of an embed page. A well-formed page without one is a wrong or deleted id. */
    fun parse(html: String, kind: MusicLink.SpotifyKind): Entity {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1) ?: throw Failure(Reason.UNREADABLE)
        val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: throw Failure(Reason.UNREADABLE)
        val e: JsonElement = root.path("props", "pageProps", "state", "data", "entity") ?: throw Failure(Reason.NOT_FOUND)
        val title = (e["title"].str ?: e["name"].str ?: "Spotify").trim()
        val tracks = if (kind == MusicLink.SpotifyKind.TRACK) {
            listOf(WantedTrack(title, e["artists"].arr.mapNotNull { it["name"].str }, e["duration"].long?.takeIf { it > 0 }))
        } else {
            e["trackList"].arr.mapNotNull { t ->
                val name = t["title"].str?.trim()?.ifEmpty { null } ?: return@mapNotNull null
                WantedTrack(name, Matcher.splitArtists(t["subtitle"].str), t["duration"].long?.takeIf { it > 0 })
            }
        }
        val subtitle = if (kind == MusicLink.SpotifyKind.TRACK) tracks.firstOrNull()?.artists?.joinToString(", ") else e["subtitle"].str
        return Entity(title, subtitle?.ifBlank { null }, tracks)
    }

    /** The embed page lists only the first 100 tracks of a playlist. */
    const val EMBED_LIMIT = 100
}
