package app.chudmusic.ui.settings

import app.chudmusic.data.readAtMost
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import app.chudmusic.audio.CrossfadeSettings
import app.chudmusic.audio.ReplayGain
import app.chudmusic.data.SettingsExchange
import app.chudmusic.library.NetworkScanner
import app.chudmusic.ui.components.Pill
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.R
import app.chudmusic.data.NetKind
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.home.HomeSection
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Person
import app.chudmusic.ui.Routes
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.ConfirmDialog
import app.chudmusic.ui.playlists.PlaylistImporter
import app.chudmusic.ui.playlists.rememberExportAll
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.theme.ChudColors
import app.chudmusic.ui.theme.Nocturne
import app.chudmusic.ui.theme.Platine
import app.chudmusic.ui.theme.Skin
import app.chudmusic.ui.tracksCount
import app.chudmusic.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    val c = Chud.colors
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = c.accent, modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(c.bg1)) { content() }
}

@Composable
fun SettingRow(title: String, text: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = Chud.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = c.text1); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text0)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = c.text1)
        }
        if (trailing != null) { Spacer(Modifier.width(12.dp)); trailing() }
    }
}

@Composable
fun ToggleRow(title: String, text: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Chud.colors
    SettingRow(title, text, onClick = { onChange(!checked) }) {
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = c.accentInk, checkedTrackColor = c.accent))
    }
}

private fun whenText(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

/** Which shelves Home shows: one switch each. */
@Composable
private fun HomeGroup() {
    val ui = LocalUi.current
    val shown by ui.g.settings.homeSections.collectAsState(initial = HomeSection.entries.toSet())
    Group(stringResource(R.string.set_home)) {
        Text(
            stringResource(R.string.set_home_text), style = MaterialTheme.typography.bodySmall, color = Chud.colors.text1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        for (s in HomeSection.entries) {
            val title = when (s) {
                HomeSection.RECENT_PLAYED -> R.string.lib_recent_played
                HomeSection.RANDOM -> R.string.home_random
                HomeSection.PLAYLISTS -> R.string.nav_playlists
                HomeSection.RECENT_ADDED -> R.string.lib_recent_added
                HomeSection.RADIO_FAVORITES -> R.string.set_home_radios
                HomeSection.GETTING_STARTED -> R.string.home_start
            }
            ToggleRow(stringResource(title), null, s in shown) { on -> ui.scope.launch { ui.g.settings.setHomeSection(s, on) } }
        }
    }
}

/** One row leading to the hidden artists' own page, so their names are not on the main settings page. */
/** "Trouver les pochettes manquantes" (Deezer, iTunes), with where the search is. */
@Composable
private fun CoversRow() {
    val ui = LocalUi.current
    val state by ui.g.onlineCovers.state.collectAsState()
    val lib by ui.g.library.state.collectAsState()
    val missing = lib.albums.count { it.cover == null }
    val text = when (val s = state) {
        app.chudmusic.library.OnlineCovers.State.Idle -> pluralStringResource(R.plurals.set_covers_missing, missing, missing)
        is app.chudmusic.library.OnlineCovers.State.Running -> stringResource(R.string.set_covers_running, s.done, s.total)
        is app.chudmusic.library.OnlineCovers.State.Done -> pluralStringResource(R.plurals.set_covers_done, s.found, s.found, s.searched)
        is app.chudmusic.library.OnlineCovers.State.Failed -> stringResource(R.string.set_covers_failed, s.message)
    }
    SettingRow(stringResource(R.string.set_covers), text + "\n" + stringResource(R.string.set_covers_text), onClick = {
        if (state !is app.chudmusic.library.OnlineCovers.State.Running) ui.g.onlineCovers.start(lib.albums)
    })
    if (state is app.chudmusic.library.OnlineCovers.State.Running) {
        val r = state as app.chudmusic.library.OnlineCovers.State.Running
        LinearProgressIndicator(progress = { if (r.total > 0) r.done.toFloat() / r.total else 0f }, Modifier.fillMaxWidth(), color = Chud.colors.accent, trackColor = Chud.colors.bg3)
    }
}

@Composable
private fun HiddenArtistsRow() {
    val ui = LocalUi.current
    val hidden by ui.g.hiddenArtists.collectAsState()
    val n = hidden.list.size
    SettingRow(
        stringResource(R.string.set_hidden_artists),
        if (n == 0) stringResource(R.string.set_hidden_artists_zero) else pluralStringResource(R.plurals.set_hidden_artists_count, n, n),
        icon = Icons.Rounded.VisibilityOff,
        onClick = { ui.navigate(Routes.HiddenArtists) },
    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Chud.colors.text2) }
}

/** Settings › YouTube Music › Hidden artists: each one with "Rétablir"; how to hide one when there are none. */
@Composable
fun HiddenArtistsScreen() {
    val ui = LocalUi.current
    val c = Chud.colors
    val hidden by ui.g.hiddenArtists.collectAsState()
    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.set_hidden_artists), ui::back)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Text(
                    stringResource(if (hidden.list.isEmpty()) R.string.set_hidden_artists_none else R.string.set_hidden_artists_text),
                    style = MaterialTheme.typography.bodySmall, color = c.text1, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (hidden.list.isNotEmpty()) item {
                Group(stringResource(R.string.nav_youtube)) {
                    for (a in hidden.list.sortedBy { it.name.lowercase() }) {
                        SettingRow(a.name, null, icon = Icons.Rounded.Person) {
                            androidx.compose.material3.TextButton(onClick = { ui.restoreArtist(a) }) {
                                Text(stringResource(R.string.set_hidden_restore), color = c.accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Play, pause, next and previous on the lock screen come with the media notification (Media3).
 * Android shows it even without the notification permission, but some phones hide it when the
 * app's notifications are turned off: say so, and lead to the switch.
 */
@Composable
private fun PlaybackGroup() {
    val ui = LocalUi.current
    val c = Chud.colors
    var enabled by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        enabled = androidx.core.app.NotificationManagerCompat.from(ui.context).areNotificationsEnabled()
        onPauseOrDispose {}
    }
    Group(stringResource(R.string.set_playback)) {
        SettingRow(
            stringResource(R.string.set_lock_title),
            stringResource(if (enabled) R.string.set_lock_on else R.string.set_lock_off),
            icon = if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            onClick = {
                runCatching {
                    ui.context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ui.context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.text2) }
        SettingRow(stringResource(R.string.set_gapless), stringResource(R.string.set_gapless_text), icon = Icons.Rounded.CheckCircle)
        CrossfadeRows()
        GainRow()
    }
}

@Composable
private fun CrossfadeRows() {
    val ui = LocalUi.current
    val c = Chud.colors
    val saved by ui.g.settings.crossfade.collectAsState(initial = CrossfadeSettings())
    // While the slider moves; written when the finger lifts.
    var live by remember(saved) { mutableStateOf<Float?>(null) }
    val seconds = live?.roundToInt() ?: saved.seconds
    SettingRow(stringResource(R.string.set_crossfade), stringResource(R.string.set_crossfade_text)) {
        Text(
            if (seconds == 0) stringResource(R.string.set_crossfade_off) else stringResource(R.string.set_crossfade_on, seconds),
            style = MaterialTheme.typography.labelLarge, color = if (seconds == 0) c.text2 else c.accent,
        )
    }
    Slider(
        value = live ?: saved.seconds.toFloat(), onValueChange = { live = it },
        onValueChangeFinished = { live?.let { v -> ui.scope.launch { ui.g.settings.setCrossfade(saved.copy(seconds = v.roundToInt())) } } },
        valueRange = 0f..CrossfadeSettings.MAX_SECONDS.toFloat(), steps = CrossfadeSettings.MAX_SECONDS - 1,
        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.bg4),
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (seconds > 0) ToggleRow(stringResource(R.string.set_crossfade_albums), stringResource(R.string.set_crossfade_albums_text), saved.keepAlbums) {
        ui.scope.launch { ui.g.settings.setCrossfade(saved.copy(keepAlbums = it)) }
    }
}

@Composable
private fun GainRow() {
    val ui = LocalUi.current
    val mode by ui.g.settings.gainMode.collectAsState(initial = ReplayGain.Mode.OFF)
    SettingRow(stringResource(R.string.set_gain), stringResource(R.string.set_gain_text))
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ReplayGain.Mode.OFF to R.string.set_gain_off, ReplayGain.Mode.TRACK to R.string.set_gain_track, ReplayGain.Mode.ALBUM to R.string.set_gain_album).forEach { (m, label) ->
            Pill(stringResource(label), mode == m, onClick = { ui.scope.launch { ui.g.settings.setGainMode(m) } })
        }
    }
}

/** The settings both apps understand, in one file ([SettingsExchange]). */
@Composable
private fun SyncGroup() {
    val ui = LocalUi.current
    fun fail(e: Throwable) = ui.snack(if (e is SettingsExchange.NotSettings) ui.str(R.string.set_sync_bad) else ui.str(R.string.set_sync_failed, if (e is app.chudmusic.data.TooLarge) ui.str(R.string.file_too_large, (e.max / (1024 * 1024)).toInt()) else e.message ?: e.javaClass.simpleName))
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ui.scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = SettingsExchange.export(ui.g.settings, ui.g.db)
                    ui.context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: error("write")
                }
            }.onSuccess { ui.snack(ui.str(R.string.set_sync_exported)) }.onFailure(::fail)
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ui.scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ui.context.contentResolver.openInputStream(uri)?.use { it.readAtMost(5L * 1024 * 1024) } ?: error("read")
                    SettingsExchange.import(bytes.decodeToString(), ui.g.settings, ui.g.db)
                }
            }.onSuccess { ui.snack(ui.str(R.string.set_sync_imported)) }.onFailure(::fail)
        }
    }
    Group(stringResource(R.string.set_sync)) {
        SettingRow(stringResource(R.string.set_sync_export), stringResource(R.string.set_sync_export_text), onClick = { runCatching { export.launch("chud-music-settings.json") }.onFailure(::fail) })
        SettingRow(stringResource(R.string.set_sync_import), stringResource(R.string.set_sync_import_text), onClick = { runCatching { import.launch(arrayOf("*/*")) }.onFailure(::fail) })
    }
}

/** What went wrong with a network place, and what to do about it ([NetworkScanner]'s error codes). */
@Composable
private fun scanError(error: String): String = when (error) {
    NetworkScanner.ERROR_AUTH -> stringResource(R.string.net_err_auth)
    NetworkScanner.ERROR_UNREACHABLE -> stringResource(R.string.net_err_unreachable)
    NetworkScanner.ERROR_FOLDER -> stringResource(R.string.net_err_folder)
    else -> stringResource(R.string.net_err_other, error)
}

@Composable
fun SettingsScreen(importer: PlaylistImporter) {
    val ui = LocalUi.current
    val g = ui.g
    val c = Chud.colors
    val skin by g.settings.skin.collectAsState(initial = g.initialSkin)
    val sources by g.db.sources().flow().collectAsState(initial = emptyList())
    val scan by g.scanner.state.collectAsState()
    val hideSmall by g.settings.hideSmallAlbums.collectAsState(initial = false)
    val wifiOnly by g.settings.netWifiOnly.collectAsState(initial = true)
    val autoScan by g.settings.netAutoScan.collectAsState(initial = true)
    val opus by g.settings.ytPreferOpus.collectAsState(initial = true)
    val exclusions by g.db.sources().exclusionCount().collectAsState(initial = 0)
    val excludedFolders by g.db.sources().excludedFolders().collectAsState(initial = emptyList())
    val paused by g.settings.scanPaused.collectAsState(initial = emptySet())
    var restoring by remember { mutableStateOf(false) }
    val exportAll = rememberExportAll()

    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.section_settings), ui::back)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Group(stringResource(R.string.set_skin)) {
                    SkinRow(null, stringResource(R.string.skin_system), stringResource(R.string.skin_system_text), skin)
                    SkinRow(Skin.NOCTURNE, "Chud Nocturne", stringResource(R.string.skin_nocturne_text), skin)
                    SkinRow(Skin.PLATINE, "Platine", stringResource(R.string.skin_platine_text), skin)
                }
            }
            item { HomeGroup() }
            item { PlaybackGroup() }
            item {
                Group(stringResource(R.string.set_network)) {
                    Text(stringResource(R.string.set_network_text), style = MaterialTheme.typography.bodySmall, color = c.text1, modifier = Modifier.padding(16.dp))
                    sources.forEach { s ->
                        val running = (scan as? app.chudmusic.library.ScanState.Running)?.place(s.id)
                        SettingRow(
                            s.name,
                            when {
                                running != null -> stringResource(R.string.scan_running, s.name) + running.counter
                                s.lastError != null -> scanError(s.lastError)
                                s.lastScanAt != null -> tracksCount(s.trackCount) + " · " + stringResource(R.string.set_last_scan, whenText(s.lastScanAt))
                                else -> stringResource(R.string.net_never_scanned)
                            } + (if (s.id in paused && running == null) " · " + stringResource(R.string.net_paused) else "") + "\n" + (if (s.kind == NetKind.SMB) "SMB · \\\\${s.host}\\${s.share}" else "WebDAV · ${s.host}/${s.share.trim('/')}") +
                                (if (app.chudmusic.net.NetworkSources.passwordInClear(s)) " · ⚠ " + stringResource(R.string.net_http_short) else ""),
                            icon =if (s.lastError != null) Icons.Rounded.ErrorOutline else Icons.Rounded.Lan,
                            onClick = { ui.navigate(Routes.NetSource(s.id)) },
                        ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.text2) }
                    }
                    SettingRow(stringResource(R.string.net_add), stringResource(R.string.net_add_text), icon = Icons.Rounded.Add, onClick = { ui.navigate(Routes.NetSource()) })
                    if (scan.running) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.bg3)
                    ToggleRow(stringResource(R.string.net_auto_scan), stringResource(R.string.net_auto_scan_text), autoScan) {
                        ui.scope.launch { g.settings.setNetAutoScan(it); g.scanner.schedulePeriodic() }
                    }
                    ToggleRow(stringResource(R.string.net_wifi_only), stringResource(R.string.net_wifi_only_text), wifiOnly) {
                        ui.scope.launch { g.settings.setNetWifiOnly(it); g.scanner.schedulePeriodic() }
                    }
                }
            }
            item {
                Group(stringResource(R.string.set_phone)) {
                    SettingRow(
                        stringResource(R.string.set_folders),
                        if (excludedFolders.isEmpty()) stringResource(R.string.set_folders_all) else pluralStringResource(R.plurals.set_folders_excluded, excludedFolders.size, excludedFolders.size),
                        onClick = { ui.navigate(Routes.Folders) },
                    ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.text2) }
                    SettingRow(stringResource(R.string.set_rescan), stringResource(R.string.set_rescan_text), onClick = { if (!scan.running) g.scanner.scanAll(force = true) })
                    CoversRow()
                    ToggleRow(stringResource(R.string.set_hide_small), stringResource(R.string.set_hide_small_text), hideSmall) { ui.scope.launch { g.settings.setHideSmallAlbums(it) } }
                    if (exclusions > 0) SettingRow(pluralStringResource(R.plurals.lib_restore, exclusions, exclusions), stringResource(R.string.lib_restore_text), onClick = { restoring = true })
                }
            }
            item {
                Group(stringResource(R.string.nav_youtube)) {
                    SettingRow(stringResource(R.string.ab_title), stringResource(R.string.ab_text), icon = Icons.Rounded.CheckCircle) {
                        Text(stringResource(R.string.ab_active), style = MaterialTheme.typography.labelLarge, color = c.accent)
                    }
                    ToggleRow(stringResource(R.string.yt_opus), stringResource(R.string.yt_opus_text), opus) { ui.scope.launch { g.settings.setYtPreferOpus(it) } }
                    HiddenArtistsRow()
                    Text(stringResource(R.string.set_yt_text), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(16.dp))
                }
            }
            item {
                Group(stringResource(R.string.nav_playlists)) {
                    Text(stringResource(R.string.set_playlists_text), style = MaterialTheme.typography.bodySmall, color = c.text1, modifier = Modifier.padding(16.dp))
                    SettingRow(stringResource(R.string.set_import), onClick = { importer.pick() })
                    SettingRow(stringResource(R.string.set_export_all), onClick = exportAll)
                }
            }
            item { SyncGroup() }
            if (Build.VERSION.SDK_INT >= 33) item {
                Group(stringResource(R.string.set_language)) {
                    SettingRow(stringResource(R.string.set_language_label), stringResource(R.string.set_language_hint), onClick = {
                        runCatching {
                            ui.context.startActivity(Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${ui.context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                }
            }
            item { UpdatesGroup() }
            item {
                Group(stringResource(R.string.about)) {
                    SettingRow(stringResource(R.string.section_stats), onClick = { ui.navigate(Routes.Stats) })
                    SettingRow(stringResource(R.string.about_licenses), stringResource(R.string.about_licenses_text), onClick = { ui.navigate(Routes.About) })
                }
            }
        }
    }
    if (restoring) ConfirmDialog(
        title = pluralStringResource(R.plurals.lib_restore, exclusions, exclusions), text = stringResource(R.string.lib_restore_text), confirm = stringResource(R.string.lib_restore_do), danger = false,
        onDismiss = { restoring = false },
        onConfirm = { ui.scope.launch(Dispatchers.IO) { g.db.sources().clearExclusions(); g.scanner.scanAll(force = true) } },
    )
}

@Composable
private fun SkinRow(value: Skin?, title: String, text: String, current: Skin?) {
    val ui = LocalUi.current
    val c = Chud.colors
    SettingRow(title, text, onClick = { ui.scope.launch { ui.g.settings.setSkin(value) } }, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (value) {
                Skin.NOCTURNE -> Swatch(Nocturne)
                Skin.PLATINE -> Swatch(Platine)
                null -> { Swatch(Platine); Spacer(Modifier.width(4.dp)); Swatch(Nocturne) }
            }
            RadioButton(selected = current == value, onClick = { ui.scope.launch { ui.g.settings.setSkin(value) } }, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
        }
    })
}

@Composable
private fun Swatch(s: ChudColors) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(s.bg1).border(1.dp, s.lineStrong, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(s.accent))
    }
}

@Composable
private fun UpdatesGroup() {
    val ui = LocalUi.current
    val c = Chud.colors
    val up = ui.g.updater
    val state by up.state.collectAsState()
    val auto by ui.g.settings.updAuto.collectAsState(initial = true)
    val last by ui.g.settings.updLastCheck.collectAsState(initial = 0L)
    fun openReleases(url: String = up.releasesPage) = runCatching {
        ui.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Group(stringResource(R.string.upd_title)) {
        val status = when (val s = state) {
            Updater.State.Idle -> if (last > 0) stringResource(R.string.upd_up_to_date_when, up.currentVersion, whenText(last)) else stringResource(R.string.upd_version, up.currentVersion)
            Updater.State.Checking -> stringResource(R.string.upd_checking)
            is Updater.State.UpToDate -> stringResource(R.string.upd_up_to_date_when, up.currentVersion, whenText(s.checkedAt))
            is Updater.State.Available -> stringResource(R.string.upd_available, s.release.version, up.currentVersion)
            is Updater.State.Downloading -> stringResource(R.string.upd_downloading, s.release.version, (s.progress * 100).toInt())
            is Updater.State.Installing -> stringResource(R.string.upd_installing, s.release.version)
            is Updater.State.NeedsPermission -> stringResource(R.string.upd_needs_permission)
            is Updater.State.Failed -> stringResource(R.string.upd_error, s.message)
        }
        Text(stringResource(R.string.upd_text), style = MaterialTheme.typography.bodySmall, color = c.text1, modifier = Modifier.padding(16.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = c.text0, modifier = Modifier.padding(horizontal = 16.dp))
        (state as? Updater.State.Downloading)?.let {
            LinearProgressIndicator(progress = { it.progress }, Modifier.fillMaxWidth().padding(16.dp), color = c.accent, trackColor = c.bg3)
        }
        val release = when (val s = state) {
            is Updater.State.Available -> s.release
            is Updater.State.NeedsPermission -> s.release
            is Updater.State.Failed -> s.release
            else -> null
        }
        if (release != null && release.notes.isNotBlank() && state is Updater.State.Available) {
            val language = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language
            val notes = remember(release.notes, language) {
                androidx.compose.ui.text.buildAnnotatedString {
                    app.chudmusic.update.ReleaseNotes.lines(app.chudmusic.update.ReleaseNotes.select(release.notes, language).take(1500)).forEachIndexed { i, line ->
                        if (i > 0) append("\n")
                        line.forEach { span ->
                            if (span.bold) pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text0))
                            append(span.text)
                            if (span.bold) pop()
                        }
                    }
                }
            }
            Text(notes, style = MaterialTheme.typography.bodySmall, color = c.text1, maxLines = 18, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is Updater.State.Available, is Updater.State.Failed -> if (release != null) {
                    if (release.apkUrl != null) Button(onClick = { up.downloadAndInstall(release) }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk)) {
                        Text(stringResource(R.string.upd_update))
                    }
                    OutlinedButton(onClick = { openReleases(release.pageUrl) }) { Text(stringResource(R.string.upd_download_github)) }
                } else OutlinedButton(onClick = { ui.scope.launch { up.check() } }) { Text(stringResource(R.string.upd_check)) }
                is Updater.State.NeedsPermission -> {
                    Button(onClick = { runCatching { ui.context.startActivity(up.permissionIntent()) } }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk)) {
                        Text(stringResource(R.string.upd_allow))
                    }
                    TextButton(onClick = { release?.let { up.downloadAndInstall(it) } }) { Text(stringResource(R.string.retry)) }
                }
                is Updater.State.Downloading, is Updater.State.Installing, Updater.State.Checking -> {}
                else -> {
                    OutlinedButton(onClick = { ui.scope.launch { up.check() } }) { Text(stringResource(R.string.upd_check)) }
                    TextButton(onClick = { openReleases() }) { Text(stringResource(R.string.upd_releases)) }
                }
            }
        }
        ToggleRow(stringResource(R.string.upd_auto), stringResource(R.string.upd_auto_text), auto) { ui.scope.launch { ui.g.settings.setUpdAuto(it) } }
        Spacer(Modifier.height(4.dp))
    }
}
