package app.chudmusic.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chudmusic.R
import app.chudmusic.audio.SpectrumAnalyzer
import androidx.media3.common.Player
import app.chudmusic.model.Source
import app.chudmusic.model.Track
import app.chudmusic.playback.PlayerConnection
import app.chudmusic.playback.SleepTimer
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.MenuContext
import app.chudmusic.ui.components.Cover
import app.chudmusic.ui.formatTime
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.LcdFamily
import app.chudmusic.ui.theme.Radii
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Panel { COVER, LYRICS, QUEUE, EQ }

/** The full player: cover (or lyrics, queue, equaliser), the LCD display, the transport. */
@Composable
fun NowPlaying(onClose: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    val st by ui.g.player.state.collectAsState()
    val eq by ui.g.settings.eq.collectAsState(initial = app.chudmusic.data.EqState())
    var panel by rememberSaveable { mutableStateOf(Panel.COVER) }
    var speedDialog by remember { mutableStateOf(false) }
    var sleepDialog by remember { mutableStateOf(false) }
    val sleep by ui.g.sleep.state.collectAsState()
    val t = st.track

    Column(Modifier.fillMaxSize().background(c.bg0).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.close), tint = c.text0) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.now_playing).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.text2)
                Text(
                    when (t?.source) {
                        Source.YOUTUBE -> stringResource(R.string.src_youtube)
                        Source.NETWORK -> stringResource(R.string.src_network)
                        Source.RADIO -> stringResource(R.string.player_live_radio)
                        Source.LOCAL -> stringResource(R.string.src_phone)
                        null -> ""
                    },
                    style = MaterialTheme.typography.labelMedium, color = c.text1,
                )
            }
            IconButton(onClick = { t?.let { ui.openMenu(it, MenuContext.None) } }, enabled = t != null) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_actions), tint = c.text0)
            }
        }

        if (t == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.player_empty), color = c.text1, style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(panel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "panel") { p ->
                when (p) {
                    Panel.COVER -> CoverPanel(t)
                    Panel.LYRICS -> LyricsPanel(t, st.streamTitle)
                    Panel.QUEUE -> QueuePanel()
                    Panel.EQ -> EqPanel()
                }
            }
        }

        Lcd(t, st.streamTitle, st.isPlaying, eqOn = eq.enabled, compact = panel != Panel.COVER, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        if (!t.isRadio) SeekBar(st.durationMs)
        Transport(st)

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            PanelButton(Icons.Rounded.Lyrics, R.string.panel_lyrics, panel == Panel.LYRICS) { panel = if (panel == Panel.LYRICS) Panel.COVER else Panel.LYRICS }
            PanelButton(Icons.AutoMirrored.Rounded.QueueMusic, R.string.queue_title, panel == Panel.QUEUE) { panel = if (panel == Panel.QUEUE) Panel.COVER else Panel.QUEUE }
            PanelButton(Icons.Rounded.Equalizer, R.string.eq_title, panel == Panel.EQ) { panel = if (panel == Panel.EQ) Panel.COVER else Panel.EQ }
            PanelButton(Icons.Rounded.Bedtime, R.string.sleep_title, sleep != SleepTimer.State.Off) { sleepDialog = true }
            if (!t.isRadio) PanelButton(Icons.Rounded.Speed, R.string.player_speed, false) { speedDialog = true }
        }
    }
    if (speedDialog) SpeedDialog { speedDialog = false }
    if (sleepDialog) SleepDialog(t) { sleepDialog = false }
}

@Composable
private fun PanelButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, selected: Boolean, onClick: () -> Unit) {
    val c = Chud.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier.clip(CircleShape).background(if (selected) c.accentSoft else Color.Transparent),
    ) { Icon(icon, stringResource(label), tint = if (selected) c.accent else c.text1) }
}

@Composable
private fun CoverPanel(t: Track) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Cover(
            t.coverUri, size = side, shape = RoundedCornerShape(Radii.xl),
            placeholder = if (t.isRadio) Icons.Rounded.Radio else Icons.Rounded.MusicNote,
        )
    }
}

/**
 * The LCD display of the PC skins: phosphor-green on black (Nocturne), dark ink on a
 * grey-green reflective panel (Platine), VT323 font, and the spectrum in segmented bars.
 */
@Composable
fun Lcd(t: Track, streamTitle: String?, playing: Boolean, eqOn: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    val c = Chud.colors
    val ui = LocalUi.current
    val pos by ui.g.player.position.collectAsState()
    val st by ui.g.player.state.collectAsState()
    val sleep by ui.g.sleep.state.collectAsState()
    val glow = if (c.isDark) Shadow(c.lcdGlow, Offset.Zero, 12f) else null
    val big = TextStyle(fontFamily = LcdFamily, fontSize = if (compact) 24.sp else 30.sp, color = c.lcdFg, shadow = glow)
    val small = TextStyle(fontFamily = LcdFamily, fontSize = 19.sp, color = c.lcdFg.copy(alpha = 0.85f), shadow = glow)
    val tiny = TextStyle(fontFamily = LcdFamily, fontSize = 16.sp, color = c.lcdFg.copy(alpha = 0.75f))

    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radii.m)).background(c.lcdBg)
            .border(1.dp, if (c.isDark) c.lcdDim else Color.Black.copy(alpha = 0.18f), RoundedCornerShape(Radii.m))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (playing) "▶ PLAY" else if (st.phase == PlayerConnection.Phase.WAITING) "··· WAIT" else "❚❚ PAUSE", style = tiny)
            Spacer(Modifier.width(12.dp))
            Text(formatLabel(t), style = tiny, maxLines = 1, modifier = Modifier.weight(1f))
            if (eqOn) Text("EQ", style = tiny)
            if (sleep != SleepTimer.State.Off) Text("  SLEEP", style = tiny)
            if (st.shuffle) Text("  SHUF", style = tiny)
            if (st.repeat != Player.REPEAT_MODE_OFF) Text(if (st.repeat == Player.REPEAT_MODE_ONE) "  REP1" else "  REP", style = tiny)
        }
        val title = if (t.isRadio) streamTitle?.takeIf { it.isNotBlank() } ?: t.title else t.title
        Text(title, style = big, maxLines = 1, modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000))
        val sub = if (t.isRadio) t.title else listOfNotNull(t.artist, t.album).joinToString(" · ").ifBlank { stringResource(R.string.unknown_artist) }
        Text(sub, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Spectrum(playing, Modifier.weight(1f).height(if (compact) 26.dp else 40.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                if (t.isRadio) stringResource(R.string.player_live).uppercase() else formatTime(pos),
                style = big.copy(fontSize = if (compact) 24.sp else 30.sp),
            )
        }
    }
}

private fun formatLabel(t: Track): String {
    val codec = t.mime?.substringAfter('/')?.removePrefix("x-")?.uppercase()?.let {
        when (it) { "MPEG" -> "MP3"; "MP4", "MP4A-LATM" -> "AAC"; "VORBIS" -> "OGG"; else -> it }
    }
    return when (t.source) {
        Source.YOUTUBE -> "YT MUSIC"
        Source.RADIO -> listOfNotNull("RADIO", t.artist?.substringAfter(" · ", "")?.ifBlank { null }?.uppercase()).joinToString(" ")
        Source.NETWORK -> listOfNotNull(codec, "LAN").joinToString(" · ")
        Source.LOCAL -> codec ?: ""
    }
}

/** Segmented bars: lit segments in the LCD ink, the others as faint "ghost" segments, like a real LCD. */
@Composable
fun Spectrum(playing: Boolean, modifier: Modifier = Modifier, bands: Int = 24, segments: Int = 8) {
    val c = Chud.colors
    val analyzer = remember(bands) { SpectrumAnalyzer(bands) }
    var levels by remember(bands) { mutableStateOf(FloatArray(bands)) }
    // One frame per vsync while there is something to show; idle once the bars have fallen.
    LaunchedEffect(analyzer, playing) {
        while (true) {
            withFrameMillis { }
            val b = analyzer.bars(playing)
            levels = b.copyOf()
            if (!playing && b.all { it < 0.01f }) break
        }
    }
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (bands - 1)) / bands
        val segH = (size.height - gap * (segments - 1)) / segments
        for (i in 0 until bands) {
            val lit = (levels[i] * segments + 0.5f).toInt()
            for (s in 0 until segments) {
                val x = i * (w + gap)
                val y = size.height - (s + 1) * segH - s * gap
                drawRoundRect(if (s < lit) c.lcdFg else c.lcdDim, Offset(x, y), Size(w, segH), CornerRadius(1.5f))
            }
        }
    }
}

@Composable
private fun SeekBar(durationMs: Long) {
    val ui = LocalUi.current
    val c = Chud.colors
    val pos by ui.g.player.position.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else if (durationMs > 0) (pos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.padding(horizontal = 20.dp)) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { ui.g.player.seekTo((dragValue * durationMs).toLong()); dragging = false },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.bg4),
            modifier = Modifier.height(32.dp),
        )
        Row {
            Text(formatTime(if (dragging) (dragValue * durationMs).toLong() else pos), style = MaterialTheme.typography.labelMedium, color = c.text1)
            Spacer(Modifier.weight(1f))
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium, color = c.text1)
        }
    }
}

@Composable
private fun Transport(st: app.chudmusic.playback.PlayerConnection.State) {
    val ui = LocalUi.current
    val c = Chud.colors
    val radio = st.track?.isRadio == true
    val nextOn = !radio || st.queue.size > 1
    // Disabled buttons (a live radio has no shuffle, repeat or previous) are drawn faded.
    fun on(enabled: Boolean, color: androidx.compose.ui.graphics.Color) = if (enabled) color else color.copy(alpha = 0.3f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = { ui.g.player.setShuffle(!st.shuffle) }, enabled = !radio) {
            Icon(Icons.Rounded.Shuffle, stringResource(R.string.player_shuffle), tint = on(!radio, if (st.shuffle) c.accent else c.text1))
        }
        IconButton(onClick = { ui.g.player.previous() }, enabled = !radio, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.player_prev), tint = on(!radio, c.text0), modifier = Modifier.size(36.dp))
        }
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(c.accent).clickable { ui.g.player.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (st.loading) CircularProgressIndicator(Modifier.size(64.dp), color = c.accentInk, strokeWidth = 2.dp)
            Icon(
                if (st.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play),
                tint = c.accentInk, modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = { ui.g.player.next() }, enabled = nextOn, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.player_next), tint = on(nextOn, c.text0), modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = { ui.g.player.cycleRepeat() }, enabled = !radio) {
            Icon(
                if (st.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                stringResource(
                    when (st.repeat) { Player.REPEAT_MODE_ONE -> R.string.player_repeat_one; Player.REPEAT_MODE_ALL -> R.string.player_repeat_all; else -> R.string.player_repeat_off },
                ),
                tint = on(!radio, if (st.repeat != Player.REPEAT_MODE_OFF) c.accent else c.text1),
            )
        }
    }
}

@Composable
private fun SpeedDialog(onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val speed by ui.g.settings.speed.collectAsState(initial = 1f)
    var v by remember(speed) { mutableFloatStateOf(speed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.player_speed)) },
        text = {
            Column {
                Text("×" + "%.2f".format(v), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                Slider(value = v, onValueChange = { v = (it * 20).toInt() / 20f }, valueRange = 0.5f..2f, steps = 29)
                Text(stringResource(R.string.player_speed_note), style = MaterialTheme.typography.bodySmall, color = Chud.colors.text1)
            }
        },
        confirmButton = { TextButton(onClick = { ui.scope.launch { ui.g.settings.setSpeed(v) }; onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = { ui.scope.launch { ui.g.settings.setSpeed(1f) }; onDismiss() }) { Text(stringResource(R.string.player_speed_reset)) } },
    )
}

@Composable
private fun SleepDialog(track: Track?, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    val state by ui.g.sleep.state.collectAsState()
    var custom by remember { mutableStateOf("") }
    fun minutes(n: Int) { ui.g.sleep.inMinutes(n); ui.snack(ui.str(R.string.sleep_set, n)); onDismiss() }
    val typed = custom.toIntOrNull()?.takeIf { it in 1..SleepTimer.MAX_MINUTES }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    SleepTimer.State.Off -> {}
                    is SleepTimer.State.At -> {
                        var left by remember(s) { mutableLongStateOf(s.endsAt - System.currentTimeMillis()) }
                        LaunchedEffect(s) { while (true) { left = s.endsAt - System.currentTimeMillis(); delay(500) } }
                        Text(stringResource(R.string.sleep_left, formatTime(left.coerceAtLeast(0))), color = c.accent)
                    }
                    SleepTimer.State.EndOfTrack -> Text(stringResource(R.string.sleep_set_track), color = c.accent)
                    SleepTimer.State.EndOfAlbum -> Text(stringResource(R.string.sleep_set_album), color = c.accent)
                }
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { n -> SuggestionChip(onClick = { minutes(n) }, label = { Text(stringResource(R.string.sleep_minutes, n)) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = custom, onValueChange = { v -> custom = v.filter(Char::isDigit).take(4) }, singleLine = true,
                        label = { Text(stringResource(R.string.sleep_custom)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = typed != null, onClick = { typed?.let(::minutes) }) { Text(stringResource(R.string.sleep_start)) }
                }
                if (track?.isRadio == false) {
                    TextButton(onClick = { ui.g.sleep.atEndOfTrack(); ui.snack(ui.str(R.string.sleep_set_track)); onDismiss() }) { Text(stringResource(R.string.sleep_end_track)) }
                    if (track.album != null || track.ytAlbumId != null) {
                        TextButton(onClick = { ui.g.sleep.atEndOfAlbum(); ui.snack(ui.str(R.string.sleep_set_album)); onDismiss() }) { Text(stringResource(R.string.sleep_end_album)) }
                    }
                }
            }
        },
        confirmButton = { if (state != SleepTimer.State.Off) TextButton(onClick = { ui.g.sleep.off(); onDismiss() }) { Text(stringResource(R.string.sleep_cancel)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
