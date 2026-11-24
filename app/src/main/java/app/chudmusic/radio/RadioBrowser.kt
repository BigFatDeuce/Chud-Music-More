package app.chudmusic.radio

import app.chudmusic.data.RadioDao
import app.chudmusic.data.RadioStationEntity
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Internet radio through radio-browser.info (free, open directory, no key), like the PC (§21):
 * search, popular stations of a country, by genre; favourites stay on the phone.
 */
class RadioBrowser(private val dao: RadioDao) {
    @Serializable
    data class Station(
        @SerialName("stationuuid") val uuid: String,
        val name: String = "",
        val url: String = "",
        @SerialName("url_resolved") val urlResolved: String? = null,
        val favicon: String? = null,
        @SerialName("countrycode") val country: String? = null,
        val tags: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        val hls: Int = 0,
        @SerialName("lastcheckok") val lastCheckOk: Int = 1,
    ) {
        fun entity(favorite: Boolean = false, lastPlayedAt: Long = 0) = RadioStationEntity(
            uuid = uuid, name = name.trim(), url = urlResolved?.ifBlank { null } ?: url, favicon = favicon?.ifBlank { null },
            country = country, tags = tags, codec = codec, bitrate = bitrate?.takeIf { it > 0 }, hls = hls == 1,
            favorite = favorite, lastPlayedAt = lastPlayedAt,
        )
    }

    @Serializable
    data class Tag(val name: String, @SerialName("stationcount") val count: Int = 0)

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val headers = mapOf("User-Agent" to "ChudMusic-Android/1")
    @Volatile private var servers: List<String>? = null

    /** The directory asks clients to pick one of its mirrors; the list is fetched once. */
    private fun servers(): List<String> = servers ?: run {
        val fromApi = runCatching {
            json.decodeFromString(ListSerializer(ServerName.serializer()), Http.getText("https://all.api.radio-browser.info/json/servers", headers))
                .map { "https://${it.name}" }.distinct().shuffled()
        }.getOrNull().orEmpty()
        (fromApi + listOf("https://de1.api.radio-browser.info", "https://de2.api.radio-browser.info", "https://fi1.api.radio-browser.info"))
            .distinct().also { servers = it }
    }

    @Serializable private data class ServerName(val name: String)

    private fun get(path: String): String {
        var last: Exception? = null
        for (s in servers().take(4)) {
            try { return Http.getText(s + path, headers) } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("radio-browser")
    }

    private fun stations(path: String): List<Station> =
        json.decodeFromString(ListSerializer(Station.serializer()), get(path)).filter { it.lastCheckOk == 1 && (it.urlResolved ?: it.url).isNotBlank() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val common = "hidebroken=true&order=clickcount&reverse=true"

    suspend fun search(q: String, limit: Int = 60): List<Station> = withContext(Dispatchers.IO) { stations("/json/stations/search?name=${enc(q)}&limit=$limit&$common") }
    suspend fun top(country: String, limit: Int = 60): List<Station> = withContext(Dispatchers.IO) { stations("/json/stations/search?countrycode=${enc(country)}&limit=$limit&$common") }
    suspend fun byTag(tag: String, country: String?, limit: Int = 60): List<Station> = withContext(Dispatchers.IO) {
        stations("/json/stations/search?tag=${enc(tag)}&tagExact=true${country?.let { "&countrycode=${enc(it)}" } ?: ""}&limit=$limit&$common")
    }
    suspend fun tags(limit: Int = 40): List<Tag> = withContext(Dispatchers.IO) {
        json.decodeFromString(ListSerializer(Tag.serializer()), get("/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=$limit"))
    }

    /** The directory counts plays to rank stations (what the API asks of clients). */
    suspend fun countClick(uuid: String) = withContext(Dispatchers.IO) { runCatching { get("/json/url/${enc(uuid)}") } }

    // ── Favourites (on the phone) ───────────────────────────────────────────────────────────

    val favorites: Flow<List<Track>> = dao.favorites().map { l -> l.map { toTrack(it) } }
    val recent: Flow<List<Track>> = dao.recent(12).map { l -> l.map { toTrack(it) } }
    suspend fun favoritesNow(): List<Track> = favorites.first()

    suspend fun setFavorite(st: Station, fav: Boolean) {
        val prev = dao.get(st.uuid)
        dao.upsert(st.entity(fav, prev?.lastPlayedAt ?: 0))
    }
    suspend fun setFavorite(uuid: String, fav: Boolean) = dao.setFavorite(uuid, fav)
    suspend fun isFavorite(uuid: String) = dao.get(uuid)?.favorite == true

    /** Called when a station starts: kept for "Écoutées récemment" and counted on the directory. */
    suspend fun noteStarted(t: Track, st: Station? = null) {
        val uuid = uuidOf(t) ?: return
        val prev = dao.get(uuid)
        when {
            prev != null -> dao.touch(uuid, System.currentTimeMillis())
            st != null -> dao.upsert(st.entity(false, System.currentTimeMillis()))
        }
        countClick(uuid)
    }

    companion object {
        const val PREFIX = "radio:"
        fun uuidOf(t: Track) = t.id.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

        fun toTrack(e: RadioStationEntity) = Track(
            id = PREFIX + e.uuid, source = Source.RADIO, uri = e.url, title = e.name,
            artist = listOfNotNull(e.country, codecLabel(e.codec, e.hls)?.let { c -> e.bitrate?.let { "$c $it kbit/s" } ?: c }).joinToString(" · ").ifBlank { null },
            coverUri = e.favicon?.takeIf { it.startsWith("https://") }, mime = if (e.hls) "audio/x-mpegurl" else null,
        )

        fun toTrack(s: Station) = toTrack(s.entity())

        /** The directory writes "UNKNOWN" for streams it could not probe, HLS ones among them. */
        fun codecLabel(codec: String?, hls: Boolean): String? =
            codec?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) } ?: if (hls) "HLS" else null
    }
}
