# User guide

*[Version française](fr/GUIDE.md)*

## First launch

1. Chud Music asks for access to the **music on the phone** and for permission to show a
   **notification** (the playback controls). Both are optional: without the first one, only
   network shares, YouTube Music and the radio are available.
2. The library fills in a few seconds. Covers are the ones embedded in the files; on network
   shares, the album folder's picture is used too.

The five tabs at the bottom: **Home**, **Library**, **YouTube**, **Playlists**, **Radio**. The
mini player above them opens the full player.

## Home

- **Recently played**, then **Random**: 18 tracks of your library drawn when the app starts. The
  draw stays the same while the app runs; the ⟳ button draws others.
- Your playlists, recently added albums, favourite stations.
- A dot on the gear means a new version is waiting in Settings › Updates.

## Music on the phone

- **Settings › Phone music › Folders**: untick a folder (ringtones, voice notes…) to leave it out
  of the library. Nothing is deleted.
- **Rescan** reads the phone and the network shares again right away.
- "Remove from library" hides a track; it comes back at the next scan.
- A scan stopped halfway (app closed, phone switched off) goes on where it stopped at the next
  start: what was already read is kept. The same goes for network shares.
- If access to the music was refused twice, Android stops asking: Home then offers **Open
  settings**, where you allow "Music and audio".

## Network shares (SMB and WebDAV)

**Settings › Network shares › Add a network share.**

The easy way: **Search the network**. Chud Music looks for the PCs and NAS switched on on your
Wi-Fi (a few seconds) and lists them. Tap a server: its shared folders appear (if it asks for an
account, fill in "User" and "Password", then tap "List shares"). Tap a share: the connection is
tested and you can pick a sub-folder. Then **Save**.

The search only opens a connection to each address of the local network and closes it: nothing
is read or written on the servers. You can also type everything by hand:

| Field | SMB (Windows PC, NAS, Samba) | WebDAV (Nextcloud…) |
|---|---|---|
| Server | name or IP of the PC/NAS, e.g. `192.168.1.20` | host, e.g. `cloud.example.com` |
| Share / path | name of the shared folder, e.g. `Music` | base path, e.g. `remote.php/dav/files/me` |
| Folder | optional: a sub-folder | optional |
| User / password | an account allowed on the share (empty = guest) | the server's account |

Pasting a full address (`\\NAS\Music\Rock`, `smb://nas/Music`, `https://cloud.example.com/dav/`)
in the Server field fills in the rest.

- **Test** checks the connection before saving and opens a folder browser.
- Passwords are encrypted on the phone (Android Keystore).
- Files stay on the server: Chud Music streams them and never modifies or deletes anything. Only
  tags and covers are read during the scan.
- **Automatic scan**: shares are read again every 6 hours, on Wi-Fi only if that option is on.
- The phone must be on the same network as the server (or reach it through a VPN).

## YouTube Music

- Search with suggestions; results as **Songs**, **Other versions** (live, covers, uploads; always played as sound only), **Albums**, **Artists**.
- Album and artist pages (top songs, albums, singles, similar artists).
- **Start mix** plays an endless radio from a track.
- **Paste a link** in the search box, as in the PC app: a YouTube or YouTube Music song, playlist,
  album or artist, or a Spotify song, album, playlist or artist. A Spotify link only names
  songs: each one is looked up on YouTube Music (title, artists, duration), and those with no
  close enough version are listed apart, one tap away from a search. **Save as playlist** keeps
  the result.
- From the YouTube or Spotify app or a browser, **Share › Chud Music**: a song plays at once, a
  playlist or an album opens as above.
- No Google account, no ads. YouTube Music tracks go into playlists like any other.
- **Settings › YouTube Music › Prefer Opus**: best quality; switch it off if an old Bluetooth
  device plays badly (AAC is used instead).

## Playlists

- **+ New playlist**, or ⋮ › "Add to a playlist" from a track, an album…
- An empty playlist offers **Import from a link**: paste a YouTube, YouTube Music or Spotify
  playlist (or album) link and its tracks are found on YouTube Music and added to it.
- In a playlist: the ≡ handle reorders, ⋮ › "Remove from playlist" takes a track out; rename
  and delete are in the playlist's menu (your files are not touched).
- Tracks that cannot be found (file gone, share off) stay in the playlist, greyed.
- "Replace YouTube tracks with my files" swaps, where possible, YouTube Music tracks for the same
  track in your library.
- Import and export: see [Exchanging playlists with the PC app](PC-PLAYLISTS.md).

## Radio

Popular stations, genres, countries, worldwide search. The star adds a station to **Favorites**,
which then opens first. The list a station was started from becomes the queue: "next" and
"previous" (lock screen, headset) change station. The title on air is shown when the station sends it; its lyrics are
looked up too.

## Player

- Shuffle, repeat (all / one), editable queue, playback speed.
- **Equalizer**: 10 bands, pre-amp, presets and your own; applied to everything that plays.
- **Lyrics**: synced when LRCLIB has them, plain otherwise. "Search differently" lets you correct
  the title or the artist.
- Controls are also in the notification, on the lock screen, on Bluetooth headsets and cars.
  **Settings › Playback** says whether the app's notifications are on; if the controls are
  missing from the lock screen, check in the phone's settings that the lock screen shows
  notifications and media.
- A track that cannot be played (missing file, unsupported format, share unreachable) is skipped
  with one message; the music goes on.

## Statistics

The icon at the top of Home: listening time, plays, discoveries, streaks, by hour and weekday,
favourite tracks, artists, albums and stations, over 7 days, 30 days, a year or since the start.
Everything stays on the phone.

## Skins and language

**Settings › Skin**: *Follow the system*, *Chud Nocturne* (dark) or *Platine* (light).
**Settings › Language**: English or French (the phone's language by default).

## Updates

**Settings › Updates**: at most once a day, Chud Music looks at the GitHub releases page. A new
version is downloaded and installed **only when you tap "Update"**; the first time, Android asks
to allow Chud Music to install apps. Your playlists, settings and statistics are kept. The app
closes while Android replaces it: open it again afterwards.
