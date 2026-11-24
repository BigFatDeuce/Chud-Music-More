package app.chudmusic.library

import app.chudmusic.data.ChudDatabase
import app.chudmusic.net.Http
import app.chudmusic.youtube.InnerTube
import app.chudmusic.youtube.Matcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * Covers for the albums whose files hold none, from Deezer, then iTunes (the PC app's *Find missing
 * covers*). Only when the user asks: the album's artist and title are the only things sent. The
 * picture is kept in the app's folder ([CoverStore.webKey]); the music files are never touched.
 *
 * ```
 * Idle ──start──▶ Running(done, total) ──▶ Done(found, searched)
 *                      │ three network failures in a row
 *                      └──────────────────▶ Failed(message)
 * ```
 * A search already running ignores [start]; the album screen's own search ([findOne]) runs apart.
 */
class OnlineCovers(private val db: ChudDatabase, private val covers: CoverStore, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Running(val done: Int, val total: Int) : State
        data class Done(val found: Int, val searched: Int) : State
        data class Failed(val message: String) : State
    }

    data class Candidate(val artist: String, val album: String, val imageUrl: String)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Searches every album of [albums] without a cover and not already searched in vain. */
    fun start(albums: List<Album>) {
        if (_state.value is State.Running) return
        val todo = albums.filter { it.cover == null && !it.artist.isNullOrBlank() && !covers.knownWithout(covers.webKey(it.key)) }
        _state.value = State.Running(0, todo.size)
        scope.launch(Dispatchers.IO) {
            var found = 0
            var failures = 0
            var lastError = ""
            for ((i, a) in todo.withIndex()) {
                val r = runCatching { findAndSave(a) }
                r.onSuccess { if (it) found++; failures = 0 }
                r.onFailure { failures++; lastError = it.message ?: it.javaClass.simpleName }
                if (failures >= 3) { _state.value = State.Failed(lastError); return@launch }
                _state.value = State.Running(i + 1, todo.size)
            }
            _state.value = State.Done(found, todo.size)
        }
    }

    /** One album, asked for from its page: searched again even if an earlier search found nothing. */
    suspend fun findOne(a: Album): Boolean {
        covers.markWithout(covers.webKey(a.key), false)
        return findAndSave(a)
    }

    /** Takes a wrong online cover away from [a]'s tracks. */
    suspend fun drop(a: Album) {
        covers.dropWeb(a.key)
        setCover(a, null)
    }

    private suspend fun setCover(a: Album, uri: String?) =
        db.tracks().byAlbum(a.key).map { it.id }.chunked(500).forEach { db.tracks().setCover(it, uri) }

    /** Whether [a]'s cover is one found online (it can then be taken away). */
    fun isOnline(a: Album): Boolean = a.cover != null && a.cover == covers.existing(covers.webKey(a.key))

    /** True when a cover was found and given to the album's tracks; false when none was found. Throws on network errors. */
    private suspend fun findAndSave(a: Album): Boolean {
        val key = covers.webKey(a.key)
        val artist = a.artist ?: return false
        val uri = covers.existing(key) ?: run {
            val pick = pick(artist, a.title, deezer(artist, a.title)) ?: pick(artist, a.title, itunes(artist, a.title))
            pick?.let { covers.saveBytes(key, Http.getBytes(it.imageUrl)) }
        }
        if (uri == null) { covers.markWithout(key, true); return false }
        setCover(a, uri)
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val headers = mapOf("User-Agent" to Http.APP_UA)

    private fun deezer(artist: String, album: String): List<Candidate> =
        parseDeezer(Http.getText("https://api.deezer.com/search/album?limit=10&q=" + enc("artist:\"${Library.mainArtist(artist) ?: artist}\" album:\"$album\""), headers))

    private fun itunes(artist: String, album: String): List<Candidate> =
        parseItunes(Http.getText("https://itunes.apple.com/search?entity=album&limit=10&term=" + enc("${Library.mainArtist(artist) ?: artist} $album"), headers))

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        fun parseDeezer(text: String): List<Candidate> = runCatching {
            (json.parseToJsonElement(text) as JsonObject)["data"].let { it as? JsonArray }.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val artist = (o["artist"] as? JsonObject)?.str("name") ?: return@mapNotNull null
                val url = o.str("cover_xl") ?: o.str("cover_big") ?: return@mapNotNull null
                Candidate(artist, o.str("title") ?: return@mapNotNull null, url)
            }
        }.getOrDefault(emptyList())

        fun parseItunes(text: String): List<Candidate> = runCatching {
            (json.parseToJsonElement(text) as JsonObject)["results"].let { it as? JsonArray }.orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = o.str("artworkUrl100")?.replace("100x100bb", "600x600bb") ?: return@mapNotNull null
                Candidate(o.str("artistName") ?: return@mapNotNull null, o.str("collectionName") ?: return@mapNotNull null, url)
            }
        }.getOrDefault(emptyList())

        /** "(Deluxe Edition)", "[Remastered 2011]", "- Expanded": the same album for a cover. */
        private val EDITION = Regex("""\s*(?:[(\[][^)\]]*\b(?:deluxe|edition|[eé]dition|remaster\w*|expanded|anniversary|bonus|version)\b[^)\]]*[)\]]|\s-\s.*\b(?:deluxe|edition|remaster\w*|expanded)\b.*$)""", RegexOption.IGNORE_CASE)

        private fun albumName(s: String) = InnerTube.norm(EDITION.replace(s, ""))

        /**
         * The candidate that is this album: its artist is one of the album's artists and its title
         * is the same once editions are set aside. None rather than a wrong picture.
         */
        fun pick(artist: String, album: String, candidates: List<Candidate>): Candidate? {
            if (!candidates.all { it.imageUrl.startsWith("https://") }) return pick(artist, album, candidates.filter { it.imageUrl.startsWith("https://") })
            val who = InnerTube.artistsOf(artist) + listOfNotNull(Library.mainArtist(artist)?.let(InnerTube::norm))
            val want = albumName(album)
            if (want.isBlank() || who.isEmpty()) return null
            return candidates.firstOrNull { c ->
                InnerTube.artistsOf(c.artist).any { it in who } && albumName(c.album) == want
            } ?: candidates.firstOrNull { c ->
                InnerTube.artistsOf(c.artist).any { it in who } && Matcher.similarity(albumName(c.album), want) >= 0.8
            }
        }
    }
}
