package app.chudmusic.ui.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.data.fold
import app.chudmusic.model.Track
import app.chudmusic.radio.RadioBrowser
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.ErrorBox
import app.chudmusic.ui.components.Load
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.SectionTitle
import app.chudmusic.ui.components.rememberLoader
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.Radii
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

private enum class RadioTab(val label: Int) { FAVORITES(R.string.radio_favorites), POPULAR(R.string.radio_popular), GENRES(R.string.radio_genres) }

@Composable
fun RadioScreen() {
    val ui = LocalUi.current
    val c = Chud.colors
    val focus = LocalFocusManager.current
    val savedCountry by ui.g.settings.radioCountry.collectAsState(initial = null)
    val country = savedCountry ?: Locale.getDefault().country.takeIf { it.length == 2 } ?: "FR"
    // null = not read from the database yet (an empty list would look like "no favourite").
    val storedFavorites by remember { ui.g.radio.favorites.map<List<app.chudmusic.model.Track>, List<app.chudmusic.model.Track>?> { it } }.collectAsState(initial = null)
    val favorites = storedFavorites.orEmpty()
    val recent by ui.g.radio.recent.collectAsState(initial = emptyList())
    // The first tab is Favourites when there are some: decided once, when the favourites are known.
    var tabChoice by rememberSaveable { mutableStateOf<RadioTab?>(null) }
    LaunchedEffect(storedFavorites == null) {
        val known = storedFavorites
        if (tabChoice == null && known != null) tabChoice = if (known.isEmpty()) RadioTab.POPULAR else RadioTab.FAVORITES
    }
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingCountry by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_radio), style = MaterialTheme.typography.displaySmall, color = c.text0)
                Text(stringResource(R.string.radio_sub), style = MaterialTheme.typography.bodySmall, color = c.text1)
            }
            TextButton(onClick = { choosingCountry = true }) {
                Icon(Icons.Rounded.Public, null); Spacer(Modifier.width(6.dp)); Text(country)
            }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it; if (it.isBlank()) submitted = null }, singleLine = true,
            placeholder = { Text(stringResource(R.string.radio_search_world)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; submitted = null }) { Icon(Icons.Rounded.Clear, stringResource(R.string.clear_search)) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) submitted = query.trim(); focus.clearFocus() }),
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = c.bg2, focusedContainerColor = c.bg2, unfocusedBorderColor = c.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (submitted != null) {
            val q = submitted!!
            val loader = rememberLoader("radio:search:$q") { ui.g.radio.search(q) }
            Stations(loader.state, loader::reload)
            return@Column
        }
        val tab = tabChoice ?: return@Column
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(RadioTab.entries) { t -> Pill(stringResource(t.label), tab == t, onClick = { tabChoice = t; tag = null }) }
        }
        when (tab) {
            RadioTab.FAVORITES -> if (favorites.isEmpty() && recent.isEmpty()) {
                EmptyState(Icons.Rounded.StarBorder, stringResource(R.string.radio_no_fav), stringResource(R.string.radio_no_fav_text))
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(favorites, key = { "f" + it.id }) { StationRow(it, null, favorites) }
                val others = recent.filter { r -> favorites.none { it.id == r.id } }
                if (others.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.radio_recent)) }
                    items(others, key = { "r" + it.id }) { StationRow(it, null, others) }
                }
            }
            RadioTab.POPULAR -> {
                val loader = rememberLoader("radio:top:$country") { ui.g.radio.top(country) }
                Stations(loader.state, loader::reload)
            }
            RadioTab.GENRES -> {
                val t = tag
                if (t == null) {
                    val tags = rememberLoader("radio:tags") { ui.g.radio.tags(60) }
                    when (val s = tags.state) {
                        Load.Loading -> Loading()
                        is Load.Err -> ErrorBox(stringResource(R.string.radio_error, s.message), tags::reload)
                        is Load.Ok -> LazyColumn(Modifier.fillMaxSize()) {
                            items(s.value, key = { it.name }) { tg ->
                                Row(Modifier.fillMaxWidth().clickable { tag = tg.name }.padding(horizontal = 24.dp, vertical = 14.dp)) {
                                    Text(tg.name.replaceFirstChar { it.titlecase() }, color = c.text0, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Text(tg.count.toString(), color = c.text2, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Pill(t.replaceFirstChar { it.titlecase() } + "  ✕", true, onClick = { tag = null })
                    }
                    val loader = rememberLoader("radio:tag:$t:$country") { ui.g.radio.byTag(t, country).ifEmpty { ui.g.radio.byTag(t, null) } }
                    Stations(loader.state, loader::reload)
                }
            }
        }
    }

    if (choosingCountry) CountryDialog(country, onDismiss = { choosingCountry = false }) { code ->
        choosingCountry = false
        ui.scope.launch { ui.g.settings.setRadioCountry(code) }
    }
}

@Composable
private fun Stations(state: Load<List<RadioBrowser.Station>>, retry: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("") }
    when (state) {
        Load.Loading -> Loading()
        is Load.Err -> ErrorBox(stringResource(R.string.radio_error, state.message), retry)
        is Load.Ok -> if (state.value.isEmpty()) EmptyState(Icons.Rounded.Radio, stringResource(R.string.radio_no_match), null)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (state.value.size > 15) item {
                OutlinedTextField(
                    filter, { filter = it }, singleLine = true, placeholder = { Text(stringResource(R.string.radio_filter)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val f = fold(filter.trim())
            val shown = state.value.filter { f.isEmpty() || fold(it.name + " " + (it.tags ?: "")).contains(f) }
            // The list shown becomes the queue: "next" on the lock screen or a headset goes to the next station.
            val queue = shown.map { RadioBrowser.toTrack(it) }
            items(shown.size, key = { shown[it].uuid }) { i -> StationRow(queue[i], shown[i], queue) }
        }
    }
}

@Composable
private fun StationRow(t: Track, station: RadioBrowser.Station?, list: List<Track>) {
    val ui = LocalUi.current
    val c = Chud.colors
    val playing by ui.g.player.state.collectAsState()
    // null = not read from the database yet (an empty list would look like "no favourite").
    val storedFavorites by remember { ui.g.radio.favorites.map<List<app.chudmusic.model.Track>, List<app.chudmusic.model.Track>?> { it } }.collectAsState(initial = null)
    val favorites = storedFavorites.orEmpty()
    val fav = favorites.any { it.id == t.id }
    val isCurrent = playing.track?.id == t.id
    Row(
        Modifier.fillMaxWidth().clickable { if (isCurrent) ui.g.player.toggle() else ui.playRadio(t, station, list) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(t.coverUri, size = 48.dp, shape = RoundedCornerShape(Radii.s), placeholder = Icons.Rounded.Radio)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (isCurrent) c.accent else c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = station?.tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString(", ")
            Text(listOfNotNull(t.artist, tags?.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = {
            ui.scope.launch {
                if (station != null) ui.g.radio.setFavorite(station, !fav)
                else RadioBrowser.uuidOf(t)?.let { ui.g.radio.setFavorite(it, !fav) }
            }
        }) {
            Icon(
                if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                stringResource(if (fav) R.string.radio_unfav else R.string.radio_fav, t.title),
                tint = if (fav) c.accent else c.text2,
            )
        }
    }
}

@Composable
private fun CountryDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val countries = remember {
        Locale.getISOCountries().map { it to Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.getDefault()) }.sortedBy { fold(it.second) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.radio_country)) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text(stringResource(R.string.radio_country_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(countries.filter { q.isBlank() || fold(it.second).contains(fold(q)) || it.first.equals(q, true) }, key = { it.first }) { (code, name) ->
                        Text(
                            "$name ($code)", color = if (code == current) Chud.colors.accent else Chud.colors.text0,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
