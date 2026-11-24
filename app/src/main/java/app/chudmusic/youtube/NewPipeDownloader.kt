package app.chudmusic.youtube

import app.chudmusic.net.Http
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** NewPipeExtractor's network access, through the app's OkHttp client. No cookies, no account. */
object NewPipeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()
        val builder = OkRequest.Builder().url(request.url())
            .method(request.httpMethod(), body?.toRequestBody())
            .header("User-Agent", Http.BROWSER_UA)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Http.client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
        }
    }
}
