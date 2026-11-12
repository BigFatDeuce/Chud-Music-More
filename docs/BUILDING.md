# Building, signing and releasing

*[Version française](fr/COMPILER-ET-PUBLIER.md)*

The source code of each version is the `ChudMusic-<version>-sources.zip` attached to its
[release](https://github.com/BigFatDeuce/Chud-Music-More/releases). Unpack it and build from there.

## Requirements

- **JDK 21** (Temurin or another).
- The **Android SDK** with platform 37 and build-tools 36 (Android Studio installs them, or
  `sdkmanager`). Tell Gradle where it is with `ANDROID_HOME`, or in `local.properties`:
  `sdk.dir=/path/to/Android/Sdk`.
- Gradle itself is not needed: the `gradlew` script downloads the right version (9.9.1).

Versions in use: Kotlin 2.4, Android Gradle Plugin 9.5, Compose Material 3, Media3 1.11, NewPipe
Extractor 0.27, smbj 0.15. `minSdk` 26 (Android 8.0), `targetSdk` 36.

## Build

```bash
./gradlew assembleDebug
```

gives `app/build/outputs/apk/debug/app-debug.apk`. The debug build installs beside the real app
(`app.chudmusic.debug`), with its own data.

```bash
./gradlew assembleRelease
```

gives a minified APK. Without the project's signing key it is `app-release-unsigned.apk`: sign it
with your own key (`apksigner sign`). It cannot be installed over the official app, which is
signed with another key.

## Tests

```bash
./gradlew testDebugUnitTest
```

runs the unit tests (playlist exchange, equalizer maths, lyrics, version order, state machines)
in seconds, without a phone. Two families only run on request:

| Tests | How to switch them on |
|---|---|
| YouTube Music, LRCLIB, Radio Browser, for real | `-Pchud.online=true` |
| An SMB server: share list, wrong password, reading after an idle period, a dropped connection | `-Pchud.smb=host:port/share -Pchud.smbUser=user -Pchud.smbPassword=password` |

The SMB tests only read; the share needs at least one file.

## The signing key (maintainer)

Android installs an update **only if it is signed with the same key** as the installed version.

- File: `keystore/chud-release.jks` (PKCS12, RSA 4096, alias `chudmusic`, valid 30 years).
- Passwords: `keystore/release.properties` (`storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`), read by `app/build.gradle.kts`.
- SHA-256 of the certificate:
  `bc:c8:26:50:be:ad:89:93:51:25:90:ac:65:f8:17:25:30:f3:7f:3f:e8:58:88:00:08:ce:39:07:54:8b:2a:5c`

Keep two copies of `keystore/` outside the computer. Never regenerate the key, never publish it:
the folder is excluded by `.gitignore` and by the sources archive script. If the key is lost, new
versions can no longer be installed over old ones.

## Releasing a version (maintainer)

1. **Version number**: in `app/build.gradle.kts`, change the number in
   `versionName = … ?: "0.2.0"`. `versionCode` is derived from it (0.3.0 → 300), so it always grows.

2. **Build and test:**

   ```bash
   ./gradlew clean testDebugUnitTest assembleRelease
   ```

3. **Check the signature** (it must print the fingerprint above):

   ```bash
   "$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

4. **Prepare the release files:**

   ```bash
   python tools/package-release.py
   ```

   `release/` then holds `ChudMusic-X.Y.Z.apk`, `ChudMusic-X.Y.Z-sources.zip` (the complete source
   code, **without** the key, build outputs or local tools; the script stops if it finds a key in
   it) and `SHA256SUMS.txt`.

5. **Try** the APK on a phone or the emulator, installed over the previous version (playlists and
   settings must be kept).

6. **Create the release on GitHub**: tag `vX.Y.Z` (the version number with a `v`), title
   `Chud Music X.Y.Z`, the three files attached, neither *draft* nor *pre-release*.

   The description is **shown in the app** when the update is offered. Write it in English. To
   give French users a French text, add it in a comment that GitHub hides:

   ```
   What's new…

   <!-- lang:fr
   Nouveautés…
   -->
   ```

7. **Update the GitHub page** (README, documentation, screenshots, licences — not the code):

   ```bash
   python tools/publish-pages.py
   ```

   shows what would change; with `--push` it is sent to the `main` branch.

## How the app finds updates

It asks `https://api.github.com/repos/BigFatDeuce/Chud-Music-More/releases/latest` (at most once
a day if the option is on, or on request), compares the tag with the installed version and takes
the first attached `.apk` whose name does not contain `debug`. When GitHub gives the file's
SHA-256, the download is checked before installing. Nothing is installed until the user taps
"Update" and confirms in Android's own dialog. A version found is remembered until it is installed.

To test this without publishing, two build properties exist: `-PchudVersionName=0.0.9` (pretend
to be an old version) and `-PchudUpdateApi=http://10.0.2.2:8000/latest.json` (a fake local
server, as seen from the emulator).

## Translations

The interface texts are in `app/src/main/res/values/strings.xml` (French, the default) and
`values-en/strings.xml` (English); both files are the reference and carry the same keys. For a
new language: copy the file to `values-<code>/`, translate it, and add the code to
`localeFilters` in `app/build.gradle.kts`.
