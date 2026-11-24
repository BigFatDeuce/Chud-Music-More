package app.chudmusic.youtube

import app.chudmusic.model.Track
import app.chudmusic.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException

/**
 * YouTube Music's public catalogue through InnerTube (the API music.youtube.com itself uses),
 * like youtubei.js does in the PC app. No account, no cookie. Only metadata here: audio comes
 * from [YouTubeStreams]. YouTube's structures never leave this file: everything is mapped to
 * our own types (and a format change stays local).
 */
class InnerTube(private val hl: () -> String = { "fr" }, private val gl: () -> String = { "FR" }) {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var clientVersion: String? = null
    @Volatile private var visitorData: String? = null

    /** The current WEB_REMIX version, read from the site once (a too-old one is refused one day). */
    private fun version(): String {
        clientVersion?.let { return it }
        val v = runCatching {
            val html = Http.getText("https://music.youtube.com/", mapOf("User-Agent" to Http.BROWSER_UA, "Accept-Language" to "${hl()};q=0.9"))
            visitorData = Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)?.groupValues?.get(1)
            Regex(""""INNERTUBE_CLIENT_VERSION":"([^"]+)"""").find(html)?.groupValues?.get(1)
        }.getOrNull() ?: FALLBACK_VERSION
        clientVersion = v
        return v
    }

    private fun call(endpoint: String, extra: JsonObject.() -> Map<String, JsonElement> = { emptyMap() }, query: String = "", body: (MutableMap<String, JsonElement>) -> Unit): JsonElement {
        val v = version()
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", v)
                    put("hl", hl())
                    put("gl", gl())
                    visitorData?.let { put("visitorData", it) }
                }
            }
        }.let { base ->
            val m = base.toMutableMap()
            body(m)
            JsonObject(m)
        }
        val headers = buildMap {
            put("User-Agent", Http.BROWSER_UA)
            put("Origin", "https://music.youtube.com")
            put("Referer", "https://music.youtube.com/")
            put("X-YouTube-Client-Name", "67")
            put("X-YouTube-Client-Version", v)
            visitorData?.let { put("X-Goog-Visitor-Id", it) }
        }
        val text = try {
            Http.postJson("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false$query", payload.toString(), headers)
        } catch (e: IOException) {
            throw YouTubeException("network", e)
        }
        return json.parseToJsonElement(text)
    }

    private fun MutableMap<String, JsonElement>.put(key: String, value: String) { this[key] = kotlinx.serialization.json.JsonPrimitive(value) }

    // ── Search ──────────────────────────────────────────────────────────────────────────────

    fun search(query: String, type: YtSearchType): YtSearchPage {
        val res = call("search") { it.put("query", query); it.put("params", FILTER[type]!!) }
        val shelf = res.findAll("musicShelfRenderer").firstOrNull()
        return YtSearchPage(type, mapShelf(type, shelf["contents"].arr), continuationOf(shelf))
    }

    fun searchMore(type: YtSearchType, continuation: String): YtSearchPage {
        val res = call("search", query = "&ctoken=$continuation&continuation=$continuation&type=next") {}
        val shelf = res.path("continuationContents", "musicShelfContinuation")
            ?: res.findAll("musicShelfContinuation").firstOrNull()
            ?: res.findAll("musicShelfRenderer").firstOrNull()
        val items = shelf["contents"].arr.ifEmpty { res.findAll("continuationItems").firstOrNull().arr }
        return YtSearchPage(type, mapShelf(type, items), continuationOf(shelf) ?: continuationToken(items))
    }

    private fun continuationOf(shelf: JsonElement?): String? =
        shelf.path("continuations", 0, "nextContinuationData", "continuation").str
            ?: continuationToken(shelf["contents"].arr)

    private fun continuationToken(items: List<JsonElement>): String? =
        items.lastOrNull().path("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str

    fun suggestions(query: String): List<String> {
        val res = call("music/get_search_suggestions") { it.put("input", query) }
        return res.findAll("searchSuggestionRenderer").mapNotNull { it["suggestion"].text }.distinct().take(8)
    }

    private fun mapShelf(type: YtSearchType, items: List<JsonElement>): List<YtItem> = items.mapNotNull { node ->
        val r = node["musicResponsiveListItemRenderer"] ?: return@mapNotNull null
        when (type) {
            YtSearchType.SONG, YtSearchType.VIDEO -> listItemToTrack(r)?.let { YtItem.Song(it) }
            YtSearchType.ALBUM -> listItemToAlbum(r)?.let { YtItem.Album(it) }
            YtSearchType.ARTIST -> listItemToArtist(r)?.let { YtItem.Artist(it) }
        }
    }

    // ── Renderers → our types ───────────────────────────────────────────────────────────────

    private fun flexRuns(r: JsonElement?, col: Int) = r.path("flexColumns", col, "musicResponsiveListItemFlexColumnRenderer", "text", "runs").arr

    private fun pageType(run: JsonElement?) =
        run.path("navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str

    private fun browseId(run: JsonElement?) = run.path("navigationEndpoint", "browseEndpoint", "browseId").str

    /** Largest thumbnail; YouTube Music image URLs are resized by the server: ask for 544 px. */
    private fun cover(thumbs: JsonElement?): String? {
        val list = thumbs.findAll("thumbnails").firstOrNull().arr.ifEmpty { thumbs.arr }
        val best = list.maxByOrNull { it["width"].str?.toIntOrNull() ?: 0 } ?: return null
        val url = best["url"].str ?: return null
        return url.replace(Regex("""=w\d+-h\d+"""), "=w544-h544").let { if (it.startsWith("//")) "https:$it" else it }
    }

    private data class Byline(val artists: List<Pair<String, String?>>, val album: Pair<String, String?>?, val durationMs: Long?, val year: String?)

    private val SEPARATOR = Regex("""^\s*[•·&,]\s*$""")
    private val KINDS = setOf("Titre", "Song", "Vidéo", "Video", "Épisode", "Episode", "Single", "EP", "Album", "Artiste", "Artist", "Chanson")

    private fun byline(runs: List<JsonElement>): Byline {
        val artists = mutableListOf<Pair<String, String?>>()
        var album: Pair<String, String?>? = null
        var duration: Long? = null
        var year: String? = null
        val plain = mutableListOf<String>()
        for (run in runs) {
            val t = run["text"].str ?: continue
            when (pageType(run)) {
                "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> artists += t to browseId(run)
                "MUSIC_PAGE_TYPE_ALBUM" -> album = t to browseId(run)
                else -> when {
                    SEPARATOR.matches(t) -> {}
                    parseDuration(t) != null -> duration = parseDuration(t)
                    Regex("""^(19|20)\d{2}$""").matches(t.trim()) -> year = t.trim()
                    else -> plain += t.trim()
                }
            }
        }
        // Videos and some songs have their artist as plain text: "Artiste • 1,2 M de vues • 3:45".
        if (artists.isEmpty()) {
            plain.firstOrNull { it !in KINDS && !Regex("""(vues|views|lectures|plays)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?.let { artists += it to null }
        }
        return Byline(artists, album, duration, year)
    }

    private fun videoIdOf(r: JsonElement?): String? =
        r.path("playlistItemData", "videoId").str
            ?: r.path("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId").str
            ?: flexRuns(r, 0).firstOrNull().path("navigationEndpoint", "watchEndpoint", "videoId").str

    private fun listItemToTrack(r: JsonElement?, fallback: Track? = null): Track? {
        val videoId = videoIdOf(r) ?: return null
        val title = flexRuns(r, 0).joinToString("") { it["text"].str.orEmpty() }.ifBlank { null } ?: return null
        val b = byline(flexRuns(r, 1) + flexRuns(r, 2))
        val duration = b.durationMs ?: parseDuration(r.path("fixedColumns", 0, "musicResponsiveListItemFixedColumnRenderer", "text").text)
        return Track.youtube(
            videoId = videoId, title = title,
            artist = b.artists.joinToString(", ") { it.first }.ifBlank { null } ?: fallback?.artist,
            album = b.album?.first ?: fallback?.album,
            durationMs = duration,
            cover = cover(r.path("thumbnail")) ?: fallback?.coverUri,
            artistId = b.artists.firstNotNullOfOrNull { it.second } ?: fallback?.ytArtistId,
            albumId = b.album?.second ?: fallback?.ytAlbumId,
        )
    }

    private fun listItemToAlbum(r: JsonElement?): YtAlbumItem? {
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        val title = flexRuns(r, 0).joinToString("") { it["text"].str.orEmpty() }
        val runs = flexRuns(r, 1)
        val b = byline(runs)
        val kind = runs.firstOrNull()?.get("text").str?.takeIf { pageType(runs.first()) == null && parseDuration(it) == null && !Regex("^\\d{4}$").matches(it) }
        return YtAlbumItem(id, title, b.artists.firstOrNull()?.first, b.artists.firstOrNull()?.second, b.year, kind, cover(r.path("thumbnail")))
    }

    private fun listItemToArtist(r: JsonElement?): YtArtistItem? {
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        val name = flexRuns(r, 0).joinToString("") { it["text"].str.orEmpty() }
        val sub = flexRuns(r, 1).joinToString("") { it["text"].str.orEmpty() }.ifBlank { null }
        return YtArtistItem(id, name, sub, cover(r.path("thumbnail")))
    }

    private fun twoRowToAlbum(r: JsonElement?): YtAlbumItem? {
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: r.path("title", "runs", 0, "navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        if (!id.startsWith("MPRE")) return null
        val subRuns = r.path("subtitle", "runs").arr
        val b = byline(subRuns)
        val kind = subRuns.firstOrNull()?.get("text").str?.takeIf { pageType(subRuns.first()) == null && !Regex("^\\d{4}$").matches(it.trim()) }
        return YtAlbumItem(id, r["title"].text ?: "", b.artists.firstOrNull()?.first, b.artists.firstOrNull()?.second, b.year, kind, cover(r.path("thumbnailRenderer")))
    }

    private fun twoRowToArtist(r: JsonElement?): YtArtistItem? {
        val id = r.path("navigationEndpoint", "browseEndpoint", "browseId").str ?: return null
        if (!id.startsWith("UC")) return null
        return YtArtistItem(id, r["title"].text ?: "", r["subtitle"].text, cover(r.path("thumbnailRenderer")))
    }

    // ── Album and artist pages ──────────────────────────────────────────────────────────────

    fun album(browseId: String): YtAlbumPage {
        val res = call("browse") { it.put("browseId", browseId) }
        val header = res.findFirst("musicResponsiveHeaderRenderer") ?: res.findFirst("musicDetailHeaderRenderer")
        val title = header["title"].text ?: ""
        val artistRuns = header.path("straplineTextOne", "runs").arr.ifEmpty { header.path("subtitle", "runs").arr }
        val artistRun = artistRuns.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }
        val artist = artistRun?.get("text").str ?: header["straplineTextOne"].text
        val subRuns = header.path("subtitle", "runs").arr
        val year = subRuns.mapNotNull { it["text"].str?.trim() }.firstOrNull { Regex("^(19|20)\\d{2}$").matches(it) }
        val kind = subRuns.firstOrNull()?.get("text").str?.trim()?.takeIf { !Regex("^\\d{4}$").matches(it) }
        val coverUrl = cover(header.path("thumbnail"))
        val base = Track.youtube("x", "", artist, title, cover = coverUrl, artistId = browseId(artistRun), albumId = browseId)
        val shelf = res.findAll("musicShelfRenderer").firstOrNull()
        val tracks = shelf["contents"].arr.mapNotNull { listItemToTrack(it["musicResponsiveListItemRenderer"], base) }
            .map { t -> t.copy(album = title, ytAlbumId = browseId, coverUri = coverUrl ?: t.coverUri, artist = t.artist ?: artist) }
        return YtAlbumPage(browseId, title, artist, browseId(artistRun), year, kind, coverUrl, tracks)
    }

    fun artist(channelId: String): YtArtistPage {
        val res = call("browse") { it.put("browseId", channelId) }
        val header = res.findFirst("musicImmersiveHeaderRenderer") ?: res.findFirst("musicVisualHeaderRenderer") ?: res.findFirst("musicResponsiveHeaderRenderer")
        val name = header["title"].text ?: ""
        val coverUrl = cover(header.path("thumbnail")) ?: cover(header.path("foregroundThumbnail"))
        val description = header["description"].text
        val sections = res.path("contents", "singleColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents").arr
        val top = mutableListOf<Track>()
        val shelves = mutableListOf<YtShelf>()
        for (s in sections) {
            s["musicShelfRenderer"]?.let { shelf ->
                top += shelf["contents"].arr.mapNotNull { listItemToTrack(it["musicResponsiveListItemRenderer"]) }
                    .map { it.copy(artist = it.artist ?: name, ytArtistId = it.ytArtistId ?: channelId) }
            }
            s["musicCarouselShelfRenderer"]?.let { c ->
                val title = c.path("header", "musicCarouselShelfBasicHeaderRenderer", "title").text ?: ""
                val items = c["contents"].arr.mapNotNull { it["musicTwoRowItemRenderer"] }
                val albums = items.mapNotNull { twoRowToAlbum(it) }.map { it.copy(artist = it.artist ?: name, artistId = it.artistId ?: channelId) }
                val artists = items.mapNotNull { twoRowToArtist(it) }
                if (albums.isNotEmpty() || artists.isNotEmpty()) shelves += YtShelf(title, albums, artists)
            }
        }
        return YtArtistPage(channelId, name, coverUrl, description, top, shelves)
    }

    // ── Radio ("Démarrer la radio") ──────────────────────────────────────────────────────────

    fun mix(videoId: String): YtMix {
        val playlistId = "RDAMVM$videoId"
        val res = call("next") {
            it.put("videoId", videoId); it.put("playlistId", playlistId); it.put("params", "wAEB")
            it["isAudioOnly"] = kotlinx.serialization.json.JsonPrimitive(true)
        }
        val tracks = res.findAll("playlistPanelVideoRenderer").mapNotNull(::panelToTrack)
        return YtMix(tracks.firstOrNull { it.videoId == videoId }, playlistId, tracks.distinctBy { it.videoId })
    }

    /** Details of one song (artist, album) as YouTube Music's player queue shows them. */
    fun song(videoId: String): Track? {
        val res = call("next") { it.put("videoId", videoId); it["isAudioOnly"] = kotlinx.serialization.json.JsonPrimitive(true) }
        return res.findAll("playlistPanelVideoRenderer").mapNotNull(::panelToTrack).firstOrNull { it.videoId == videoId }
    }

    private fun panelToTrack(p: JsonElement?): Track? {
        val id = p["videoId"].str ?: return null
        val b = byline(p.path("longBylineText", "runs").arr)
        return Track.youtube(
            videoId = id, title = p["title"].text ?: return null,
            artist = b.artists.joinToString(", ") { it.first }.ifBlank { p["shortBylineText"].text },
            album = b.album?.first, durationMs = parseDuration(p["lengthText"].text),
            cover = cover(p["thumbnail"]), artistId = b.artists.firstNotNullOfOrNull { it.second }, albumId = b.album?.second,
        )
    }

    // ── Matching (local file → YouTube Music), same scoring idea as the PC app ───────────────

    fun findArtist(name: String): YtArtistItem? {
        val items = search(name, YtSearchType.ARTIST).items.filterIsInstance<YtItem.Artist>().map { it.artist }
        val n = norm(name)
        return items.firstOrNull { norm(it.name) == n } ?: items.firstOrNull { norm(it.name).contains(n) || n.contains(norm(it.name)) }
    }

    /** The YouTube Music version of a local file (for "Start mix"): same scorer as pasted links. */
    fun match(title: String, artist: String?, durationMs: Long?): Track? =
        match(WantedTrack(title, Matcher.splitArtists(artist).take(2), durationMs))

    /** Songs first (studio versions, with their album and artist pages), then other versions. */
    fun match(want: WantedTrack): Track? {
        val q = (listOf(want.title) + want.artists.take(2)).joinToString(" ")
        for (type in listOf(YtSearchType.SONG, YtSearchType.VIDEO)) {
            val found = search(q, type).items.filterIsInstance<YtItem.Song>().map { it.track }
            Matcher.best(found, want)?.let { return it }
        }
        return null
    }

    // ── Playlists (pasted links) ────────────────────────────────────────────────────────────

    /**
     * A public playlist, read page by page (about 100 tracks each) up to [max] tracks.
     * Throws [YouTubeException] "not-found" when YouTube has no such list (private or deleted).
     */
    fun playlist(id: String, max: Int = 1000): YtPlaylist {
        val res = call("browse") { it.put("browseId", if (id.startsWith("VL")) id else "VL$id") }
        val shelf = res.findAll("musicPlaylistShelfRenderer").firstOrNull()
            ?: throw YouTubeException("not-found")
        val header = res.findFirst("musicResponsiveHeaderRenderer") ?: res.findFirst("musicDetailHeaderRenderer")
        val tracks = mutableListOf<Track>()
        var items = shelf["contents"].arr
        var first = true
        while (true) {
            tracks += items.mapNotNull { listItemToTrack(it["musicResponsiveListItemRenderer"]) }
            val token = (if (first) continuationOf(shelf) else continuationToken(items)) ?: return YtPlaylist(id, header["title"].text, tracks.distinctBy { it.id }, truncated = false)
            if (tracks.size >= max) return YtPlaylist(id, header["title"].text, tracks.distinctBy { it.id }.take(max), truncated = true)
            first = false
            val next = call("browse") { it.put("continuation", token) }
            items = next.findAll("continuationItems").firstOrNull().arr
                .ifEmpty { next.findAll("musicPlaylistShelfContinuation").firstOrNull()?.get("contents").arr }
            if (items.isEmpty()) return YtPlaylist(id, header["title"].text, tracks.distinctBy { it.id }, truncated = false)
        }
    }

    companion object {
        const val FALLBACK_VERSION = "1.20251103.01.00"

        private val FILTER = mapOf(
            YtSearchType.SONG to "EgWKAQIIAWoKEAkQBRAKEAMQBA==",
            YtSearchType.VIDEO to "EgWKAQIQAWoKEAkQChAFEAMQBA==",
            YtSearchType.ALBUM to "EgWKAQIYAWoKEAkQChAFEAMQBA==",
            YtSearchType.ARTIST to "EgWKAQIgAWoKEAkQChAFEAMQBA==",
        )

        /** Accents, case, "(Official Video)", "feat. X", "- Remastered" left out. */
        fun norm(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase()
            .replace(Regex("""[(\[][^)\]]*(official|video|clip|audio|lyric|paroles|remaster|visualizer|hd|4k)[^)\]]*[)\]]"""), " ")
            .replace(Regex("""\s-\s.*remaster.*$"""), " ")
            .replace(Regex("""\b(feat|ft)\.?\s.*$"""), " ")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()

        fun artistsOf(s: String): Set<String> = s.split(Regex("""\s*(?:,|;|&|/|\bx\b|\bfeat\.?|\bft\.?)\s*""", RegexOption.IGNORE_CASE))
            .map { norm(it.replace(Regex("""vevo$|\s-\s*topic$""", RegexOption.IGNORE_CASE), "")) }.filter { it.isNotBlank() }.toSet()
    }
}
