package app.chudmusic.net

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** A WebDAV folder (Nextcloud, ownCloud, Synology, Apache…), basic authentication over HTTPS. */
data class DavTarget(val baseUrl: String, val user: String? = null, val password: String? = null) {
    val authHeader: String? get() = user?.takeIf { it.isNotBlank() }?.let { Credentials.basic(it, password ?: "") }
    fun url(path: String): String = baseUrl.trimEnd('/') + "/" + path.trim('/').split('/').joinToString("/") { enc(it) }
    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

class WebDavFs(private val target: DavTarget) : RemoteFs {
    private val client = Http.client
    private val basePath = java.net.URI(target.baseUrl.trimEnd('/') + "/").rawPath

    private fun req(url: String) = Request.Builder().url(url).header("User-Agent", Http.APP_UA).apply {
        target.authHeader?.let { header("Authorization", it) }
    }

    override fun list(dir: String): List<RemoteEntry> {
        val url = target.url(dir).trimEnd('/') + "/"
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        val r = try {
            client.newCall(req(url).method("PROPFIND", body.toRequestBody("application/xml".toMediaType())).header("Depth", "1").build()).execute()
        } catch (e: IOException) {
            throw RemoteUnreachable(e.message ?: "WebDAV", e)
        }
        r.use {
            if (r.code == 401 || r.code == 403) throw RemoteAuthFailed("HTTP ${r.code}")
            if (r.code == 404) throw FileNotFoundException(dir)
            if (r.code !in 200..299) throw RemoteUnreachable("HTTP ${r.code}")
            return parse(r.body.string()).filter { it.path.trim('/') != dir.trim('/') }
        }
    }

    private val dateFormat = ThreadLocal.withInitial { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US) }

    private fun parse(xml: String): List<RemoteEntry> {
        val p = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        p.setInput(xml.reader())
        val out = mutableListOf<RemoteEntry>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var mtime = 0L
        var tag = ""
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = p.name
                    when (p.name) {
                        "response" -> { href = null; isDir = false; size = 0; mtime = 0 }
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.TEXT -> when (tag) {
                    "href" -> href = (href ?: "") + p.text.trim()
                    "getcontentlength" -> size = p.text.trim().toLongOrNull() ?: 0
                    "getlastmodified" -> mtime = runCatching { dateFormat.get()!!.parse(p.text.trim())!!.time }.getOrDefault(0)
                }
                XmlPullParser.END_TAG -> {
                    if (p.name == "response" && href != null) {
                        val h: String = href
                        val raw = h.substringAfter("://").let { if (h.contains("://")) "/" + it.substringAfter('/') else h }
                        val rel = URLDecoder.decode(raw.removePrefix(basePath).removePrefix(basePath.trimEnd('/')), "UTF-8").trim('/')
                        if (rel.isNotEmpty()) out += RemoteEntry(rel.substringAfterLast('/'), rel, isDir, size, mtime)
                    }
                    tag = ""
                }
            }
        }
        return out
    }

    override fun open(path: String): RemoteFile = DavRemoteFile(path)

    /** Streaming playback: URL and header for Media3's OkHttp data source. */
    fun streamUrl(path: String) = target.url(path)
    val authHeader get() = target.authHeader

    private inner class DavRemoteFile(private val path: String) : RemoteFile {
        override val length: Long by lazy {
            client.newCall(req(target.url(path)).head().build()).execute().use { r ->
                if (r.code == 404) throw FileNotFoundException(path)
                if (r.isSuccessful) return@lazy r.header("Content-Length")?.toLongOrNull() ?: -1
                if (r.code != 405 && r.code != 501) throw RemoteUnreachable("HTTP ${r.code}")
            }
            // Some servers refuse HEAD: the total size is in the answer to a one-byte range.
            client.newCall(req(target.url(path)).header("Range", "bytes=0-0").build()).execute().use { r ->
                if (r.code == 404) throw FileNotFoundException(path)
                if (!r.isSuccessful) throw RemoteUnreachable("HTTP ${r.code}")
                if (r.code == 206) rangeTotal(r.header("Content-Range")) ?: -1 else r.body.contentLength()
            }
        }

        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (length in 0..pos) return -1
            val end = pos + len - 1
            client.newCall(req(target.url(path)).header("Range", "bytes=$pos-$end").build()).execute().use { r ->
                if (r.code == 416) return -1 // past the end of a file whose size was not known
                if (!r.isSuccessful) throw RemoteUnreachable("HTTP ${r.code}")
                answerProblem(r.code, r.header("Content-Range"), r.body.contentLength(), pos, length)?.let { throw RemoteUnreachable(it) }
                val stream = r.body.byteStream()
                // A server without ranges sends the whole file: go to [pos] in it.
                if (r.code == 200 && pos > 0) skipFully(stream, pos)
                var done = 0
                while (done < len) {
                    val n = stream.read(buf, off + done, len - done)
                    if (n <= 0) break
                    done += n
                }
                return if (done == 0) -1 else done
            }
        }

        override fun close() {}
    }

    override fun close() {}

    companion object {
        /** InputStream.skip may skip less than asked: goes on until [n] bytes are passed, or fails at the end. */
        fun skipFully(stream: java.io.InputStream, n: Long) {
            var left = n
            while (left > 0) {
                val skipped = stream.skip(left)
                if (skipped > 0) { left -= skipped; continue }
                // skip() may return 0 before the end: one read tells which it is.
                if (stream.read() < 0) throw java.io.EOFException("end of file $left bytes before the position")
                left--
            }
        }

        /**
         * Why the answer to a range asked from [pos] cannot be read as the file's bytes from there,
         * or null when it can. [length]: the file's size as first seen, -1 when unknown. A server
         * may ignore the range (200, the whole file) or send more than asked; wrong bytes must
         * never be played as the right ones, nor bytes of a file that changed in between.
         */
        fun answerProblem(code: Int, contentRange: String?, contentLength: Long, pos: Long, length: Long): String? = when (code) {
            200 -> if (length >= 0 && contentLength >= 0 && contentLength != length) "file size $contentLength, was $length" else null
            206 -> {
                // Without Content-Range (a multipart answer, or a broken server) nothing says what the bytes are.
                val m = contentRange?.trim()?.let { CONTENT_RANGE.matchEntire(it) }
                val first = m?.groupValues?.get(1)?.toLongOrNull()
                val last = m?.groupValues?.get(2)?.toLongOrNull()
                val total = m?.groupValues?.get(3)?.toLongOrNull()
                when {
                    first == null || last == null -> "Content-Range ${contentRange ?: "missing"}"
                    first != pos -> "Content-Range $first, not $pos"
                    last < first || (total != null && last >= total) -> "Content-Range $contentRange"
                    total != null && length >= 0 && total != length -> "file size $total, was $length"
                    contentLength >= 0 && contentLength != last - first + 1 -> "Content-Range $contentRange, $contentLength bytes"
                    else -> null
                }
            }
            else -> "HTTP $code"
        }

        private val CONTENT_RANGE = Regex("""^bytes (\d+)-(\d+)/(\d+|\*)$""")

        /** The first byte of a "Content-Range: bytes 100-199/5000" answer, or null. */
        fun rangeStart(header: String?): Long? = header?.trim()?.let { CONTENT_RANGE.matchEntire(it) }?.groupValues?.get(1)?.toLongOrNull()

        /** The file's total size in a Content-Range answer, or null when the server does not say it. */
        fun rangeTotal(header: String?): Long? = header?.trim()?.let { CONTENT_RANGE.matchEntire(it) }?.groupValues?.get(3)?.toLongOrNull()
    }
}
