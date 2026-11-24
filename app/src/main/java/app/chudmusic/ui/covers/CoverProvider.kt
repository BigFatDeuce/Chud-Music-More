package app.chudmusic.ui.covers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import app.chudmusic.BuildConfig
import app.chudmusic.net.Http
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Covers for other apps (Android Auto, the system media controls): they cannot read our files
 * nor always load web images. Read-only:
 * - content://<app>.covers/f/<name>  → filesDir/covers/<name> (covers extracted from music files)
 * - content://<app>.covers/w/<b64>   → a web image (YouTube Music), downloaded once into the cache.
 */
class CoverProvider : ContentProvider() {
    companion object {
        val AUTHORITY = "${BuildConfig.APPLICATION_ID}.covers"

        /** Inside the app, covers are file paths or https URLs; outside, they must be content:// URIs. */
        fun publicUri(cover: String?): Uri? {
            if (cover.isNullOrBlank()) return null
            return when {
                cover.startsWith("content://") || cover.startsWith("android.resource://") -> Uri.parse(cover)
                cover.startsWith("file://") || cover.startsWith("/") -> {
                    val name = File(Uri.parse(cover).path ?: cover).name
                    Uri.parse("content://$AUTHORITY/f/$name")
                }
                cover.startsWith("http") -> Uri.parse("content://$AUTHORITY/w/" + Base64.encodeToString(cover.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                else -> null
            }
        }
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException()
        val seg = uri.pathSegments
        if (seg.size != 2) throw FileNotFoundException(uri.toString())
        val file = when (seg[0]) {
            "f" -> {
                val name = seg[1]
                if (name.contains('/') || name.contains("..")) throw FileNotFoundException()
                File(File(ctx.filesDir, "covers"), name)
            }
            "w" -> {
                val url = String(Base64.decode(seg[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                if (!url.startsWith("https://")) throw FileNotFoundException()
                val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val f = File(File(ctx.cacheDir, "webcovers").apply { mkdirs() }, "$hash.jpg")
                if (!f.exists()) {
                    Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (!r.isSuccessful) throw FileNotFoundException(url)
                        val tmp = File(f.path + ".part")
                        tmp.outputStream().use { r.body.byteStream().copyTo(it) }
                        tmp.renameTo(f)
                    }
                }
                f
            }
            else -> throw FileNotFoundException()
        }
        if (!file.exists()) throw FileNotFoundException(file.name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
