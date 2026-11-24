package app.chudmusic.ui

import kotlinx.serialization.Serializable

/** Every page of the app (type-safe navigation). The five tabs, then the inner pages. */
object Routes {
    @Serializable data object Home
    @Serializable data object Library
    @Serializable data object YouTube
    @Serializable data object Playlists
    @Serializable data object Radio

    @Serializable data class Album(val key: String)
    @Serializable data class Artist(val name: String)
    @Serializable data class Playlist(val id: Long)
    @Serializable data class Smart(val id: Long)
    /** The rules of a smart playlist; [id] 0 = a new one. */
    @Serializable data class SmartEdit(val id: Long = 0)
    @Serializable data class YtAlbum(val id: String)
    @Serializable data class YtArtist(val id: String)
    /** A YouTube or Spotify link read as tracks; [into]: the playlist to fill, 0 for none. */
    @Serializable data class YtLink(val url: String, val into: Long = 0)
    /** A YouTube Music mix from a song; [videoId] null = match [title]/[artist] on YouTube first. */
    @Serializable data class Mix(val videoId: String?, val title: String, val artist: String? = null, val durationMs: Long? = null)
    @Serializable data object Settings
    @Serializable data class NetSource(val id: Long = 0)
    @Serializable data object Folders
    @Serializable data object HiddenArtists
    @Serializable data object Stats
    @Serializable data object About
}
