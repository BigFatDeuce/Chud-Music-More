package app.chudmusic

import app.chudmusic.data.TooLarge
import app.chudmusic.data.readAtMost
import app.chudmusic.net.DavTarget
import app.chudmusic.playback.LibraryCallback
import app.chudmusic.net.RemoteUnreachable
import app.chudmusic.net.WebDavFs
import app.chudmusic.ui.covers.CoverLinks
import app.chudmusic.youtube.MusicLink
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress

/**
 * Findings of the external audit of 2026-11-24 (MORE-01, MORE-02, MORE-03) and what the review
 * found around them. Each test failed on the code before its fix.
 */
class AuditTest {

    // ── MORE-03: links read the same way as the PC app ────────────────────────────────────────

    private fun corpus(name: String) =
        Json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText()).jsonObject["cases"]!!.jsonArray

    /** The PC corpus's shape: null, or {provider, kind, id[, videoId]}. */
    private fun shape(l: MusicLink?): Map<String, String>? = when (l) {
        null -> null
        is MusicLink.YtVideo -> mapOf("provider" to "youtube", "kind" to "video", "id" to l.id)
        is MusicLink.YtPlaylist -> mapOf("provider" to "youtube", "kind" to "playlist", "id" to l.id) + (l.videoId?.let { mapOf("videoId" to it) } ?: emptyMap())
        is MusicLink.YtAlbum -> mapOf("provider" to "youtube", "kind" to "album", "id" to l.id)
        is MusicLink.YtArtist -> mapOf("provider" to "youtube", "kind" to "artist", "id" to l.id)
        is MusicLink.Spotify -> mapOf("provider" to "spotify", "kind" to l.kind.path, "id" to l.id)
    }

    private fun checkCorpus(name: String) {
        val wrong = corpus(name).mapNotNull { c ->
            val input = c.jsonObject["input"]!!.jsonPrimitive.content
            val e = c.jsonObject["expect"]
            val expect = if (e == null || e is JsonNull) null else (e as JsonObject).mapValues { it.value.jsonPrimitive.content }
            val got = runCatching { shape(MusicLink.parse(input)) }.getOrElse { mapOf("error" to it.toString()) }
            if (got == expect) null else "$input\n    expected $expect\n    got      $got"
        }
        assertTrue("${wrong.size} case(s) differ from the PC app:\n" + wrong.joinToString("\n"), wrong.isEmpty())
    }

    /** The corpus shared with the PC app (a copy of Chud-Music/tests/unit/links-corpus.json, version 2). */
    @Test fun sharedLinkCorpus() {
        assertEquals(2, Json.parseToJsonElement(javaClass.classLoader!!.getResource("links-corpus.json")!!.readText()).jsonObject["version"]!!.jsonPrimitive.content.toInt())
        checkCorpus("links-corpus.json")
    }

    /**
     * MORE-07: an M3U line was searched for a YouTube pattern anywhere in it: a line on another
     * site gave a video, the first of two contradictory v was taken, `?feature=share&v=` was
     * missed. Same 8 cases as the PC app (tests/unit/links.mjs).
     */
    @Test fun m3uLinesReadLikeThePc() {
        val cases = listOf(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ" to "dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ" to "dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI" to "dQw4w9WgXcQ",
            "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ" to "dQw4w9WgXcQ",
            "https://example.com/go?to=music.youtube.com/watch?v=dQw4w9WgXcQ" to null,
            "https://www.youtube.com/watch?v=AAAAAAAAAAA&v=BBBBBBBBBBB" to null,
            "Music/Album/01 - Track.mp3" to null,
            "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI" to null,
        )
        for ((line, want) in cases) assertEquals(line, want, MusicLink.m3uVideoId(line))
    }

    @Test fun repeatedIdentityParameterIsNotGuessed() {
        assertNull(MusicLink.parse("https://www.youtube.com/watch?v=AAAAAAAAAAA&v=BBBBBBBBBBB"))
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&v=dQw4w9WgXcQ"))
        // In a longer text, a contradictory link is not taken for a link either.
        assertNull(MusicLink.find("listen https://www.youtube.com/watch?v=AAAAAAAAAAA&v=BBBBBBBBBBB now"))
    }

    /**
     * MORE-06: a link shared from another app was read by a regex of its own: the first `v=` of
     * any text, on any site. Shared text now goes through the same rules as a pasted link.
     */
    @Test fun sharedTextFollowsTheSameRules() {
        assertNull(MusicLink.find("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(MusicLink.find("see https://evil.example/embed/dQw4w9WgXcQ"))
        assertNull(MusicLink.find("https://www.youtube.com/watch?v=AAAAAAAAAAA&v=BBBBBBBBBBB"))
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.find("Écoute ça : https://youtu.be/dQw4w9WgXcQ?si=x !"))
        assertEquals(MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.find("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))
    }

    // ── MORE-02: a WebDAV file read at the right place, whatever the server does with Range ──

    private val content = ByteArray(5000) { (it * 7 + 3).toByte() }
    private var server: HttpServer? = null

    /** A file served by a local server that behaves like [mode]. */
    private fun serve(mode: String): WebDavFs {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/dav/a.flac") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val m = range?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
            val head = ex.requestMethod == "HEAD"
            fun send(code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
                headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
                if (head) {
                    ex.responseHeaders.add("Content-Length", body.size.toString())
                    ex.sendResponseHeaders(code, -1)
                } else {
                    ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
                    ex.responseBody.write(body)
                }
                ex.close()
            }
            when {
                head && mode == "noHead" -> send(405, ByteArray(0))
                m == null || mode == "noRange" -> send(200, content)
                else -> {
                    val start = m.groupValues[1].toInt()
                    val end = (m.groupValues[2].toIntOrNull() ?: content.lastIndex).coerceAtMost(content.lastIndex)
                    when {
                        start > content.lastIndex -> send(416, ByteArray(0), mapOf("Content-Range" to "bytes */${content.size}"))
                        mode == "wrongOffset" -> send(206, content.copyOfRange(0, end - start + 1), mapOf("Content-Range" to "bytes 0-${end - start}/${content.size}"))
                        mode == "noContentRange" -> send(206, content.copyOfRange(start, end + 1))
                        // The file was replaced by a bigger one after its size was read.
                        mode == "changedFile" -> send(206, content.copyOfRange(start, end + 1), mapOf("Content-Range" to "bytes $start-$end/6000"))
                        else -> send(206, content.copyOfRange(start, end + 1), mapOf("Content-Range" to "bytes $start-$end/${content.size}"))
                    }
                }
            }
        }
        s.start()
        server = s
        return WebDavFs(DavTarget("http://127.0.0.1:${s.address.port}/dav"))
    }

    @After fun stop() { server?.stop(0) }

    private fun readAt(fs: WebDavFs, pos: Long, len: Int): ByteArray? {
        val buf = ByteArray(len)
        val n = fs.open("a.flac").read(pos, buf, 0, len)
        return if (n < 0) null else buf.copyOf(n)
    }

    @Test fun davReadWithRanges() {
        val fs = serve("ranges")
        assertEquals(5000L, fs.open("a.flac").length)
        assertArrayEquals(content.copyOfRange(1234, 1334), readAt(fs, 1234, 100))
        assertArrayEquals(content.copyOfRange(4990, 5000), readAt(fs, 4990, 100))
        assertNull(readAt(fs, 5000, 10))
    }

    @Test fun davServerIgnoringRanges() {
        val fs = serve("noRange")
        assertArrayEquals(content.copyOfRange(1234, 1334), readAt(fs, 1234, 100))
        assertArrayEquals(content.copyOfRange(4990, 5000), readAt(fs, 4990, 100))
    }

    /** A 206 that starts somewhere else than asked must not be taken as the bytes asked for. */
    @Test fun davPartialAnswerAtTheWrongPlace() {
        val fs = serve("wrongOffset")
        try {
            val got = readAt(fs, 1234, 100)
            fail("bytes from the wrong place were returned: ${got?.take(4)}")
        } catch (e: IOException) {
            // refused: the scan reports the file as unreadable instead of reading wrong tags
        }
    }

    /**
     * A 206 without Content-Range: nothing tells what the bytes are (a multipart answer has none
     * either), so they are refused. Accepted until the second review of 25 November.
     */
    @Test fun davPartialAnswerWithoutContentRange() {
        assertRefused(serve("noContentRange"))
    }

    /** A 206 whose total size is not the size first read: the file changed, its bytes are not mixed. */
    @Test fun davPartialAnswerOfAChangedFile() {
        assertRefused(serve("changedFile"))
    }

    private fun assertRefused(fs: WebDavFs) {
        try {
            val got = readAt(fs, 1234, 100)
            fail("unchecked bytes were returned: ${got?.take(4)}")
        } catch (e: IOException) {
            // refused: reported as unreadable instead of played wrong
        }
    }

    /** Every part of a range answer is checked against the question, not only where it starts. */
    @Test fun partialAnswerIsCheckedInFull() {
        fun p(code: Int, cr: String?, cl: Long, pos: Long = 100, length: Long = 5000) = WebDavFs.answerProblem(code, cr, cl, pos, length)
        assertNull(p(206, "bytes 100-199/5000", 100))
        assertNull(p(206, "bytes 100-199/*", 100))
        assertNull(p(206, "bytes 100-199/5000", -1)) // chunked: no length to compare
        assertNull(p(206, "bytes 100-4999/5000", 4900)) // more than asked: only what was asked is read
        assertNull(p(206, "bytes 100-199/5000", 100, length = -1)) // size never known
        assertNull(p(200, null, 5000))
        assertNull(p(200, null, -1))
        assertTrue(p(206, null, 100) != null) // no Content-Range
        assertTrue(p(206, "bytes */5000", 100) != null)
        assertTrue(p(206, "bytes 0-99/5000", 100) != null) // wrong start
        assertTrue(p(206, "bytes 100-50/5000", 51) != null) // ends before it starts
        assertTrue(p(206, "bytes 100-5000/5000", 4901) != null) // ends past the file
        assertTrue(p(206, "bytes 100-199/6000", 100) != null) // the file changed
        assertTrue(p(206, "bytes 100-199/5000", 60) != null) // body shorter than announced
        assertTrue(p(206, "bytes 100-199/5000", 500) != null) // body longer than announced
        assertTrue(p(200, null, 6000) != null) // whole file, but not the one first seen
        assertTrue(p(204, null, 0) != null)
    }

    /**
     * Ports, as the PC app reads them (answers given by its own code, src/shared/links.ts, on
     * 25 November): over 65535 or not ASCII digits is not a link; Android took both.
     */
    @Test fun portsLikeThePc() {
        val v = "/watch?v=dQw4w9WgXcQ"
        for (port in listOf("65535", "00000000443", "0", ""))
            assertEquals(port, MusicLink.YtVideo("dQw4w9WgXcQ"), MusicLink.parse("https://www.youtube.com:$port$v"))
        for (port in listOf("65536", "99999", "٣", "１２", "4a", "-1", "+1"))
            assertNull(port, MusicLink.parse("https://www.youtube.com:$port$v"))
        assertNull(MusicLink.parse("youtu.be:70000/dQw4w9WgXcQ"))
        assertNull(MusicLink.parse("https://open.spotify.com:65536/track/4uLU6hMCjMI75M1A2tKUQC"))
    }

    /** Some servers refuse HEAD: the size comes from a one-byte range instead of failing the file. */
    @Test fun davServerWithoutHead() {
        val fs = serve("noHead")
        assertEquals(5000L, fs.open("a.flac").length)
        assertArrayEquals(content.copyOfRange(10, 20), readAt(fs, 10, 10))
    }

    @Test fun davZeroLengthReadAndPastTheEnd() {
        val fs = serve("ranges")
        assertEquals(0, fs.open("a.flac").read(10, ByteArray(4), 0, 0))
        assertNull(readAt(fs, 6000, 10))
    }

    @Test fun skipFullyGoesOnWhenSkipStopsShort() {
        // An InputStream may skip fewer bytes than asked; the bytes after must still be the right ones.
        val slow = object : InputStream() {
            val inner = ByteArrayInputStream(content)
            override fun read() = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
            override fun skip(n: Long) = inner.skip(minOf(n, 7))
        }
        WebDavFs.skipFully(slow, 1234)
        val b = ByteArray(10); slow.read(b)
        assertArrayEquals(content.copyOfRange(1234, 1244), b)
        try {
            WebDavFs.skipFully(ByteArrayInputStream(ByteArray(10)), 20)
            fail("skipping past the end must fail")
        } catch (e: IOException) {}
    }

    @Test fun contentRangeStart() {
        assertEquals(1234L, WebDavFs.rangeStart("bytes 1234-1333/5000"))
        assertEquals(0L, WebDavFs.rangeStart("bytes 0-0/*"))
        assertNull(WebDavFs.rangeStart("bytes */5000"))
        assertNull(WebDavFs.rangeStart(null))
        assertNull(WebDavFs.rangeStart("items 1-2/3"))
        assertEquals(5000L, WebDavFs.rangeTotal("bytes 0-0/5000"))
        assertNull(WebDavFs.rangeTotal("bytes 0-0/*"))
    }

    // ── MORE-01: covers given to other apps ───────────────────────────────────────────────────

    @Test fun coverLinksAreSignedByTheApp() {
        val links = CoverLinks(ByteArray(32) { it.toByte() })
        val w = links.web("https://i.ytimg.com/vi/x/hq.jpg")
        assertEquals(3, w.size)
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", links.openWeb(w[1], w[2]))
        // Another app cannot make up a link to a server of its choice, nor reuse a signature.
        val forged = links.web("https://192.168.1.1/admin")
        assertNull(links.openWeb(forged[1], w[2]))
        assertNull(links.openWeb(w[1], "AAAA"))
        assertNull(CoverLinks(ByteArray(32)).openWeb(w[1], w[2]))
        // Files: only names the app gave out, never a path.
        val f = links.file("a1b2c3.jpg")
        assertEquals("a1b2c3.jpg", links.openFile(f[1], f[2]))
        assertNull(links.openFile("other.jpg", f[2]))
        assertNull(links.openFile("../x", links.file("../x")[2]))
        // Only https, never another scheme, even signed.
        assertNull(links.openWeb(links.web("http://10.0.0.1/a.jpg")[1], links.web("http://10.0.0.1/a.jpg")[2]))
    }

    @Test fun onlyPicturesAreKept() {
        assertTrue(CoverLinks.isPicture(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(CoverLinks.isPicture(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertTrue(CoverLinks.isPicture("RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray()))
        assertTrue(CoverLinks.isPicture("GIF89a".toByteArray()))
        assertFalse(CoverLinks.isPicture("<html><body>".toByteArray()))
        assertFalse(CoverLinks.isPicture(ByteArray(2)))
    }

    @Test fun coverCacheStaysSmall() {
        // name, size, last modified
        val files = (1..10).map { Triple("f$it", 10L, it.toLong()) }
        assertEquals(emptyList<String>(), CoverLinks.toTrim(files, max = 100, keep = 80))
        val more = files + Triple("big", 50L, 11L)
        // 150 is over 100: the oldest go until 80 or less is left; the newest is kept.
        assertEquals((1..7).map { "f$it" }, CoverLinks.toTrim(more, max = 100, keep = 80))
    }

    /** A cover claiming a huge size is not handed to other apps, nor one without a readable size. */
    @Test fun coverSizeIsChecked() {
        assertTrue(CoverLinks.sizeOk(1280, 720, 4096))
        assertTrue(CoverLinks.sizeOk(4096, 1, 4096))
        assertFalse(CoverLinks.sizeOk(4097, 100, 4096))
        assertFalse(CoverLinks.sizeOk(100, 65535, 4096))
        assertFalse(CoverLinks.sizeOk(-1, -1, 4096)) // header not readable
        assertFalse(CoverLinks.sizeOk(0, 500, 4096))
    }

    /** Two downloads running at once must not take the cache over its maximum, even for a moment. */
    @Test fun coverCacheLeavesRoomForDownloadsUnderWay() {
        val max = 60L; val keep = 40L; val perDownload = 8L; val parallel = 2
        val files = (1..7).map { Triple("f$it", 7L, it.toLong()) } // 49: under max, but 49 + 16 is not
        val gone = CoverLinks.toTrimBeforeDownload(files, max, keep, parallel * perDownload).toSet()
        val left = files.filter { it.first !in gone }.sumOf { it.second }
        assertTrue("left $left", left + parallel * perDownload <= max)
        assertEquals(listOf("f1", "f2"), gone.sorted()) // the oldest go first
        // Already small enough: nothing goes.
        assertEquals(emptyList<String>(), CoverLinks.toTrimBeforeDownload(files.take(5), max, keep, parallel * perDownload))
    }

    // ── Found by this review ──────────────────────────────────────────────────────────────────

    /**
     * MORE-04: the playback service is exported (Android Auto, the system). A Track carried in a
     * controller's item holds its address: believed only from the app itself.
     */
    @Test fun onlyTheAppItselfIsBelievedForTrackAddresses() {
        assertTrue(LibraryCallback.trustsTrackExtras("app.chudmusic", 10123, "app.chudmusic", 10123))
        assertFalse(LibraryCallback.trustsTrackExtras("com.other.app", 10200, "app.chudmusic", 10123))
        // Same name but another user id: another app (or a renamed one on a rooted phone).
        assertFalse(LibraryCallback.trustsTrackExtras("app.chudmusic", 10200, "app.chudmusic", 10123))
    }

    /** MORE-05: a file sent by another app is refused once over the limit, not after being read whole. */
    @Test fun filesFromOtherAppsAreReadUpToALimit() {
        assertEquals(1000, ByteArrayInputStream(ByteArray(1000)).readAtMost(1000).size)
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int { read++; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        try {
            endless.readAtMost(1_000_000)
            fail("an endless stream must be refused")
        } catch (e: TooLarge) {
            assertTrue("stopped soon after the limit, read $read", read < 1_200_000)
        }
    }

    /** A WebDAV place over plain http with a saved password is flagged in Settings, not only in its form. */
    @Test fun clearPasswordIsFlagged() {
        val dav = app.chudmusic.data.NetworkSourceEntity(kind = app.chudmusic.data.NetKind.WEBDAV, name = "n", host = "h", share = "", useHttps = false, passwordEnc = "x")
        assertTrue(app.chudmusic.net.NetworkSources.passwordInClear(dav))
        assertFalse(app.chudmusic.net.NetworkSources.passwordInClear(dav.copy(useHttps = true)))
        assertFalse(app.chudmusic.net.NetworkSources.passwordInClear(dav.copy(passwordEnc = null)))
        assertFalse(app.chudmusic.net.NetworkSources.passwordInClear(dav.copy(kind = app.chudmusic.data.NetKind.SMB)))
    }
}
