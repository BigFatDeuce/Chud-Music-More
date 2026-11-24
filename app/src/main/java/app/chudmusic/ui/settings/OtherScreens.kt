package app.chudmusic.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.chudmusic.BuildConfig
import app.chudmusic.R
import app.chudmusic.data.ExcludedFolderEntity
import app.chudmusic.library.LocalScanner
import app.chudmusic.ui.LocalUi
import app.chudmusic.ui.components.BackBar
import app.chudmusic.ui.components.EmptyState
import app.chudmusic.ui.components.Loading
import app.chudmusic.ui.theme.Chud
import app.chudmusic.ui.tracksCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The phone's folders that hold music; unticking one leaves it out of the library (nothing is deleted). */
@Composable
fun FoldersScreen() {
    val ui = LocalUi.current
    val c = Chud.colors
    var folders by remember { mutableStateOf<List<LocalScanner.Folder>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { folders = withContext(Dispatchers.IO) { ui.g.scanner.local.folders() } }
    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.set_folders), ui::back)
        Text(stringResource(R.string.set_folders_text), style = MaterialTheme.typography.bodySmall, color = c.text1, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        val list = folders
        when {
            list == null -> Loading()
            list.isEmpty() -> EmptyState(Icons.Rounded.Folder, stringResource(R.string.set_folders_none), null)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.path }) { f ->
                    fun toggle() {
                        ui.scope.launch(Dispatchers.IO) {
                            if (f.excluded) ui.g.db.sources().includeFolder(f.path) else ui.g.db.sources().excludeFolder(ExcludedFolderEntity(f.path))
                            withContext(Dispatchers.Main) { version++ }
                            ui.g.scanner.request(app.chudmusic.library.ScanRequest(local = true, network = false))
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(!f.excluded, { toggle() }, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.accentInk))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.path.substringAfterLast('/').ifEmpty { "/" }, style = MaterialTheme.typography.bodyLarge, color = if (f.excluded) c.text2 else c.text0)
                            Text(f.path + " · " + tracksCount(f.count), style = MaterialTheme.typography.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** One credited work; [file] is its licence text in the APK assets (root licenses/ folder), null for an online service. */
private data class Lib(val name: String, val licence: String?, val url: String, val file: String?)

private const val APACHE = "Apache-2.0.txt"

private val libs = listOf(
    Lib("Chud Music", "GPL-3.0", "https://github.com/BigFatDeuce/Chud-Music-More", "GPL-3.0.txt"),
    Lib("NewPipe Extractor", "GPL-3.0", "https://github.com/TeamNewPipe/NewPipeExtractor", "GPL-3.0.txt"),
    Lib("Mozilla Rhino (NewPipe)", "MPL-2.0", "https://github.com/mozilla/rhino", "MPL-2.0-Rhino.txt"),
    Lib("AndroidX Media3 (ExoPlayer)", "Apache-2.0", "https://github.com/androidx/media", APACHE),
    Lib("Jetpack Compose, Room, DataStore, WorkManager, Navigation", "Apache-2.0", "https://developer.android.com/jetpack", APACHE),
    Lib("Kotlin, kotlinx.coroutines, kotlinx.serialization", "Apache-2.0", "https://kotlinlang.org", APACHE),
    Lib("OkHttp, Okio", "Apache-2.0", "https://square.github.io/okhttp/", APACHE),
    Lib("Coil", "Apache-2.0", "https://coil-kt.github.io/coil/", APACHE),
    Lib("SMBJ, ASN-One", "Apache-2.0", "https://github.com/hierynomus/smbj", APACHE),
    Lib("smbj-rpc", "BSD-3-Clause", "https://github.com/rapid7/smbj-rpc", "BSD-3-smbj-rpc.txt"),
    Lib("Apache Commons IO, Commons Lang", "Apache-2.0", "https://commons.apache.org", APACHE),
    Lib("Bouncy Castle", "MIT", "https://www.bouncycastle.org", "MIT-BouncyCastle.txt"),
    Lib("Reorderable", "Apache-2.0", "https://github.com/Calvin-LL/Reorderable", APACHE),
    Lib("Accompanist", "Apache-2.0", "https://github.com/google/accompanist", APACHE),
    Lib("Guava, Error Prone, J2ObjC, JSR-305 (annotations)", "Apache-2.0", "https://github.com/google/guava", APACHE),
    Lib("jsoup", "MIT", "https://jsoup.org", "MIT-jsoup.txt"),
    Lib("nanojson", "Apache-2.0 / MIT", "https://github.com/mmastrac/nanojson", APACHE),
    Lib("SLF4J", "MIT", "https://www.slf4j.org", "MIT-SLF4J.txt"),
    Lib("MBassador", "MIT", "https://github.com/bennidi/mbassador", "MIT-MBassador.txt"),
    Lib("Checker Framework qualifiers", "MIT", "https://checkerframework.org", "MIT-CheckerQual.txt"),
    Lib("Protocol Buffers (lite)", "BSD-3-Clause", "https://github.com/protocolbuffers/protobuf", "BSD-3-Protobuf.txt"),
    Lib("Inter", "SIL OFL 1.1", "https://github.com/rsms/inter", "OFL-Inter.txt"),
    Lib("Space Grotesk", "SIL OFL 1.1", "https://github.com/floriankarsten/space-grotesk", "OFL-SpaceGrotesk.txt"),
    Lib("VT323", "SIL OFL 1.1", "https://fonts.google.com/specimen/VT323", "OFL-VT323.txt"),
    Lib("LRCLIB", null, "https://lrclib.net", null),
    Lib("Radio Browser", null, "https://www.radio-browser.info", null),
)

@Composable
fun AboutScreen() {
    val ui = LocalUi.current
    val c = Chud.colors
    fun open(url: String) = runCatching { ui.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    var shown by remember { mutableStateOf<Lib?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackBar(stringResource(R.string.about_licenses), ui::back)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(20.dp)) {
                    Text("Chud Music ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall, color = c.text0)
                    Text(stringResource(R.string.about_gpl), style = MaterialTheme.typography.bodyMedium, color = c.text1, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        stringResource(R.string.about_source), style = MaterialTheme.typography.bodyMedium, color = c.accent,
                        modifier = Modifier.padding(top = 8.dp).clickable { open(ui.g.updater.releasesPage) },
                    )
                    Text(stringResource(R.string.about_youtube), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(top = 12.dp))
                    Text(stringResource(R.string.about_tap), style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.padding(top = 12.dp))
                }
            }
            items(libs) { l ->
                Column(Modifier.fillMaxWidth().clickable { if (l.file != null) shown = l else open(l.url) }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge, color = c.text0)
                    Text(l.licence ?: stringResource(R.string.about_service), style = MaterialTheme.typography.bodySmall, color = c.text1)
                }
            }
        }
    }
    shown?.let { l -> LicenceDialog(l, onSite = { open(l.url) }, onDismiss = { shown = null }) }
}

/** The full licence text of [lib], read from the APK assets. */
@Composable
private fun LicenceDialog(lib: Lib, onSite: () -> Unit, onDismiss: () -> Unit) {
    val ui = LocalUi.current
    val c = Chud.colors
    var text by remember(lib) { mutableStateOf<String?>(null) }
    LaunchedEffect(lib) {
        text = withContext(Dispatchers.IO) { runCatching { reflow(ui.context.assets.open(lib.file!!).use { it.readBytes().toString(Charsets.UTF_8) }) }.getOrElse { "" } }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.bg1,
        title = { Text(lib.name, color = c.text0) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(lib.licence.orEmpty(), style = MaterialTheme.typography.labelLarge, color = c.accent)
                Text(
                    text ?: "", style = MaterialTheme.typography.bodySmall,
                    color = c.text1, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), color = c.accent) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onSite) { Text(stringResource(R.string.about_site), color = c.text1) } },
    )
}

private val listItem = Regex("""^(\(?[a-zA-Z0-9]{1,3}[.)]|[*\-•]|[=\-]{3,}$)""")
private val paraBreak = Regex("""\n[ \t]*\n""")

/** Licence files are hard-wrapped at ~72 columns: join the lines of each paragraph so they wrap to the screen, keeping list items on their own line. */
private fun reflow(text: String): String =
    text.replace("\r", "").split(paraBreak).joinToString("\n\n") { para ->
        val lines = para.lines().map { it.trim() }.filter { it.isNotEmpty() }
        buildString {
            lines.forEachIndexed { i, l ->
                if (i > 0) append(if (listItem.containsMatchIn(l)) "\n" else " ")
                append(l)
            }
        }
    }.trim()
