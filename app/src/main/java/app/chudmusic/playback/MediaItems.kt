package app.chudmusic.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.ui.covers.CoverProvider
import kotlinx.serialization.json.Json

/** Track ⇄ MediaItem. The whole Track travels in the extras: a controller never sees the URI. */
object MediaItems {
    const val EXTRA_TRACK = "chud.track"
    const val EXTRA_QUEUED = "chud.queued"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun toItem(t: Track, queued: Boolean = false, mediaId: String = t.id): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_TRACK, json.encodeToString(Track.serializer(), t))
            if (queued) putBoolean(EXTRA_QUEUED, true)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setArtist(t.artist)
            .setAlbumTitle(t.album)
            .setAlbumArtist(t.albumArtist)
            .setTrackNumber(t.trackNo)
            .setDiscNumber(t.discNo)
            .setReleaseYear(t.year)
            .setGenre(t.genre)
            .setDurationMs(t.durationMs)
            .setArtworkUri(CoverProvider.publicUri(t.coverUri))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(if (t.source == Source.RADIO) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.parse(t.uri))
            .apply {
                when {
                    t.source == Source.YOUTUBE -> setMimeType(null)
                    t.mime == "audio/x-mpegurl" || t.uri.contains(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .setMediaMetadata(meta)
            .build()
    }

    /** The track of [item] as the JSON it carries: no need to read it to write it again. */
    fun trackJson(item: MediaItem): String? = item.mediaMetadata.extras?.getString(EXTRA_TRACK)

    fun track(item: MediaItem?): Track? =
        item?.mediaMetadata?.extras?.getString(EXTRA_TRACK)?.let { runCatching { json.decodeFromString(Track.serializer(), it) }.getOrNull() }

    fun isQueued(item: MediaItem?) = item?.mediaMetadata?.extras?.getBoolean(EXTRA_QUEUED) == true

    /** A browsable folder for Android Auto. */
    fun folder(id: String, title: String, subtitle: String? = null, art: String? = null, playable: Boolean = false): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(CoverProvider.publicUri(art))
                .setIsBrowsable(true).setIsPlayable(playable).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()
}
