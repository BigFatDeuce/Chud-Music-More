# Working on Chud Music for Android

For people and coding agents. Read this first; it is short on purpose.

## What this is

A music player for Android: files on the phone, SMB and WebDAV shares, YouTube Music without ads
(NewPipe Extractor + ExoPlayer), Internet radio. Kotlin, Jetpack Compose, Media3, Room. One Gradle
module, `app/`. No dependency-injection framework: `AppGraph` in `ChudApp.kt` builds everything once.

The desktop app (Electron, another repository) is the reference for behaviour and for the
playlist exchange format. **Never modify it from here.**

## Rules that are not negotiable

- The app **never writes, moves or deletes a music file**, on the phone or on a share.
- `keystore/` holds the release signing key. Never print, copy, commit, archive or regenerate it.
- Nothing is published (GitHub release, pages) without the owner saying so.
- User-visible text is in `res/values/strings.xml` (French, the default) **and**
  `res/values-en/strings.xml`, same keys in both. No hard-coded text in Kotlin.
- Code, comments and commit messages are in English.

## Commands

Git Bash, from the project root. The JDK is local to the project.

```bash
export JAVA_HOME="$PWD/.tools/jdk-21.0.13+9"
```

| Goal | Command |
|---|---|
| Unit tests (seconds, no phone) | `./gradlew testDebugUnitTest` |
| + YouTube Music, LRCLIB, Radio Browser for real | add `-Pchud.online=true` |
| + a real SMB server | add `-Pchud.smb=host:port/share -Pchud.smbUser=… -Pchud.smbPassword=…` |
| Debug APK (`app.chudmusic.debug`, installs beside the release) | `./gradlew assembleDebug` |
| Signed, minified release APK | `./gradlew assembleRelease` |
| Release files in `release/` (APK, sources zip, checksums) | `python tools/package-release.py` |
| Preview what would go to the GitHub page | `python tools/publish-pages.py` |

A change is not done until `testDebugUnitTest` passes **and** it was seen working in the app
(emulator or phone). R8 changes behaviour: anything touching reflection, a new library or
`proguard-rules.pro` must also be checked in the **release** build.

## Map

```
app/src/main/java/app/chudmusic/
  ChudApp.kt, MainActivity.kt   the app graph, the single activity
  model/      Track
  data/       Room database, DAOs, Settings (DataStore)
  library/    Scanner (phone + network), Library snapshot (albums, artists, random pick)
  net/        RemoteFs: SmbFs, WebDavFs; NetworkSources; Discovery (network search, share list)
  playback/   PlaybackService (ExoPlayer + MediaSession), PlayerConnection (UI side), data source
  youtube/    InnerTube (search, pages), YouTubeStreams (audio URLs through NewPipe Extractor)
  playlists/  Playlists, PlaylistExchange (JSON and M3U8 shared with the desktop app)
  radio/ lyrics/ stats/ audio/ update/
  ui/         Root (navigation, mini player), one folder per screen, theme, shared components
app/src/test/                   unit tests (pure logic; online and SMB tests are opt-in)
docs/                           user and developer documentation (English), docs/fr (French)
licenses/                       licence texts, shipped in the APK (Settings › Licences)
tools/                          release packaging, GitHub page publishing
```

## State machines

Read [docs/STATE-MACHINES.md](docs/STATE-MACHINES.md) before touching anything that loads,
connects, retries or asks a permission. In short: such state is **one sealed type, changed in
one place, with its transition rule in a pure function that has a unit test**; UI reads it with an
exhaustive `when`; a late answer never overwrites a newer question. New asynchronous feature →
new row in that file's index.

## Conventions

- Comments say why, or what a non-obvious thing is for. Not what the next line does.
- A bug fixed gets the smallest test that would have caught it, when a pure function can express it.
- Network code never trusts the server: every listener and parser is wrapped; a failure becomes a
  message for the user (`RemoteUnreachable`, `RemoteAuthFailed`), never a crash.
- A new library: add its licence text to `licenses/` and its row to the list in
  `ui/settings/OtherScreens.kt`; check the release build.
- Version: only `versionName` in `app/build.gradle.kts`; `versionCode` is derived from it.

## Releasing

[docs/BUILDING.md](docs/BUILDING.md) has the full checklist. Release notes are written in English;
a French translation goes in an HTML comment (`<!-- lang:fr … -->`) that the app shows to French
users and GitHub hides.
