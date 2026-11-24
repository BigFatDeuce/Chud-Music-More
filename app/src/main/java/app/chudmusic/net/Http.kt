package app.chudmusic.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app (connection pool and cache shared). */
object Http {
    /** A desktop browser: YouTube Music and some radio servers answer differently to unknown agents. */
    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    const val APP_UA = "ChudMusic-Android"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, url)
            return r.body.string()
        }
    }

    /** A small download (a cover): refused above [max] bytes. */
    fun getBytes(url: String, max: Int = 8_000_000, headers: Map<String, String> = emptyMap()): ByteArray {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, url)
            if (r.body.contentLength() > max) throw IOException("too big")
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            r.body.byteStream().use { s ->
                while (true) {
                    val n = s.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IOException("too big")
                }
            }
            return out.toByteArray()
        }
    }

    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url).post(body.toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, url)
            return r.body.string()
        }
    }
}

class HttpException(val code: Int, url: String) : IOException("HTTP $code for ${url.substringBefore('?')}")
