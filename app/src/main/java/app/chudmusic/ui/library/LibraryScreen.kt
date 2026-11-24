package app.chudmusic.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.library.Album
import app.chudmusic.library.LibraryQuery
import app.chudmusic.library.PagedTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.chudmusic.library.Artist
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.Tile
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.tracksCount

private enum class LibTab(val label: Int) { TRACKS(R.string.lib_tracks), ALBUMS(R.string.lib_albums), ARTISTS(R.string.lib_artists) }

@Composable
fun LibraryScreen() {
    val ui = LocalUi.current
    val c = Chud.colors
    val lib by ui.g.library.state.collectAsState()
    val sources by ui.g.db.sources().flow().collectAsState(initial = emptyList())
    val scan by ui.g.scanner.state.collectAsState()
    val hideSmall by ui.g.settings.hideSmallAlbums.collectAsState(initial = false)
    var tab by rememberSaveable { mutableStateOf(LibTab.TRACKS) }
    var query by rememberSaveable { mutableStateOf("") }
    /** null = everything, 0 = the phone, else a network source id. */
    var origin by rememberSaveable { mutableStateOf<Long?>(null) }

    // Row numbers of what the search and the origin leave, then the albums and artists among them.
    var tracks by remember { mutableStateOf<PagedTracks?>(null) }
    var albumKeys by remember { mutableStateOf<Set<String>?>(null) }
    var artistKeys by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(lib.version, query, origin) {
        if (!lib.loaded) return@LaunchedEffect
        // Typing: one search for the word, not one per letter.
        if (query.isNotBlank()) kotlinx.coroutines.delay(150)
        val q = LibraryQuery(query, origin)
        val whole = query.isBlank() && origin == null
        val dao = ui.g.db.tracks()
        withContext(Dispatchers.IO) {
            val rows = ui.g.library.rows(q)
            val keys = if (whole) null else dao.strings(q.albumKeys()).toHashSet()
            val names = if (whole) null else dao.strings(q.artistKeys()).toHashSet()
            withContext(Dispatchers.Main) {
                tracks = PagedTracks(rows, { withContext(Dispatchers.IO) { ui.g.library.byRow(it) } }, tracks)
                albumKeys = keys
                artistKeys = names
            }
        }
    }
    val albums = remember(lib, albumKeys, hideSmall) { lib.albums.filter { a -> albumKeys?.contains(a.key) != false && (!hideSmall || a.count >= 3) } }
    val artists = remember(lib, artistKeys) { lib.artists.filter { a -> artistKeys?.contains(a.name) != false } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_library), style = MaterialTheme.typography.displaySmall, color = c.text0)
                Text(
                    (scan as? app.chudmusic.library.ScanState.Running)?.let { stringResource(R.string.scan_running, it.label ?: stringResource(R.string.src_phone)) } ?: tracksCount(lib.count),
                    style = MaterialTheme.typography.bodySmall, color = c.text1,
                )
            }
            IconButton(onClick = { ui.g.scanner.scanAll(force = true) }, enabled = !scan.running) {
                Icon(Icons.Rounded.Sync, stringResource(R.string.set_rescan), tint = c.text1)
            }
            IconButton(onClick = { tracks?.let { ui.shuffleRows(it.rows) } }, enabled = (tracks?.size ?: 0) > 0) {
                Icon(Icons.Rounded.Shuffle, stringResource(R.string.lib_shuffle_all), tint = c.accent)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text(stringResource(R.string.lib_search_label)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, stringResource(R.string.clear_search)) } },
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.bg2, focusedContainerColor = c.bg2, unfocusedBorderColor = c.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (sources.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(stringResource(R.string.lib_all), origin == null, onClick = { origin = null }) }
                item { Pill(stringResource(R.string.src_phone), origin == 0L, onClick = { origin = 0L }) }
                items(sources, key = { it.id }) { s -> Pill(s.name, origin == s.id, onClick = { origin = s.id }) }
            }
        }
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = c.bg0, contentColor = c.text0) {
            LibTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(stringResource(t.label)) }, selectedContentColor = c.accent, unselectedContentColor = c.text1)
            }
        }

        val list = tracks
        when {
            !lib.loaded || list == null -> Loading()
            lib.count == 0 -> EmptyState(Icons.Rounded.LibraryMusic, stringResource(R.string.lib_empty_title), stringResource(R.string.lib_empty_text))
            list.size == 0 -> EmptyState(Icons.Rounded.Search, stringResource(R.string.lib_no_match), null)
            else -> when (tab) {
                LibTab.TRACKS -> TrackList(list)
                LibTab.ALBUMS -> AlbumGrid(albums)
                LibTab.ARTISTS -> ArtistList(artists)
            }
        }
    }
}

@Composable
private fun TrackList(tracks: PagedTracks) {
    val ui = LocalUi.current
    val current by ui.g.player.state.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(tracks.size, key = { tracks.rows[it] }) { i ->
            val page = tracks.pageOf(i)
            LaunchedEffect(tracks, page) { tracks.ensure(page) }
            val t = tracks[i]
            if (t == null) Spacer(Modifier.fillMaxWidth().height(60.dp))
            else TrackRow(t, onClick = { ui.playRows(tracks.rows, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id)
        }
    }
}

@Composable
private fun AlbumGrid(albums: List<Album>) {
    val ui = LocalUi.current
    if (albums.isEmpty()) { EmptyState(Icons.Rounded.LibraryMusic, stringResource(R.string.lib_no_albums), null); return }
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(pluralStringResource(R.plurals.n_albums, albums.size, albums.size), style = MaterialTheme.typography.bodySmall, color = Chud.colors.text2)
        }
        items(albums, key = { it.key }) { a ->
            Tile(a.title, listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "), a.cover, onClick = { ui.navigate(Routes.Album(a.key)) },
                size = 160.dp, onLongClick = { ui.scope.launch { ui.addTo = withContext(Dispatchers.IO) { ui.g.library.albumTracks(a.key) } } })
        }
    }
}

@Composable
private fun ArtistList(artists: List<Artist>) {
    val ui = LocalUi.current
    val c = Chud.colors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(artists, key = { it.name }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { ui.navigate(Routes.Artist(a.name)) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(a.cover, size = 52.dp, shape = CircleShape, placeholder = Icons.Rounded.Person)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.bodyLarge, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(pluralStringResource(R.plurals.n_albums, a.albums.size, a.albums.size), tracksCount(a.count)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.text1,
                    )
                }
            }
        }
    }
}
