package app.chudmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.chudmusic.R
import app.chudmusic.graph
import app.chudmusic.playback.PlaybackService
import app.chudmusic.ui.components.AddToPlaylistSheet
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.components.TrackMenuSheet
import app.chudmusic.ui.home.HomeScreen
import app.chudmusic.ui.library.AlbumScreen
import app.chudmusic.ui.library.ArtistScreen
import app.chudmusic.ui.library.LibraryScreen
import app.chudmusic.ui.player.NowPlaying
import app.chudmusic.ui.playlists.PlaylistScreen
import app.chudmusic.ui.playlists.PlaylistsScreen
import app.chudmusic.ui.playlists.rememberPlaylistImporter
import app.chudmusic.ui.radio.RadioScreen
import app.chudmusic.ui.settings.AboutScreen
import app.chudmusic.ui.settings.FoldersScreen
import app.chudmusic.ui.settings.NetSourceScreen
import app.chudmusic.ui.settings.SettingsScreen
import app.chudmusic.ui.stats.StatsScreen
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.youtube.MixScreen
import app.chudmusic.ui.youtube.YouTubeScreen
import app.chudmusic.ui.youtube.YtAlbumScreen
import app.chudmusic.ui.youtube.YtArtistScreen
import app.chudmusic.ui.youtube.YtLinkScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class Tab(val route: Any, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.Home, R.string.nav_home, Icons.Rounded.Home),
    Tab(Routes.Library, R.string.nav_library, Icons.Rounded.LibraryMusic),
    Tab(Routes.YouTube, R.string.nav_youtube_short, Icons.Rounded.SmartDisplay),
    Tab(Routes.Playlists, R.string.nav_playlists, Icons.AutoMirrored.Rounded.QueueMusic),
    Tab(Routes.Radio, R.string.nav_radio, Icons.Rounded.Radio),
)

@Composable
fun ChudRoot() {
    val context = LocalContext.current
    val g = context.graph
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val ui = remember { UiController(context.applicationContext, g, scope, nav, snackbar) }
    val c = Chud.colors
    val importer = rememberPlaylistImporter(ui)
    app.chudmusic.ui.playlists.CoverPicker(ui)

    // A search field left focused under the player would bring the keyboard up over it.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(ui.playerOpen) { if (ui.playerOpen) focus.clearFocus() }
    // Messages from the player (a track skipped because unreadable, everything failed).
    LaunchedEffect(Unit) {
        PlaybackService.eventFlow.collect { e ->
            when (e) {
                is PlaybackService.PlayerEvent.Skipped -> {
                    val reason = when (e.reason) {
                        PlaybackService.SkipReason.MISSING -> R.string.err_reason_missing
                        PlaybackService.SkipReason.NETWORK -> R.string.err_reason_network
                        PlaybackService.SkipReason.AUTH -> R.string.err_reason_auth
                        PlaybackService.SkipReason.FORMAT -> R.string.err_reason_format
                        PlaybackService.SkipReason.OTHER -> R.string.err_reason_other
                    }
                    ui.snack(ui.str(R.string.err_skipped, e.title, ui.str(reason)))
                }
                PlaybackService.PlayerEvent.WaitingNetwork -> ui.snack(ui.str(R.string.err_waiting_network))
                PlaybackService.PlayerEvent.AllFailed -> ui.snack(ui.str(R.string.err_all_failed))
            }
        }
    }
    // Things other apps hand over: a playlist file to import, a YouTube link to play.
    LaunchedEffect(Unit) {
        Incoming.items.collect { item ->
            when (item) {
                is Incoming.Item.PlaylistFile -> importer(item.uri)
                is Incoming.Item.SharedText -> {
                    // Read by the same rules as a link pasted in the search box (and as on the PC).
                    val link = app.chudmusic.youtube.MusicLink.find(item.text)
                    // A song plays at once; a playlist, an album or a Spotify link opens as a list.
                    if (link == null) ui.snack(ui.str(R.string.share_not_youtube))
                    else if (link !is app.chudmusic.youtube.MusicLink.YtVideo) ui.openLink(item.text)
                    else {
                        val t = withContext(Dispatchers.IO) { runCatching { g.youtube.song(link.id) }.getOrNull() }
                        if (t != null) { ui.play(listOf(t)); ui.playerOpen = true } else ui.snack(ui.str(R.string.yt_error_generic))
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalUi provides ui) {
        val entry by nav.currentBackStackEntryAsState()
        val dest = entry?.destination
        Box(Modifier.fillMaxSize().background(c.bg0)) {
            Scaffold(
                containerColor = c.bg0,
                snackbarHost = { if (!ui.playerOpen) SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    Column {
                        MiniPlayer(onOpen = { ui.playerOpen = true })
                        NavigationBar(containerColor = c.bg1, tonalElevation = 0.dp) {
                            tabs.forEach { tab ->
                                val selected = dest?.hierarchy()?.any { it.hasRoute(tab.route::class) } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, null) },
                                    label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.accentInk, indicatorColor = c.accent, selectedTextColor = c.text0,
                                        unselectedIconColor = c.text1, unselectedTextColor = c.text1,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { pad ->
                NavHost(nav, startDestination = Routes.Home, modifier = Modifier.padding(pad)) {
                    composable<Routes.Home> { HomeScreen() }
                    composable<Routes.Library> { LibraryScreen() }
                    composable<Routes.YouTube> { YouTubeScreen() }
                    composable<Routes.Playlists> { PlaylistsScreen(importer) }
                    composable<Routes.Radio> { RadioScreen() }
                    composable<Routes.Album> { AlbumScreen(it.toRoute<Routes.Album>().key) }
                    composable<Routes.Artist> { ArtistScreen(it.toRoute<Routes.Artist>().name) }
                    composable<Routes.Playlist> { PlaylistScreen(it.toRoute<Routes.Playlist>().id) }
                    composable<Routes.Smart> { app.chudmusic.ui.playlists.SmartScreen(it.toRoute<Routes.Smart>().id) }
                    composable<Routes.SmartEdit> { app.chudmusic.ui.playlists.SmartEditScreen(it.toRoute<Routes.SmartEdit>().id) }
                    composable<Routes.YtAlbum> { YtAlbumScreen(it.toRoute<Routes.YtAlbum>().id) }
                    composable<Routes.YtArtist> { YtArtistScreen(it.toRoute<Routes.YtArtist>().id) }
                    composable<Routes.YtLink> { it.toRoute<Routes.YtLink>().let { r -> YtLinkScreen(r.url, r.into) } }
                    composable<Routes.Mix> { MixScreen(it.toRoute<Routes.Mix>()) }
                    composable<Routes.Settings> { SettingsScreen(importer) }
                    composable<Routes.NetSource> { NetSourceScreen(it.toRoute<Routes.NetSource>().id) }
                    composable<Routes.Folders> { FoldersScreen() }
                    composable<Routes.HiddenArtists> { app.chudmusic.ui.settings.HiddenArtistsScreen() }
                    composable<Routes.Stats> { StatsScreen() }
                    composable<Routes.About> { AboutScreen() }
                }
            }

            // Lists scroll under the transparent status bar: keep the clock and icons readable.
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.bg0.copy(alpha = 0.94f)))

            AnimatedVisibility(
                visible = ui.playerOpen,
                enter = slideInVertically(tween(280)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(200)),
            ) {
                BackHandler { ui.playerOpen = false }
                NowPlaying(onClose = { ui.playerOpen = false })
                // Over the player too, away from its buttons.
                Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) { SnackbarHost(snackbar) }
            }
        }

        ui.menu?.let { (t, ctx) -> TrackMenuSheet(t, ctx) { ui.menu = null } }
        ui.playlistMenu?.let { p -> app.chudmusic.ui.components.PlaylistMenuSheet(p) { ui.playlistMenu = null } }
        ui.addTo?.let { list -> AddToPlaylistSheet(list) { ui.addTo = null } }
        ui.card?.let { t -> app.chudmusic.ui.components.TrackCardSheet(t) { ui.card = null } }
    }
}

private fun androidx.navigation.NavDestination.hierarchy() = generateSequence(this) { it.parent }

/** The bar above the tabs: what plays, play/pause, next; a tap opens "En lecture". */
@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    val st by ui.g.player.state.collectAsState()
    val pos by ui.g.player.position.collectAsState()
    val t = st.track ?: return
    Column(Modifier.fillMaxWidth().background(c.bg2).clickable(onClick = onOpen)) {
        if (!t.isRadio) {
            LinearProgressIndicator(
                progress = { if (st.durationMs > 0) (pos.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.line, drawStopIndicator = {},
            )
        } else Spacer(Modifier.height(2.dp))
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(t.coverUri, size = 42.dp, shape = RoundedCornerShape(6.dp), placeholder = if (t.isRadio) Icons.Rounded.Radio else androidx.compose.material.icons.Icons.Rounded.LibraryMusic)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    st.streamTitle ?: t.artist ?: stringResource(R.string.unknown_artist),
                    style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (st.loading) CircularProgressIndicator(Modifier.size(40.dp), color = c.accent, strokeWidth = 2.dp)
                IconButton(onClick = { ui.g.player.toggle() }) {
                    Icon(
                        if (st.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play), tint = c.text0,
                    )
                }
            }
            if (!t.isRadio) IconButton(onClick = { ui.g.player.next() }) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), tint = c.text0) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).clip(RoundedCornerShape(0.dp)).background(c.line))
    }
}
