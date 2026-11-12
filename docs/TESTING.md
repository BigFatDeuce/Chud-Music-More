# What was tested, what was not, known limits

*[Version française](fr/TESTS.md)*

State on 12 November 2026, version 0.2.0. Everything below was done on the **Android 16
emulator (API 36)**, with the debug build and with the signed, minified release build. The
author's test servers were Samba and rclone (WebDAV) running in WSL.

## Checked automatically

`./gradlew testDebugUnitTest`: 42 unit tests, of which 9 only run on request (5 against the
Internet services, 4 against an SMB server). All pass.

- Playlist exchange with the PC app (JSON and M3U8, both ways, path matching).
- Equalizer maths, synced lyrics parsing, version order.
- The state machines listed in [STATE-MACHINES.md](STATE-MACHINES.md): player phase, playback
  errors, scan, YouTube search paging, network search, permission, random draw, release notes.
- SMB against a real server: share list without hidden shares, wrong password, reading after
  40 s of silence, a connection cut by the server.

## Checked by hand on the emulator

**Phone and network**
- Scan of the phone's music (tags, embedded covers; FLAC, MP3, M4A).
- **Search the network**: the sweep finds the SMB server; a server that refuses guests asks for an
  account; with an account, its shares are listed; picking one tests it, opens the folder
  browser, saves, scans and plays. Same in the release build.
- SMB (user and password) and WebDAV (HTTP, read-only): test, scan, playback (FLAC included).
- An SMB track starts within seconds after the connection sat idle for 50 s.
- No music file is modified or deleted: the test servers were read-only.

**YouTube Music**
- Search, suggestions, the four filters, further pages while scrolling.
- Playback without ads (Opus, the default; the AAC option was not tried on its own).
- Album and artist pages, "Start mix", a `music.youtube.com` link shared to the app.

**Playlists and the PC app**
- Import of a JSON in the PC's format: Windows path → phone file; `\\10.0.2.2\Musique\…` → SMB
  share; `\\NAS-SALON\…` → found by the end of the path; tags only → found by title, artist and
  duration; missing file → kept, greyed; YouTube Music tracks imported.
- M3U8 import; opening an `.m3u8` from a file manager.
- JSON and M3U8 exports: compared with the PC app's format and import code.

**Home, radio, player, statistics**
- The Random shelf: same draw until redrawn, plays from the tapped track.
- Radio: MP3 and AAC stations, favourites, recents; opens on Favourites when there are some.
- Equalizer, synced lyrics, shuffle, repeat, queue, queue restored after the app is stopped.
- Statistics: every section; copies of the same track are counted together.

**Permissions and updates**
- Music permission: refused once (asked again), refused twice (the card offers Android's
  settings), allowed in the settings (seen when coming back, scan starts).
- Updates, with the real GitHub release 0.1.0: found from an older test build, downloaded,
  SHA-256 checked, installed over it, data kept. A version found is still offered after a restart;
  the dot on Home's gear; release notes with bold text and bullets.
- 0.1.0 → 0.2.0, both as published on GitHub: the app found the new version by itself at
  start-up, downloaded it and installed it; the playlist was kept.

## Not checked

- A **real phone** (Bluetooth headset or car included) and **Android Auto**.
- A real **NAS**, a Windows share with an account, **Nextcloud** over HTTPS.
- Network search on a real Wi-Fi: **server names** (NetBIOS) and servers that **announce
  themselves** (mDNS, which is how WebDAV servers are found) cannot be seen from the emulator.
  Only the address sweep was exercised.
- Playback resuming after the phone **restarts**.
- Importing, in the **PC app** itself, the files exported by the phone.

## Known limits

- **WMA** is not played by Android: the track is skipped with a message.
- The network search looks at the 254 addresses around the phone's own; a server elsewhere on a
  large network must be typed by hand. A VPN on the phone hides the local network.
- Listing a server's shares needs an account on most Windows PCs (guests are refused).
- After an update the app closes (Android replaces it): open it again.
- Importing the same file twice makes two playlists, as on the PC.
- YouTube changes how it works now and then: if YouTube Music stops playing, a new version of
  NewPipe Extractor (hence of Chud Music) is needed.

## Cleaning the author's test environment

The test servers ran in the WSL distribution `FedoraLinux-45`. To remove everything, once they
are stopped:

```bash
wsl -d FedoraLinux-45 -u root -- dnf remove -y samba rclone
```

```bash
wsl -d FedoraLinux-45 -u root -- userdel chudtest
```

```bash
wsl -d FedoraLinux-45 -u root -- rm -rf /srv/chudtest
```

`/srv/chudtest` only holds generated test files, no real music.
