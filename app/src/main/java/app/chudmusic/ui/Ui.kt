package app.chudmusic.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import app.chudmusic.AppGraph
import app.chudmusic.R
import app.chudmusic.data.ExclusionEntity
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.library.Library
import app.chudmusic.library.QueueWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Extra entries of a track's menu that depend on where it is shown. */
sealed interface MenuContext {
    data object None : MenuContext
    data class InPlaylist(val playlistId: Long, val entryId: Long) : MenuContext
    data class InQueue(val index: Int) : MenuContext
    /** In "Recently played" on Home: can be taken out of it. */
    data object RecentPlayed : MenuContext
    /** In a smart playlist: can be left out of it. */
    data class InSmart(val id: Long) : MenuContext
}

/** A playlist whose menu is open, or whose picture is being chosen. */
data class PlaylistRef(val id: Long, val name: String, val cover: String?, val smart: Boolean = false)

/**
 * What every screen can ask of the app shell: navigate, show a message, open a track's menu,
 * add to a playlist. One instance, created by [ChudRoot] and provided through [LocalUi].
 */
class UiController(
    val context: Context,
    val g: AppGraph,
    val scope: CoroutineScope,
    private val nav: NavHostController,
    private val snackbar: SnackbarHostState,
) {
    var menu by mutableStateOf<Pair<Track, MenuContext>?>(null)
    var addTo by mutableStateOf<List<Track>?>(null)
    /** The track whose card (details, history, links about the artist) is open. */
    var card by mutableStateOf<Track?>(null)
    /** The playlist whose menu is open (long press on it). */
    var playlistMenu by mutableStateOf<PlaylistRef?>(null)
    /** Opens the picture chooser for a playlist (set by the root screen, which owns the launcher). */
    var pickCover: (PlaylistRef) -> Unit = {}
    var playerOpen by mutableStateOf(false)

    fun str(id: Int, vararg args: Any) = context.getString(id, *args)

    fun snack(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, action, withDismissAction = action == null, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    fun navigate(route: Any) {
        playerOpen = false
        nav.navigate(route) { launchSingleTop = true }
    }

    fun back() { nav.popBackStack() }

    fun openMenu(t: Track, ctx: MenuContext = MenuContext.None) { menu = t to ctx }

    /** Hides [a] from YouTube Music lists, with "Annuler" on the snackbar; Settings › YouTube Music restores it later. */
    fun hideArtist(a: app.chudmusic.youtube.HiddenArtist) {
        scope.launch { g.settings.hideArtist(a) }
        snack(str(R.string.yt_artist_hidden, a.name), str(R.string.undo)) { scope.launch { g.settings.restoreArtist(a) } }
    }

    fun restoreArtist(a: app.chudmusic.youtube.HiddenArtist) {
        scope.launch { g.settings.restoreArtist(a) }
        snack(str(R.string.yt_artist_restored, a.name))
    }

    // ── Playback shortcuts with feedback ────────────────────────────────────────────────────

    fun play(tracks: List<Track>, index: Int = 0) {
        if (tracks.getOrNull(index)?.available == false) { snack(str(R.string.track_missing)); return }
        g.player.playList(tracks, index)
    }

    fun shuffle(tracks: List<Track>) = g.player.playList(tracks, 0, shuffle = true)

    /** Plays the library list [rows] from [index]: only the part that goes into the queue is read. */
    fun playRows(rows: LongArray, index: Int) {
        val kept = QueueWindow.around(rows.size, index)
        scope.launch {
            val found = withContext(Dispatchers.IO) { g.library.byRow(kept.map { rows[it] }) }
            val tracks = kept.mapNotNull { found[rows[it]] }
            val at = found[rows[index]]?.let { t -> tracks.indexOfFirst { it.id == t.id } } ?: 0
            play(tracks, at.coerceAtLeast(0))
        }
    }

    fun shuffleRows(rows: LongArray) {
        scope.launch {
            val picked = QueueWindow.drawn(rows.size, null).map { rows[it] }
            shuffle(withContext(Dispatchers.IO) { g.library.tracks(picked) })
        }
    }

    // ── Pictures of playlists ───────────────────────────────────────────────────────────────

    fun setPlaylistCover(p: PlaylistRef, picture: Uri?) {
        scope.launch {
            val old = withContext(Dispatchers.IO) { if (p.smart) g.db.smart().get(p.id)?.cover else g.db.playlists().get(p.id)?.cover }
            val cover = picture?.let { u -> withContext(Dispatchers.IO) { g.covers.saveChosen(context, u) } }
            if (picture != null && cover == null) { snack(str(R.string.pl_cover_failed)); return@launch }
            withContext(Dispatchers.IO) {
                if (p.smart) g.db.smart().setCover(p.id, cover, System.currentTimeMillis()) else g.playlists.setCover(p.id, cover)
                g.covers.dropChosen(old)
            }
            snack(str(R.string.pl_cover_changed, p.name))
        }
    }

    fun deletePlaylist(p: PlaylistRef) {
        scope.launch(Dispatchers.IO) {
            val cover = if (p.smart) g.db.smart().get(p.id)?.cover else g.db.playlists().get(p.id)?.cover
            if (p.smart) g.smart.delete(p.id) else g.playlists.delete(p.id)
            g.covers.dropChosen(cover)
        }
    }

    fun playNext(tracks: List<Track>) {
        g.player.playNext(tracks)
        snack(str(R.string.menu_play_next_done))
    }

    fun enqueue(tracks: List<Track>) {
        g.player.addToQueue(tracks)
        snack(str(R.string.menu_enqueued))
    }

    /** Plays [t] with the stations of [list] around it, so "next" and "previous" change station. */
    fun playRadio(t: Track, station: app.chudmusic.radio.RadioBrowser.Station? = null, list: List<Track> = listOf(t)) {
        val queue = list.takeIf { l -> l.any { it.id == t.id } } ?: listOf(t)
        g.player.playList(queue, queue.indexOfFirst { it.id == t.id })
        scope.launch(Dispatchers.IO) { g.radio.noteStarted(t, station) }
    }

    // ── Going to an album / artist from any track ───────────────────────────────────────────

    fun goAlbum(t: Track) {
        if (t.isYoutube) { t.ytAlbumId?.let { navigate(Routes.YtAlbum(it)) }; return }
        val key = Library.albumKey(t) ?: return
        if (g.library.state.value.albumByKey.containsKey(key)) navigate(Routes.Album(key))
    }

    fun goArtist(t: Track) {
        if (t.isYoutube) {
            val id = t.ytArtistId
            if (id != null) { navigate(Routes.YtArtist(id)); return }
            t.artist?.let { openYtArtist(it) }
            return
        }
        val name = Library.mainArtist(t.albumArtist ?: t.artist) ?: return
        navigate(Routes.Artist(name))
    }

    /** The YouTube Music page of an artist known only by name. */
    fun openYtArtist(name: String) {
        val main = Library.mainArtist(name) ?: name
        scope.launch {
            val a = withContext(Dispatchers.IO) { runCatching { g.youtube.findArtist(main) }.getOrNull() }
            if (a != null) navigate(Routes.YtArtist(a.id)) else snack(str(R.string.yt_artist_not_found, main))
        }
    }

    fun canGoAlbum(t: Track) = if (t.isYoutube) t.ytAlbumId != null else Library.albumKey(t)?.let { g.library.state.value.albumByKey.containsKey(it) } == true
    fun canGoArtist(t: Track) = !t.isRadio && (t.isYoutube || Library.mainArtist(t.albumArtist ?: t.artist) != null)

    fun startMix(t: Track) = navigate(Routes.Mix(t.videoId, t.title, t.artist, t.durationMs))

    /**
     * Opens a YouTube or Spotify link found in [text]: an album or an artist on its own page, the rest
     * as a list of tracks ([into]: the playlist they fill). False when the text holds no such link.
     */
    fun openLink(text: String, into: Long = 0): Boolean {
        val link = app.chudmusic.youtube.MusicLink.find(text) ?: return false
        when (link) {
            is app.chudmusic.youtube.MusicLink.YtAlbum -> if (into == 0L) { navigate(Routes.YtAlbum(link.id)); return true }
            is app.chudmusic.youtube.MusicLink.YtArtist -> if (into == 0L) { navigate(Routes.YtArtist(link.id)); return true }
            else -> {}
        }
        navigate(Routes.YtLink(text.trim(), into))
        return true
    }

    /** Runs a YouTube Music search in the YouTube tab. */
    fun searchYouTube(query: String) {
        g.ytQuery.value = query
        nav.navigate(Routes.YouTube) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openInYouTube(t: Track) {
        val id = t.videoId ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/watch?v=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun share(t: Track) {
        val text = when {
            t.videoId != null -> "${t.title} – ${t.artist ?: ""}\nhttps://music.youtube.com/watch?v=${t.videoId}"
            else -> listOfNotNull(t.title, t.artist).joinToString(" – ")
        }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Retirer de la bibliothèque": the file stays where it is, playlists keep the track. */
    fun removeFromLibrary(tracks: List<Track>) {
        val ids = tracks.filter { it.source == Source.LOCAL || it.source == Source.NETWORK }.map { it.id }
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            g.db.sources().exclude(ids.map { ExclusionEntity(it) })
            ids.chunked(500).forEach { g.db.tracks().leaveLibrary(it) }
            withContext(Dispatchers.Main) {
                snack(context.resources.getQuantityString(R.plurals.lib_removed, ids.size, ids.size), str(R.string.undo)) {
                    scope.launch(Dispatchers.IO) { g.db.sources().unexclude(ids); g.scanner.scanAll() }
                }
            }
        }
    }

    /** Takes [t] out of "Recently played" (its plays stay in the statistics), with Undo. */
    fun forgetRecentPlay(t: Track) {
        scope.launch {
            val before = withContext(Dispatchers.IO) { g.db.stats().recent(t.id) } ?: return@launch
            withContext(Dispatchers.IO) { g.db.stats().forgetRecent(t.id) }
            snack(str(R.string.home_forgot_played, t.title), str(R.string.undo)) {
                scope.launch(Dispatchers.IO) { g.db.stats().putRecent(before); g.db.stats().trimRecent(app.chudmusic.stats.Stats.RECENT_MAX) }
            }
        }
    }

    /** Empties "Recently played", with Undo. */
    fun clearRecentPlays() {
        scope.launch {
            val before = withContext(Dispatchers.IO) { g.db.stats().recent().also { g.db.stats().clearRecent() } }
            snack(str(R.string.home_cleared_played), str(R.string.undo)) {
                scope.launch(Dispatchers.IO) { before.forEach { g.db.stats().putRecent(it) }; g.db.stats().trimRecent(app.chudmusic.stats.Stats.RECENT_MAX) }
            }
        }
    }

    /** Takes an album out of "Recently added" on Home, with Undo. */
    fun forgetRecentAlbum(key: String, title: String) {
        scope.launch { g.settings.setHomeHiddenAdded(key, true) }
        snack(str(R.string.home_forgot_added, title), str(R.string.undo)) { scope.launch { g.settings.setHomeHiddenAdded(key, false) } }
    }

    fun addToPlaylist(playlistId: Long, name: String, tracks: List<Track>) {
        scope.launch {
            withContext(Dispatchers.IO) { g.playlists.add(playlistId, tracks) }
            snack(context.resources.getQuantityString(R.plurals.pl_added, tracks.size, tracks.size, name))
        }
    }

    fun createPlaylist(name: String, tracks: List<Track>, open: Boolean = false) {
        scope.launch {
            val id = withContext(Dispatchers.IO) { g.playlists.create(name.trim(), tracks) }
            snack(str(R.string.pl_created, name.trim()))
            if (open) navigate(Routes.Playlist(id))
        }
    }
}

val LocalUi = staticCompositionLocalOf<UiController> { error("UiController not provided") }
