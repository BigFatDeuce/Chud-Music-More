package app.chudmusic.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/**
 * Covers extracted from music files (embedded picture, folder image, MediaStore thumbnail),
 * resized to 512 px and kept in the app's own folder. Nothing is ever written next to the music.
 */
class CoverStore(context: Context) {
    val dir = File(context.filesDir, "covers").apply { mkdirs() }

    fun keyFor(vararg parts: String?): String =
        MessageDigest.getInstance("SHA-1").digest(parts.joinToString("\u0000") { it ?: "" }.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)

    fun existing(key: String): String? = File(dir, "$key.jpg").takeIf { it.exists() }?.let { "file://${it.absolutePath}" }

    /**
     * Albums whose files were searched and hold no picture: the next scan does not search them
     * again (it would redo every cover-less track each time, which looks like a scan starting over).
     */
    private val noneDir = File(context.filesDir, "covers-none").apply { mkdirs() }

    fun knownWithout(key: String): Boolean = File(noneDir, key).exists()

    fun markWithout(key: String, without: Boolean) {
        val f = File(noneDir, key)
        runCatching { if (without) f.createNewFile() else f.delete() }
    }

    fun saveBytes(key: String, bytes: ByteArray): String? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 512 && opts.outHeight / (sample * 2) >= 512) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return saveBitmap(key, bmp)
    }

    fun saveBitmap(key: String, bmp: Bitmap): String? {
        val scaled = if (bmp.width > 512 || bmp.height > 512) {
            val s = 512f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else bmp
        val f = File(dir, "$key.jpg")
        val tmp = File(dir, "$key.tmp")
        return runCatching {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(f)
            "file://${f.absolutePath}"
        }.getOrNull()
    }

    /**
     * The picture the user chose for a playlist, copied here (their photo can be moved or deleted
     * afterwards). A new name each time: an image already shown under the old one is not reused.
     */
    fun saveChosen(context: Context, uri: android.net.Uri): String? = runCatching {
        val bmp = if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Turns a photo the way it was taken (EXIF) and never loads it in full size.
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(info.size.width, info.size.height)
                if (s > 1024) d.setTargetSampleSize(s / 512)
            }
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return saveBytes(chosenKey(), bytes)
        }
        saveBitmap(chosenKey(), square(bmp))
    }.getOrNull()

    private fun chosenKey() = CHOSEN + java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    /** The middle of the picture: covers are square. */
    private fun square(b: Bitmap): Bitmap {
        val s = minOf(b.width, b.height)
        return if (b.width == b.height) b else Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
    }

    /** A picture chosen by the user, as the exchange file carries it. */
    fun toDataUri(cover: String?): String? = cover?.let { fileOf(it) }?.let {
        runCatching { "data:image/jpeg;base64," + android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }.getOrNull()
    }

    fun fromDataUri(text: String): String? {
        if (!text.startsWith("data:image/") || text.length > 4_000_000) return null
        val bytes = runCatching { android.util.Base64.decode(text.substringAfter(";base64,", ""), android.util.Base64.DEFAULT) }.getOrNull() ?: return null
        return saveBytes(chosenKey(), bytes)
    }

    /** Deletes a picture chosen by the user, once nothing shows it; never another cover. */
    fun dropChosen(cover: String?) { cover?.let { fileOf(it) }?.delete() }

    private fun fileOf(cover: String): File? =
        File(dir, cover.substringAfterLast('/')).takeIf { it.name.startsWith(CHOSEN) && it.exists() }

    /**
     * The cover found online for an album ([OnlineCovers]), named after its [Library.albumKey]: the
     * scanners give it back to the album's tracks when their files hold none.
     */
    fun webKey(albumKey: String) = keyFor("web", albumKey)
    fun webCover(t: app.chudmusic.model.Track): String? = Library.albumKey(t)?.let { existing(webKey(it)) }

    /** Takes a wrong online cover away; that album is not searched again until asked for by itself. */
    fun dropWeb(albumKey: String) {
        val key = webKey(albumKey)
        runCatching { File(dir, "$key.jpg").delete() }
        markWithout(key, true)
    }

    private companion object { const val CHOSEN = "pl-" }
}
