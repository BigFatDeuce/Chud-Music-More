package app.chudmusic.youtube

import app.chudmusic.model.Track

enum class YtSearchType { SONG, VIDEO, ALBUM, ARTIST }

data class YtAlbumItem(
    val id: String,
    val title: String,
    val artist: String?,
    val artistId: String?,
    val year: String?,
    val kind: String?,
    val coverUrl: String?,
)

data class YtArtistItem(val id: String, val name: String, val subtitle: String?, val coverUrl: String?)

sealed interface YtItem {
    data class Song(val track: Track) : YtItem
    data class Album(val album: YtAlbumItem) : YtItem
    data class Artist(val artist: YtArtistItem) : YtItem
}

data class YtSearchPage(val type: YtSearchType, val items: List<YtItem>, val continuation: String?)

data class YtAlbumPage(
    val id: String,
    val title: String,
    val artist: String?,
    val artistId: String?,
    val year: String?,
    val kind: String?,
    val coverUrl: String?,
    val tracks: List<Track>,
)

data class YtShelf(val title: String, val albums: List<YtAlbumItem> = emptyList(), val artists: List<YtArtistItem> = emptyList(), val songs: List<Track> = emptyList())

data class YtArtistPage(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val description: String?,
    val topSongs: List<Track>,
    val shelves: List<YtShelf>,
)

data class YtPlaylist(val id: String, val title: String?, val tracks: List<Track>, val truncated: Boolean)

/** The radio YouTube Music builds itself for a song ("Démarrer la radio"): RDAMVM<videoId>. */
data class YtMix(val seed: Track?, val playlistId: String, val tracks: List<Track>)

class YouTubeException(message: String, cause: Throwable? = null) : Exception(message, cause)
