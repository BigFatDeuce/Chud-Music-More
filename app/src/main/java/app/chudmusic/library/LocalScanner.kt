package app.chudmusic.library

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.KnownFile
import app.chudmusic.data.TrackEntity
import app.chudmusic.model.Source
import app.chudmusic.model.Track

/**
 * The phone's music, as Android's media index (MediaStore) knows it: fast (no file is opened to
 * list the library), always up to date, and the same tags the system shows. Folders can be left
 * out in Settings. Covers are extracted once per album into the app's folder.
 */
class LocalScanner(private val context: Context, private val db: ChudDatabase, private val covers: CoverStore) {

    data class Folder(val path: String, val count: Int, val excluded: Boolean)

    /** One row of MediaStore, before exclusions. */
    private data class Row(val track: Track, val mtime: Long, val albumId: Long)

    /** Every row of MediaStore, one at a time: a big library is never held as a whole. */
    private inline fun eachRow(block: (row: Row, done: Int, total: Int) -> Unit) {
        val col = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val proj = buildList {
            add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM); add("album_artist"); add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK); add(MediaStore.Audio.Media.YEAR); add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE); add(MediaStore.Audio.Media.DATE_MODIFIED); add(MediaStore.Audio.Media.ALBUM_ID)
            @Suppress("DEPRECATION") add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()
        context.contentResolver.query(col, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iAlbumArtist = c.getColumnIndex("album_artist")
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            @Suppress("DEPRECATION") val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iGenre = if (Build.VERSION.SDK_INT >= 30) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1
            val total = c.count
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val path = c.getString(iData)
                val name = c.getString(iName) ?: path?.substringAfterLast('/') ?: "?"
                val unknown = { s: String? -> s?.takeIf { it.isNotBlank() && it != "<unknown>" } }
                var title = unknown(c.getString(iTitle))
                var artist = unknown(c.getString(iArtist))
                // MediaStore puts the file name in TITLE when there is no tag.
                if (title == null || title == name.substringBeforeLast('.')) {
                    val (t, a) = TagReader.guessFromName(name)
                    title = t
                    if (artist == null) artist = a
                }
                val rawTrack = c.getInt(iTrack)
                val folder = path?.substringBeforeLast('/')
                val track = Track(
                    id = "local:$id",
                    source = Source.LOCAL,
                    uri = ContentUris.withAppendedId(col, id).toString(),
                    title = title,
                    artist = artist,
                    album = unknown(c.getString(iAlbum)),
                    albumArtist = if (iAlbumArtist >= 0) unknown(c.getString(iAlbumArtist)) else null,
                    durationMs = c.getLong(iDur).takeIf { it > 0 },
                    trackNo = (rawTrack % 1000).takeIf { it > 0 },
                    discNo = (rawTrack / 1000).takeIf { it > 0 },
                    year = c.getInt(iYear).takeIf { it > 0 },
                    genre = if (iGenre >= 0) unknown(c.getString(iGenre)) else null,
                    mime = c.getString(iMime),
                    sizeBytes = c.getLong(iSize),
                    path = path,
                    folder = folder,
                )
                block(Row(track, c.getLong(iMod) * 1000, c.getLong(iAlbumId)), c.position + 1, total)
            }
        }
    }

    /** Every folder holding music, with its track count, for the "phone folders" setting. */
    suspend fun folders(): List<Folder> {
        val excluded = db.sources().excludedFolderPaths().toSet()
        val counts = HashMap<String, Int>()
        eachRow { r, _, _ -> counts.merge(r.track.folder ?: "/", 1, Int::plus) }
        return counts
            .map { (p, n) -> Folder(p, n, excluded.any { p == it || p.startsWith("$it/") }) }
            .sortedBy { it.path.lowercase() }
    }

    /** Syncs the library with MediaStore. Returns the number of phone tracks in the library. */
    suspend fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val excludedFolders = db.sources().excludedFolderPaths()
        val excludedIds = db.sources().exclusions().toSet()
        val existing = db.tracks().knownBySource(Source.LOCAL).associateBy { it.id }
        val present = HashSet<String>()
        val seen = HashSet<String>()

        // Covers: one per album (album id of MediaStore), extracted only if not already there.
        val coverByAlbum = HashMap<Long, String?>()
        // Written by batches: a scan stopped halfway (app closed) keeps what it did, and the next
        // one skips the files already up to date instead of starting over.
        val batch = BatchWriter<TrackEntity>(FLUSH_EVERY) { db.tracks().upsert(it) }
        fun coverKey(r: Row) = covers.keyFor("local-album", r.albumId.toString(), r.track.album, r.track.folder)
        eachRow { r, done, total ->
            if (done % 50 == 0) onProgress(done, total)
            seen += r.track.id
            if (r.track.id in excludedIds || excludedFolders.any { f -> r.track.folder == f || r.track.folder?.startsWith("$f/") == true }) return@eachRow
            present += r.track.id
            val old = existing[r.track.id]
            if (old != null && isUpToDate(old, r.track, r.mtime) { covers.knownWithout(coverKey(r)) }) return@eachRow
            val cover = coverByAlbum.getOrPut(r.albumId) {
                val key = coverKey(r)
                (covers.existing(key) ?: extractCover(r.track, key)).also { covers.markWithout(key, it == null) }
            } ?: covers.webCover(r.track)
            val t = r.track.copy(coverUri = cover)
            val e = TrackEntity.from(t, inLibrary = true, mtime = r.mtime)
            batch.add(if (old != null) e.copy(addedAt = old.addedAt, lastPlayedAt = old.lastPlayedAt, playCount = old.playCount) else e)
        }
        batch.flush()
        // Files that left MediaStore (deleted, moved, folder excluded): out of the library, kept for playlists.
        val gone = existing.values.filter { it.inLibrary && it.id !in present }.map { it.id }
        val vanished = existing.keys - seen
        gone.chunked(500).forEach { db.tracks().leaveLibrary(it) }
        vanished.toList().chunked(500).forEach { db.tracks().setAvailable(it, false) }
        db.tracks().purgeOrphans()
        onProgress(present.size, present.size)
        return present.size
    }

    companion object {
        const val FLUSH_EVERY = 200

        /**
         * Already in the library as MediaStore has it now (same file, same date, cover found or
         * already searched for in vain): nothing to redo. A changed file (new date) is read again,
         * its cover searched again too.
         */
        fun isUpToDate(old: KnownFile, now: Track, mtime: Long, coverSearchedInVain: () -> Boolean = { false }): Boolean =
            old.inLibrary && old.available && old.mtime == mtime && old.path == now.path &&
                (old.coverUri != null || coverSearchedInVain())
    }

    private fun extractCover(t: Track, key: String): String? {
        val uri = android.net.Uri.parse(t.uri)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()?.let { return covers.saveBitmap(key, it) }
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.embeddedPicture?.let { covers.saveBytes(key, it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }
}
