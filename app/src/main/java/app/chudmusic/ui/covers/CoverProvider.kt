package app.chudmusic.ui.covers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.chudmusic.BuildConfig
import app.chudmusic.net.Http
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Covers for other apps (Android Auto, the system media controls): they cannot read our files
 * nor always load web images. Exported so that they can read it, hence open to every app on the
 * phone: only links signed by this app ([CoverLinks]) open anything. Read-only:
 * - content://<app>.covers/f/<name>/<sig>  → filesDir/covers/<name> (covers from music files)
 * - content://<app>.covers/w/<b64>/<sig>   → a web image (YouTube Music, online covers),
 *   downloaded once into the cache (at most [MAX_BYTES], pictures only, cache kept small).
 */
class CoverProvider : ContentProvider() {
    companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.covers"
        private const val MAX_BYTES = 8_000_000L
        private const val CACHE_MAX = 60_000_000L
        private const val CACHE_KEEP = 40_000_000L
        /** Biggest side of a web cover given to other apps (YouTube and online covers are 1280 or less). */
        private const val MAX_SIDE = 4096
        /** Downloads at once: with [MAX_BYTES] each, what the cache may grow by while they run. */
        private const val PARALLEL = 2
        private val downloads = java.util.concurrent.Semaphore(PARALLEL)

        @Volatile private var links: CoverLinks? = null

        /** The app's own key, made once and kept in its private files (not in backups). */
        private fun links(ctx: Context?): CoverLinks = links ?: synchronized(this) {
            links ?: CoverLinks(keyBytes(ctx)).also { links = it }
        }

        private fun keyBytes(ctx: Context?): ByteArray {
            val random = { ByteArray(32).also { java.security.SecureRandom().nextBytes(it) } }
            val f = ctx?.let { File(it.filesDir, "cover-links.key") } ?: return random()
            return runCatching { f.readBytes().takeIf { it.size == 32 } }.getOrNull()
                ?: random().also { k -> runCatching { f.writeBytes(k) } }
        }

        /** Set from Application.onCreate so that [publicUri] signs with the same key as the provider. */
        fun init(ctx: Context) { links(ctx.applicationContext) }

        /** Inside the app, covers are file paths or https URLs; outside, they must be content:// URIs. */
        fun publicUri(cover: String?): Uri? {
            if (cover.isNullOrBlank()) return null
            val l = links ?: return null
            return when {
                cover.startsWith("content://") || cover.startsWith("android.resource://") -> Uri.parse(cover)
                cover.startsWith("file://") || cover.startsWith("/") -> build(l.file(File(Uri.parse(cover).path ?: cover).name))
                cover.startsWith("https://") -> build(l.web(cover))
                else -> null
            }
        }

        private fun build(segments: List<String>): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).apply { segments.forEach { appendPath(it) } }.build()

        /** Redirects are followed, but never from https down to plain http. */
        private val client by lazy { Http.client.newBuilder().followSslRedirects(false).build() }
    }

    override fun onCreate(): Boolean {
        links(context)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        if (seg.size != 3) throw FileNotFoundException()
        val l = links(ctx)
        val file = when (seg[0]) {
            "f" -> File(File(ctx.filesDir, "covers"), l.openFile(seg[1], seg[2]) ?: throw FileNotFoundException())
            "w" -> webCover(ctx, l.openWeb(seg[1], seg[2]) ?: throw FileNotFoundException())
            else -> throw FileNotFoundException()
        }
        if (!file.isFile) throw FileNotFoundException(file.name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun webCover(ctx: Context, url: String): File {
        val dir = File(ctx.cacheDir, "webcovers").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, "$hash.jpg")
        if (f.isFile) return f
        if (!downloads.tryAcquire(30, java.util.concurrent.TimeUnit.SECONDS)) throw FileNotFoundException()
        try {
            if (f.isFile) return f // fetched by another caller while this one waited
            trim(dir)
            download(url, dir, f)
        } finally {
            downloads.release()
        }
        return f
    }

    private fun download(url: String, dir: File, f: File) {
        // A name of its own for each download: two apps asking at once never write the same file.
        val tmp = File.createTempFile("cover", ".part", dir) // (Java wants a prefix of 3 characters or more)
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", Http.APP_UA).build()).execute().use { r ->
                if (!r.isSuccessful || r.body.contentLength() > MAX_BYTES) throw FileNotFoundException()
                r.body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) throw FileNotFoundException()
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            val head = ByteArray(12).also { b -> tmp.inputStream().use { it.read(b) } }
            if (!CoverLinks.isPicture(head)) throw FileNotFoundException()
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.path, bounds)
            if (!CoverLinks.sizeOk(bounds.outWidth, bounds.outHeight, MAX_SIDE)) throw FileNotFoundException()
            if (!tmp.renameTo(f) && !f.isFile) throw FileNotFoundException()
        } catch (e: java.io.IOException) {
            throw FileNotFoundException().apply { initCause(e) }
        } finally {
            tmp.delete()
        }
    }

    /** Room for [PARALLEL] downloads of [MAX_BYTES]: the cache never goes over [CACHE_MAX], even with all of them running. */
    private fun trim(dir: File) = runCatching {
        val now = System.currentTimeMillis()
        val files = dir.listFiles().orEmpty()
        // Left by a download that was killed half-way.
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > 600_000 }.forEach { it.delete() }
        val covers = files.filter { it.name.endsWith(".jpg") }
        val gone = CoverLinks.toTrimBeforeDownload(covers.map { Triple(it.name, it.length(), it.lastModified()) }, CACHE_MAX, CACHE_KEEP, PARALLEL * MAX_BYTES).toSet()
        covers.filter { it.name in gone }.forEach { it.delete() }
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
