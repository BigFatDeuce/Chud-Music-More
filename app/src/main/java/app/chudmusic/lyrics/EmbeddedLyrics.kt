package app.chudmusic.lyrics

import android.content.Context
import android.net.Uri
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.net.CachedRemoteFile
import app.chudmusic.net.NetworkSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Lyrics written in the tags of a music file: ID3v2 USLT (MP3), Vorbis comments (FLAC, Ogg,
 * Opus), the ©lyr atom (M4A). Read where the file is, never copied nor modified. Text with LRC
 * stamps counts as synced lyrics.
 */
class EmbeddedLyrics(private val context: Context, private val sources: NetworkSources) {
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    /** The lyrics in [t]'s file, or null (no lyrics, not a file, file unreadable). */
    suspend fun of(t: Track): Lyrics.Result? = withContext(Dispatchers.IO) {
        if (t.source != Source.LOCAL && t.source != Source.NETWORK) return@withContext null
        val text = synchronized(cache) { cache[t.id] } ?: (runCatching { read(t) }.getOrNull() ?: "").also { synchronized(cache) { cache[t.id] = it } }
        toResult(text)
    }

    private fun read(t: Track): String? = when (t.source) {
        Source.LOCAL -> context.contentResolver.openFileDescriptor(Uri.parse(t.uri), "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                Parser.find(object : Parser.Bytes {
                    override val length = ch.size()
                    override fun read(pos: Long, len: Int): ByteArray {
                        val b = ByteBuffer.allocate(len.coerceAtMost((length - pos).coerceAtLeast(0).toInt()))
                        while (b.hasRemaining()) { if (ch.read(b, pos + b.position()) <= 0) break }
                        return b.array().copyOf(b.position())
                    }
                })
            }
        }
        Source.NETWORK -> {
            val (id, path) = NetworkSources.parse(Uri.parse(t.uri)) ?: return null
            CachedRemoteFile(sources.fsBlocking(id).second.open(path)).use { f ->
                Parser.find(object : Parser.Bytes {
                    override val length = f.length
                    override fun read(pos: Long, len: Int): ByteArray {
                        val n = len.coerceAtMost((length - pos).coerceAtLeast(0).toInt())
                        val b = ByteArray(n)
                        var got = 0
                        while (got < n) { val r = f.read(pos + got, b, got, n - got); if (r <= 0) break; got += r }
                        return b.copyOf(got)
                    }
                })
            }
        }
        else -> null
    }

    companion object {
        fun toResult(text: String?): Lyrics.Result? {
            val s = text?.replace("\r\n", "\n")?.replace('\r', '\n')?.trim()
            if (s.isNullOrEmpty()) return null
            val lines = Lyrics.parseLrc(s)
            return if (lines.size >= 3) Lyrics.Result.Synced(lines) else Lyrics.Result.Plain(s)
        }
    }

    /** The tag parsers, on any random-access bytes (pure, unit-tested). */
    object Parser {
        interface Bytes {
            val length: Long
            fun read(pos: Long, len: Int): ByteArray
        }

        private val VORBIS_KEYS = listOf("SYNCEDLYRICS", "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")

        fun find(b: Bytes): String? {
            val head = b.read(0, 12)
            if (head.size < 8) return null
            return when {
                head.startsWith("ID3") -> id3(b)
                head.startsWith("fLaC") -> flac(b, 4)
                head.startsWith("OggS") -> ogg(b)
                head.copyOfRange(4, 8).toString(Charsets.ISO_8859_1) == "ftyp" -> mp4(b)
                else -> null
            }
        }

        private fun ByteArray.startsWith(s: String) = size >= s.length && s.indices.all { this[it] == s[it].code.toByte() }
        private fun u8(a: ByteArray, i: Int) = a[i].toInt() and 0xFF
        private fun be32(a: ByteArray, i: Int) = (u8(a, i).toLong() shl 24) or (u8(a, i + 1).toLong() shl 16) or (u8(a, i + 2).toLong() shl 8) or u8(a, i + 3).toLong()
        private fun le32(a: ByteArray, i: Int) = (u8(a, i + 3).toLong() shl 24) or (u8(a, i + 2).toLong() shl 16) or (u8(a, i + 1).toLong() shl 8) or u8(a, i).toLong()
        private fun syncsafe(a: ByteArray, i: Int) = (u8(a, i) shl 21) or (u8(a, i + 1) shl 14) or (u8(a, i + 2) shl 7) or u8(a, i + 3)

        // ── ID3v2.3 / 2.4: USLT, or TXXX "LYRICS" ────────────────────────────────────────────
        private fun id3(b: Bytes): String? {
            val h = b.read(0, 10)
            val major = u8(h, 3)
            if (major !in 3..4) return null
            val end = 10L + syncsafe(h, 6)
            var pos = 10L
            if (u8(h, 5) and 0x40 != 0) { // extended header
                val e = b.read(pos, 4); if (e.size < 4) return null
                pos += if (major == 4) syncsafe(e, 0) else be32(e, 0).toInt() + 4
            }
            var txxx: String? = null
            while (pos + 10 <= end) {
                val f = b.read(pos, 10); if (f.size < 10 || f[0].toInt() == 0) break
                val id = f.copyOfRange(0, 4).toString(Charsets.ISO_8859_1)
                val size = if (major == 4) syncsafe(f, 4) else be32(f, 4).toInt()
                if (size <= 0 || pos + 10 + size > end) break
                if ((id == "USLT" || id == "TXXX") && size < 2_000_000) {
                    val body = b.read(pos + 10, size)
                    if (id == "USLT" && body.size > 4) {
                        val text = afterTerminated(body, 4, body[0].toInt())
                        if (!text.isNullOrBlank()) return text
                    }
                    if (id == "TXXX" && body.size > 1) {
                        val enc = body[0].toInt()
                        val desc = terminatedAt(body, 1, enc)
                        if (desc != null && decode(body.copyOfRange(1, desc.first), enc).trim().uppercase().let { it in VORBIS_KEYS || it == "USLT" }) txxx = decode(body.copyOfRange(desc.second, body.size), enc)
                    }
                }
                pos += 10 + size
            }
            return txxx
        }

        /** (end of the text, start of what follows) of a string terminated per the ID3 [enc]. */
        private fun terminatedAt(a: ByteArray, from: Int, enc: Int): Pair<Int, Int>? {
            if (enc == 1 || enc == 2) {
                var i = from
                while (i + 1 < a.size) { if (a[i].toInt() == 0 && a[i + 1].toInt() == 0) return i to i + 2; i += 2 }
            } else {
                for (i in from until a.size) if (a[i].toInt() == 0) return i to i + 1
            }
            return null
        }

        private fun afterTerminated(a: ByteArray, from: Int, enc: Int): String? {
            val t = terminatedAt(a, from, enc) ?: return null
            return decode(a.copyOfRange(t.second, a.size), enc)
        }

        private fun decode(a: ByteArray, enc: Int): String = when (enc) {
            1 -> String(a, Charsets.UTF_16)
            2 -> String(a, Charsets.UTF_16BE)
            3 -> String(a, Charsets.UTF_8)
            else -> String(a, Charsets.ISO_8859_1)
        }.trimEnd('\u0000')

        // ── FLAC: the VORBIS_COMMENT block ────────────────────────────────────────────────────
        private fun flac(b: Bytes, start: Long): String? {
            var pos = start
            while (pos + 4 <= b.length) {
                val h = b.read(pos, 4); if (h.size < 4) return null
                val last = u8(h, 0) and 0x80 != 0
                val type = u8(h, 0) and 0x7F
                val len = (u8(h, 1) shl 16) or (u8(h, 2) shl 8) or u8(h, 3)
                if (type == 4 && len < 4_000_000) return comments(b.read(pos + 4, len), 0)
                if (last) return null
                pos += 4 + len
            }
            return null
        }

        /** A Vorbis comment list (FLAC block, Ogg comment packet) from [at]: the first lyrics field. */
        fun comments(a: ByteArray, at: Int): String? = runCatching {
            var i = at
            val vendor = le32(a, i).toInt(); i += 4 + vendor
            val count = le32(a, i).toInt(); i += 4
            val found = HashMap<String, String>()
            repeat(count.coerceAtMost(10_000)) {
                if (i + 4 > a.size) return@repeat
                val n = le32(a, i).toInt(); i += 4
                if (n < 0 || i + n > a.size) return@repeat
                val field = String(a, i, n, Charsets.UTF_8); i += n
                val eq = field.indexOf('=')
                if (eq > 0) { val k = field.substring(0, eq).uppercase(); if (k in VORBIS_KEYS && k !in found) found[k] = field.substring(eq + 1) }
            }
            VORBIS_KEYS.firstNotNullOfOrNull { found[it]?.takeIf { s -> s.isNotBlank() } }
        }.getOrNull()

        // ── Ogg (Vorbis, Opus): the second packet holds the comments ─────────────────────────
        private fun ogg(b: Bytes): String? {
            val packet = java.io.ByteArrayOutputStream()
            var pos = 0L
            var index = 0
            var pages = 0
            while (pos + 27 <= b.length && pages++ < 400) {
                val h = b.read(pos, 27); if (h.size < 27 || !h.startsWith("OggS")) return null
                val segs = u8(h, 26)
                val table = b.read(pos + 27, segs)
                var body = pos + 27 + segs
                for (s in 0 until segs) {
                    val n = u8(table, s)
                    if (index == 1) packet.write(b.read(body, n))
                    body += n
                    if (n < 255) {
                        if (index == 1) {
                            val p = packet.toByteArray()
                            return when {
                                p.startsWith("\u0003vorbis") -> comments(p, 7)
                                p.startsWith("OpusTags") -> comments(p, 8)
                                else -> null
                            }
                        }
                        index++
                    }
                    if (packet.size() > 4_000_000) return null
                }
                pos = body
            }
            return null
        }

        // ── MP4 / M4A: moov › udta › meta › ilst › ©lyr › data ────────────────────────────────
        private fun mp4(b: Bytes): String? {
            val moov = atom(b, 0, b.length, "moov") ?: return null
            val udta = atom(b, moov.first, moov.second, "udta") ?: return null
            val meta = atom(b, udta.first, udta.second, "meta") ?: return null
            val ilst = atom(b, meta.first + 4, meta.second, "ilst") ?: return null // meta is a full atom
            val lyr = atom(b, ilst.first, ilst.second, "©lyr") ?: return null
            val data = atom(b, lyr.first, lyr.second, "data") ?: return null
            val len = (data.second - data.first - 8).toInt()
            if (len <= 0 || len > 2_000_000) return null
            return String(b.read(data.first + 8, len), Charsets.UTF_8)
        }

        /** (content start, content end) of the first child atom of [type] between [from] and [to]. */
        private fun atom(b: Bytes, from: Long, to: Long, type: String): Pair<Long, Long>? {
            var pos = from
            while (pos + 8 <= to) {
                val h = b.read(pos, 16); if (h.size < 8) return null
                var size = be32(h, 0)
                val name = h.copyOfRange(4, 8).toString(Charsets.ISO_8859_1)
                var header = 8L
                if (size == 1L && h.size >= 16) { size = (be32(h, 8) shl 32) or be32(h, 12); header = 16 }
                if (size == 0L) size = to - pos
                if (size < header) return null
                if (name == type) return (pos + header) to minOf(pos + size, to)
                pos += size
            }
            return null
        }
    }
}
