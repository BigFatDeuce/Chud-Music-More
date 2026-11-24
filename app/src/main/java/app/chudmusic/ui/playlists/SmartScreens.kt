package app.chudmusic.ui.playlists

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.playlists.SmartPlaylists
import app.chudmusic.playlists.SmartRules
import app.chudmusic.playlists.SmartRules.Field
import app.chudmusic.playlists.SmartRules.Op
import app.chudmusic.playlists.SmartRules.Order
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.MenuContext
import app.chudmusic.ui.PlaylistRef
import app.chudmusic.ui.Routes
import app.chudmusic.ui.UiController
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.ConfirmDialog
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.formatLong
import app.chudmusic.ui.home.Mosaic
import app.chudmusic.ui.library.CollectionHeader
import app.chudmusic.ui.settings.ToggleRow
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.Radii
import app.chudmusic.ui.tracksCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The picture chooser of playlists: one launcher for the whole app ([UiController.pickCover]).
 * Which playlist it is for survives the app being killed behind the system's photo picker.
 */
@Composable
fun CoverPicker(ui: UiController) {
    var id by rememberSaveable { mutableStateOf(0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var smart by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && id != 0L) ui.setPlaylistCover(PlaylistRef(id, name, null, smart), uri)
    }
    SideEffect {
        ui.pickCover = { p ->
            id = p.id; name = p.name; smart = p.smart
            runCatching { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .onFailure { ui.snack(ui.str(R.string.pl_cover_failed)) }
        }
    }
}

/** A row of the smart playlists: its picture, its name, how much it holds right now. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmartRow(p: SmartPlaylists.Entry) {
    val ui = LocalUi.current
    val c = Chud.colors
    val totals by remember(p.rules) { ui.g.smart.totals(p.rules) }.collectAsState(initial = null)
    var covers by remember(p.id) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(p.rules, totals) { if (p.cover == null) covers = withContext(Dispatchers.IO) { ui.g.smart.covers(p.rules) } }
    val menu = { ui.playlistMenu = PlaylistRef(p.id, p.name, p.cover, smart = true) }
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { ui.navigate(Routes.Smart(p.id)) }, onLongClick = menu).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.cover != null) Cover(p.cover, size = 64.dp, shape = RoundedCornerShape(Radii.m), placeholder = Icons.Rounded.AutoAwesome)
        else if (covers.isEmpty()) Cover(null, size = 64.dp, shape = RoundedCornerShape(Radii.m), placeholder = Icons.Rounded.AutoAwesome)
        else Mosaic(covers, 64.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, color = c.text0, maxLines = 1, overflow = TextOverflow.Ellipsis)
            totals?.let { Text(tracksCount(it.count) + " · " + formatLong(it.durationMs), style = MaterialTheme.typography.bodySmall, color = c.text1) }
        }
        IconButton(onClick = menu) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.pl_menu), tint = c.text1) }
    }
}

@Composable
fun SmartScreen(id: Long) {
    val ui = LocalUi.current
    val c = Chud.colors
    val g = ui.g
    val entry by remember(id) { g.smart.flow(id) }.collectAsState(initial = null)
    val p = entry
    val tracks by remember(p?.rules) { p?.let { g.smart.tracks(it.rules) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(initial = null)
    val current by g.player.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var seen by remember { mutableStateOf(false) }
    // Deleted, from here or from its long-press menu: nothing left to show.
    LaunchedEffect(p) { if (p != null) seen = true else if (seen) ui.back() }
    val ref = PlaylistRef(id, p?.name ?: "", p?.cover, smart = true)

    Column(Modifier.fillMaxSize()) {
        BackBar(p?.name ?: "", ui::back, actions = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_actions)) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.smart_edit)) }, onClick = { menu = false; ui.navigate(Routes.SmartEdit(id)) })
                DropdownMenuItem(text = { Text(stringResource(R.string.pl_cover_change)) }, onClick = { menu = false; ui.pickCover(ref) })
                if (p?.cover != null) DropdownMenuItem(text = { Text(stringResource(R.string.pl_cover_auto)) }, onClick = { menu = false; ui.setPlaylistCover(ref, null) })
                val now = tracks.orEmpty()
                if (now.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.smart_save_as)) }, onClick = {
                    menu = false
                    ui.createPlaylist(ref.name, now, open = true)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_ellipsis), color = c.danger) }, onClick = { menu = false; deleting = true })
            }
        })
        val list = tracks
        if (p == null || list == null) { Loading(); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                CollectionHeader(
                    p.name, null, listOf(tracksCount(list.size), formatLong(list.sumOf { it.durationMs ?: 0 })).joinToString(" · "),
                    p.cover ?: list.firstNotNullOfOrNull { it.coverUri }, list, placeholder = Icons.Rounded.AutoAwesome,
                    onCoverLongClick = { ui.playlistMenu = ref },
                )
                if (list.isEmpty()) EmptyState(Icons.Rounded.AutoAwesome, stringResource(R.string.smart_empty), stringResource(R.string.smart_empty_text)) {
                    TextButton(onClick = { ui.navigate(Routes.SmartEdit(id)) }) { Text(stringResource(R.string.smart_edit), color = c.accent) }
                }
            }
            itemsIndexed(list, key = { _, t -> t.id }) { i, t ->
                TrackRow(
                    t, onClick = { ui.play(list, i) }, onMore = { ui.openMenu(t, MenuContext.InSmart(id)) },
                    playing = current.track?.id == t.id,
                )
            }
        }
    }
    if (deleting) ConfirmDialog(
        title = stringResource(R.string.pl_delete_q, ref.name), text = stringResource(R.string.smart_delete_body), confirm = stringResource(R.string.delete),
        onDismiss = { deleting = false }, onConfirm = { ui.deletePlaylist(ref) },
    )
}

private val presets: List<Pair<Int, SmartRules>> = listOf(
    R.string.smart_never to SmartRules.neverPlayed(),
    R.string.smart_rediscover to SmartRules.rediscover(),
    R.string.smart_recent to SmartRules.recentlyAdded(),
    R.string.smart_most to SmartRules.mostPlayed(),
    R.string.smart_custom to SmartRules(rules = listOf(SmartRules.Rule.new(Field.GENRE)), order = Order.TITLE, descending = false),
)

private val Field.label: Int
    get() = when (this) {
        Field.GENRE -> R.string.smart_f_genre
        Field.ARTIST -> R.string.smart_f_artist
        Field.ALBUM -> R.string.smart_f_album
        Field.TITLE -> R.string.smart_f_title
        Field.FOLDER -> R.string.smart_f_folder
        Field.YEAR -> R.string.smart_f_year
        Field.PLAYS -> R.string.smart_f_plays
        Field.ADDED -> R.string.smart_f_added
        Field.PLAYED -> R.string.smart_f_played
        Field.DURATION -> R.string.smart_f_duration
    }

private val Op.label: Int
    get() = when (this) {
        Op.CONTAINS -> R.string.smart_op_contains
        Op.NOT_CONTAINS -> R.string.smart_op_not_contains
        Op.IS -> R.string.smart_op_is
        Op.IS_NOT -> R.string.smart_op_is_not
        Op.EMPTY -> R.string.smart_op_empty
        Op.EQ -> R.string.smart_op_eq
        Op.GTE -> R.string.smart_op_least
        Op.LTE -> R.string.smart_op_most
        Op.UNKNOWN -> R.string.smart_op_unknown
        Op.WITHIN -> R.string.smart_op_within
        Op.BEFORE -> R.string.smart_op_not_within
        Op.NEVER -> R.string.smart_op_never
        Op.EVER -> R.string.smart_op_ever
    }

private val Order.label: Int
    get() = when (this) {
        Order.ADDED -> R.string.smart_o_added
        Order.PLAYS -> R.string.smart_o_plays
        Order.PLAYED -> R.string.smart_o_played
        Order.TITLE -> R.string.smart_o_title
        Order.ARTIST -> R.string.smart_o_artist
        Order.ALBUM -> R.string.smart_o_album
        Order.YEAR -> R.string.smart_o_year
        Order.DURATION -> R.string.smart_o_duration
        Order.RANDOM -> R.string.smart_o_random
    }

/** The rules of a smart playlist; nothing is written before "Save". [id] 0 = a new one. */
@Composable
fun SmartEditScreen(id: Long) {
    val ui = LocalUi.current
    val c = Chud.colors
    val firstName = stringResource(R.string.smart_never)
    // Kept as text: what is being typed survives a rotation and the app being killed.
    var json by rememberSaveable { mutableStateOf(if (id == 0L) SmartRules.neverPlayed().toJson() else null) }
    var name by rememberSaveable { mutableStateOf(if (id == 0L) firstName else "") }
    var limitText by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(id) {
        if (json != null) return@LaunchedEffect
        val e = withContext(Dispatchers.IO) { ui.g.db.smart().get(id) } ?: run { ui.back(); return@LaunchedEffect }
        val r = SmartRules.parse(e.rules) ?: SmartRules()
        name = e.name; limitText = r.limit?.toString() ?: ""; json = r.toJson()
    }
    val rules = remember(json) { json?.let { SmartRules.parse(it, keepIncomplete = true) } }
    fun set(r: SmartRules) { json = r.toJson(raw = true) }
    fun save() {
        val r = rules ?: return
        val clean = SmartRules.parse(r.toJson()) ?: return
        ui.scope.launch {
            val saved = withContext(Dispatchers.IO) { if (id == 0L) ui.g.smart.create(name, clean) else { ui.g.smart.save(id, name, clean); id } }
            ui.back()
            if (id == 0L) ui.navigate(Routes.Smart(saved))
            ui.snack(ui.str(R.string.smart_saved))
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(if (id == 0L) R.string.smart_new else R.string.smart_title), ui::back, actions = {
            TextButton(onClick = ::save, enabled = rules != null && name.isNotBlank()) { Text(stringResource(R.string.save), color = if (name.isNotBlank()) c.accent else c.text2) }
        })
        if (rules == null) { Loading(); return@Column }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (id == 0L) {
                val names = presets.map { stringResource(it.first) }
                Label(stringResource(R.string.smart_start_from))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEachIndexed { i, (_, preset) ->
                        Pill(names[i], rules == preset, onClick = {
                            set(preset)
                            limitText = preset.limit?.toString() ?: ""
                            // A name the user typed is theirs.
                            if (name.isBlank() || name in names) name = if (i == presets.lastIndex) "" else names[i]
                        })
                    }
                }
            }
            OutlinedTextField(value = name, onValueChange = { name = it.take(100) }, singleLine = true, label = { Text(stringResource(R.string.smart_name)) }, modifier = Modifier.fillMaxWidth())

            Preview(rules)

            Label(stringResource(R.string.smart_rules))
            if (rules.rules.size > 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.smart_match), style = MaterialTheme.typography.bodyMedium, color = c.text1)
                Choice(stringResource(if (rules.all) R.string.smart_match_all else R.string.smart_match_any), listOf(true, false), { stringResource(if (it) R.string.smart_match_all else R.string.smart_match_any) }) { set(rules.copy(all = it)) }
            }
            if (rules.rules.isEmpty()) Note(stringResource(R.string.smart_rules_none))
            rules.rules.forEachIndexed { i, r ->
                RuleCard(r, { n -> set(rules.copy(rules = rules.rules.toMutableList().also { it[i] = n })) }, { set(rules.copy(rules = rules.rules.filterIndexed { k, _ -> k != i })) })
            }
            AddButton(stringResource(R.string.smart_add_rule)) { set(rules.copy(rules = rules.rules + SmartRules.Rule.new(Field.GENRE))) }

            Label(stringResource(R.string.smart_excludes))
            if (rules.exclude.isEmpty() && rules.excludeIds.isEmpty()) Note(stringResource(R.string.smart_excludes_none))
            rules.exclude.forEachIndexed { i, r ->
                RuleCard(r, { n -> set(rules.copy(exclude = rules.exclude.toMutableList().also { it[i] = n })) }, { set(rules.copy(exclude = rules.exclude.filterIndexed { k, _ -> k != i })) })
            }
            if (rules.excludeIds.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.smart_excluded_tracks, rules.excludeIds.size), style = MaterialTheme.typography.bodyMedium, color = c.text1, modifier = Modifier.weight(1f))
                TextButton(onClick = { set(rules.copy(excludeIds = emptyList())) }) { Text(stringResource(R.string.smart_excluded_reset), color = c.accent) }
            }
            AddButton(stringResource(R.string.smart_add_exclude)) { set(rules.copy(exclude = rules.exclude + SmartRules.Rule.new(Field.GENRE))) }

            Label(stringResource(R.string.smart_order))
            Choice(stringResource(rules.order.label), Order.entries, { stringResource(it.label) }) { set(rules.copy(order = it)) }
            if (rules.order != Order.RANDOM) ToggleRow(stringResource(R.string.smart_desc), null, rules.descending) { set(rules.copy(descending = it)) }
            OutlinedTextField(
                value = limitText,
                onValueChange = { v ->
                    limitText = v.filter(Char::isDigit).take(6)
                    set(rules.copy(limit = limitText.toIntOrNull()?.takeIf { it > 0 }))
                },
                singleLine = true, label = { Text(stringResource(R.string.smart_limit)) }, supportingText = { Text(stringResource(R.string.smart_limit_none)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** What the rules give right now, a moment after the last change. */
@Composable
private fun Preview(rules: SmartRules) {
    val ui = LocalUi.current
    var settled by remember { mutableStateOf(rules) }
    LaunchedEffect(rules) { delay(300); settled = rules }
    val totals by remember(settled) { ui.g.smart.totals(settled) }.collectAsState(initial = null)
    Text(
        totals?.let { stringResource(R.string.smart_preview, tracksCount(it.count) + " · " + formatLong(it.durationMs)) } ?: "",
        style = MaterialTheme.typography.bodyMedium, color = Chud.colors.accent,
    )
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Chud.colors.accent, modifier = Modifier.padding(top = 10.dp))

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Chud.colors.text1)

@Composable
private fun AddButton(text: String, onClick: () -> Unit) = TextButton(onClick = onClick) {
    Icon(Icons.Rounded.Add, null, tint = Chud.colors.accent)
    Spacer(Modifier.width(6.dp))
    Text(text, color = Chud.colors.accent)
}

/** A button showing what is chosen; a tap lists the others. */
@Composable
private fun <T> Choice(shown: String, options: List<T>, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.clip(RoundedCornerShape(Radii.s)).background(Chud.colors.bg3)) {
            Text(shown, color = Chud.colors.text0)
            Icon(Icons.Rounded.ArrowDropDown, null, tint = Chud.colors.text1)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
private fun RuleCard(r: SmartRules.Rule, onChange: (SmartRules.Rule) -> Unit, onRemove: () -> Unit) {
    val c = Chud.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.m)).background(c.bg1).padding(start = 10.dp, top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Choice(stringResource(r.field.label), Field.entries, { stringResource(it.label) }) { f -> if (f != r.field) onChange(SmartRules.Rule.new(f)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.smart_remove_rule), tint = c.text1) }
        }
        Choice(stringResource(r.op.label), r.field.ops, { stringResource(it.label) }) { op ->
            onChange(r.copy(op = op, value = if (op.needsValue && r.value.isBlank() && r.field.kind == SmartRules.Kind.DAYS) "182" else r.value))
        }
        if (r.op.needsValue) {
            OutlinedTextField(
                value = r.value, onValueChange = { onChange(r.copy(value = it.take(200))) }, singleLine = true,
                isError = !r.complete,
                keyboardOptions = KeyboardOptions(keyboardType = if (r.field.kind == SmartRules.Kind.TEXT) KeyboardType.Text else KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
            )
            if (r.op == Op.BEFORE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.smart_days_180), r.value == "182", onClick = { onChange(r.copy(value = "182")) })
                Pill(stringResource(R.string.smart_days_365), r.value == "365", onClick = { onChange(r.copy(value = "365")) })
            }
        }
    }
}
