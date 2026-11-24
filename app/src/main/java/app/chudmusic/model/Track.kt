package app.chudmusic.model

import kotlinx.serialization.Serializable

/** Where a track comes from. A track never changes source; a playlist can mix them all. */
enum class Source { LOCAL, NETWORK, YOUTUBE, RADIO }

/**
 * One playable thing, whatever its origin. Mirrors the PC app's `Track`:
 * - LOCAL: a file on the phone, `uri` = content:// (MediaStore), `path` = its file path;
 * - NETWORK: a file on an SMB/WebDAV source, `uri` = chud-net://<sourceId>/<relPath>, `path` = relPath;
 * - YOUTUBE: `uri` = chud-yt://<videoId>, never a stream URL (they expire);
 * - RADIO: `uri` = the station's stream URL; never saved in playlists or history.
 */
@Serializable
data class Track(
    val id: String,
    val source: Source,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val coverUri: String? = null,
    val available: Boolean = true,
    val path: String? = null,
    val sourceId: Long? = null,
    val videoId: String? = null,
    val ytArtistId: String? = null,
    val ytAlbumId: String? = null,
    val mime: String? = null,
    val sizeBytes: Long? = null,
    val folder: String? = null,
) {
    val isYoutube get() = source == Source.YOUTUBE
    val isRadio get() = source == Source.RADIO

    companion object {
        fun ytId(videoId: String) = "yt:$videoId"
        fun ytUri(videoId: String) = "chud-yt://$videoId"
        fun youtube(
            videoId: String, title: String, artist: String?, album: String? = null, durationMs: Long? = null,
            cover: String? = null, artistId: String? = null, albumId: String? = null,
        ) = Track(
            id = ytId(videoId), source = Source.YOUTUBE, uri = ytUri(videoId), title = title, artist = artist,
            album = album, durationMs = durationMs, coverUri = cover, videoId = videoId, ytArtistId = artistId, ytAlbumId = albumId,
        )
    }
}
