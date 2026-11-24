package app.chudmusic.youtube

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * The audio of a YouTube video, through NewPipeExtractor (GPL-3.0): the same extraction as
 * NewPipe and Spotube. Streams are only read while playing, never saved. Their URLs expire after
 * a few hours, so they are resolved at play time and cached briefly.
 */
object YouTubeStreams {
    data class Resolved(val url: String, val mime: String, val bitrate: Int, val durationMs: Long?, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Resolved>()
    @Volatile private var ready = false

    fun init(lang: String = "fr", country: String = "FR") {
        if (ready) return
        synchronized(this) {
            if (ready) return
            NewPipe.init(NewPipeDownloader, Localization(lang, country), ContentCountry(country))
            ready = true
        }
    }

    /** Blocking (network): call it from a loader or IO thread. [preferOpus]: smaller and better than AAC at the same bitrate. */
    fun resolve(videoId: String, preferOpus: Boolean = true): Resolved {
        init()
        cache[videoId]?.let { if (it.expiresAt > System.currentTimeMillis() + 60_000) return it }
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: Exception) {
            throw YouTubeException(e.message ?: e.javaClass.simpleName, e)
        }
        val audio = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        if (audio.isEmpty()) throw YouTubeException("no-audio")
        // Original audio track first (some videos have dubbed tracks), then the best bitrate.
        val original = audio.filter { it.audioTrackType == null || it.audioTrackType?.name == "ORIGINAL" }.ifEmpty { audio }
        val pick = original.sortedWith(
            compareByDescending<AudioStream> { if (preferOpus) it.format == MediaFormat.WEBMA_OPUS else it.format == MediaFormat.M4A }
                .thenByDescending { it.averageBitrate },
        ).first()
        val expire = Regex("""[?&]expire=(\d+)""").find(pick.content)?.groupValues?.get(1)?.toLongOrNull()?.times(1000)
            ?: (System.currentTimeMillis() + 3 * 3600_000)
        val r = Resolved(
            url = pick.content,
            mime = pick.format?.mimeType ?: "audio/webm",
            bitrate = pick.averageBitrate,
            durationMs = info.duration.takeIf { it > 0 }?.times(1000),
            expiresAt = expire,
        )
        cache[videoId] = r
        return r
    }

    fun forget(videoId: String) { cache.remove(videoId) }
}
