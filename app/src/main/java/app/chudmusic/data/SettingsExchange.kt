package app.chudmusic.data

import app.chudmusic.audio.CrossfadeSettings
import app.chudmusic.audio.ReplayGain
import app.chudmusic.youtube.HiddenArtist
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The settings that mean the same thing on Android and on PC, in one file (docs/EXCHANGE.md):
 * `format: "chud-music-settings"`, version 1. Every part is optional, unknown ones are ignored.
 *
 * Never in it: passwords, server addresses, paths, nor anything that belongs to one device.
 */
object SettingsExchange {
    const val FORMAT = "chud-music-settings"

    @Serializable
    data class Equalizer(val enabled: Boolean = false, val preampDb: Float = 0f, val gains: List<Float> = emptyList())

    @Serializable
    data class Preset(val name: String, val preampDb: Float = 0f, val gains: List<Float> = emptyList())

    @Serializable
    data class Station(
        val uuid: String,
        val name: String,
        val url: String,
        val favicon: String? = null,
        val country: String? = null,
        val tags: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        val hls: Boolean = false,
    )

    @Serializable
    data class File(
        val format: String,
        val version: Int = 1,
        val exportedAt: String? = null,
        val equalizer: Equalizer? = null,
        val eqPresets: List<Preset>? = null,
        val crossfadeSec: Int? = null,
        /** off, track or album. */
        val replayGain: String? = null,
        val hiddenArtists: List<HiddenArtist>? = null,
        val radioFavorites: List<Station>? = null,
    )

    class NotSettings : Exception("not a Chud Music settings file")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }

    fun toJson(f: File): String = json.encodeToString(File.serializer(), f)

    fun parse(text: String): File {
        val f = runCatching { json.decodeFromString(File.serializer(), text.removePrefix("﻿")) }.getOrNull()
        if (f == null || f.format != FORMAT) throw NotSettings()
        return f
    }

    /** 10 finite gains within the equalizer's range, whatever the file says; null when it is not one. */
    private fun gains(l: List<Float>): List<Float>? = l.takeIf { it.size == 10 && it.all(Float::isFinite) }?.map { it.coerceIn(-12f, 12f) }

    private fun preamp(v: Float) = if (v.isFinite()) v.coerceIn(-12f, 12f) else 0f

    fun presets(f: File): List<EqPreset> = f.eqPresets.orEmpty().mapNotNull { p ->
        gains(p.gains)?.let { EqPreset(p.name.trim().take(60), preamp(p.preampDb), it) }
    }.filter { it.name.isNotEmpty() }

    fun equalizer(f: File): EqState? = f.equalizer?.let { e -> gains(e.gains)?.let { EqState(e.enabled, preamp(e.preampDb), it, preset = null) } }

    fun gainMode(f: File): ReplayGain.Mode? = f.replayGain?.let { v -> ReplayGain.Mode.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }

    /** Only a station that can be played: http(s), with a name. */
    fun stations(f: File): List<RadioStationEntity> = f.radioFavorites.orEmpty()
        .filter { it.uuid.isNotBlank() && it.name.isNotBlank() && (it.url.startsWith("http://") || it.url.startsWith("https://")) }
        .map { RadioStationEntity(it.uuid, it.name, it.url, it.favicon, it.country, it.tags, it.codec, it.bitrate, it.hls, favorite = true) }

    suspend fun export(settings: Settings, db: ChudDatabase): String {
        val eq = settings.eqNow()
        return toJson(
            File(
                FORMAT, 1, Instant.now().toString(),
                equalizer = Equalizer(eq.enabled, eq.preampDb, eq.bandsDb),
                eqPresets = settings.eqUserPresets.first().map { Preset(it.name, it.preampDb, it.bandsDb) },
                crossfadeSec = settings.crossfade.first().seconds,
                replayGain = settings.gainMode.first().name.lowercase(),
                hiddenArtists = settings.hiddenArtists.first().list,
                radioFavorites = db.radio().favorites().first().map { Station(it.uuid, it.name, it.url, it.favicon, it.country, it.tags, it.codec, it.bitrate, it.hls) },
            ),
        )
    }

    /** Lists are added to what is there; single settings replace theirs. */
    suspend fun import(text: String, settings: Settings, db: ChudDatabase) {
        val f = parse(text)
        equalizer(f)?.let { settings.setEq(it) }
        val presets = presets(f)
        if (presets.isNotEmpty()) {
            val names = presets.mapTo(HashSet()) { it.name }
            settings.setEqUserPresets(settings.eqUserPresets.first().filter { it.name !in names } + presets)
        }
        f.crossfadeSec?.let { settings.setCrossfade(settings.crossfade.first().copy(seconds = it.coerceIn(0, CrossfadeSettings.MAX_SECONDS))) }
        gainMode(f)?.let { settings.setGainMode(it) }
        f.hiddenArtists.orEmpty().filter { it.name.isNotBlank() }.forEach { settings.hideArtist(it) }
        // A station already known keeps what this device knows of it (last played).
        stations(f).forEach { s -> if (db.radio().get(s.uuid) != null) db.radio().setFavorite(s.uuid, true) else db.radio().upsert(s) }
    }
}
