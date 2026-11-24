package app.chudmusic.library

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import app.chudmusic.net.RemoteFile

/** Tags of a music file, read where it is (phone or network): the file is never copied nor modified. */
data class Tags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val genre: String?,
    val mime: String?,
    val picture: ByteArray?,
)

object TagReader {
    val AUDIO_EXT = setOf(
        "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "aifc", "ape", "wv",
        "mka", "ac3", "eac3", "dts", "amr", "awb", "3gp", "webm", "caf", "tta", "mp2", "mpc", "alac",
    )
    val IMAGE_NAMES = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")

    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    fun read(file: RemoteFile, withPicture: Boolean): Tags? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    if (size == 0) 0 else file.read(position, buffer, offset, size)
                override fun getSize(): Long = file.length
                override fun close() {}
            })
            fromRetriever(mmr, withPicture)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    fun fromRetriever(mmr: MediaMetadataRetriever, withPicture: Boolean): Tags {
        fun k(key: Int) = mmr.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        return Tags(
            title = k(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            durationMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            trackNo = k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
            discNo = k(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
            year = k(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
            genre = k(MediaMetadataRetriever.METADATA_KEY_GENRE),
            mime = k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
            picture = if (withPicture) runCatching { mmr.embeddedPicture }.getOrNull() else null,
        )
    }

    /**
     * Title and artist from a file name when there are no tags: "Artiste - Titre.mp3",
     * "01 - Titre.flac" (a track number is not an artist: bug found and fixed on the PC side).
     */
    fun guessFromName(fileName: String): Pair<String, String?> {
        val base = fileName.substringBeforeLast('.').replace('_', ' ').trim()
        val noNum = base.replace(Regex("""^\d{1,3}\s*[-.)]\s*"""), "")
        val parts = noNum.split(Regex("""\s+-\s+"""), limit = 2)
        return if (parts.size == 2 && !parts[0].matches(Regex("""\d+"""))) parts[1].trim() to parts[0].trim() else noNum to null
    }

    /** "CD1", "Disc 2", "Disque 3"… count as their parent folder: a multi-disc album stays one album. */
    fun albumFolder(folder: String?): String? {
        if (folder == null) return null
        val f = folder.trimEnd('/', '\\')
        val last = f.substringAfterLast('/').substringAfterLast('\\')
        return if (Regex("""^(cd|dis[ck]|disque|disco|disk)\s*\d+$""", RegexOption.IGNORE_CASE).matches(last.trim())) f.dropLast(last.length).trimEnd('/', '\\') else f
    }
}
