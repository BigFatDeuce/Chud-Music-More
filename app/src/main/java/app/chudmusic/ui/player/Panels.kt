package app.chudmusic.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.audio.Eq
import app.chudmusic.data.EqPreset
import app.chudmusic.data.EqState
import app.chudmusic.lyrics.Lyrics
import app.chudmusic.model.Track
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.MenuContext
import app.chudmusic.ui.components.ConfirmDialog
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.components.Pill
import app.chudmusic.ui.components.TextInputDialog
import app.chudmusic.ui.components.TrackRow
import app.chudmusic.ui.theme.Chud
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.log10
import kotlin.math.pow

// ── Queue ───────────────────────────────────────────────────────────────────────────────────

private data class QItem(val key: String, val index: Int, val track: Track, val queued: Boolean)

@Composable
fun QueuePanel() {
    val ui = LocalUi.current
    val c = Chud.colors
    val st by ui.g.player.state.collectAsState()
    fun keyed(): List<QItem> {
        val seen = HashMap<String, Int>()
        return st.queue.mapIndexed { i, t -> val n = seen.merge(t.id, 1, Int::plus)!!; QItem("${t.id}#$n", i, t, st.queued.getOrElse(i) { false }) }
    }
    var items by remember { mutableStateOf(keyed()) }
    var dragging by remember { mutableStateOf<QItem?>(null) }
    LaunchedEffect(st.queue, st.queued) { if (dragging == null) items = keyed() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (st.index - 1).coerceAtLeast(0))
    val reorder = rememberReorderableLazyListState(list) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    // null: not selecting. Else the keys of the chosen rows.
    var chosen by remember { mutableStateOf<Set<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val picked = chosen?.let { k -> items.filter { it.key in k } }
    androidx.activity.compose.BackHandler(enabled = chosen != null) { chosen = null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (picked != null) {
                IconButton(onClick = { chosen = null }) { Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = c.text1) }
                Text(stringResource(R.string.queue_selected, picked.size), style = MaterialTheme.typography.titleMedium, color = c.text0, modifier = Modifier.weight(1f))
                IconButton(onClick = { chosen = if (picked.size == items.size) emptySet() else items.mapTo(HashSet()) { it.key } }) {
                    Icon(Icons.Rounded.SelectAll, stringResource(R.string.queue_select_all), tint = c.text1)
                }
                IconButton(enabled = picked.isNotEmpty(), onClick = {
                    ui.g.player.removeAll(picked.mapTo(HashSet()) { it.index })
                    chosen = null
                    ui.snack(ui.str(R.string.queue_removed))
                }) { Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.queue_remove_selected), tint = if (picked.isEmpty()) c.text2 else c.danger) }
            } else {
                Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleLarge, color = c.text0, modifier = Modifier.weight(1f))
                Text(app.chudmusic.ui.tracksCount(st.queue.size), style = MaterialTheme.typography.bodySmall, color = c.text2)
                Box {
                    IconButton(onClick = { menu = true }, enabled = st.queue.isNotEmpty()) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.queue_more), tint = c.text1) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        @Composable fun entry(label: Int, action: () -> Unit) = DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { menu = false; action() })
                        entry(R.string.queue_select) { chosen = emptySet() }
                        entry(R.string.queue_save) { saving = true }
                        // Shuffled, what was played is not what stands above the current track.
                        if (!st.shuffle && st.index > 0) entry(R.string.queue_played) { ui.g.player.removeAll((0 until st.index).toSet()) }
                        if (st.queue.size > 1) entry(R.string.queue_clear) { ui.g.player.clearQueue() }
                        entry(R.string.queue_clear_all) { ui.g.player.stop() }
                    }
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(items, key = { _, it -> it.key }) { _, q ->
                ReorderableItem(reorder, key = q.key) { isDragging ->
                    val current = q.index == st.index && dragging == null
                    fun flip() { chosen = chosen?.let { if (q.key in it) it - q.key else it + q.key } }
                    TrackRow(
                        q.track,
                        onClick = { if (chosen != null) flip() else ui.g.player.skipTo(q.index) },
                        onMore = if (chosen != null) null else { { ui.openMenu(q.track, MenuContext.InQueue(q.index)) } },
                        playing = current,
                        subtitle = if (q.queued) stringResource(R.string.queue_up_next) + " · " + (q.track.artist ?: "") else null,
                        modifier = Modifier.background(if (isDragging) c.bg3 else if (current) c.accentSoft else c.bg0),
                        trailing = {
                            if (chosen != null) Checkbox(q.key in chosen.orEmpty(), { flip() }, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.accentInk))
                            else Icon(
                                Icons.Rounded.DragHandle, stringResource(R.string.queue_move), tint = c.text2,
                                modifier = Modifier.padding(horizontal = 4.dp).draggableHandle(
                                    onDragStarted = { dragging = q },
                                    onDragStopped = {
                                        val d = dragging
                                        dragging = null
                                        if (d != null) {
                                            val to = items.indexOfFirst { it.key == d.key }
                                            if (to >= 0 && to != d.index) ui.g.player.move(d.index, to)
                                        }
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
    if (saving) TextInputDialog(
        title = stringResource(R.string.queue_save_title), initial = "", confirm = stringResource(R.string.create), label = stringResource(R.string.name),
        onDismiss = { saving = false },
        onConfirm = { name -> saving = false; ui.scope.launch { ui.createPlaylist(name, ui.g.player.fullQueue()) } },
    )
}

// ── Lyrics ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun LyricsPanel(t: Track, streamTitle: String?) {
    val ui = LocalUi.current
    val c = Chud.colors
    var override by remember(t.id) { mutableStateOf<Pair<String, String?>?>(null) }
    var result by remember(t.id, streamTitle) { mutableStateOf<Lyrics.Result?>(null) }
    var fixing by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var fromFile by remember(t.id) { mutableStateOf(false) }

    // A radio: the lyrics of what it announces ("Artiste - Titre"), when it does.
    val subject: Track? = when {
        !t.isRadio -> t
        streamTitle.isNullOrBlank() -> null
        else -> t.copy(id = "icy:$streamTitle", title = streamTitle, artist = null, album = null, durationMs = null)
    }
    LaunchedEffect(subject?.id, override, attempt) {
        if (subject == null) return@LaunchedEffect
        result = null
        // The file's own lyrics first; a search by hand ("Chercher d'autres paroles") goes to LRCLIB.
        val own = if (override == null) ui.g.embeddedLyrics.of(subject) else null
        fromFile = own != null
        result = own ?: ui.g.lyrics.get(subject, override?.first, override?.second)
    }

    when {
        subject == null -> EmptyState(Icons.Rounded.Lyrics, stringResource(R.string.panel_lyrics), stringResource(R.string.lyrics_radio_no_title))
        result == null -> Loading()
        else -> when (val r = result!!) {
            is Lyrics.Result.Synced -> SyncedLyrics(r.lines)
            is Lyrics.Result.Plain -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(stringResource(if (fromFile) R.string.lyrics_badge_file else R.string.lyrics_badge_plain).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.text2)
                Spacer(Modifier.height(8.dp))
                Text(r.text, style = MaterialTheme.typography.bodyLarge, color = c.text0)
                TextButton(onClick = { fixing = true }) { Text(stringResource(R.string.lyrics_search_other)) }
            }
            Lyrics.Result.Instrumental -> EmptyState(Icons.Rounded.Lyrics, stringResource(R.string.lyrics_instrumental), null)
            is Lyrics.Result.NotFound -> EmptyState(Icons.Rounded.Lyrics, stringResource(R.string.lyrics_not_found), stringResource(R.string.lyrics_not_found_text, r.query)) {
                TextButton(onClick = { fixing = true }) { Text(stringResource(R.string.lyrics_search_other)) }
            }
            is Lyrics.Result.Error -> EmptyState(Icons.Rounded.Lyrics, stringResource(R.string.lyrics_unavailable, r.message), null) {
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
            }
        }
    }

    if (fixing && subject != null) {
        val (t0, a0) = ui.g.lyrics.clean(subject)
        var title by remember { mutableStateOf(override?.first ?: t0) }
        var artist by remember { mutableStateOf(override?.second ?: a0 ?: "") }
        AlertDialog(
            onDismissRequest = { fixing = false },
            title = { Text(stringResource(R.string.lyrics_search_other)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.col_title)) }, singleLine = true)
                    OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.col_artist)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = { fixing = false; override = title.trim() to artist.trim().ifBlank { null } }) {
                    Text(stringResource(R.string.search))
                }
            },
            dismissButton = { TextButton(onClick = { fixing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SyncedLyrics(lines: List<Lyrics.Line>) {
    val ui = LocalUi.current
    val c = Chud.colors
    val pos by ui.g.player.position.collectAsState()
    val current = Lyrics.currentLine(lines, pos + 250)
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0 && !list.isScrollInProgress) list.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
        itemsIndexed(lines) { i, line ->
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.Medium,
                color = when { i == current -> c.accent; i < current -> c.text2; else -> c.text1 },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { ui.g.player.seekTo(line.timeMs) }.padding(vertical = 6.dp),
            )
        }
    }
}

// ── Equaliser ───────────────────────────────────────────────────────────────────────────────

@Composable
fun EqPanel() {
    val ui = LocalUi.current
    val c = Chud.colors
    val saved by ui.g.settings.eq.collectAsState(initial = EqState())
    val userPresets by ui.g.settings.eqUserPresets.collectAsState(initial = emptyList())
    val names = stringArrayResource(R.array.eq_presets)
    // While a slider moves, the sound follows at once; the setting is written when the finger lifts.
    var live by remember { mutableStateOf<EqState?>(null) }
    val eq = live ?: saved
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EqPreset?>(null) }

    fun apply(e: EqState, persist: Boolean) {
        ui.g.equalizer.set(e.enabled, e.preampDb, e.bandsDb.toFloatArray())
        if (persist) { live = null; ui.scope.launch { ui.g.settings.setEq(e) } } else live = e
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.eq_title), style = MaterialTheme.typography.titleLarge, color = c.text0, modifier = Modifier.weight(1f))
            Switch(
                checked = eq.enabled, onCheckedChange = { apply(eq.copy(enabled = it), true) },
                colors = SwitchDefaults.colors(checkedThumbColor = c.accentInk, checkedTrackColor = c.accent),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(Eq.PRESETS.indices.toList()) { i ->
                Pill(names.getOrElse(i) { "$i" }, eq.preset == "$i", onClick = {
                    val (pre, bands) = Eq.PRESETS[i]
                    apply(eq.copy(enabled = true, preampDb = pre, bandsDb = bands.toList(), preset = "$i"), true)
                })
            }
            items(userPresets) { p ->
                Pill(p.name, eq.preset == "u:${p.name}", onClick = {
                    apply(eq.copy(enabled = true, preampDb = p.preampDb, bandsDb = p.bandsDb, preset = "u:${p.name}"), true)
                }, modifier = Modifier.pointerInput(p) { detectTapGestures(onLongPress = { deleting = p }) })
            }
            if (eq.preset == null) item { Pill(stringResource(R.string.eq_custom), true, onClick = {}) }
        }

        ResponseCurve(eq, Modifier.fillMaxWidth().height(84.dp))
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BandSlider(stringResource(R.string.eq_preamp_short), eq.preampDb, enabled = eq.enabled,
                onChange = { apply(eq.copy(preampDb = it, preset = null), false) }, onDone = { live?.let { apply(it, true) } })
            Box(Modifier.width(1.dp).fillMaxSize().padding(vertical = 16.dp).background(c.line))
            Eq.BANDS.forEachIndexed { i, f ->
                BandSlider(if (f >= 1000) "${f / 1000}k" else "$f", eq.bandsDb.getOrElse(i) { 0f }, enabled = eq.enabled,
                    onChange = { v -> apply(eq.copy(bandsDb = eq.bandsDb.toMutableList().also { it[i] = v }, preset = null), false) },
                    onDone = { live?.let { apply(it, true) } })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { apply(EqState(enabled = eq.enabled), true) }) { Text(stringResource(R.string.eq_reset)) }
            TextButton(onClick = { naming = true }) { Text(stringResource(R.string.eq_save_preset)) }
        }
        Text(stringResource(R.string.eq_live_note), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(bottom = 12.dp))
    }

    if (naming) TextInputDialog(
        title = stringResource(R.string.eq_save_preset), initial = stringResource(R.string.eq_my_preset), confirm = stringResource(R.string.save),
        onDismiss = { naming = false },
        onConfirm = { name ->
            naming = false
            ui.scope.launch {
                ui.g.settings.setEqUserPresets(userPresets.filter { it.name != name } + EqPreset(name, eq.preampDb, eq.bandsDb))
                ui.g.settings.setEq(eq.copy(preset = "u:$name"))
            }
        },
    )
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.delete_named, p.name), text = stringResource(R.string.eq_delete_preset_text), confirm = stringResource(R.string.delete),
            onDismiss = { deleting = null },
            onConfirm = { ui.scope.launch { ui.g.settings.setEqUserPresets(userPresets.filter { it.name != p.name }) } },
        )
    }
}

/** A vertical ±12 dB slider: drag, or double-tap to go back to 0. */
@Composable
private fun BandSlider(label: String, value: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val c = Chud.colors
    val range = Eq.RANGE_DB
    Column(Modifier.width(30.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (value > 0) "+%.0f".format(value) else "%.0f".format(value), style = MaterialTheme.typography.labelSmall, color = c.text2)
        var h by remember { mutableStateOf(1f) }
        var v by remember { mutableStateOf(value) }
        LaunchedEffect(value) { v = value }
        Canvas(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures(
                        onDragEnd = onDone,
                        onVerticalDrag = { ch, dy ->
                            ch.consume()
                            v = (v - dy / h * 2 * range).coerceIn(-range, range)
                            onChange((v * 2).toInt() / 2f)
                        },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = { onChange(0f); onDone() })
                },
        ) {
            h = size.height
            val cx = size.width / 2
            val trackW = 4.dp.toPx()
            drawRoundRect(c.bg4, Offset(cx - trackW / 2, 0f), Size(trackW, size.height), CornerRadius(trackW))
            val y0 = size.height / 2
            val y = y0 - value / range * (size.height / 2)
            val col = if (enabled) c.accent else c.text2
            drawRect(col, Offset(cx - trackW / 2, minOf(y, y0)), Size(trackW, kotlin.math.abs(y - y0)))
            drawLine(c.lineStrong, Offset(cx - 8.dp.toPx(), y0), Offset(cx + 8.dp.toPx(), y0), 1.dp.toPx())
            drawCircle(col, 8.dp.toPx(), Offset(cx, y))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.text1, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** What the equaliser does to the sound, 20 Hz → 20 kHz, ±15 dB (same maths as the filters). */
@Composable
private fun ResponseCurve(eq: EqState, modifier: Modifier) {
    val c = Chud.colors
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(c.bg2)) {
        val lo = log10(20.0); val hi = log10(20000.0)
        val mid = size.height / 2
        drawLine(c.line, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        for (f in listOf(100.0, 1000.0, 10000.0)) {
            val x = ((log10(f) - lo) / (hi - lo) * size.width).toFloat()
            drawLine(c.line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
        val gains = eq.bandsDb.toFloatArray()
        val path = Path()
        val n = 120
        for (i in 0..n) {
            val f = 10.0.pow(lo + (hi - lo) * i / n)
            val db = if (eq.enabled) Eq.responseDb(eq.preampDb, gains, f) else 0.0
            val x = size.width * i / n
            val y = (mid - db / 15.0 * mid).toFloat().coerceIn(0f, size.height)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (eq.enabled) c.accent else c.text2, style = Stroke(2.dp.toPx()))
    }
}
