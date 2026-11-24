package app.chudmusic.library

import app.chudmusic.data.ChudDatabase
import app.chudmusic.data.KnownFile
import app.chudmusic.data.NetworkSourceEntity
import app.chudmusic.data.TrackEntity
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.net.CachedRemoteFile
import app.chudmusic.net.NetworkSources
import app.chudmusic.net.RemoteAuthFailed
import app.chudmusic.net.RemoteEntry
import app.chudmusic.net.RemoteFile
import app.chudmusic.net.RemoteFs
import app.chudmusic.net.RemoteUnreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scans one network place. Same rules as the PC app (§32):
 * - incremental: a file with the same size and date is not read again;
 * - each request is a network round trip: folders listed 6 at a time, tags read 4 at a time;
 * - an unreachable place changes nothing (a NAS asleep or the phone away from home never empties the library);
 * - a file that disappeared leaves the library but stays in its playlists, flagged unavailable;
 *   a file moved or renamed inside the source (same size, same duration) is followed by its playlists.
 */
class NetworkScanner(private val db: ChudDatabase, private val sources: NetworkSources, private val covers: CoverStore) {
    companion object {
        /** Tracks written per batch: a stopped scan loses at most this many tag reads. */
        const val FLUSH_EVERY = 25
        /** Files queued at once, four being read: not one coroutine per file of a huge share. */
        private const val READ_AHEAD = 400
        /** [NetworkSourceEntity.lastError]: what went wrong, explained by the settings screen. */
        const val ERROR_AUTH = "auth"
        const val ERROR_FOLDER = "folder"
        const val ERROR_UNREACHABLE = "unreachable"

        private val RETRY_MS = longArrayOf(2_000, 5_000, 15_000, 30_000)
    }

    data class Result(val tracks: Int, val read: Int, val error: String?)

    /** What the end of a scan needs to remember of a file it read. */
    private class Fresh(val id: String, val path: String, val folder: String?, val size: Long?, val durationMs: Long?, val bare: Boolean)

    suspend fun scan(source: NetworkSourceEntity, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result = withContext(Dispatchers.IO) {
        try {
            read(source, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Everything read so far is written: the next scan goes on from there.
            fail(source, e)
        }
    }

    private suspend fun read(source: NetworkSourceEntity, onProgress: (done: Int, total: Int) -> Unit): Result {
        val fs = sources.fsFor(source).second
        val files = mutableListOf<RemoteEntry>()
        val imagesByDir = HashMap<String, List<RemoteEntry>>()
        val closed = HashSet<String>()
        walk(fs, source.rootPath.trim('/'), files, imagesByDir, closed)

        val existing = db.tracks().knownByNetworkSource(source.id).associateBy { it.path }
        val excludedIds = db.sources().exclusions().toSet()
        val toRead = files.filter { f -> existing[f.path]?.let { it.sizeBytes != f.size || it.mtime != f.mtime || !it.available } ?: true }
        // Counted over every file, those already read included: a scan that goes on after a stop
        // shows "400/1200" and climbs, instead of a new count from 0 that looks like starting over.
        val done = AtomicInteger(files.size - toRead.size)
        val total = files.size
        onProgress(done.get(), total)
        val coverByDir = HashMap<String, String?>()
        val sem = Semaphore(4)
        val now = System.currentTimeMillis()
        val mtimes = HashMap<String, Long>(toRead.size * 2).apply { toRead.forEach { put(it.path, it.mtime) } }
        fun entity(t: Track): TrackEntity {
            val old = existing[t.path]
            val withCover = if (t.coverUri == null) t.copy(coverUri = synchronized(coverByDir) { coverByDir[t.folder] }) else t
            val e = TrackEntity.from(withCover, inLibrary = t.id !in excludedIds, mtime = mtimes[t.path], now = now)
            return if (old != null) e.copy(addedAt = old.addedAt, lastPlayedAt = old.lastPlayedAt, playCount = old.playCount) else e
        }

        // Written by batches as they are read: a scan stopped halfway (app closed, phone off) keeps
        // what it read, and the next one only reads the rest (same size and date = not read again).
        val batch = BatchWriter(FLUSH_EVERY) { db.tracks().upsert(it.map(::entity)) }
        val fresh = ArrayList<Fresh>(toRead.size)
        try {
            for (part in toRead.chunked(READ_AHEAD)) {
                fresh += coroutineScope {
                    part.map { f ->
                        async {
                            sem.withPermit {
                                ensureActive()
                                var attempt = 0
                                val t = resuming(fs) { readOne(source, fs, f, coverByDir, imagesByDir, again = attempt++ > 0) }
                                batch.add(t)
                                onProgress(done.incrementAndGet(), total)
                                Fresh(t.id, f.path, t.folder, t.sizeBytes, t.durationMs, t.coverUri == null)
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            withContext(NonCancellable) { batch.flush() }
        }

        // A track read while its folder's cover was being extracted gets it now.
        fresh.filter { it.bare && coverByDir[it.folder] != null }.groupBy { it.folder }.forEach { (dir, list) ->
            list.map { it.id }.chunked(500).forEach { db.tracks().setCover(it, coverByDir[dir]) }
        }
        // Unchanged files: make sure they are in the library (a source re-added after removal).
        files.mapNotNull { existing[it.path] }.filter { !it.inLibrary && it.id !in excludedIds && it.available }
            .map { it.id }.chunked(500).forEach { db.tracks().joinLibrary(it) }

        // Gone: out of the library, flagged unavailable; followed by its playlists if it moved.
        val present = files.mapTo(HashSet()) { it.path }
        // What lies in a folder that could not be opened is not known to be gone.
        val gone = existing.values.filter { k -> k.path !in present && k.available && closed.none { k.path?.startsWith("$it/") == true } }
        if (gone.isNotEmpty()) {
            relinkMoved(gone, fresh.filter { it.path !in existing.keys })
            gone.map { it.id }.chunked(500).forEach { db.tracks().leaveLibrary(it); db.tracks().setAvailable(it, false) }
        }
        db.tracks().purgeOrphans()
        db.sources().scanned(source.id, System.currentTimeMillis(), null, total)
        return Result(total, fresh.size, null)
    }

    /**
     * Runs [block] again after a lost connection, waiting longer each time ([RETRY_MS]): a Wi-Fi that
     * drops for a minute only pauses the scan. A refused password is not retried.
     */
    private suspend fun <T> resuming(fs: RemoteFs, waits: LongArray = RETRY_MS, block: () -> T): T {
        for (wait in waits) {
            try {
                return block()
            } catch (e: RemoteAuthFailed) {
                throw e
            } catch (e: java.io.FileNotFoundException) {
                throw e
            } catch (_: java.io.IOException) {
                delay(wait)
                fs.networkChanged()
            }
        }
        return block()
    }

    private suspend fun fail(source: NetworkSourceEntity, e: Exception): Result {
        val msg = when (e) {
            is RemoteAuthFailed -> ERROR_AUTH
            is java.io.FileNotFoundException -> ERROR_FOLDER
            is java.io.IOException -> ERROR_UNREACHABLE
            else -> e.message ?: e.javaClass.simpleName
        }
        db.sources().scanned(source.id, System.currentTimeMillis(), msg, source.trackCount)
        return Result(source.trackCount, 0, msg)
    }

    /** A folder deleted during the scan, or one the account may not open ([closed]), is skipped; not the root. */
    private suspend fun listOrNothing(fs: RemoteFs, dir: String, root: Boolean, closed: MutableSet<String>): List<RemoteEntry> = try {
        // A place that never answered is off, or the phone is away from it: no insisting.
        resuming(fs, if (root) RETRY_MS.copyOf(1) else RETRY_MS) { fs.list(dir) }
    } catch (e: java.io.FileNotFoundException) {
        if (root) throw e else emptyList()
    } catch (e: RemoteAuthFailed) {
        if (root) throw e
        synchronized(closed) { closed += dir }
        emptyList()
    }

    /** Lists every folder, 6 at a time (each listing is one round trip). */
    private suspend fun walk(fs: RemoteFs, root: String, files: MutableList<RemoteEntry>, images: HashMap<String, List<RemoteEntry>>, closed: MutableSet<String>) = coroutineScope {
        val sem = Semaphore(6)
        var level = listOf(root)
        while (level.isNotEmpty()) {
            val listed = level.map { dir -> async { sem.withPermit { dir to listOrNothing(fs, dir, dir == root, closed) } } }.awaitAll()
            val next = mutableListOf<String>()
            for ((dir, entries) in listed) {
                for (e in entries) {
                    when {
                        e.isDir -> if (!e.name.startsWith(".") && !e.name.startsWith("@") && e.name != "#recycle") next += e.path
                        TagReader.isAudio(e.name) -> files += e
                    }
                }
                val imgs = entries.filter { !it.isDir && it.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png") }
                if (imgs.isNotEmpty()) images[dir] = imgs
            }
            level = next
        }
    }

    /** Remembers that a read failed: MediaMetadataRetriever swallows it and only says "no tags". */
    private class Watched(private val inner: RemoteFile) : RemoteFile {
        var failure: java.io.IOException? = null
        override val length: Long get() = guard { inner.length }
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = guard { inner.read(pos, buf, off, len) }
        private fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: java.io.IOException) {
            failure = e
            throw e
        } catch (e: RuntimeException) {
            // smbj's own exceptions (connection reset, session gone).
            failure = RemoteUnreachable(e.message ?: e.javaClass.simpleName, e)
            throw e
        }
        override fun close() { runCatching { inner.close() } }
    }

    /**
     * Throws when the connection is lost while the file is read: written then, the track would keep
     * no tags until its file changes. A file that cannot be read on a share that answers is kept
     * under its file name.
     */
    private fun readOne(source: NetworkSourceEntity, fs: RemoteFs, f: RemoteEntry, coverByDir: HashMap<String, String?>, images: Map<String, List<RemoteEntry>>, again: Boolean): Track {
        val dir = f.path.substringBeforeLast('/', "")
        val needCover = synchronized(coverByDir) { !coverByDir.containsKey(dir) }
        val tags = try {
            fs.open(f.path).use { raw ->
                val file = Watched(raw)
                val t = TagReader.read(CachedRemoteFile(file), withPicture = needCover)
                file.failure?.let { throw it }
                t
            }
        } catch (e: Exception) {
            if (e is RemoteAuthFailed || e is java.io.FileNotFoundException) null
            else {
                // Does the share still answer, the second time? Then this file alone is the problem. The
                // first time proves nothing: the connection may have come back between the two.
                if (!again || runCatching { fs.list(dir) }.isFailure) throw (e as? java.io.IOException ?: RemoteUnreachable(e.message ?: e.javaClass.simpleName, e))
                null
            }
        }
        val (guessTitle, guessArtist) = TagReader.guessFromName(f.name)
        // One cover per folder; the tracks read while it was being extracted get it after the scan.
        val cover = if (!needCover) synchronized(coverByDir) { coverByDir[dir] } else {
            val key = covers.keyFor("net", source.id.toString(), dir)
            val c = covers.existing(key)
                ?: tags?.picture?.let { covers.saveBytes(key, it) }
                ?: folderImage(fs, images[dir], key, again)
            synchronized(coverByDir) { coverByDir[dir] = c }
            c
        }
        return Track(
            id = "net:${source.id}:${f.path}",
            source = Source.NETWORK,
            uri = NetworkSources.trackUri(source.id, f.path),
            title = tags?.title ?: guessTitle,
            artist = tags?.artist ?: guessArtist,
            album = tags?.album,
            albumArtist = tags?.albumArtist,
            durationMs = tags?.durationMs,
            trackNo = tags?.trackNo,
            discNo = tags?.discNo,
            year = tags?.year,
            genre = tags?.genre,
            coverUri = cover ?: covers.webCover(Track(id = "", source = Source.NETWORK, uri = "", title = "", album = tags?.album, albumArtist = tags?.albumArtist, sourceId = source.id, folder = dir)),
            mime = tags?.mime,
            sizeBytes = f.size,
            path = f.path,
            sourceId = source.id,
            folder = dir,
            available = true,
        )
    }

    private fun folderImage(fs: RemoteFs, imgs: List<RemoteEntry>?, key: String, again: Boolean): String? {
        if (imgs.isNullOrEmpty()) return null
        val pick = imgs.minByOrNull { img ->
            val n = img.name.substringBeforeLast('.').lowercase()
            TagReader.IMAGE_NAMES.indexOf(n).let { if (it < 0) 99 else it }
        } ?: return null
        if (pick.size > 15_000_000) return null
        return runCatching {
            fs.open(pick.path).use { f ->
                val bytes = ByteArray(f.length.toInt())
                var got = 0
                while (got < bytes.size) { val n = f.read(got.toLong(), bytes, got, bytes.size - got); if (n <= 0) break; got += n }
                covers.saveBytes(key, bytes)
            }
        }.getOrElse { if (!again && it is java.io.IOException && it !is RemoteAuthFailed && it !is java.io.FileNotFoundException) throw it else null }
    }

    /** A playlist entry follows its file when it was moved or renamed (same size and duration). */
    private suspend fun relinkMoved(gone: List<KnownFile>, added: List<Fresh>) {
        if (added.isEmpty()) return
        val bySize = added.groupBy { it.size }
        for (old in gone) {
            val match = bySize[old.sizeBytes]?.singleOrNull { n ->
                old.durationMs == null || n.durationMs == null || kotlin.math.abs(old.durationMs - n.durationMs) < 1500
            } ?: continue
            if (db.playlists().usage(old.id) > 0) db.playlists().relink(old.id, match.id)
        }
    }
}
