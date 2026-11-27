package app.chudmusic.ui.covers

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The paths of the cover links given to other apps (Android Auto, the system's media controls),
 * signed with a key that only this app knows. CoverProvider must be exported for those apps to
 * read it, so any app installed on the phone can call it: without the signature, one of them could
 * make Chud Music download from any server it chose (a box on the home network, say) or read every
 * cover by name. With it, only the links Chud Music made itself open anything.
 *
 * Path segments: `f/<file name>/<sig>` for a cover in filesDir/covers, `w/<base64 url>/<sig>`
 * for a web image.
 */
class CoverLinks(private val key: ByteArray) {

    fun file(name: String): List<String> = listOf("f", name, sign("f", name))

    fun web(url: String): List<String> {
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray(Charsets.UTF_8))
        return listOf("w", b64, sign("w", b64))
    }

    /** The file name of a link this app made, or null. */
    fun openFile(name: String, sig: String): String? =
        name.takeIf { verify("f", it, sig) && !it.contains('/') && !it.contains('\\') && !it.contains("..") }

    /** The https address of a link this app made, or null. */
    fun openWeb(b64: String, sig: String): String? {
        if (!verify("w", b64, sig)) return null
        val url = runCatching { String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8) }.getOrNull() ?: return null
        return url.takeIf { it.startsWith("https://") }
    }

    private fun sign(kind: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val d = mac.doFinal("$kind/$value".toByteArray(Charsets.UTF_8))
        // 128 bits are plenty for a link nobody can try more than a few times a second.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d.copyOf(16))
    }

    private fun verify(kind: String, value: String, sig: String) =
        MessageDigest.isEqual(sign(kind, value).toByteArray(), sig.toByteArray())

    companion object {
        /** The first bytes of a JPEG, PNG, GIF or WebP: anything else is not kept as a cover. */
        fun isPicture(head: ByteArray): Boolean {
            fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
            fun ascii(i: Int, s: String) = s.indices.all { at(i + it) == s[it].code }
            return (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) ||
                (at(0) == 0x89 && ascii(1, "PNG")) ||
                ascii(0, "GIF8") ||
                (ascii(0, "RIFF") && ascii(8, "WEBP"))
        }

        /**
         * A picture whose header gives a size other apps can show: the system and Android Auto may
         * decode a cover in full, and a small file can claim a huge size (4 bytes per pixel).
         * Width and height of 0 or less: no readable header.
         */
        fun sizeOk(width: Int, height: Int, maxSide: Int): Boolean = width in 1..maxSide && height in 1..maxSide

        /**
         * The web covers to delete before a download starts: up to [inFlight] bytes may still be
         * coming from downloads under way, and the cache with them must stay under [max].
         */
        fun toTrimBeforeDownload(files: List<Triple<String, Long, Long>>, max: Long, keep: Long, inFlight: Long): List<String> =
            toTrim(files, max - inFlight, minOf(keep, max - inFlight))

        /**
         * The web covers to delete so that the cache stays small: when it is over [max] bytes, the
         * oldest go until [keep] bytes or less are left. [files]: name, size, last modified.
         */
        fun toTrim(files: List<Triple<String, Long, Long>>, max: Long, keep: Long): List<String> {
            var total = files.sumOf { it.second }
            if (total <= max) return emptyList()
            val out = ArrayList<String>()
            for (f in files.sortedBy { it.third }) {
                if (total <= keep) break
                out += f.first
                total -= f.second
            }
            return out
        }
    }
}
