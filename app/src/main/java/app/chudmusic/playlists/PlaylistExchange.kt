package app.chudmusic.playlists

import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.youtube.InnerTube
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Instant

/**
 * Playlist files shared with Chud Music for PC, both ways, with no change on the PC side:
 *
 * JSON — the PC's own format (`format: "chud-music-playlists"`, version 1, src/main/playlists.ts).
 * The PC ignores fields it does not know when it imports (checked in ensureTrackRow), so the
 * Android app adds a few (albumArtist, trackNo…) that make the next import more precise.
 *
 * M3U8 — `#EXTM3U`, `#PLAYLIST:name`, `#EXTINF:secs,Artist - Title`, then a path or a YouTube Music link.
 *
 * A file path written by one device rarely exists on the other one. So an imported "local" entry is
 * looked for, in this order: same path; an SMB path (`\\nas\share\…`) on a share added on the phone;
 * the same end of path (Artist/Album/file); the same title, artist and duration. An entry found
 * nowhere stays in the playlist, flagged unavailable, with its original path (exported unchanged).
 */
object PlaylistExchange {
    const val FORMAT = "chud-music-playlists"

    @Serializable
    data class ExportedTrack(
        val source: String,
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val durationSec: Double? = null,
        val path: String? = null,
        val videoId: String? = null,
        val coverUrl: String? = null,
        // Ignored by the PC (v1), used by the Android import:
        val albumArtist: String? = null,
        val trackNo: Int? = null,
        val discNo: Int? = null,
        val year: Int? = null,
    )

    @Serializable
    data class ExportedPlaylist(
        val name: String,
        val tracks: List<ExportedTrack>,
        /** The picture the user chose, as a data: URI (JPEG, 512 px at most). Optional, ignored by the PC (v1). */
        val cover: String? = null,
        /** The description of a smart playlist ([SmartRules]), as the PC writes it; [tracks] are then those of the moment. */
        val smart: kotlinx.serialization.json.JsonObject? = null,
    )

    /** A playlist to write in a file. */
    data class Out(val name: String, val tracks: List<Track>, val cover: String? = null, val smart: SmartRules? = null)

    @Serializable
    data class ChudPlaylistFile(
        val format: String,
        val version: Int = 1,
        val exportedAt: String? = null,
        val playlists: List<ExportedPlaylist>,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; prettyPrintIndent = "  "; explicitNulls = true; encodeDefaults = true }

    /** A track as the other device will read it; [exchangePath] gives SMB files their UNC path. */
    fun exportTrack(t: Track, exchangePath: (Track) -> String?): ExportedTrack? = when {
        t.source == Source.YOUTUBE && t.videoId != null -> ExportedTrack(
            source = "youtube", title = t.title, artist = t.artist, album = t.album, durationSec = t.durationMs?.div(1000.0),
            videoId = t.videoId, coverUrl = t.coverUri?.takeIf { it.startsWith("http") },
        )
        t.source == Source.LOCAL || t.source == Source.NETWORK -> {
            val path = exchangePath(t) ?: t.path
            path?.let {
                ExportedTrack(
                    source = "local", title = t.title, artist = t.artist, album = t.album, durationSec = t.durationMs?.div(1000.0), path = it,
                    albumArtist = t.albumArtist, trackNo = t.trackNo, discNo = t.discNo, year = t.year,
                )
            }
        }
        else -> null
    }

    fun toJson(playlists: List<Out>, exchangePath: (Track) -> String?): String = json.encodeToString(
        ChudPlaylistFile.serializer(),
        ChudPlaylistFile(FORMAT, 1, Instant.now().toString(), playlists.map { p ->
            // Tracks left out by hand are ids of this device: they mean nothing on the other one.
            ExportedPlaylist(p.name, p.tracks.mapNotNull { exportTrack(it, exchangePath) }, p.cover, p.smart?.copy(excludeIds = emptyList())?.toJsonObject())
        }),
    )

    /** Same lines as the PC (CRLF, `#EXTINF:-1` when the duration is unknown). Returns the text and the number of entries skipped (radio). */
    fun toM3u(name: String, tracks: List<Track>, exchangePath: (Track) -> String?): Pair<String, Int> {
        val lines = mutableListOf("#EXTM3U", "#PLAYLIST:$name")
        var skipped = 0
        for (t in tracks) {
            val secs = t.durationMs?.let { Math.round(it / 1000.0) } ?: -1
            val label = (if (t.artist != null) "${t.artist} - " else "") + t.title
            when {
                t.source == Source.YOUTUBE && t.videoId != null -> lines += listOf("#EXTINF:$secs,$label", "https://music.youtube.com/watch?v=${t.videoId}")
                (t.source == Source.LOCAL || t.source == Source.NETWORK) && (exchangePath(t) ?: t.path) != null -> lines += listOf("#EXTINF:$secs,$label", exchangePath(t) ?: t.path!!)
                else -> skipped++
            }
        }
        return lines.joinToString("\r\n", postfix = "\r\n") to skipped
    }

    // ── Import ────────────────────────────────────────────────────────────────────────────

    /** What the import needs to know about the phone: its library and its network places. */
    interface Resolver {
        val library: List<Track>
        /** `\\host\share\rest` → the phone's track id and URI for it, if that share was added here. */
        fun fromUnc(host: String, share: String, rest: String, hint: Track): Track?
    }

    data class Imported(val name: String, val tracks: List<Track>, val found: Int, val missing: Int, val cover: String? = null, val smart: SmartRules? = null)

    private fun ends(c: List<String>) = c.takeLast(2).joinToString("/")

    /**
     * Whether a library track may be one the file [text] speaks of: the same end of path or the
     * same title. What [parse] looks for among the tracks it accepts, it finds as in the whole library.
     */
    fun wanted(text: String): (Track) -> Boolean {
        val clean = text.removePrefix("\uFEFF")
        val paths = HashSet<String>()
        val titles = HashSet<String>()
        fun want(path: String?, title: String) {
            path?.let { paths += ends(components(it)) }
            titles += InnerTube.norm(title)
        }
        if (clean.trimStart().startsWith("{")) {
            runCatching { json.decodeFromString(ChudPlaylistFile.serializer(), clean) }.getOrNull()
                ?.playlists?.forEach { p -> p.tracks.forEach { if (it.source == "local") want(it.path, it.title) } }
        } else {
            var label = ""
            for (raw in clean.lines()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF:", ignoreCase = true) -> label = line.substringAfter(',', "").trim()
                    line.isEmpty() || line.startsWith("#") -> {}
                    else -> { want(line, splitLabel(label, line).first); label = "" }
                }
            }
        }
        return { t -> (t.path != null && ends(components(t.path)) in paths) || InnerTube.norm(t.title) in titles }
    }

    fun parse(fileName: String, text: String, resolver: Resolver): List<Imported> {
        val clean = text.removePrefix("\uFEFF")
        val trimmed = clean.trimStart()
        return if (trimmed.startsWith("{")) parseJson(clean, resolver) else listOf(parseM3u(fileName, clean, resolver))
    }

    class NotChudFile : Exception("not a Chud Music playlist file")

    private fun parseJson(text: String, resolver: Resolver): List<Imported> {
        val data = runCatching { json.decodeFromString(ChudPlaylistFile.serializer(), text) }.getOrElse { throw NotChudFile() }
        if (data.format != FORMAT) throw NotChudFile()
        val index = Index(resolver)
        return data.playlists.map { p ->
            var found = 0; var missing = 0
            val tracks = p.tracks.mapNotNull { e ->
                when (e.source) {
                    "youtube" -> e.videoId?.let { Track.youtube(it, e.title, e.artist, e.album, e.durationSec?.let { s -> (s * 1000).toLong() }, e.coverUrl) }?.also { found++ }
                    "local" -> e.path?.let { path ->
                        index.resolve(path, e.title, e.artist, e.album, e.durationSec?.let { (it * 1000).toLong() })?.also { found++ }
                            ?: missingTrack(path, e.title, e.artist, e.album, e.durationSec).also { missing++ }
                    }
                    else -> null
                }
            }
            Imported(p.name, tracks, found, missing, p.cover, p.smart?.let { SmartRules.from(it).copy(excludeIds = emptyList()) })
        }
    }

    private fun parseM3u(fileName: String, text: String, resolver: Resolver): Imported {
        val index = Index(resolver)
        val name = Regex("""#PLAYLIST:(.+)""").find(text)?.groupValues?.get(1)?.trim()
            ?: fileName.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""\.m3u8?$""", RegexOption.IGNORE_CASE), "")
        val tracks = mutableListOf<Track>()
        var found = 0; var missing = 0
        var info: Pair<Long?, String>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val body = line.substring(8)
                val secs = body.substringBefore(',').trim().toDoubleOrNull()?.takeIf { it >= 0 }
                info = secs?.let { (it * 1000).toLong() } to body.substringAfter(',', "").trim()
                continue
            }
            if (line.startsWith("#")) continue
            val (dur, label) = info ?: (null to "")
            info = null
            val (title, artist) = splitLabel(label, line)
            // Read like a pasted link (as the PC does), not a pattern found anywhere in the line.
            val yt = app.chudmusic.youtube.MusicLink.m3uVideoId(line)
            if (yt != null) {
                tracks += Track.youtube(yt, title, artist, durationMs = dur)
                found++
                continue
            }
            val t = index.resolve(line, title, artist, null, dur)
            if (t != null) { tracks += t; found++ } else { tracks += missingTrack(line, title, artist, null, dur?.div(1000.0)); missing++ }
        }
        return Imported(name, tracks, found, missing)
    }

    private fun splitLabel(label: String, path: String): Pair<String, String?> {
        if (label.isBlank()) return path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.') to null
        val parts = label.split(" - ", limit = 2)
        return if (parts.size == 2) parts[1].trim() to parts[0].trim() else label to null
    }

    private fun missingTrack(path: String, title: String, artist: String?, album: String?, durationSec: Double?) = Track(
        id = "missing:" + sha1(path),
        source = Source.LOCAL,
        uri = "missing:",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationSec?.let { (it * 1000).toLong() },
        available = false,
        path = path,
    )

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** Splits a path from either system into lowercase components. */
    fun components(path: String): List<String> = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.map { it.lowercase() }

    private class Index(private val r: Resolver) {
        private val byPath = r.library.filter { it.path != null }.groupBy { it.path!!.replace('\\', '/').lowercase() }
        // End of path (last 3 and last 2 components) → tracks; network tracks' path is relative to the share.
        private val bySuffix3 = HashMap<String, MutableList<Track>>()
        private val bySuffix2 = HashMap<String, MutableList<Track>>()
        private val byTitle = r.library.groupBy { InnerTube.norm(it.title) }

        init {
            for (t in r.library) {
                val c = components(t.path ?: continue)
                if (c.size >= 3) bySuffix3.getOrPut(c.takeLast(3).joinToString("/")) { mutableListOf() } += t
                if (c.size >= 2) bySuffix2.getOrPut(c.takeLast(2).joinToString("/")) { mutableListOf() } += t
            }
        }

        fun resolve(path: String, title: String, artist: String?, album: String?, durationMs: Long?): Track? {
            byPath[path.replace('\\', '/').lowercase()]?.firstOrNull()?.let { return it }
            Regex("""^[\\/]{2}([^\\/]+)[\\/]+([^\\/]+)[\\/]+(.+)$""").find(path)?.let { m ->
                val hint = Track(id = "", source = Source.NETWORK, uri = "", title = title, artist = artist, album = album, durationMs = durationMs)
                r.fromUnc(m.groupValues[1], m.groupValues[2], m.groupValues[3].replace('\\', '/'), hint)?.let { return it }
            }
            val c = components(path)
            if (c.size >= 3) bySuffix3[c.takeLast(3).joinToString("/")]?.singleOrNull()?.let { return it }
            if (c.size >= 2) bySuffix2[c.takeLast(2).joinToString("/")]?.singleOrNull()?.let { return it }
            return byMeta(title, artist, album, durationMs)
        }

        /** Same title (normalised), an artist in common, durations within 5 s when both are known. */
        private fun byMeta(title: String, artist: String?, album: String?, durationMs: Long?): Track? {
            val candidates = byTitle[InnerTube.norm(title)] ?: return null
            val who = artist?.let { InnerTube.artistsOf(it) } ?: emptySet()
            return candidates
                .filter { t -> who.isEmpty() || InnerTube.artistsOf(listOfNotNull(t.artist, t.albumArtist).joinToString(", ")).any { it in who } }
                .filter { t -> durationMs == null || t.durationMs == null || kotlin.math.abs(durationMs - t.durationMs) <= 5000 }
                .sortedBy { t -> if (album != null && t.album != null && InnerTube.norm(album) == InnerTube.norm(t.album)) 0 else 1 }
                .firstOrNull()
        }
    }
}
