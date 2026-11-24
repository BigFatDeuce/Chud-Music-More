package app.chudmusic.ui.youtube

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chudmusic.R
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.ErrorBox
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.Tile
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.Radii
import app.chudmusic.youtube.InnerTube
import app.chudmusic.youtube.YtItem
import app.chudmusic.youtube.YtSearchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One YouTube Music search, from the question to its pages of answers (state machine, see
 * docs/STATE-MACHINES.md):
 *
 *     Idle ──search()──▶ Loading ──▶ Results | Failed
 *     Results ──more()──▶ Results(loadingMore) ──▶ Results(+ the next page)
 *     any ──search()──▶ Loading     (the request in flight is cancelled: it can no longer write)
 *     any ──clear()──▶ Idle
 */
sealed interface YtSearch {
    /** The text that was searched, null before any search. */
    val query: String?

    data object Idle : YtSearch { override val query: String? get() = null }
    data class Loading(override val query: String, val type: YtSearchType) : YtSearch
    data class Failed(override val query: String, val type: YtSearchType, val message: String) : YtSearch
    data class Results(
        override val query: String,
        val type: YtSearchType,
        val items: List<YtItem>,
        /** Null when there is no further page. */
        val continuation: String?,
        val loadingMore: Boolean = false,
    ) : YtSearch {
        /** These results with the next [page] added (no duplicate); a page that brings nothing ends the list. */
        fun plus(page: app.chudmusic.youtube.YtSearchPage): Results {
            val seen = items.mapTo(HashSet()) { keyOf(it) }
            val fresh = page.items.filter { seen.add(keyOf(it)) }
            return copy(items = items + fresh, continuation = page.continuation.takeIf { it != continuation && page.items.isNotEmpty() }, loadingMore = false)
        }
    }

    companion object {
        fun keyOf(i: YtItem): String = when (i) { is YtItem.Song -> i.track.id; is YtItem.Album -> i.album.id; is YtItem.Artist -> i.artist.id }
    }
}

/** Search state, kept while the tab lives (switching tabs and coming back keeps the results). */
class YtSearchVm(private val yt: InnerTube) : ViewModel() {
    /** What is typed in the field (not necessarily what was searched). */
    var query by mutableStateOf("")
        private set
    /** The chosen filter, also before the first search. */
    var type by mutableStateOf(YtSearchType.SONG)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var search by mutableStateOf<YtSearch>(YtSearch.Idle)
        private set
    var history by mutableStateOf<List<String>>(emptyList())
        private set
    private var suggestJob: Job? = null
    /** The one request in flight (a search or a next page); a new search cancels it. */
    private var job: Job? = null

    val submitted: String? get() = search.query

    fun onQuery(q: String) {
        query = q
        suggestJob?.cancel()
        // A pasted link is opened, not searched: no suggestions for it.
        if (q.isBlank() || app.chudmusic.youtube.MusicLink.find(q) != null) { suggestions = emptyList(); return }
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions = withContext(Dispatchers.IO) { runCatching { yt.suggestions(q) }.getOrDefault(emptyList()) }
        }
    }

    fun search(q: String = query, t: YtSearchType = type) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text; type = t; suggestions = emptyList(); suggestJob?.cancel()
        history = (listOf(text) + history.filter { !it.equals(text, true) }).take(12)
        job?.cancel()
        val asked = YtSearch.Loading(text, t)
        search = asked
        job = viewModelScope.launch {
            val next = try {
                val page = withContext(Dispatchers.IO) { yt.search(text, t) }
                YtSearch.Results(text, t, page.items, page.continuation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                YtSearch.Failed(text, t, e.message ?: e.javaClass.simpleName)
            }
            if (search === asked) search = next
        }
    }

    fun setFilter(t: YtSearchType) { search.query?.let { search(it, t) } ?: run { type = t } }

    fun retry() { search.query?.let { search(it, type) } }

    fun more() {
        val shown = search as? YtSearch.Results ?: return
        val token = shown.continuation ?: return
        if (shown.loadingMore) return
        val waiting = shown.copy(loadingMore = true)
        search = waiting
        job = viewModelScope.launch {
            val page = try {
                withContext(Dispatchers.IO) { yt.searchMore(shown.type, token) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (search !== waiting) return@launch
            search = if (page != null) waiting.plus(page) else waiting.copy(continuation = null, loadingMore = false)
        }
    }

    fun clear() { job?.cancel(); query = ""; search = YtSearch.Idle; suggestions = emptyList() }
}

@Composable
fun YouTubeScreen() {
    val ui = LocalUi.current
    val c = Chud.colors
    val vm: YtSearchVm = viewModel { YtSearchVm(ui.g.youtube) }
    val focus = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val current by ui.g.player.state.collectAsState()
    // A search asked from elsewhere (a song a pasted link did not find).
    val asked by ui.g.ytQuery.collectAsState()
    LaunchedEffect(asked) { asked?.let { vm.search(it); ui.g.ytQuery.value = null } }
    /**
     * The search box's text holds a link: open it instead of searching (true when done). `find`, not
     * `parse`: what apps copy is often a sentence around the link ("Listen to … : https://…"), and a
     * link pasted after some text sticks to it.
     */
    fun openIfLink(text: String): Boolean {
        if (app.chudmusic.youtube.MusicLink.find(text) == null) return false
        keyboard?.hide(); focus.clearFocus()
        return ui.openLink(text)
    }
    val hidden by ui.g.hiddenArtists.collectAsState()
    val recentAll by remember {
        ui.g.db.tracks().recentlyPlayed().map { l -> l.map { it.toTrack() }.filter { it.isYoutube } }
    }.collectAsState(initial = emptyList())
    val recentYt = remember(recentAll, hidden) { hidden.keep(recentAll).take(20) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_youtube), style = MaterialTheme.typography.displaySmall, color = c.text0, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Shield, stringResource(R.string.ab_title), tint = c.accent)
        }
        OutlinedTextField(
            value = vm.query,
            onValueChange = { q ->
                val pasted = q.length - vm.query.length > 8
                vm.onQuery(q)
                // Pasting a link opens it at once, as on the PC.
                if (pasted) openIfLink(q)
            },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.yt_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (vm.query.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Rounded.Clear, stringResource(R.string.clear_search)) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (!openIfLink(vm.query)) { vm.search(); keyboard?.hide(); focus.clearFocus() } }),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.bg2, focusedContainerColor = c.bg2, unfocusedBorderColor = c.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { focused = it.isFocused },
        )

        if (app.chudmusic.youtube.MusicLink.find(vm.query) != null) {
            Row(
                Modifier.fillMaxWidth().clickable { openIfLink(vm.query) }.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Link, null, tint = c.accent)
                Spacer(Modifier.width(16.dp))
                Text(stringResource(R.string.yt_open_link), style = MaterialTheme.typography.bodyLarge, color = c.text0, modifier = Modifier.weight(1f))
            }
        }

        // Suggestions while typing (YouTube Music's own), then the recent searches.
        val showSuggest = focused && vm.query.isNotBlank() && vm.query != vm.submitted && vm.suggestions.isNotEmpty()
        if (showSuggest) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.suggestions) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.search(s); keyboard?.hide(); focus.clearFocus() }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = c.text2)
                        Spacer(Modifier.width(16.dp))
                        Text(s, style = MaterialTheme.typography.bodyLarge, color = c.text0, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { vm.onQuery("$s ") }) { Icon(Icons.AutoMirrored.Rounded.CallMade, null, tint = c.text2) }
                    }
                }
            }
            return@Column
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(YtSearchType.entries) { t ->
                Pill(
                    stringResource(when (t) { YtSearchType.SONG -> R.string.yt_songs; YtSearchType.VIDEO -> R.string.yt_versions; YtSearchType.ALBUM -> R.string.lib_albums; YtSearchType.ARTIST -> R.string.lib_artists }),
                    vm.type == t, onClick = { vm.setFilter(t) },
                )
            }
        }

        when (val s = vm.search) {
            YtSearch.Idle -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (vm.history.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.yt_recent_searches)) }
                    items(vm.history) { h ->
                        Row(Modifier.fillMaxWidth().clickable { vm.search(h) }.padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = c.text2); Spacer(Modifier.width(16.dp))
                            Text(h, color = c.text0, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (recentYt.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.lib_recent_played)) }
                    itemsIndexed(recentYt, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t, onClick = { ui.play(recentYt, i) }, onMore = { ui.openMenu(t) }, playing = current.track?.id == t.id)
                    }
                } else if (vm.history.isEmpty()) item {
                    EmptyState(Icons.Rounded.SmartDisplay, stringResource(R.string.yt_empty_title), stringResource(R.string.yt_empty_text))
                }
            }
            is YtSearch.Loading -> Loading()
            is YtSearch.Failed -> ErrorBox(stringResource(R.string.yt_error, s.message), onRetry = vm::retry)
            is YtSearch.Results -> {
                // Hidden artists are left out here rather than in the search state, so restoring one shows it again at once.
                val shown = remember(s.items, hidden) { if (hidden.isEmpty) s.items else s.items.filterNot(hidden::hides) }
                if (shown.isEmpty() && s.continuation == null) EmptyState(Icons.Rounded.Search, stringResource(R.string.yt_no_result), null)
                else Results(s, shown, vm::more, current.track?.id)
            }
        }
    }
}

@Composable
private fun Results(results: YtSearch.Results, shown: List<YtItem>, more: () -> Unit, playingId: String?) {
    val ui = LocalUi.current
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 6 } == true } }
    // A new page arrived while still near the end: ask for the next one too.
    LaunchedEffect(list, results.items.size) { snapshotFlow { nearEnd || shown.size < 6 }.collect { if (it) more() } }
    val songs = remember(shown) { shown.filterIsInstance<YtItem.Song>().map { it.track } }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(shown.size) { i ->
            when (val it = shown[i]) {
                is YtItem.Song -> TrackRow(
                    it.track, onClick = { ui.play(songs, songs.indexOf(it.track)) }, onMore = { ui.openMenu(it.track) },
                    playing = playingId == it.track.id,
                )
                is YtItem.Album -> ListEntry(
                    it.album.title, listOfNotNull(it.album.kind, it.album.artist, it.album.year).joinToString(" · "), it.album.coverUrl, round = false,
                ) { ui.navigate(Routes.YtAlbum(it.album.id)) }
                is YtItem.Artist -> ListEntry(it.artist.name, it.artist.subtitle, it.artist.coverUrl, round = true) { ui.navigate(Routes.YtArtist(it.artist.id)) }
            }
        }
        if (results.loadingMore) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Chud.colors.accent, strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
fun ListEntry(title: String, subtitle: String?, cover: String?, round: Boolean, onClick: () -> Unit) {
    val c = Chud.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(cover, size = 56.dp, shape = if (round) CircleShape else RoundedCornerShape(Radii.s), placeholder = if (round) Icons.Rounded.Person else androidx.compose.material.icons.Icons.Rounded.SmartDisplay)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shared by the YouTube pages: a horizontal row of albums. */
@Composable
fun AlbumRow(albums: List<app.chudmusic.youtube.YtAlbumItem>) {
    val ui = LocalUi.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(albums, key = { it.id }) { a ->
            Tile(a.title, listOfNotNull(a.kind, a.year).joinToString(" · ").ifBlank { a.artist }, a.coverUrl, onClick = { ui.navigate(Routes.YtAlbum(a.id)) }, size = 140.dp)
        }
    }
}
