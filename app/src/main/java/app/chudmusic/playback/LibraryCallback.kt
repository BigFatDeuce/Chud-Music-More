package app.chudmusic.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.chudmusic.R
import app.chudmusic.graph
import app.chudmusic.model.Track
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future

/**
 * What Android Auto (and any media browser) can browse: playlists, albums, artists, recent
 * tracks, favourite radios — plus voice/text search. Choosing a track plays its whole list
 * from that track (mediaId "ctx:<list>|<index>"), like a tap in the app.
 */
class LibraryCallback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val saved: suspend () -> QueueStore.Saved?,
) : MediaLibrarySession.Callback {
    private val g get() = context.graph
    private fun s(id: Int) = context.getString(id)

    // The root never changes: answered at once, without a coroutine.
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        com.google.common.util.concurrent.Futures.immediateFuture(LibraryResult.ofItem(MediaItems.folder(ROOT, s(R.string.app_name)), params))

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future(Dispatchers.IO) {
            val t = resolve(mediaId).firstOrNull()?.first
            if (t != null) LibraryResult.ofItem(MediaItems.toItem(t, mediaId = mediaId), null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

    override fun onGetChildren(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val lib = g.library.state.value
        val items: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                MediaItems.folder(PLAYLISTS, s(R.string.nav_playlists)),
                MediaItems.folder(ALBUMS, s(R.string.lib_albums)),
                MediaItems.folder(ARTISTS, s(R.string.lib_artists)),
                MediaItems.folder(RECENT, s(R.string.home_recent)),
                MediaItems.folder(RADIOS, s(R.string.nav_radio)),
            )
            parentId == PLAYLISTS -> g.db.playlists().all().map { p ->
                MediaItems.folder("pl:${p.id}", p.name, art = p.cover ?: g.db.playlists().covers(p.id).firstOrNull(), playable = false)
            } + g.db.smart().all().map { p -> MediaItems.folder("sp:${p.id}", p.name, art = p.cover, playable = false) }
            parentId == ALBUMS -> lib.albums.map { a -> MediaItems.folder("al:${a.key}", a.title, a.artist, a.cover) }
            parentId == ARTISTS -> lib.artists.map { a -> MediaItems.folder("ar:${a.name}", a.name, null, a.cover) }
            parentId == RADIOS -> g.radio.favoritesNow().mapIndexed { i, st -> MediaItems.toItem(st, mediaId = "ctx:$RADIOS|$i") }
            else -> list(parentId).mapIndexed { i, t -> MediaItems.toItem(t, mediaId = "ctx:$parentId|$i") }
        }
        val from = (page * pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
    }

    /** The tracks of a browsable list. */
    private suspend fun list(id: String): List<Track> {
        return when {
            id.startsWith("pl:") -> g.playlists.tracks(id.removePrefix("pl:").toLong()).filter { it.available }
            id.startsWith("sp:") -> g.smart.tracksNow(id.removePrefix("sp:").toLong())
            id.startsWith("al:") -> g.library.albumTracks(id.removePrefix("al:"))
            id.startsWith("ar:") -> g.library.artistTracks(id.removePrefix("ar:"))
            id == RECENT -> g.db.tracks().recentlyPlayed().first().map { it.toTrack() }
            id == RADIOS -> g.radio.favoritesNow()
            id.startsWith("search:") -> g.library.search(id.removePrefix("search:"), 100)
            else -> emptyList()
        }
    }

    /** "ctx:<list>|<i>" → the whole list and the start index; a plain track id → that track. */
    private suspend fun resolve(mediaId: String): List<Pair<Track, Boolean>> {
        if (mediaId.startsWith("ctx:")) {
            val list = mediaId.removePrefix("ctx:").substringBeforeLast('|')
            val i = mediaId.substringAfterLast('|').toIntOrNull() ?: 0
            val tracks = list(list)
            return tracks.mapIndexed { idx, t -> t to (idx == i) }
        }
        val t = g.db.tracks().get(mediaId)?.toTrack() ?: return emptyList()
        return listOf(t to true)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        // One browsed item stands for its whole list.
        if (mediaItems.size == 1 && mediaItems[0].mediaId.startsWith("ctx:")) {
            val resolved = resolve(mediaItems[0].mediaId)
            val all = resolved.indexOfFirst { it.second }.coerceAtLeast(0)
            val kept = app.chudmusic.library.QueueWindow.around(resolved.size, all)
            return@future MediaSession.MediaItemsWithStartPosition(resolved.slice(kept).map { MediaItems.toItem(it.first) }, all - kept.first, startPositionMs)
        }
        val own = isOwn(controller)
        val items = mediaItems.mapNotNull { rebuild(it, own) }
        MediaSession.MediaItemsWithStartPosition(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startPositionMs)
    }

    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> =
        scope.future(Dispatchers.IO) { val own = isOwn(controller); mediaItems.mapNotNull { rebuild(it, own) } }

    private fun isOwn(controller: MediaSession.ControllerInfo) =
        trustsTrackExtras(controller.packageName, controller.uid, context.packageName, android.os.Process.myUid())

    /**
     * Items from a controller have no URI (Media3 strips it): rebuild them from the Track in their
     * extras or from the database. The Track in the extras carries its address, so only this app's
     * own screens are believed: any other app (the service is exported for Android Auto and the
     * system) could otherwise make Chud Music open any address with its own rights and saved
     * network passwords. Other controllers get the tracks the app already knows, by id.
     */
    private suspend fun rebuild(item: MediaItem, own: Boolean): MediaItem? {
        if (own && item.localConfiguration != null) return item
        val queued = MediaItems.isQueued(item)
        if (own) MediaItems.track(item)?.let { return MediaItems.toItem(it, queued) }
        val id = item.mediaId.ifEmpty { MediaItems.track(item)?.id ?: return null }
        return resolve(id).firstOrNull { it.second }?.first?.let { MediaItems.toItem(it, queued) }
    }

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        scope.future(Dispatchers.IO) {
            session.notifySearchResultChanged(browser, query, g.library.search(query, 100).size, params)
            LibraryResult.ofVoid()
        }

    override fun onGetSearchResult(
        session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val items = g.library.search(query, 100).mapIndexed { i, t -> MediaItems.toItem(t, mediaId = "ctx:search:$query|$i") }
        val from = (page * pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
    }

    /** "Play" from the system (Bluetooth button, media resumption card) with nothing loaded: the last queue. */
    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) {
            val q = saved() ?: throw UnsupportedOperationException("nothing to resume")
            MediaSession.MediaItemsWithStartPosition(q.tracks.mapIndexed { i, t -> MediaItems.toItem(t, q.queued.getOrElse(i) { false }) }, q.index, q.positionMs)
        }

    companion object {
        const val ROOT = "root"
        const val PLAYLISTS = "playlists"
        const val ALBUMS = "albums"
        const val ARTISTS = "artists"
        const val RECENT = "recent"
        const val RADIOS = "radios"

        /** The controller is this app itself: same package and same Linux user id. */
        fun trustsTrackExtras(pkg: String, uid: Int, ownPkg: String, ownUid: Int) = pkg == ownPkg && uid == ownUid
    }
}
