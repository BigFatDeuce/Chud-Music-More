package app.chudmusic.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import app.chudmusic.BuildConfig
import app.chudmusic.data.Settings
import app.chudmusic.graph
import app.chudmusic.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Looks for a newer version on the GitHub releases of [BuildConfig.RELEASES_REPO], downloads the
 * APK and hands it to the system installer (the user confirms, Android checks the signature is
 * the same). If anything goes wrong, the release page link is always offered instead.
 *
 * State machine ([State], see docs/STATE-MACHINES.md):
 *
 *     Idle ──check()──▶ Checking ──▶ UpToDate | Available | Failed(release = null)
 *     Available ──downloadAndInstall()──▶ NeedsPermission | Downloading ──▶ Installing ──▶ (app replaced)
 *     Downloading / Installing ──error──▶ Failed(release)      Installing ──user cancels──▶ Available
 *
 * A found release is remembered across restarts ([Settings.updAvailable]): the app comes back in
 * Available, not in Idle, until that version (or a newer one) is installed.
 */
class Updater(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    @Serializable
    data class Release(
        val version: String,
        val name: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?,
        val apkSize: Long,
        val sha256: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAt: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Installing(val release: Release) : State
        /** The user must allow "install unknown apps" for Chud Music first. */
        data class NeedsPermission(val release: Release) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME.removeSuffix("-debug")
    val releasesPage get() = "https://github.com/${BuildConfig.RELEASES_REPO}/releases"

    @Serializable private data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0, val digest: String? = null)
    @Serializable private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch { restore() }
    }

    /** Back in Available after a restart, if the release found earlier is still newer than this version. */
    private suspend fun restore() {
        val saved = settings.updAvailable()?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }
        when {
            saved == null -> {}
            compareVersions(saved.version, currentVersion) > 0 -> _state.compareAndSet(State.Idle, State.Available(saved))
            else -> settings.setUpdAvailable(null)
        }
    }

    private suspend fun remember(state: State) {
        when (state) {
            is State.Available -> settings.setUpdAvailable(json.encodeToString(Release.serializer(), state.release))
            is State.UpToDate -> settings.setUpdAvailable(null)
            else -> {}
        }
    }

    /** Once a day at most, if the user left it on. */
    fun autoCheck() {
        scope.launch {
            if (!settings.updAuto.first()) return@launch
            if (System.currentTimeMillis() - settings.updLastCheck.first() < 20 * 3600_000L) return@launch
            check()
        }
    }

    suspend fun check(): State = withContext(Dispatchers.IO) {
        // A check never interrupts a download or an installation in progress.
        _state.value.let { if (it is State.Downloading || it is State.Installing) return@withContext it }
        val before = _state.value
        _state.value = State.Checking
        val result = try {
            val text = Http.getText(
                BuildConfig.UPDATE_API,
                mapOf("Accept" to "application/vnd.github+json", "User-Agent" to Http.APP_UA),
            )
            val r = json.decodeFromString(GhRelease.serializer(), text)
            settings.setUpdLastCheck(System.currentTimeMillis())
            val version = r.tag.trimStart('v', 'V')
            if (compareVersions(version, currentVersion) > 0) {
                val apk = r.assets.filter { it.name.endsWith(".apk", true) }.let { l -> l.firstOrNull { !it.name.contains("debug", true) } ?: l.firstOrNull() }
                State.Available(
                    Release(version, r.name?.ifBlank { null } ?: r.tag, r.body.orEmpty().trim(), r.htmlUrl, apk?.url, apk?.size ?: 0,
                        apk?.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")),
                )
            } else State.UpToDate(System.currentTimeMillis())
        } catch (e: app.chudmusic.net.HttpException) {
            // 404: no release published yet.
            if (e.code == 404) { settings.setUpdLastCheck(System.currentTimeMillis()); State.UpToDate(System.currentTimeMillis()) }
            else State.Failed(e.message ?: "GitHub", null)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            State.Failed(e.message ?: e.javaClass.simpleName, null)
        }
        // Offline: a release already found stays offered instead of being replaced by the error.
        val next = if (result is State.Failed && before is State.Available) before else result
        remember(next)
        _state.value = next
        next
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads then installs [r]. */
    fun downloadAndInstall(r: Release) {
        if (r.apkUrl == null) { _state.value = State.Failed("no APK", r); return }
        if (!canInstall()) { _state.value = State.NeedsPermission(r); return }
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val file = download(r)
                _state.value = State.Installing(r)
                install(file)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName, r)
            }
        }
    }

    private fun download(r: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "chud-music-${r.version}.apk")
        val md = MessageDigest.getInstance("SHA-256")
        Http.client.newCall(Request.Builder().url(r.apkUrl!!).header("User-Agent", Http.APP_UA).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw app.chudmusic.net.HttpException(resp.code, r.apkUrl)
            val total = resp.body.contentLength().takeIf { it > 0 } ?: r.apkSize
            resp.body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - lastEmit > 256 * 1024) {
                            lastEmit = done
                            _state.value = State.Downloading(r, if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f)
                        }
                    }
                }
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (r.sha256 != null && !hex.equals(r.sha256, ignoreCase = true)) {
            out.delete()
            throw SecurityException("SHA-256")
        }
        return out
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val r = (state.value as? State.Installing)?.release
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) (r?.let { State.Available(it) } ?: State.Idle)
            else State.Failed(message ?: "install ($status)", r)
        }
    }

    companion object {
        /** "1.10.0" > "1.9.2"; a pre-release suffix ("-beta") counts as lower than the same number. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            val sa = a.contains('-'); val sb = b.contains('-')
            return when { sa == sb -> 0; sa -> -1; else -> 1 }
        }
    }
}

/** The system installer's answer: asks the user to confirm, or reports a failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
        context.graph.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
